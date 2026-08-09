/*
 * FireflyVPN Cloudflare Worker backend (single-file deployment)
 *
 * Required Cloudflare configuration:
 *   - KV namespace binding: CONFIG
 *   - D1 database binding: USERS_DB
 *   - Worker secret: ADMIN_TOKEN
 *
 * Public client endpoint:
 *   GET /api/client
 *   GET /api/client?refresh=1   // refresh is handled by the app; query is tolerated for compatibility
 *   GET /api/subscription/<id>  // authenticated Worker relay; all sources are encrypted here
 *
 * Admin endpoints:
 *   GET  /<ADMIN_UUID>
 *   GET  /<ADMIN_UUID>/api/state
 *   PUT  /<ADMIN_UUID>/api/state
 *   GET  /<ADMIN_UUID>/api/users
 */

const STATE_KEY = "fireflyvpn:state:v1";
const CLIENT_IP_HEADER = "X-Firefly-Client-IP";
const MAX_MANAGED_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;
const SUBSCRIPTION_CACHE_KEY_PREFIX = "fireflyvpn:subscription-cache:v1:";
const DEFAULT_SUBSCRIPTION_CACHE_MINUTES = 30;
const MAX_SUBSCRIPTION_CACHE_MINUTES = 7 * 24 * 60;
const PLAINTEXT_NODE_PATTERN = /(?:^|\n)\s*(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+/i;
const PRIVATE_B64_ALPHABET =
  "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX";
const TEXT_ENCODER = new TextEncoder();
// Change this UUID to customize the private management route.
const ADMIN_UUID = "3e9a0eed-6a29-4d16-91b1-3adae4bd88e6";
const ADMIN_BASE_PATH = `/${ADMIN_UUID}`;

const USER_SCHEMA_STATEMENTS = [
`CREATE TABLE IF NOT EXISTS users_mb (
  id TEXT PRIMARY KEY,
  nickname TEXT NOT NULL,
  level INTEGER NOT NULL DEFAULT 1 CHECK (level BETWEEN 0 AND 6),
  brand TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  android_version TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  last_report_at TEXT,
  updated_at TEXT NOT NULL,
  total_upload_mb REAL NOT NULL DEFAULT 0,
  total_download_mb REAL NOT NULL DEFAULT 0
)`,
`CREATE INDEX IF NOT EXISTS idx_users_mb_last_seen ON users_mb(last_seen_at DESC)`,
`CREATE TABLE IF NOT EXISTS user_usage_mb (
  account_id TEXT NOT NULL,
  period_type TEXT NOT NULL CHECK (period_type IN ('day', 'month')),
  period_key TEXT NOT NULL,
  upload_mb REAL NOT NULL DEFAULT 0,
  download_mb REAL NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (account_id, period_type, period_key),
  FOREIGN KEY (account_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_user_usage_mb_period ON user_usage_mb(period_type, period_key)`,
`CREATE TABLE IF NOT EXISTS reported_sessions_mb (
  account_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  reported_at TEXT NOT NULL,
  upload_mb REAL NOT NULL DEFAULT 0,
  download_mb REAL NOT NULL DEFAULT 0,
  day_key TEXT NOT NULL,
  month_key TEXT NOT NULL,
  PRIMARY KEY (account_id, session_id),
  FOREIGN KEY (account_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_reported_sessions_mb_time
  ON reported_sessions_mb(account_id, reported_at DESC)`,
`CREATE TRIGGER IF NOT EXISTS trg_reported_session_usage_mb
AFTER INSERT ON reported_sessions_mb
BEGIN
  UPDATE users_mb SET
    last_seen_at = NEW.reported_at,
    last_report_at = NEW.reported_at,
    updated_at = NEW.reported_at,
    total_upload_mb = total_upload_mb + NEW.upload_mb,
    total_download_mb = total_download_mb + NEW.download_mb
  WHERE id = NEW.account_id;
  INSERT INTO user_usage_mb
    (account_id, period_type, period_key, upload_mb, download_mb, active)
    VALUES (NEW.account_id, 'day', NEW.day_key, NEW.upload_mb, NEW.download_mb, 1)
    ON CONFLICT(account_id, period_type, period_key) DO UPDATE SET
      upload_mb = upload_mb + excluded.upload_mb,
      download_mb = download_mb + excluded.download_mb,
      active = 1;
  INSERT INTO user_usage_mb
    (account_id, period_type, period_key, upload_mb, download_mb, active)
    VALUES (NEW.account_id, 'month', NEW.month_key, NEW.upload_mb, NEW.download_mb, 1)
    ON CONFLICT(account_id, period_type, period_key) DO UPDATE SET
      upload_mb = upload_mb + excluded.upload_mb,
      download_mb = download_mb + excluded.download_mb,
      active = 1;
END`,
];
let userSchemaReady = false;

const DEFAULT_STATE = {
  version: 1,
  subscriptions: [],
  notice: null,
  appUpdate: null,
  settings: {
    websiteUrl: "",
    feedbackEmail: "",
    feedbackUrl: "",
    githubUrl: "",
    nodeRequestTimeoutMs: 25000,
  },
};

export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";

      if (path === "/" || path === "/health") {
        return json({
          ok: true,
          service: "fireflyvpn-worker",
          bindings: {
            config: Boolean(env.CONFIG),
            usersDb: Boolean(env.USERS_DB),
            adminToken: Boolean(env.ADMIN_TOKEN),
          },
        });
      }

      if (path === ADMIN_BASE_PATH && request.method === "GET") {
        return new Response(adminHtml(), {
          headers: { "content-type": "text/html; charset=utf-8" },
        });
      }

      if (path === "/api/client" && request.method === "GET") {
        return handleClient(request, env);
      }

      if (path.startsWith("/api/subscription/") && request.method === "GET") {
        return handleManagedSubscription(path, request, env);
      }

      if (path.startsWith("/api/account/")) {
        return handleAccount(path, request, env);
      }

      if (path.startsWith(`${ADMIN_BASE_PATH}/api/`)) {
        if (!isAdmin(request, env)) {
          return json({ error: "unauthorized" }, 401);
        }
        return handleAdmin(path.slice(ADMIN_BASE_PATH.length), request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};

async function handleClient(request, env) {
  const requestUrl = new URL(request.url);
  const origin = requestUrl.origin;
  const state = await readState(env);

  return json({
    subscriptions: state.subscriptions
      .filter((source) => source.enabled)
      .map((source) => ({
        id: source.id,
        name: source.name,
        enabled: source.enabled,
        encrypted: true,
        minLevel: source.minLevel,
        url: `${origin}/api/subscription/${encodeURIComponent(source.id)}`,
      })),
    notice: state.notice,
    appUpdate: state.appUpdate,
    settings: state.settings,
    generatedAt: new Date().toISOString(),
  });
}

async function handleManagedSubscription(path, request, env) {
  const id = path.slice("/api/subscription/".length);
  if (!/^\d+$/.test(id)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const state = await readState(env);
  const source = state.subscriptions.find((item) => item.id === id);
  if (!source || !source.enabled || (!source.content && !source.url)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const accountId = normalizeAccountId(new URL(request.url).searchParams.get("accountId"));
  if (!accountId) {
    return json({ ok: false, error: "account_hash_required" }, 401);
  }
  const access = await resolveAccountAccess(env, accountId);
  if (access.registered && access.level === 0) {
    return json({ ok: false, error: "account_banned" }, 403);
  }
  if (source.minLevel > access.level) {
    return json({ ok: false, error: "insufficient_level" }, 403);
  }

  const clientIp = getClientIp(request);
  const plaintextContent = source.content || await readExternalSubscription(env, source);
  const protectedContent = protectSubscription(plaintextContent, clientIp);
  return new Response(protectedContent, {
    headers: {
      ...corsHeaders(),
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
      [CLIENT_IP_HEADER]: clientIp,
    },
  });
}

async function handleAccount(path, request, env) {
  if (path === "/api/account/register" && request.method === "POST") {
    const body = await request.json();
    const accountId = normalizeAccountId(body && body.deviceId);
    if (!accountId) {
      return json({ ok: false, error: "invalid_device_id" }, 400);
    }

    const now = new Date();
    await ensureUserSchema(env);
    await registerOrTouchUser(env, accountId, body || {}, now);
    const user = await readD1User(env, accountId);

    return json({ ok: true, account: publicAccount(user) });
  }

  if (path === "/api/account/profile" && request.method === "PATCH") {
    const body = await request.json();
    const accountId = normalizeAccountId(body && body.accountId);
    const nickname = normalizeNickname(body && body.nickname);
    if (!accountId || !nickname) {
      return json({ ok: false, error: "invalid_profile" }, 400);
    }

    const db = await ensureUserSchema(env);
    const result = await db.prepare(
      "UPDATE users_mb SET nickname = ?, updated_at = ? WHERE id = ?",
    ).bind(nickname, new Date().toISOString(), accountId).run();
    if (!result.meta || Number(result.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, accountId);
    return json({ ok: true, account: publicAccount(user) });
  }

  if (path === "/api/account/report" && request.method === "POST") {
    const body = await request.json();
    const accountId = normalizeAccountId(body && body.accountId);
    const sessionId = cleanText(body && body.sessionId).slice(0, 128);
    if (!accountId || !sessionId) {
      return json({ ok: false, error: "invalid_report" }, 400);
    }

    await ensureUserSchema(env);
    const user = await readD1User(env, accountId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }

    const now = new Date();
    const timestamp = now.toISOString();
    const db = usersDb(env);
    const uploadMb = boundedMegabytes(body && body.uploadMb);
    const downloadMb = boundedMegabytes(body && body.downloadMb);
    const { day, month } = usagePeriodKeys(now);
    const inserted = await db.prepare(
      `INSERT OR IGNORE INTO reported_sessions_mb
       (account_id, session_id, reported_at, upload_mb, download_mb, day_key, month_key)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      accountId,
      sessionId,
      timestamp,
      uploadMb,
      downloadMb,
      day,
      month,
    ).run();
    const activityStatements = userActivityStatements(db, accountId, now);
    if (!inserted.meta || Number(inserted.meta.changes) < 1) {
      await db.batch([
        db.prepare("UPDATE users_mb SET last_seen_at = ?, updated_at = ? WHERE id = ?")
          .bind(timestamp, timestamp, accountId),
        ...activityStatements,
      ]);
      return json({ ok: true, duplicate: true });
    }

    await db.batch([
      db.prepare(`DELETE FROM reported_sessions_mb
        WHERE account_id = ? AND session_id NOT IN (
          SELECT session_id FROM reported_sessions_mb
          WHERE account_id = ? ORDER BY reported_at DESC LIMIT 200
        )`).bind(accountId, accountId),
      db.prepare(`DELETE FROM user_usage_mb
        WHERE account_id = ? AND period_type = 'day' AND period_key NOT IN (
          SELECT period_key FROM user_usage_mb WHERE account_id = ? AND period_type = 'day'
          ORDER BY period_key DESC LIMIT 400
        )`).bind(accountId, accountId),
      db.prepare(`DELETE FROM user_usage_mb
        WHERE account_id = ? AND period_type = 'month' AND period_key NOT IN (
          SELECT period_key FROM user_usage_mb WHERE account_id = ? AND period_type = 'month'
          ORDER BY period_key DESC LIMIT 24
        )`).bind(accountId, accountId),
    ]);

    return json({ ok: true });
  }

  return json({ error: "account_route_not_found" }, 404);
}

async function handleAdmin(path, request, env) {
  if (path === "/api/users" && request.method === "GET") {
    const db = await ensureUserSchema(env);
    const { day, month } = usagePeriodKeys(new Date());
    const query = await db.prepare(`SELECT
      u.*,
      COALESCE(d.upload_mb, 0) AS today_upload_mb,
      COALESCE(d.download_mb, 0) AS today_download_mb,
      COALESCE(d.active, 0) AS active_today,
      COALESCE(m.upload_mb, 0) AS month_upload_mb,
      COALESCE(m.download_mb, 0) AS month_download_mb,
      COALESCE(m.active, 0) AS active_this_month
      FROM users_mb u
      LEFT JOIN user_usage_mb d ON d.account_id = u.id
        AND d.period_type = 'day' AND d.period_key = ?
      LEFT JOIN user_usage_mb m ON m.account_id = u.id
        AND m.period_type = 'month' AND m.period_key = ?
      ORDER BY u.last_seen_at DESC`).bind(day, month).all();
    const publicUsers = (query.results || []).map(publicAdminUser);

    return json({
      ok: true,
      date: day,
      month,
      users: publicUsers,
      totals: {
        today: aggregatePublicUsage(publicUsers, "today"),
        month: aggregatePublicUsage(publicUsers, "month"),
        allTime: aggregatePublicTotals(publicUsers),
      },
      activeUsers: {
        today: publicUsers.filter((user) => user.activeToday).length,
        month: publicUsers.filter((user) => user.activeThisMonth).length,
      },
      generatedAt: new Date().toISOString(),
    });
  }

  const userMatch = path.match(/^\/api\/users\/([a-f0-9]{64})$/);
  if (userMatch && request.method === "PATCH") {
    const accountId = normalizeAccountId(userMatch[1]);
    const body = await request.json();
    const assignments = [];
    const values = [];
    const db = await ensureUserSchema(env);
    const timestamp = new Date().toISOString();

    if (Object.prototype.hasOwnProperty.call(body || {}, "nickname")) {
      const nickname = normalizeNickname(body.nickname);
      if (!nickname) return json({ ok: false, error: "invalid_nickname" }, 400);
      assignments.push("nickname = ?");
      values.push(nickname);
    }
    if (Object.prototype.hasOwnProperty.call(body || {}, "level")) {
      const level = Number(body.level);
      if (!Number.isInteger(level) || level < 0 || level > 6) {
        return json({ ok: false, error: "invalid_level" }, 400);
      }
      assignments.push("level = ?");
      values.push(level);
    }
    const editableTextFields = [
      ["brand", "brand", 80],
      ["model", "model", 120],
      ["androidVersion", "android_version", 80],
      ["appVersion", "app_version", 40],
    ];
    for (const [property, column, maxLength] of editableTextFields) {
      if (!Object.prototype.hasOwnProperty.call(body || {}, property)) continue;
      const fieldValue = cleanText(body[property]).slice(0, maxLength);
      assignments.push(`${column} = ?`);
      values.push(fieldValue);
    }
    if (assignments.length === 0) {
      return json({ ok: false, error: "no_user_changes" }, 400);
    }
    const updateResult = await db.prepare(
      `UPDATE users_mb SET ${assignments.join(", ")}, updated_at = ? WHERE id = ?`,
    ).bind(...values, timestamp, accountId).run();
    if (!updateResult.meta || Number(updateResult.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({ ok: true, user: publicAccount(await readD1User(env, accountId)) });
  }

  if (path === "/api/state" && request.method === "GET") {
    return json(await readState(env));
  }

  if (path === "/api/state" && request.method === "PUT") {
    const incoming = await request.json();
    const next = normalizeState(incoming);
    try {
      await validateAndWarmSubscriptionSources(env, next.subscriptions);
    } catch (error) {
      return json({
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }, 400);
    }
    next.updatedAt = new Date().toISOString();
    await writeState(env, next);
    return json({ ok: true, state: next });
  }

  return json({ error: "admin_route_not_found" }, 404);
}

function publicAccount(user) {
  return {
    id: user.id,
    nickname: user.nickname,
    level: normalizeLevel(user.level),
    brand: user.brand,
    model: user.model,
    androidVersion: user.androidVersion,
    appVersion: user.appVersion,
  };
}

function publicAdminUser(row) {
  const todayUpload = boundedMegabytes(row.today_upload_mb);
  const todayDownload = boundedMegabytes(row.today_download_mb);
  const monthUpload = boundedMegabytes(row.month_upload_mb);
  const monthDownload = boundedMegabytes(row.month_download_mb);
  return {
    ...publicAccount(rowToUser(row)),
    createdAt: cleanText(row.created_at),
    lastSeenAt: cleanText(row.last_seen_at),
    lastReportAt: cleanText(row.last_report_at) || null,
    totalUploadMb: boundedMegabytes(row.total_upload_mb),
    totalDownloadMb: boundedMegabytes(row.total_download_mb),
    totalMb: boundedMegabytes(row.total_upload_mb) + boundedMegabytes(row.total_download_mb),
    activeToday: Number(row.active_today) === 1,
    activeThisMonth: Number(row.active_this_month) === 1,
    today: {
      uploadMb: todayUpload,
      downloadMb: todayDownload,
      totalMb: todayUpload + todayDownload,
    },
    month: {
      uploadMb: monthUpload,
      downloadMb: monthDownload,
      totalMb: monthUpload + monthDownload,
    },
  };
}

function aggregatePublicUsage(users, property) {
  return users.reduce((total, user) => {
    const usage = user[property] || emptyUsage();
    total.uploadMb += boundedMegabytes(usage.uploadMb);
    total.downloadMb += boundedMegabytes(usage.downloadMb);
    total.totalMb += boundedMegabytes(usage.totalMb);
    return total;
  }, emptyUsage());
}

function aggregatePublicTotals(users) {
  return users.reduce((total, user) => {
    total.uploadMb += boundedMegabytes(user.totalUploadMb);
    total.downloadMb += boundedMegabytes(user.totalDownloadMb);
    total.totalMb += boundedMegabytes(user.totalMb);
    return total;
  }, emptyUsage());
}

function emptyUsage() {
  return { uploadMb: 0, downloadMb: 0, totalMb: 0 };
}

function usersDb(env) {
  if (!env.USERS_DB) throw new Error("Missing D1 binding USERS_DB");
  return env.USERS_DB;
}

async function ensureUserSchema(env) {
  const db = usersDb(env);
  // D1 I/O promises are request-bound in Workers and must never be cached globally.
  // A plain boolean is safe to retain and avoids repeating DDL in a warm isolate.
  if (!userSchemaReady) {
    await db.batch(USER_SCHEMA_STATEMENTS.map((statement) => db.prepare(statement)));
    userSchemaReady = true;
  }
  return db;
}

async function resolveAccountAccess(env, accountId) {
  const db = await ensureUserSchema(env);
  const row = await db.prepare("SELECT level FROM users_mb WHERE id = ?")
    .bind(accountId).first();
  return row
    ? { registered: true, level: normalizeLevel(row.level) }
    : { registered: false, level: 0 };
}

async function readD1User(env, accountId) {
  const db = usersDb(env);
  const row = await db.prepare("SELECT * FROM users_mb WHERE id = ?")
    .bind(accountId).first();
  return row ? rowToUser(row) : null;
}

function rowToUser(row) {
  return {
    id: normalizeAccountId(row.id),
    nickname: normalizeNickname(row.nickname, defaultNickname(row.id)),
    level: normalizeLevel(row.level),
    brand: cleanText(row.brand),
    model: cleanText(row.model),
    androidVersion: cleanText(row.android_version),
    appVersion: cleanText(row.app_version),
    createdAt: cleanText(row.created_at),
    lastSeenAt: cleanText(row.last_seen_at),
    lastReportAt: cleanText(row.last_report_at) || null,
    updatedAt: cleanText(row.updated_at),
    totalUploadMb: boundedMegabytes(row.total_upload_mb),
    totalDownloadMb: boundedMegabytes(row.total_download_mb),
  };
}

async function registerOrTouchUser(env, accountId, profile, now) {
  const db = usersDb(env);
  const timestamp = now.toISOString();
  const brand = cleanText(profile.brand).slice(0, 80);
  const model = cleanText(profile.model).slice(0, 120);
  const androidVersion = cleanText(profile.androidVersion).slice(0, 80);
  const appVersion = cleanText(profile.appVersion).slice(0, 40);
  await db.batch([
    db.prepare(`INSERT INTO users_mb
      (id, nickname, level, brand, model, android_version, app_version,
       created_at, last_seen_at, last_report_at, updated_at,
       total_upload_mb, total_download_mb)
      VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, NULL, ?, 0, 0)
      ON CONFLICT(id) DO UPDATE SET
        brand = CASE WHEN excluded.brand <> '' THEN excluded.brand ELSE users_mb.brand END,
        model = CASE WHEN excluded.model <> '' THEN excluded.model ELSE users_mb.model END,
        android_version = CASE WHEN excluded.android_version <> '' THEN excluded.android_version ELSE users_mb.android_version END,
        app_version = CASE WHEN excluded.app_version <> '' THEN excluded.app_version ELSE users_mb.app_version END,
        last_seen_at = excluded.last_seen_at,
        updated_at = excluded.updated_at`)
      .bind(
        accountId,
        defaultNickname(accountId),
        brand,
        model,
        androidVersion,
        appVersion,
        timestamp,
        timestamp,
        timestamp,
      ),
    ...userActivityStatements(db, accountId, now),
  ]);
}

function userActivityStatements(db, accountId, now) {
  const { day, month } = usagePeriodKeys(now);
  return [
    db.prepare(`INSERT INTO user_usage_mb
      (account_id, period_type, period_key, upload_mb, download_mb, active)
      VALUES (?, 'day', ?, 0, 0, 1)
      ON CONFLICT(account_id, period_type, period_key) DO UPDATE SET active = 1`)
      .bind(accountId, day),
    db.prepare(`INSERT INTO user_usage_mb
      (account_id, period_type, period_key, upload_mb, download_mb, active)
      VALUES (?, 'month', ?, 0, 0, 1)
      ON CONFLICT(account_id, period_type, period_key) DO UPDATE SET active = 1`)
      .bind(accountId, month),
  ];
}

function normalizeNickname(value, fallback = "") {
  const nickname = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 24);
  return nickname || fallback;
}

function normalizeLevel(value) {
  const level = Number(value);
  return Number.isInteger(level) && level >= 0 && level <= 6 ? level : 1;
}

function defaultNickname(accountId) {
  return `流萤用户${cleanText(accountId).slice(-6).toUpperCase()}`;
}

function usagePeriodKeys(date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {});
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, month: `${parts.year}-${parts.month}` };
}

function normalizeAccountId(value) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

function boundedMegabytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number * 1e6) / 1e6, 1e9)
    : 0;
}

function normalizeManagedContent(value) {
  if (typeof value !== "string") return "";
  const content = value.replace(/\r\n?/g, "\n").trim();
  if (TEXT_ENCODER.encode(content).length > MAX_MANAGED_SUBSCRIPTION_BYTES) {
    throw new Error("managed_subscription_too_large");
  }
  return content;
}

function normalizeSubscriptionCacheMinutes(value) {
  if (value === undefined || value === null || value === "") {
    return DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
  }
  const minutes = Number(value);
  return Number.isFinite(minutes)
    ? Math.min(Math.max(Math.round(minutes), 0), MAX_SUBSCRIPTION_CACHE_MINUTES)
    : DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
}

function assertPlainSubscriptionContent(value) {
  const content = normalizeManagedContent(value);
  if (!content || !PLAINTEXT_NODE_PATTERN.test(content)) {
    throw new Error("subscription_must_be_plaintext_nodes");
  }
  return content;
}

function subscriptionCacheKey(source) {
  return `${SUBSCRIPTION_CACHE_KEY_PREFIX}${source.id}`;
}

async function readExternalSubscription(env, source) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  if (!source || !source.url) throw new Error("external_subscription_url_required");

  const cacheMinutes = normalizeSubscriptionCacheMinutes(source.cacheMinutes);
  const cacheKey = subscriptionCacheKey(source);
  const now = Date.now();
  if (cacheMinutes > 0) {
    const cached = await env.CONFIG.get(cacheKey, "json");
    const cachedAt = Number(cached && cached.cachedAt);
    const freshUntil = cachedAt + cacheMinutes * 60 * 1000;
    if (
      cached &&
      cached.url === source.url &&
      Number.isFinite(cachedAt) &&
      freshUntil > now
    ) {
      return assertPlainSubscriptionContent(cached.content);
    }
  }

  let response;
  try {
    response = await fetch(source.url, {
      method: "GET",
      headers: {
        accept: "text/plain, */*;q=0.1",
        "user-agent": "FireflyVPN-Worker/1.0",
      },
      redirect: "follow",
    });
  } catch (_) {
    throw new Error("external_subscription_unreachable");
  }
  if (!response.ok) {
    throw new Error(`external_subscription_http_${response.status}`);
  }

  const content = assertPlainSubscriptionContent(await response.text());
  if (cacheMinutes > 0) {
    const cacheSeconds = cacheMinutes * 60;
    await env.CONFIG.put(cacheKey, JSON.stringify({
      url: source.url,
      content,
      cachedAt: now,
    }), { expirationTtl: Math.max(cacheSeconds, 60) });
  } else {
    await env.CONFIG.delete(cacheKey);
  }
  return content;
}

async function validateAndWarmSubscriptionSources(env, subscriptions) {
  for (const source of subscriptions) {
    if (source.content) {
      assertPlainSubscriptionContent(source.content);
    } else if (source.url) {
      await readExternalSubscription(env, source);
    }
  }
}

function privateBase64Encode(value) {
  const bytes = TEXT_ENCODER.encode(value);
  let result = "";
  let chunk = "";

  for (let i = 0; i < bytes.length; i += 3) {
    const remaining = bytes.length - i;
    const a = bytes[i];
    const b = remaining > 1 ? bytes[i + 1] : 0;
    const c = remaining > 2 ? bytes[i + 2] : 0;
    const block = (a << 16) | (b << 8) | c;

    chunk += PRIVATE_B64_ALPHABET[(block >>> 18) & 0x3f];
    chunk += PRIVATE_B64_ALPHABET[(block >>> 12) & 0x3f];
    chunk += remaining > 1
      ? PRIVATE_B64_ALPHABET[(block >>> 6) & 0x3f]
      : "=";
    chunk += remaining > 2
      ? PRIVATE_B64_ALPHABET[block & 0x3f]
      : "=";
    if (chunk.length >= 0x8000) {
      result += chunk;
      chunk = "";
    }
  }

  return result + chunk;
}

function parseIpv4Tail(value) {
  let address = value;
  if (address.includes(":") && address.includes(".")) {
    address = address.slice(address.lastIndexOf(":") + 1);
  }

  const parts = address.split(".");
  if (parts.length !== 4) return null;
  const numbers = parts.map((part) => {
    if (!/^\d{1,3}$/.test(part)) return NaN;
    return Number(part);
  });
  if (numbers.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return null;
  }
  return numbers[3];
}

function shiftFromClientIp(clientIp) {
  let address = String(clientIp || "").split(",", 1)[0].trim();
  if (address.startsWith("[") && address.includes("]")) {
    address = address.slice(1, address.indexOf("]"));
  }

  const ipv4Tail = parseIpv4Tail(address);
  if (ipv4Tail !== null) return ipv4Tail % 64;

  if (address.includes(":")) {
    const lastGroup = address.slice(address.lastIndexOf(":") + 1);
    if (lastGroup === "") return 0;
    if (/^[a-f\d]{1,4}$/i.test(lastGroup)) {
      return parseInt(lastGroup, 16) % 64;
    }
  }

  throw new Error("invalid_cf_connecting_ip");
}

function protectSubscription(value, clientIp) {
  const shift = shiftFromClientIp(clientIp);
  const encoded = privateBase64Encode(value);
  if (shift === 0) return encoded;

  let result = "";
  let chunk = "";
  for (const character of encoded) {
    if (character === "=") {
      chunk += character;
      continue;
    }
    const index = PRIVATE_B64_ALPHABET.indexOf(character);
    if (index < 0) throw new Error("private_base64_encoding_failed");
    chunk += PRIVATE_B64_ALPHABET[(index + shift) % 64];
    if (chunk.length >= 0x8000) {
      result += chunk;
      chunk = "";
    }
  }
  return result + chunk;
}

function getClientIp(request) {
  const clientIp = request.headers.get("CF-Connecting-IP");
  if (!clientIp) throw new Error("missing_cf_connecting_ip");
  shiftFromClientIp(clientIp);
  return clientIp.trim();
}

async function readState(env) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const stored = await env.CONFIG.get(STATE_KEY, "json");
  return normalizeState(stored || DEFAULT_STATE);
}

async function writeState(env, state) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  await env.CONFIG.put(STATE_KEY, JSON.stringify(normalizeState(state)));
}

function normalizeState(input) {
  const value = input && typeof input === "object" ? input : {};
  const list = Array.isArray(value.subscriptions)
    ? value.subscriptions
    : Array.isArray(value.sources)
      ? value.sources
      : [];

  const subscriptions = list
    .filter((item) => item && typeof item === "object")
    .map((item) => {
      const content = normalizeManagedContent(
        item.content ?? item.managedContent ?? item.nodesText,
      );
      if (content) assertPlainSubscriptionContent(content);
      const externalUrl = typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
        ? item.url.trim()
        : "";
      return {
        url: content ? "" : externalUrl,
        name: cleanText(item.name || item.title),
        enabled: parseFlag(item.enabled, true),
        minLevel: normalizeLevel(item.minLevel ?? item.requiredLevel),
        cacheMinutes: normalizeSubscriptionCacheMinutes(item.cacheMinutes),
        content,
        remark: cleanText(item.remark || item.note).slice(0, 500),
      };
    })
    .filter((item) => item.url || item.content)
    .map((item, index) => ({
      id: String(index + 1),
      name: item.name || `线路${index + 1}`,
      url: item.url,
      enabled: item.enabled,
      minLevel: item.minLevel,
      cacheMinutes: item.cacheMinutes,
      content: item.content,
      remark: item.remark,
    }));

  return {
    version: 1,
    subscriptions,
    notice: normalizeNotice(value.notice),
    appUpdate: plainObject(value.appUpdate) ? value.appUpdate : null,
    settings: normalizeSettings(value.settings),
    ...(value.updatedAt ? { updatedAt: String(value.updatedAt) } : {}),
  };
}

function normalizeNotice(value) {
  if (!plainObject(value)) return null;
  return {
    hasNotice: parseFlag(value.hasNotice, false),
    title: cleanText(value.title),
    content: cleanText(value.content),
    noticeId: cleanText(value.noticeId),
    showOnce: parseFlag(value.showOnce, true),
  };
}

function normalizeSettings(value) {
  const settings = plainObject(value) ? value : {};
  const timeout = Number(settings.nodeRequestTimeoutMs);
  return {
    websiteUrl: cleanText(settings.websiteUrl),
    feedbackEmail: cleanText(settings.feedbackEmail),
    feedbackUrl: cleanText(settings.feedbackUrl),
    githubUrl: cleanText(settings.githubUrl),
    nodeRequestTimeoutMs: Number.isFinite(timeout)
      ? Math.round(Math.min(Math.max(timeout, 5000), 60000))
      : 25000,
  };
}

function cleanText(value) {
  return typeof value === "string" ? value.trim() : "";
}

function plainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function parseFlag(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return !["0", "false", "off", "no"].includes(String(value).trim().toLowerCase());
}

function isAdmin(request, env) {
  const token = env.ADMIN_TOKEN;
  const authorization = request.headers.get("authorization") || "";
  return Boolean(token) && authorization === `Bearer ${token}`;
}

function corsHeaders() {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,PUT,PATCH,OPTIONS",
    "access-control-allow-headers": "Content-Type, Authorization",
    "access-control-expose-headers": CLIENT_IP_HEADER,
  };
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...corsHeaders(),
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function adminHtml() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FireflyVPN Worker 管理后台</title>
  <style>
    :root { color-scheme: dark; font-family: system-ui, -apple-system, sans-serif; }
     body { margin: 0; background: #111827; color: #e5e7eb; }
     main { width: min(980px, calc(100% - 28px)); margin: 24px auto 60px; }
     h1 { font-size: 24px; margin: 0 0 6px; }
     h2 { font-size: 18px; margin: 0 0 14px; }
     p, small, label { color: #aab4c5; line-height: 1.55; }
     .card { background: #1f2937; border: 1px solid #374151; border-radius: 14px; padding: 18px; margin-top: 16px; }
     .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
     .tab-nav { display: flex; gap: 8px; overflow-x: auto; padding: 6px; margin-top: 18px; background: #1f2937; border: 1px solid #374151; border-radius: 12px; scrollbar-width: thin; }
     .tab-button { flex: 0 0 auto; margin: 0; padding: 9px 14px; background: transparent; color: #aab4c5; border: 1px solid transparent; white-space: nowrap; }
     .tab-button:hover { background: #374151; color: #f9fafb; }
     .tab-button.active { background: #22c55e; color: #052e16; }
     .tab-panel { display: none; }
     .tab-panel.active { display: block; }
     .login-gate { max-width: 430px; margin: 12vh auto 0; }
     .login-gate h1 { margin-bottom: 10px; }
     .login-gate .card { margin-top: 18px; }
     .dashboard-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
     .dashboard-header button { flex: 0 0 auto; margin-top: 0; }
     #loginStatus { min-height: 24px; color: #fca5a5; margin-top: 10px; }
     .field { display: flex; flex-direction: column; gap: 6px; }
    input, textarea, select { box-sizing: border-box; width: 100%; color: #e5e7eb; background: #111827; border: 1px solid #4b5563; border-radius: 8px; padding: 10px; }
    input[type="checkbox"] { width: auto; accent-color: #22c55e; transform: scale(1.15); margin-right: 8px; }
    textarea { min-height: 110px; resize: vertical; }
    button { cursor: pointer; border: 0; border-radius: 8px; padding: 10px 14px; margin: 10px 8px 0 0; background: #22c55e; color: #052e16; font-weight: 700; }
    button.secondary { background: #374151; color: #e5e7eb; }
    button.danger { background: #7f1d1d; color: #fee2e2; }
    .check { display: flex; align-items: center; margin: 8px 0; }
    .source-row { border: 1px solid #4b5563; border-radius: 10px; padding: 12px; margin: 10px 0; }
     .source-row .row-actions { display: flex; align-items: center; justify-content: space-between; }
     .source-row small { display: block; margin-top: 8px; }
     .user-sheet-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
     .user-sheet-toolbar button { margin: 0; }
     .user-sheet-toolbar small { margin-left: auto; }
     .user-search { flex: 1 1 220px; width: min(300px, 100%); min-width: 180px; padding: 9px 10px; }
     .sheet-summary { display: block; margin: -4px 0 10px; }
     .table-wrap { max-height: 70vh; overflow: auto; border: 1px solid #4b5563; border-radius: 10px; background: #111827; }
     table.user-sheet { width: 100%; min-width: 100%; table-layout: fixed; border-collapse: separate; border-spacing: 0; font-size: 13px; }
     .user-sheet th, .user-sheet td { text-align: left; padding: 0; overflow: hidden; text-overflow: ellipsis; border-right: 1px solid #374151; border-bottom: 1px solid #374151; white-space: nowrap; background: #111827; }
     .user-sheet th { position: sticky; top: 0; z-index: 3; padding: 9px 12px 9px 8px; overflow: visible; color: #d1d5db; background: #273244; font-weight: 700; user-select: none; }
     .column-resizer { position: absolute; top: 0; right: -4px; width: 9px; height: 100%; z-index: 6; cursor: col-resize; touch-action: none; }
     .column-resizer::after { content: ''; position: absolute; top: 20%; bottom: 20%; left: 4px; width: 1px; background: #4b5563; }
     .column-resizer:hover::after, .column-resizer:focus::after, body.column-resizing .column-resizer::after { background: #22c55e; width: 2px; }
     body.column-resizing, body.column-resizing * { cursor: col-resize !important; user-select: none !important; }
     .user-sheet th:first-child, .user-sheet td:first-child { position: sticky; left: 0; z-index: 2; }
     .user-sheet th:first-child { z-index: 4; }
     .user-sheet td:first-child { padding: 8px; background: #172033; }
     .user-sheet td.readonly { padding: 8px; color: #cbd5e1; }
     .user-sheet td code { color: #c4b5fd; }
     .sheet-input, .sheet-select { width: 100%; min-width: 0; height: 36px; padding: 7px 8px; border: 0; border-radius: 0; background: transparent; }
     .sheet-input:focus, .sheet-select:focus { position: relative; z-index: 1; outline: 2px solid #22c55e; outline-offset: -2px; background: #0f172a; }
     .sheet-save { margin: 4px; padding: 7px 10px; }
     .user-row.dirty td { background: #312e1f; }
     .user-row.dirty td:first-child { background: #3b321d; }
     .user-row.search-current td { box-shadow: inset 0 0 0 2px #38bdf8; }
     .user-row[hidden] { display: none; }
     button:disabled { cursor: not-allowed; opacity: .5; }
     #status { min-height: 24px; color: #86efac; margin-top: 8px; }
     .hint { margin-top: 4px; }
  </style>
</head>
<body>
 <main>
   <section id="loginGate" class="login-gate">
     <h1>FireflyVPN 管理后台</h1>
     <p>请输入管理密码后进入后台。未验证前不会展示任何管理模块。</p>
     <div class="card">
       <form id="loginForm">
         <div class="field"><label>管理密码<input id="adminPassword" type="password" autocomplete="current-password" placeholder="输入 ADMIN_TOKEN"></label></div>
         <button type="submit">进入后台</button>
         <div id="loginStatus"></div>
       </form>
     </div>
   </section>

   <div id="dashboard" hidden>
     <div class="dashboard-header">
       <h1>FireflyVPN Worker 管理后台</h1>
       <button class="secondary" type="button" onclick="logout()">退出登录</button>
     </div>
     <p>在这里配置订阅线路、网站信息、联系方式、公告和版本更新。每个模块可单独推送到 Worker。</p>
     <div id="status"></div>

     <nav class="tab-nav" aria-label="后台模块">
     <button class="tab-button" type="button" data-tab="users">用户统计</button>
     <button class="tab-button" type="button" data-tab="subscriptions">订阅线路</button>
     <button class="tab-button" type="button" data-tab="app-settings">应用设置</button>
     <button class="tab-button" type="button" data-tab="notice">公告</button>
     <button class="tab-button" type="button" data-tab="app-update">版本更新</button>
   </nav>

   <section class="card tab-panel" data-panel="users">
     <h2>用户数据表</h2>
     <div class="user-sheet-toolbar">
       <button class="secondary" type="button" onclick="loadUsers()">刷新</button>
       <button id="saveAllUsers" type="button" onclick="saveAllUsers()" disabled>保存全部修改</button>
       <button class="secondary" type="button" onclick="exportUsersCsv()">导出 Excel（CSV）</button>
       <button class="secondary" type="button" onclick="resetUserColumnWidths()">重置列宽</button>
       <input id="userSearch" class="user-search" type="search" placeholder="搜索账户、昵称、设备或版本" oninput="applyUserSearch()" onkeydown="handleUserSearchKey(event)">
       <button class="secondary" type="button" onclick="locateUserSearch(1)">定位下一个</button>
       <button class="secondary" type="button" onclick="clearUserSearch()">清除</button>
       <small id="userSearchStatus"></small>
     </div>
     <small id="userSummary" class="sheet-summary">登录后自动读取用户数据。</small>
     <div id="usersTable" class="table-wrap"></div>
     <small class="hint">双击或直接点击单元格即可编辑昵称、等级、品牌、型号、Android 和 App 版本；黄色行表示尚未保存。流量、活跃状态与时间为只读统计字段。导出的 UTF-8 CSV 可直接使用 Excel 打开。</small>
   </section>

  <section class="card tab-panel" data-panel="subscriptions">
    <h2>订阅线路</h2>
    <div id="subscriptionRows"></div>
     <button class="secondary" onclick="addSource()">添加线路</button>
     <small class="hint">每条线路可设置最低账户等级（Lv0～Lv6）。纯文本节点由 Worker 直接加密；外部地址必须返回明文节点，由 Worker 中转、缓存并加密，已经加密或无法识别的接口会被拒绝。缓存时间为 0 时每次请求都会重新访问上游。</small>
     <button type="button" onclick="pushSection('subscriptions')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="app-settings">
    <h2>应用基础设置</h2>
    <div class="grid">
      <div class="field"><label>官方网站<input id="websiteUrl" type="url" placeholder="https://example.com"></label></div>
      <div class="field"><label>反馈邮箱<input id="feedbackEmail" type="email" placeholder="support@example.com"></label></div>
      <div class="field"><label>问题反馈链接<input id="feedbackUrl" type="url" placeholder="https://github.com/.../issues"></label></div>
      <div class="field"><label>项目源码链接<input id="githubUrl" type="url" placeholder="https://github.com/.../.../"></label></div>
      <div class="field"><label>后台请求超时（毫秒）<input id="nodeRequestTimeoutMs" type="number" min="5000" max="60000" step="1000"></label></div>
     </div>
     <small class="hint">留空的网站、邮箱或链接会在 App 中自动隐藏对应入口。超时范围为 5000～60000 毫秒。</small>
     <button type="button" onclick="pushSection('app-settings')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="notice">
    <h2>公告</h2>
    <label class="check"><input id="noticeEnabled" type="checkbox">显示公告</label>
    <div class="grid">
      <div class="field"><label>公告标题<input id="noticeTitle" type="text"></label></div>
      <div class="field"><label>公告 ID<input id="noticeId" type="text" placeholder="notice_20260806"></label></div>
    </div>
    <div class="field"><label>公告内容<textarea id="noticeContent" placeholder="支持普通文本和 HTML"></textarea></label></div>
    <label class="check"><input id="noticeShowOnce" type="checkbox">每个公告 ID 只显示一次</label>
     <button type="button" onclick="pushSection('notice')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="app-update">
    <h2>版本更新</h2>
    <label class="check"><input id="appUpdateEnabled" type="checkbox">启用版本更新提示</label>
    <div class="grid">
      <div class="field"><label>版本名称<input id="appVersion" type="text" placeholder="1.1.0"></label></div>
      <div class="field"><label>版本号<input id="appVersionCode" type="number" min="1" step="1"></label></div>
      <div class="field"><label>APK 下载地址<input id="appDownloadUrl" type="url" placeholder="https://example.com/app.apk"></label></div>
     </div>
     <div class="field"><label>更新说明<textarea id="appChangelog"></textarea></label></div>
     <label class="check"><input id="appForce" type="checkbox">强制更新</label>
     <button type="button" onclick="pushSection('app-update')">推送</button>
   </section>
   </div>
 </main>
<script>
 let currentState = null;
 let adminToken = '';
 let currentUsersData = null;
 const dirtyUserIds = new Set();
 let savingAllUsers = false;
 let userSearchMatches = [];
 let userSearchIndex = -1;
 const USER_COLUMN_WIDTHS_KEY = 'firefly:user-sheet-column-widths:v1';
 const USER_SHEET_COLUMNS = [
   {key: 'accountId', label: '账户哈希', width: 135, min: 90},
   {key: 'nickname', label: '昵称', width: 110, min: 70},
   {key: 'level', label: '等级', width: 80, min: 65},
   {key: 'brand', label: '品牌', width: 90, min: 60},
   {key: 'model', label: '型号', width: 120, min: 70},
   {key: 'androidVersion', label: 'Android', width: 85, min: 65},
   {key: 'appVersion', label: 'App 版本', width: 85, min: 65},
   {key: 'activeToday', label: '今日活跃', width: 70, min: 60},
   {key: 'activeMonth', label: '本月活跃', width: 70, min: 60},
   {key: 'trafficToday', label: '今日流量', width: 90, min: 65},
   {key: 'trafficMonth', label: '本月流量', width: 90, min: 65},
   {key: 'trafficTotal', label: '累计流量', width: 90, min: 65},
   {key: 'createdAt', label: '注册时间', width: 140, min: 90},
   {key: 'lastSeenAt', label: '最后活跃', width: 140, min: 90},
   {key: 'lastReportAt', label: '最后上报', width: 140, min: 90},
   {key: 'actions', label: '操作', width: 70, min: 60}
 ];
 let userColumnWidths = loadUserColumnWidths();
 const ADMIN_API_BASE = "${ADMIN_BASE_PATH}/api";
 const loginGate = document.getElementById('loginGate');
 const loginForm = document.getElementById('loginForm');
 const passwordInput = document.getElementById('adminPassword');
 const loginStatusEl = document.getElementById('loginStatus');
 const statusEl = document.getElementById('status');
 function el(id) { return document.getElementById(id); }
 function value(id) { return el(id).value.trim(); }
 function setValue(id, data) { el(id).value = data == null ? '' : data; }
 function checked(id, data) { el(id).checked = Boolean(data); }
 function esc(data) { return String(data == null ? '' : data).replace(/[&<>"']/g, function(c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
 function headers() { return {'content-type':'application/json','authorization':'Bearer ' + adminToken}; }
 function show(message, error) { statusEl.textContent = message; statusEl.style.color = error ? '#fca5a5' : '#86efac'; }
 function showLoginError(message) { loginStatusEl.textContent = message || ''; }
 function adminErrorMessage(error, fallback) {
   if (error === 'subscription_must_be_plaintext_nodes') return '订阅必须返回可识别的明文节点，不能使用已加密接口';
   if (error === 'external_subscription_unreachable') return '外部明文订阅接口无法访问';
   if (error === 'external_subscription_url_required') return '请填写外部明文订阅地址';
   if (String(error || '').indexOf('external_subscription_http_') === 0) {
     return '外部明文订阅接口请求失败（HTTP ' + String(error).slice('external_subscription_http_'.length) + '）';
   }
   return error || fallback;
 }
 loginForm.onsubmit = async function(event) {
   event.preventDefault();
   const password = passwordInput.value.trim();
   if (!password) return showLoginError('请输入管理密码');
   adminToken = password;
   showLoginError('验证中…');
   try {
     const response = await fetch(ADMIN_API_BASE + '/state', {headers: headers()});
     const data = await response.json();
     if (!response.ok) {
       adminToken = '';
       if (response.status === 401) {
         try {
           const healthResponse = await fetch('/health');
           const health = await healthResponse.json();
           if (health.bindings && health.bindings.adminToken === false) {
             return showLoginError('Worker 尚未配置 ADMIN_TOKEN');
           }
         } catch (_) {}
       }
       return showLoginError('密码错误或后台地址无效');
     }
     currentState = data;
     loginGate.hidden = true;
     el('dashboard').hidden = false;
     fillState(data);
     activateTab('users');
     if (await loadUsers()) show('登录成功');
     passwordInput.value = '';
     showLoginError('');
   } catch (error) {
     adminToken = '';
     showLoginError('无法连接后台，请检查网络');
   }
 };
 function logout() {
   adminToken = '';
   currentState = null;
   el('dashboard').hidden = true;
   loginGate.hidden = false;
   passwordInput.value = '';
   showLoginError('');
   passwordInput.focus();
 }
function activateTab(name) {
  document.querySelectorAll('.tab-button').forEach(function(button) {
    const active = button.dataset.tab === name;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', active ? 'true' : 'false');
  });
  document.querySelectorAll('.tab-panel').forEach(function(panel) {
    panel.classList.toggle('active', panel.dataset.panel === name);
  });
}
document.querySelectorAll('.tab-button').forEach(function(button) {
  button.onclick = function() { activateTab(button.dataset.tab); };
});
function formatMegabytes(megabytes) {
  let value = Number(megabytes) || 0;
  const units = ['MB', 'GB', 'TB', 'PB'];
  let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
  if (value === 0) return '0 MB';
  if (index === 0 && value < 0.01) return '<0.01 MB';
  return value.toFixed(value >= 100 ? 0 : value >= 10 ? 1 : 2) + ' ' + units[index];
}
function csvMegabytes(megabytes) {
  return Math.round((Number(megabytes) || 0) * 10000) / 10000;
}
function usageTotal(usage) { return formatMegabytes(usage && usage.totalMb); }
function levelOptions(selectedLevel) {
  const selected = Number(selectedLevel);
  return [0, 1, 2, 3, 4, 5, 6].map(function(level) {
    return '<option value="' + level + '" ' + (level === selected ? 'selected' : '') +
      '>Lv' + level + (level === 0 ? '（最低）' : '') + '</option>';
  }).join('');
}
function userLevelOptions(selectedLevel) {
  const selected = Number(selectedLevel);
  return [0, 1, 2, 3, 4, 5, 6].map(function(level) {
    return '<option value="' + level + '" ' + (level === selected ? 'selected' : '') +
      '>Lv' + level + (level === 0 ? '（封禁）' : '') + '</option>';
  }).join('');
}
function loadUserColumnWidths() {
  try {
    const stored = JSON.parse(localStorage.getItem(USER_COLUMN_WIDTHS_KEY) || '{}');
    return stored && typeof stored === 'object' ? stored : {};
  } catch (_) {
    return {};
  }
}
function persistUserColumnWidths() {
  try { localStorage.setItem(USER_COLUMN_WIDTHS_KEY, JSON.stringify(userColumnWidths)); } catch (_) {}
}
function userColumnDefinition(columnKey) {
  return USER_SHEET_COLUMNS.find(function(column) { return column.key === columnKey; });
}
function resolvedUserColumnWidth(column) {
  const storedWidth = Number(userColumnWidths[column.key]);
  return Number.isFinite(storedWidth)
    ? Math.min(Math.max(Math.round(storedWidth), column.min), 500)
    : column.width;
}
function userSheetColgroup() {
  return '<colgroup>' + USER_SHEET_COLUMNS.map(function(column) {
    return '<col data-user-column="' + column.key + '" style="width:' + resolvedUserColumnWidth(column) + 'px">';
  }).join('') + '</colgroup>';
}
function userSheetHeader() {
  return USER_SHEET_COLUMNS.map(function(column) {
    return '<th data-user-column-header="' + column.key + '">' + esc(column.label) +
      '<span class="column-resizer" data-resize-column="' + column.key +
      '" role="separator" tabindex="0" title="拖动调整列宽，双击恢复默认宽度"></span></th>';
  }).join('');
}
function updateUserSheetWidth(table) {
  if (!table) return;
  const totalWidth = USER_SHEET_COLUMNS.reduce(function(total, column) {
    const col = table.querySelector('col[data-user-column="' + column.key + '"]');
    return total + (col ? Number.parseFloat(col.style.width) || column.width : column.width);
  }, 0);
  table.style.width = Math.ceil(totalWidth) + 'px';
}
function setUserColumnWidth(table, columnKey, requestedWidth, persist) {
  const column = userColumnDefinition(columnKey);
  const col = table && table.querySelector('col[data-user-column="' + columnKey + '"]');
  if (!column || !col) return;
  const width = Math.min(Math.max(Math.round(requestedWidth), column.min), 500);
  col.style.width = width + 'px';
  userColumnWidths[columnKey] = width;
  updateUserSheetWidth(table);
  if (persist) persistUserColumnWidths();
}
function resetUserColumnWidths() {
  userColumnWidths = {};
  try { localStorage.removeItem(USER_COLUMN_WIDTHS_KEY); } catch (_) {}
  const table = el('usersTable').querySelector('table.user-sheet');
  if (table) {
    USER_SHEET_COLUMNS.forEach(function(column) {
      const col = table.querySelector('col[data-user-column="' + column.key + '"]');
      if (col) col.style.width = column.width + 'px';
    });
    updateUserSheetWidth(table);
  }
  show('列宽已恢复为紧凑默认值');
}
function attachUserColumnResizers() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table) return;
  updateUserSheetWidth(table);
  table.querySelectorAll('[data-resize-column]').forEach(function(handle) {
    const columnKey = handle.dataset.resizeColumn;
    handle.onpointerdown = function(event) {
      if (event.button !== 0) return;
      event.preventDefault();
      const col = table.querySelector('col[data-user-column="' + columnKey + '"]');
      const startWidth = col ? Number.parseFloat(col.style.width) : 0;
      const startX = event.clientX;
      document.body.classList.add('column-resizing');
      function move(moveEvent) {
        setUserColumnWidth(table, columnKey, startWidth + moveEvent.clientX - startX, false);
      }
      function stop() {
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', stop);
        document.removeEventListener('pointercancel', stop);
        document.body.classList.remove('column-resizing');
        persistUserColumnWidths();
      }
      document.addEventListener('pointermove', move);
      document.addEventListener('pointerup', stop);
      document.addEventListener('pointercancel', stop);
    };
    handle.ondblclick = function(event) {
      event.preventDefault();
      const column = userColumnDefinition(columnKey);
      if (!column) return;
      delete userColumnWidths[columnKey];
      setUserColumnWidth(table, columnKey, column.width, false);
      persistUserColumnWidths();
    };
    handle.onkeydown = function(event) {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      const col = table.querySelector('col[data-user-column="' + columnKey + '"]');
      const currentWidth = col ? Number.parseFloat(col.style.width) : 0;
      setUserColumnWidth(table, columnKey, currentWidth + (event.key === 'ArrowRight' ? 10 : -10), true);
    };
  });
}
function userRowSearchText(row) {
  const values = [row.dataset.userRow || ''];
  row.querySelectorAll('[data-user-field]').forEach(function(control) {
    values.push(control.value || '');
    if (control.dataset.userField === 'level') values.push('lv' + control.value);
  });
  row.querySelectorAll('td.readonly').forEach(function(cell) {
    values.push(cell.textContent || '');
  });
  return values.join(' ').toLowerCase();
}
function applyUserSearch(resetPosition) {
  const input = el('userSearch');
  const status = el('userSearchStatus');
  const query = input ? input.value.trim().toLowerCase() : '';
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'));
  userSearchMatches = [];
  rows.forEach(function(row) {
    const matched = !query || userRowSearchText(row).includes(query);
    row.hidden = !matched;
    row.classList.remove('search-current');
    if (matched) userSearchMatches.push(row);
  });
  if (resetPosition !== false || userSearchIndex >= userSearchMatches.length) {
    userSearchIndex = -1;
  }
  if (userSearchIndex >= 0 && userSearchMatches[userSearchIndex]) {
    userSearchMatches[userSearchIndex].classList.add('search-current');
  }
  if (status) {
    status.textContent = query
      ? '匹配 ' + userSearchMatches.length + ' / ' + rows.length
      : '';
  }
}
function locateUserSearch(direction) {
  const input = el('userSearch');
  if (!input || !input.value.trim()) {
    if (input) input.focus();
    show('请先输入搜索内容', true);
    return;
  }
  applyUserSearch(false);
  if (userSearchMatches.length === 0) {
    show('没有找到匹配的用户', true);
    return;
  }
  userSearchMatches.forEach(function(row) { row.classList.remove('search-current'); });
  const step = direction < 0 ? -1 : 1;
  userSearchIndex = (userSearchIndex + step + userSearchMatches.length) % userSearchMatches.length;
  const row = userSearchMatches[userSearchIndex];
  row.classList.add('search-current');
  row.scrollIntoView({behavior: 'smooth', block: 'center', inline: 'nearest'});
  el('userSearchStatus').textContent =
    '第 ' + (userSearchIndex + 1) + ' / ' + userSearchMatches.length + ' 个匹配';
}
function handleUserSearchKey(event) {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  locateUserSearch(event.shiftKey ? -1 : 1);
}
function clearUserSearch() {
  const input = el('userSearch');
  if (!input) return;
  input.value = '';
  applyUserSearch();
  input.focus();
}
function renderUsers(data) {
  currentUsersData = data || {users: []};
  dirtyUserIds.clear();
  const totals = data.totals || {};
  const activeUsers = data.activeUsers || {};
  const today = totals.today || {};
  const month = totals.month || {};
  el('userSummary').textContent =
    '账户 ' + ((data.users || []).length) +
    ' · 日活 ' + (Number(activeUsers.today) || 0) +
    ' · 月活 ' + (Number(activeUsers.month) || 0) +
    ' · 今日 ' + usageTotal(today) +
    ' · 本月 ' + usageTotal(month) +
    ' · 累计 ' + usageTotal(totals.allTime);
  const rows = (data.users || []).map(function(user) {
    return '<tr class="user-row" data-user-row="' + esc(user.id) + '">' +
      '<td><code title="' + esc(user.id) + '">' + esc(user.id.slice(0, 16) + '…') + '</code></td>' +
      '<td><input class="sheet-input" data-user-field="nickname" maxlength="24" value="' + esc(user.nickname) + '"></td>' +
      '<td><select class="sheet-select" data-user-field="level">' + userLevelOptions(user.level) + '</select></td>' +
      '<td><input class="sheet-input" data-user-field="brand" maxlength="80" value="' + esc(user.brand) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="model" maxlength="120" value="' + esc(user.model) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="androidVersion" maxlength="80" value="' + esc(user.androidVersion) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="appVersion" maxlength="40" value="' + esc(user.appVersion) + '"></td>' +
      '<td class="readonly">' + (user.activeToday ? '是' : '否') + '</td>' +
      '<td class="readonly">' + (user.activeThisMonth ? '是' : '否') + '</td>' +
      '<td class="readonly">' + usageTotal(user.today) + '</td>' +
      '<td class="readonly">' + usageTotal(user.month) + '</td>' +
      '<td class="readonly">' + usageTotal(user) + '</td>' +
      '<td class="readonly">' + esc(user.createdAt || '暂无') + '</td>' +
      '<td class="readonly">' + esc(user.lastSeenAt || '暂无') + '</td>' +
      '<td class="readonly">' + esc(user.lastReportAt || '暂无') + '</td>' +
      '<td><button class="sheet-save" type="button" data-save-user="' + esc(user.id) + '">保存</button></td>' +
      '</tr>';
  }).join('');
  el('usersTable').innerHTML = rows
    ? '<table class="user-sheet">' + userSheetColgroup() + '<thead><tr>' +
      userSheetHeader() + '</tr></thead><tbody>' + rows + '</tbody></table>'
    : '<small>暂无注册用户。</small>';
  attachUserColumnResizers();
  el('usersTable').querySelectorAll('[data-user-field]').forEach(function(control) {
    control.oninput = function() { markUserDirty(control); applyUserSearch(false); };
    control.onchange = function() { markUserDirty(control); applyUserSearch(false); };
  });
  el('usersTable').querySelectorAll('[data-save-user]').forEach(function(button) {
    button.onclick = function() { saveUser(button.dataset.saveUser); };
  });
  updateUserToolbar();
  applyUserSearch();
}
function markUserDirty(control) {
  const row = control.closest('[data-user-row]');
  if (!row) return;
  dirtyUserIds.add(row.dataset.userRow);
  row.classList.add('dirty');
  updateUserToolbar();
}
function updateUserToolbar() {
  const button = el('saveAllUsers');
  if (!button) return;
  button.disabled = savingAllUsers || dirtyUserIds.size === 0;
  button.textContent = savingAllUsers
    ? '保存中…'
    : dirtyUserIds.size
    ? '保存全部修改（' + dirtyUserIds.size + '）'
    : '保存全部修改';
}
function userRow(accountId) {
  return el('usersTable').querySelector('[data-user-row="' + accountId + '"]');
}
function userFieldValue(row, field) {
  const control = row && row.querySelector('[data-user-field="' + field + '"]');
  return control ? control.value.trim() : '';
}
async function loadUsers(force) {
  if (!force && dirtyUserIds.size > 0 && !confirm('刷新会丢弃尚未保存的修改，确定继续吗？')) {
    return false;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users', {headers: headers()});
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(data.error || ('读取用户失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    renderUsers(data);
    return true;
  } catch (error) {
    show('读取用户失败，请检查 USERS_DB 绑定', true);
    return false;
  }
}
async function saveUser(accountId, silent) {
  const row = userRow(accountId);
  if (!row) return false;
  const payload = {
    nickname: userFieldValue(row, 'nickname'),
    level: Number(userFieldValue(row, 'level')),
    brand: userFieldValue(row, 'brand'),
    model: userFieldValue(row, 'model'),
    androidVersion: userFieldValue(row, 'androidVersion'),
    appVersion: userFieldValue(row, 'appVersion')
  };
  if (!payload.nickname) {
    show('昵称不能为空', true);
    return false;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(accountId), {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify(payload)
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(data.error || ('用户信息保存失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    dirtyUserIds.delete(accountId);
    row.classList.remove('dirty');
    updateUserToolbar();
    if (!silent) show('用户信息已保存');
    return true;
  } catch (error) {
    show('用户信息保存失败，请检查网络', true);
    return false;
  }
}
async function saveAllUsers() {
  const accountIds = Array.from(dirtyUserIds);
  if (accountIds.length === 0) return;
  savingAllUsers = true;
  updateUserToolbar();
  let saved = 0;
  for (const accountId of accountIds) {
    if (await saveUser(accountId, true)) saved++;
  }
  savingAllUsers = false;
  updateUserToolbar();
  if (saved === accountIds.length) {
    show('已保存 ' + saved + ' 个用户的修改');
  } else {
    show('已保存 ' + saved + ' 个，仍有 ' + dirtyUserIds.size + ' 个保存失败', true);
  }
}
function csvCell(value) {
  return '"' + String(value == null ? '' : value).split('"').join('""') + '"';
}
function exportUsersCsv() {
  const users = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users
    : [];
  if (users.length === 0) {
    show('暂无可导出的用户数据', true);
    return;
  }
  const headers = [
    '账户哈希', '昵称', '等级', '品牌', '型号', 'Android', 'App版本',
    '今日活跃', '本月活跃', '今日上传(MB)', '今日下载(MB)', '今日总量(MB)',
    '本月上传(MB)', '本月下载(MB)', '本月总量(MB)',
    '累计上传(MB)', '累计下载(MB)', '累计总量(MB)',
    '注册时间', '最后活跃', '最后上报'
  ];
  const records = users.map(function(user) {
    const row = userRow(user.id);
    return [
      user.id,
      userFieldValue(row, 'nickname') || user.nickname,
      userFieldValue(row, 'level'),
      userFieldValue(row, 'brand'),
      userFieldValue(row, 'model'),
      userFieldValue(row, 'androidVersion'),
      userFieldValue(row, 'appVersion'),
      user.activeToday ? '是' : '否',
      user.activeThisMonth ? '是' : '否',
      csvMegabytes(user.today && user.today.uploadMb),
      csvMegabytes(user.today && user.today.downloadMb),
      csvMegabytes(user.today && user.today.totalMb),
      csvMegabytes(user.month && user.month.uploadMb),
      csvMegabytes(user.month && user.month.downloadMb),
      csvMegabytes(user.month && user.month.totalMb),
      csvMegabytes(user.totalUploadMb),
      csvMegabytes(user.totalDownloadMb),
      csvMegabytes(user.totalMb),
      user.createdAt || '',
      user.lastSeenAt || '',
      user.lastReportAt || ''
    ];
  });
  const lineBreak = String.fromCharCode(13, 10);
  const csv = [headers].concat(records).map(function(record) {
    return record.map(csvCell).join(',');
  }).join(lineBreak);
  const blob = new Blob([String.fromCharCode(0xfeff), csv], {type: 'text/csv;charset=utf-8'});
  const downloadUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = downloadUrl;
  link.download = 'firefly-users-' + (currentUsersData.date || 'export') + '.csv';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(function() { URL.revokeObjectURL(downloadUrl); }, 1000);
  show('用户表格已导出，可直接使用 Excel 打开');
}
function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  row.innerHTML = '<div class="grid">' +
    '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
    '<div class="field"><label>外部明文订阅地址（与纯文本二选一）<input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/plain-sub"></label></div>' +
    '<div class="field"><label>外部接口缓存（分钟）<input data-field="cacheMinutes" type="number" min="0" max="10080" step="1" value="' + esc(source.cacheMinutes == null ? 30 : source.cacheMinutes) + '"></label></div>' +
    '<div class="field"><label>最低账户等级<select data-field="minLevel">' + levelOptions(source.minLevel == null ? 1 : source.minLevel) + '</select></label></div>' +
    '<div class="field"><label>后台备注（仅后台保存）<input data-field="remark" type="text" value="' + esc(source.remark) + '" placeholder="例如：备用线路、长期稳定"></label></div>' +
    '</div><div class="field"><label>纯文本节点（每行一个，填写后由 Worker 自动加密）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div>' +
    '<label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
    '<div class="row-actions"><button class="danger" type="button">删除</button></div>';
  row.querySelector('.danger').onclick = function() { row.remove(); };
  el('subscriptionRows').appendChild(row);
}
function fillState(state) {
  currentState = state || {};
  el('subscriptionRows').innerHTML = '';
  (currentState.subscriptions || []).forEach(addSource);
  if (!currentState.subscriptions || currentState.subscriptions.length === 0) addSource();
  const settings = currentState.settings || {};
  setValue('websiteUrl', settings.websiteUrl);
  setValue('feedbackEmail', settings.feedbackEmail);
  setValue('feedbackUrl', settings.feedbackUrl);
  setValue('githubUrl', settings.githubUrl);
  setValue('nodeRequestTimeoutMs', settings.nodeRequestTimeoutMs || 25000);
  const notice = currentState.notice || {};
  checked('noticeEnabled', Boolean(currentState.notice && notice.hasNotice !== false));
  setValue('noticeTitle', notice.title);
  setValue('noticeId', notice.noticeId);
  setValue('noticeContent', notice.content);
  checked('noticeShowOnce', notice.showOnce !== false);
  const update = currentState.appUpdate || {};
  checked('appUpdateEnabled', Boolean(currentState.appUpdate));
  setValue('appVersion', update.version);
  setValue('appVersionCode', update.versionCode);
  setValue('appDownloadUrl', update.downloadUrl);
  setValue('appChangelog', update.changelog);
  checked('appForce', update.is_force === 1 || update.isForce === 1);
}
function formState() {
  const subscriptions = Array.from(document.querySelectorAll('.source-row')).map(function(row, index) {
    const content = row.querySelector('[data-field="content"]').value.trim();
    return {
      name: row.querySelector('[data-field="name"]').value.trim() || '线路' + (index + 1),
      url: row.querySelector('[data-field="url"]').value.trim(),
      enabled: row.querySelector('[data-field="enabled"]').checked,
      minLevel: Number(row.querySelector('[data-field="minLevel"]').value || 1),
      cacheMinutes: Number(row.querySelector('[data-field="cacheMinutes"]').value || 0),
      content: content,
      remark: row.querySelector('[data-field="remark"]').value.trim()
    };
  }).filter(function(item) { return item.url || item.content; });
  const notice = el('noticeEnabled').checked ? {
    hasNotice: true,
    title: value('noticeTitle'),
    content: value('noticeContent'),
    noticeId: value('noticeId'),
    showOnce: el('noticeShowOnce').checked
  } : null;
  const appUpdate = el('appUpdateEnabled').checked ? {
    version: value('appVersion'),
    versionCode: Number(value('appVersionCode') || 0),
    downloadUrl: value('appDownloadUrl'),
    changelog: value('appChangelog'),
    is_force: el('appForce').checked ? 1 : 0
  } : null;
  return {
    subscriptions: subscriptions,
    settings: {
      websiteUrl: value('websiteUrl'),
      feedbackEmail: value('feedbackEmail'),
      feedbackUrl: value('feedbackUrl'),
      githubUrl: value('githubUrl'),
      nodeRequestTimeoutMs: Number(value('nodeRequestTimeoutMs') || 25000)
    },
    notice: notice,
    appUpdate: appUpdate
  };
}
async function pushSection(section) {
  if (section === 'users') {
    if (await loadUsers()) show('用户统计已刷新');
    return;
  }

  const form = formState();
  const state = {
    subscriptions: section === 'subscriptions' ? form.subscriptions : currentState.subscriptions,
    settings: section === 'app-settings' ? form.settings : currentState.settings,
    notice: section === 'notice' ? form.notice : currentState.notice,
    appUpdate: section === 'app-update' ? form.appUpdate : currentState.appUpdate
  };
  const response = await fetch(ADMIN_API_BASE + '/state', {
    method: 'PUT',
    headers: headers(),
    body: JSON.stringify(state)
  });
  const data = await response.json();
  if (!response.ok) return show(adminErrorMessage(data.error, '推送失败'), true);
  currentState = data.state;
  fillState(data.state);
  show('当前模块已推送');
}
addSource();
</script>
</body>
</html>`;
}
