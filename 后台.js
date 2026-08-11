
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
 *   DELETE /<ADMIN_UUID>/api/users/<ACCOUNT_ID>
 *   PATCH /<ADMIN_UUID>/api/users/batch       // batch level changes
 *   DELETE /<ADMIN_UUID>/api/users/batch      // batch user deletion
 */

const STATE_KEY = "fireflyvpn:state:v1";
const CLIENT_IP_HEADER = "X-Firefly-Client-IP";
const MAX_MANAGED_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;
const MAX_BATCH_USER_COUNT = 100;
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
`CREATE INDEX IF NOT EXISTS idx_reported_sessions_mb_day
  ON reported_sessions_mb(day_key, account_id)`,
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
    generatedAt: beijingTimestamp(),
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
  if (source.minLevel > access.level) {
    return json({ ok: false, error: "insufficient_level" }, 403);
  }
  if (source.minAppVersion && !isAppVersionAtLeast(access.appVersion, source.minAppVersion)) {
    return json({
      ok: false,
      error: "app_version_too_low",
      requiredVersion: source.minAppVersion,
      currentVersion: access.appVersion || null,
    }, 403);
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
    ).bind(nickname, beijingTimestamp(), accountId).run();
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
    const timestamp = beijingTimestamp(now);
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
    if (!inserted.meta || Number(inserted.meta.changes) < 1) {
      await db.prepare("UPDATE users_mb SET last_seen_at = ?, updated_at = ? WHERE id = ?")
        .bind(timestamp, timestamp, accountId).run();
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
    // 只有成功接收新的流量会话后，last_report_at 才会更新。
    // 不使用旧版流量记录的 active 标记，因为早期版本可能在注册时就将其设为活跃。
    const query = await db.prepare(`SELECT
      u.*,
      COALESCE(d.upload_mb, 0) AS today_upload_mb,
      COALESCE(d.download_mb, 0) AS today_download_mb,
      CASE WHEN substr(COALESCE(u.last_report_at, ''), 1, 10) = ?
        THEN 1 ELSE 0 END AS active_today,
      COALESCE(m.upload_mb, 0) AS month_upload_mb,
      COALESCE(m.download_mb, 0) AS month_download_mb,
      CASE WHEN substr(COALESCE(u.last_report_at, ''), 1, 7) = ?
        THEN 1 ELSE 0 END AS active_this_month
      FROM users_mb u
      LEFT JOIN user_usage_mb d ON d.account_id = u.id
        AND d.period_type = 'day' AND d.period_key = ?
      LEFT JOIN user_usage_mb m ON m.account_id = u.id
        AND m.period_type = 'month' AND m.period_key = ?
      ORDER BY u.last_seen_at DESC`).bind(day, month, day, month).all();
    const publicUsers = (query.results || []).map(publicAdminUser);
    const analyticsStartDay = shiftDayKey(day, -119);
    const hourlyStart = beijingTimestamp(new Date(Date.now() - 48 * 60 * 60 * 1000));
    const analyticsResults = await db.batch([
      db.prepare(`WITH RECURSIVE dates(day_key) AS (
        SELECT ?
        UNION ALL
        SELECT date(day_key, '+1 day') FROM dates WHERE day_key < ?
      ), daily_usage AS (
        SELECT period_key AS day_key,
          SUM(upload_mb) AS upload_mb,
          SUM(download_mb) AS download_mb
        FROM user_usage_mb
        WHERE period_type = 'day' AND period_key >= ?
        GROUP BY period_key
      ), daily_activity_source AS (
        SELECT account_id, day_key
        FROM reported_sessions_mb
        WHERE day_key >= ?
        UNION
        SELECT account_id, period_key AS day_key
        FROM user_usage_mb
        WHERE period_type = 'day' AND period_key >= ?
          AND (upload_mb > 0 OR download_mb > 0)
      ), daily_active AS (
        SELECT day_key, COUNT(DISTINCT account_id) AS dau
        FROM daily_activity_source
        GROUP BY day_key
      ), daily_registration AS (
        SELECT substr(created_at, 1, 10) AS day_key, COUNT(*) AS registrations
        FROM users_mb
        WHERE substr(created_at, 1, 10) >= ?
        GROUP BY substr(created_at, 1, 10)
      )
      SELECT dates.day_key,
        COALESCE(daily_active.dau, 0) AS dau,
        (SELECT COUNT(DISTINCT activity.account_id)
          FROM daily_activity_source activity
          WHERE activity.day_key BETWEEN date(dates.day_key, '-29 days') AND dates.day_key
        ) AS mau,
        COALESCE(daily_registration.registrations, 0) AS registrations,
        COALESCE(daily_usage.upload_mb, 0) AS upload_mb,
        COALESCE(daily_usage.download_mb, 0) AS download_mb
      FROM dates
      LEFT JOIN daily_usage ON daily_usage.day_key = dates.day_key
      LEFT JOIN daily_active ON daily_active.day_key = dates.day_key
      LEFT JOIN daily_registration ON daily_registration.day_key = dates.day_key
      ORDER BY dates.day_key`)
        .bind(
          analyticsStartDay,
          day,
          analyticsStartDay,
          analyticsStartDay,
          analyticsStartDay,
          analyticsStartDay,
        ),
      db.prepare(`SELECT
        substr(reported_at, 1, 13) || ':00:00+08:00' AS hour,
        SUM(upload_mb) AS upload_mb,
        SUM(download_mb) AS download_mb,
        COUNT(DISTINCT account_id) AS active_users
      FROM reported_sessions_mb
      WHERE reported_at >= ?
      GROUP BY substr(reported_at, 1, 13)
      ORDER BY hour`).bind(hourlyStart),
    ]);
    const dailyAnalytics = ((analyticsResults[0] && analyticsResults[0].results) || [])
      .map(publicDailyAnalytics);
    const hourlyAnalytics = ((analyticsResults[1] && analyticsResults[1].results) || [])
      .map(publicHourlyAnalytics);

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
      analytics: {
        historyStart: analyticsStartDay,
        historyEnd: day,
        daily: dailyAnalytics,
        hourly: hourlyAnalytics,
        dimensions: {
          platform: "inferred_from_existing_device_fields",
          systemVersion: "android_version_only",
        },
      },
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/users/batch" && request.method === "PATCH") {
    const body = await request.json();
    const accountIds = normalizeBatchAccountIds(body && body.accountIds);
    const level = Number(body && body.level);
    if (
      accountIds.length === 0 ||
      accountIds.length > MAX_BATCH_USER_COUNT ||
      !Number.isInteger(level) ||
      level < 0 ||
      level > 6
    ) {
      return json({ ok: false, error: "invalid_batch_user_update" }, 400);
    }

    const db = await ensureUserSchema(env);
    const timestamp = beijingTimestamp();
    const result = await db.batch(accountIds.map((accountId) => db.prepare(
      "UPDATE users_mb SET level = ?, updated_at = ? WHERE id = ?",
    ).bind(level, timestamp, accountId)));
    const changed = result.reduce(
      (total, item) => total + Number(item && item.meta && item.meta.changes || 0),
      0,
    );
    return json({ ok: true, accountIds, level, changed });
  }

  if (path === "/api/users/batch" && request.method === "DELETE") {
    const body = await request.json();
    const accountIds = normalizeBatchAccountIds(body && body.accountIds);
    if (accountIds.length === 0 || accountIds.length > MAX_BATCH_USER_COUNT) {
      return json({ ok: false, error: "invalid_batch_user_delete" }, 400);
    }

    const db = await ensureUserSchema(env);
    const statements = [];
    for (const accountId of accountIds) {
      statements.push(
        db.prepare("DELETE FROM reported_sessions_mb WHERE account_id = ?").bind(accountId),
        db.prepare("DELETE FROM user_usage_mb WHERE account_id = ?").bind(accountId),
        db.prepare("DELETE FROM users_mb WHERE id = ?").bind(accountId),
      );
    }
    const result = await db.batch(statements);
    const changed = accountIds.reduce(
      (total, _accountId, index) => total + Number(
        result[index * 3 + 2] && result[index * 3 + 2].meta
          ? result[index * 3 + 2].meta.changes
          : 0,
      ),
      0,
    );
    return json({ ok: true, accountIds, changed });
  }

  const userMatch = path.match(/^\/api\/users\/([a-f0-9]{64})$/);
  if (userMatch && request.method === "DELETE") {
    const accountId = normalizeAccountId(userMatch[1]);
    const db = await ensureUserSchema(env);
    const result = await db.batch([
      db.prepare("DELETE FROM reported_sessions_mb WHERE account_id = ?").bind(accountId),
      db.prepare("DELETE FROM user_usage_mb WHERE account_id = ?").bind(accountId),
      db.prepare("DELETE FROM users_mb WHERE id = ?").bind(accountId),
    ]);
    const deleteResult = result[result.length - 1];
    if (!deleteResult || !deleteResult.meta || Number(deleteResult.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({ ok: true, accountId });
  }

  if (userMatch && request.method === "PATCH") {
    const accountId = normalizeAccountId(userMatch[1]);
    const body = await request.json();
    const assignments = [];
    const values = [];
    const db = await ensureUserSchema(env);
    const timestamp = beijingTimestamp();

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
    try {
      const next = normalizeState(incoming);
      await validateAndWarmSubscriptionSources(env, next.subscriptions);
      next.updatedAt = beijingTimestamp();
      await writeState(env, next);
      return json({ ok: true, state: next });
    } catch (error) {
      return json({
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }, 400);
    }
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

function publicDailyAnalytics(row) {
  const uploadMb = boundedMegabytes(row.upload_mb);
  const downloadMb = boundedMegabytes(row.download_mb);
  return {
    date: cleanText(row.day_key),
    dau: Math.max(0, Math.round(Number(row.dau) || 0)),
    mau: Math.max(0, Math.round(Number(row.mau) || 0)),
    registrations: Math.max(0, Math.round(Number(row.registrations) || 0)),
    uploadMb,
    downloadMb,
    totalMb: uploadMb + downloadMb,
  };
}

function publicHourlyAnalytics(row) {
  const uploadMb = boundedMegabytes(row.upload_mb);
  const downloadMb = boundedMegabytes(row.download_mb);
  return {
    timestamp: cleanText(row.hour),
    activeUsers: Math.max(0, Math.round(Number(row.active_users) || 0)),
    uploadMb,
    downloadMb,
    totalMb: uploadMb + downloadMb,
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
  const row = await db.prepare("SELECT level, app_version FROM users_mb WHERE id = ?")
    .bind(accountId).first();
  return row
    ? {
      level: normalizeLevel(row.level, 0),
      appVersion: cleanText(row.app_version),
    }
    : { level: 0, appVersion: "" };
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
  const timestamp = beijingTimestamp(now);
  const brand = cleanText(profile.brand).slice(0, 80);
  const model = cleanText(profile.model).slice(0, 120);
  const androidVersion = cleanText(profile.androidVersion).slice(0, 80);
  const appVersion = cleanText(profile.appVersion).slice(0, 40);
  await db.prepare(`INSERT INTO users_mb
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
    ).run();
}

function normalizeNickname(value, fallback = "") {
  const nickname = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 24);
  return nickname || fallback;
}

function normalizeLevel(value, fallback = 1) {
  const level = Number(value);
  return Number.isInteger(level) && level >= 0 && level <= 6 ? level : fallback;
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

function shiftDayKey(dayKey, offsetDays) {
  const parts = String(dayKey || "").split("-").map(Number);
  if (parts.length !== 3 || parts.some((part) => !Number.isInteger(part))) return dayKey;
  const date = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
  date.setUTCDate(date.getUTCDate() + Number(offsetDays || 0));
  return date.toISOString().slice(0, 10);
}

function beijingTimestamp(date = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`;
}

function normalizeAccountId(value) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

function normalizeBatchAccountIds(value) {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.map(normalizeAccountId).filter(Boolean)));
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

function normalizeMinimumAppVersion(value) {
  const version = cleanText(value).replace(/^v/i, "").slice(0, 40);
  if (!version) return "";
  if (!/^\d+(?:\.\d+)*$/.test(version)) {
    throw new Error("invalid_min_app_version");
  }
  return version;
}

function appVersionParts(value) {
  const version = cleanText(value).replace(/^v/i, "");
  if (!/^\d+(?:\.\d+)*$/.test(version)) return null;
  return version.split(".").map((part) => Number(part));
}

function isAppVersionAtLeast(currentVersion, minimumVersion) {
  const current = appVersionParts(currentVersion);
  const minimum = appVersionParts(minimumVersion);
  if (!current || !minimum) return false;
  const length = Math.max(current.length, minimum.length);
  for (let index = 0; index < length; index += 1) {
    const currentPart = current[index] || 0;
    const minimumPart = minimum[index] || 0;
    if (currentPart !== minimumPart) return currentPart > minimumPart;
  }
  return true;
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
        minAppVersion: normalizeMinimumAppVersion(
          item.minAppVersion ?? item.minimumAppVersion,
        ),
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
      minAppVersion: item.minAppVersion,
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
    "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
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
  <title>管理后台</title>
  <style>
    :root { color-scheme: dark; font-family: system-ui, -apple-system, sans-serif; }
     body { min-height: 100vh; margin: 0; background: #111827; color: #e5e7eb; }
     main { width: 100%; min-height: 100vh; margin: 0; }
     h1 { font-size: 24px; margin: 0 0 6px; }
     h2 { font-size: 18px; margin: 0 0 14px; }
     p, small, label { color: #aab4c5; line-height: 1.55; }
     .card { background: #1f2937; border: 1px solid #374151; border-radius: 14px; padding: 18px; margin-top: 16px; }
     .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
     .analytics-filter-bar { position: sticky; top: 0; z-index: 12; box-sizing: border-box; padding: 14px 28px 12px; background: rgba(17, 24, 39, .97); border-bottom: 1px solid #374151; backdrop-filter: blur(10px); }
     .analytics-filter-bar[hidden] { display: none; }
     .filter-row, .range-presets, .custom-date-range { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
     .range-button, .filter-reset, .text-button { margin: 0; }
     .range-button { padding: 8px 11px; color: #cbd5e1; background: #1f2937; border: 1px solid #374151; font-size: 12px; }
     .range-button:hover { background: #334155; }
     .range-button.active { color: #052e16; background: #4ade80; border-color: #4ade80; }
     .analytics-filter-select, .chart-select { width: auto; min-width: 128px; padding: 8px 10px; }
     .custom-date-range { margin-top: 10px; }
     .custom-date-range[hidden] { display: none; }
     .custom-date-range label { display: flex; align-items: center; gap: 8px; }
     .custom-date-range input { width: auto; padding: 8px; }
     .custom-date-range button { margin: 0; padding: 8px 12px; }
     #analyticsFilterStatus { display: block; margin-top: 9px; }
     .analytics-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 18px; }
     .analytics-heading h2 { margin-bottom: 5px; }
     .analytics-heading p { margin: 0; }
     .analytics-heading button { margin: 0; }
     .data-note { max-width: 420px; text-align: right; }
     .kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 18px; }
     .kpi-card { box-sizing: border-box; min-width: 0; min-height: 128px; padding: 15px 16px; background: #1f2937; border: 1px solid #374151; border-radius: 12px; }
     .kpi-topline { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
     .kpi-label { color: #94a3b8; font-size: 13px; }
     .kpi-value { display: block; margin-top: 9px; color: #f8fafc; font-size: clamp(24px, 2.25vw, 34px); line-height: 1.08; letter-spacing: -.02em; }
     .kpi-comparison { display: block; margin-top: 9px; color: #94a3b8; font-size: 12px; }
     .trend-pill { flex: 0 0 auto; padding: 3px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; }
     .trend-up { color: #86efac; background: rgba(34, 197, 94, .14); }
     .trend-down { color: #fca5a5; background: rgba(239, 68, 68, .14); }
     .trend-flat { color: #cbd5e1; background: rgba(148, 163, 184, .14); }
     .analytics-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
     .analytics-card { box-sizing: border-box; min-width: 0; padding: 17px; background: #1f2937; border: 1px solid #374151; border-radius: 12px; }
     .analytics-card.span-2 { grid-column: 1 / -1; }
     .chart-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
     .chart-controls { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
     .chart-heading h3 { margin: 0 0 4px; color: #e5e7eb; font-size: 15px; }
     .chart-heading small { display: block; }
     .chart-host { position: relative; min-height: 250px; overflow: hidden; }
     .chart-host-large { min-height: 310px; }
     .chart-svg { display: block; width: 100%; height: 100%; min-height: inherit; overflow: visible; }
     .chart-grid-line { stroke: #334155; stroke-width: 1; }
     .chart-axis-label { fill: #94a3b8; font-size: 11px; }
     .chart-line { fill: none; stroke: #4ade80; stroke-width: 2.5; stroke-linecap: round; stroke-linejoin: round; }
     .chart-line-secondary { fill: none; stroke: #38bdf8; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
     .chart-area-upload { fill: rgba(56, 189, 248, .42); stroke: #38bdf8; stroke-width: 1.5; }
     .chart-area-download { fill: rgba(74, 222, 128, .38); stroke: #4ade80; stroke-width: 1.5; }
     .chart-bar { fill: #22c55e; opacity: .8; }
     .chart-point { fill: #111827; stroke: #4ade80; stroke-width: 2; }
     [data-tooltip] { cursor: crosshair; }
     .chart-tooltip { position: fixed; z-index: 100; max-width: 250px; padding: 8px 10px; pointer-events: none; color: #f8fafc; background: #020617; border: 1px solid #475569; border-radius: 7px; box-shadow: 0 10px 30px rgba(0,0,0,.35); font-size: 12px; white-space: pre-line; transform: translate(12px, 12px); }
     .chart-tooltip[hidden] { display: none; }
     .chart-legend { display: flex; gap: 12px; color: #94a3b8; font-size: 12px; }
     .chart-legend span { display: inline-flex; align-items: center; gap: 5px; }
     .chart-legend i { width: 9px; height: 9px; border-radius: 2px; }
     .legend-upload { background: #38bdf8; }
     .legend-download { background: #4ade80; }
     .empty-state, .loading-state, .error-state { display: flex; min-height: 220px; align-items: center; justify-content: center; padding: 18px; color: #94a3b8; text-align: center; }
     .error-state { color: #fca5a5; }
     .kpi-grid > .empty-state, .kpi-grid > .loading-state, .kpi-grid > .error-state { grid-column: 1 / -1; }
     .bar-chart-host { min-height: 260px; }
     .distribution-row { display: grid; grid-template-columns: minmax(90px, 150px) minmax(120px, 1fr) auto; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid rgba(55, 65, 81, .7); }
     .distribution-row:last-child { border-bottom: 0; }
     button.distribution-label { overflow: hidden; margin: 0; padding: 0; color: #dbeafe; background: transparent; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
     button.distribution-label:hover { color: #86efac; }
     .distribution-track { height: 9px; overflow: hidden; background: #111827; border-radius: 999px; }
     .distribution-fill { display: block; height: 100%; background: #38bdf8; border-radius: inherit; }
     .distribution-row:nth-child(even) .distribution-fill { background: #4ade80; }
     .distribution-values { min-width: 125px; color: #cbd5e1; font-size: 12px; text-align: right; }
     .distribution-meta { display: block; margin-top: 2px; color: #64748b; }
     .stat-list { display: grid; gap: 9px; }
     .stat-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 11px 12px; background: #111827; border: 1px solid #334155; border-radius: 8px; }
     .stat-row strong { color: #f8fafc; }
     .structure-host { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
     .structure-card { padding: 16px; background: #111827; border: 1px solid #334155; border-radius: 10px; }
     .structure-card strong { display: block; margin: 7px 0; color: #f8fafc; font-size: 25px; }
     .compact-table-wrap { max-height: 330px; overflow: auto; border: 1px solid #374151; border-radius: 8px; }
     table.compact-table { width: 100%; border-collapse: collapse; font-size: 12px; }
     .compact-table th, .compact-table td { padding: 9px 10px; text-align: left; border-bottom: 1px solid #374151; white-space: nowrap; }
     .compact-table th { position: sticky; top: 0; z-index: 1; color: #cbd5e1; background: #273244; }
     .compact-table td { color: #cbd5e1; }
     .alert-preview, .alert-list { display: grid; gap: 9px; }
     .alert-item { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 12px; color: inherit; background: #111827; border: 1px solid #374151; border-radius: 9px; text-align: left; }
     button.alert-item { width: 100%; margin: 0; font-weight: 400; }
     button.alert-item:hover { border-color: #64748b; }
     .severity-dot { width: 9px; height: 9px; border-radius: 50%; }
     .severity-critical { color: #fecaca; background: #450a0a; }
     .severity-warning { color: #fed7aa; background: #431407; }
     .severity-attention { color: #fef08a; background: #422006; }
     .severity-normal { color: #bbf7d0; background: #052e16; }
     .severity-dot.severity-critical { background: #ef4444; }
     .severity-dot.severity-warning { background: #f97316; }
     .severity-dot.severity-attention { background: #eab308; }
     .severity-dot.severity-normal { background: #22c55e; }
     .severity-badge { padding: 4px 7px; border-radius: 999px; font-size: 11px; }
     .alert-main strong { display: block; color: #e5e7eb; font-size: 13px; }
     .alert-main small { display: block; margin-top: 3px; }
     .alert-value { color: #f8fafc; font-weight: 700; text-align: right; }
     .alert-kpis { grid-template-columns: repeat(4, minmax(0, 1fr)); }
     .text-button { padding: 0; color: #7dd3fc; background: transparent; font-size: 12px; }
     #dashboard { display: grid; grid-template-columns: clamp(170px, 16vw, 230px) minmax(0, 1fr); min-height: 100vh; }
     #dashboard[hidden] { display: none; }
     .dashboard-sidebar { position: sticky; top: 0; box-sizing: border-box; display: flex; flex-direction: column; width: 100%; height: 100vh; padding: 22px 14px 16px; overflow: hidden; background: #1f2937; border-right: 1px solid #374151; }
     .sidebar-brand { padding: 0 8px 20px; border-bottom: 1px solid #374151; }
     .sidebar-brand h1 { margin: 0; font-size: 20px; }
     .sidebar-brand small { display: block; margin-top: 4px; }
     .tab-nav { display: flex; flex: 1 1 auto; flex-direction: column; gap: 8px; overflow-y: auto; padding: 18px 0; margin: 0; scrollbar-width: thin; }
     .tab-button { flex: 0 0 auto; width: 100%; margin: 0; padding: 12px 14px; text-align: left; background: transparent; color: #aab4c5; border: 1px solid transparent; white-space: nowrap; }
     .tab-button:hover { background: #374151; color: #f9fafb; }
     .tab-button.active { background: #22c55e; color: #052e16; }
     .sidebar-logout { width: 100%; margin: 16px 0 0; }
     .dashboard-workspace { display: flex; flex-direction: column; min-width: 0; min-height: 100vh; background: #111827; }
     .tab-panel { display: none; box-sizing: border-box; flex: 1 1 auto; width: 100%; min-height: 0; padding: 28px; margin: 0; background: #111827; border: 0; border-radius: 0; }
     .tab-panel.active { display: block; }
     .login-gate { max-width: 430px; margin: 12vh auto 0; }
     .login-gate h1 { margin-bottom: 10px; }
     .login-gate .card { margin-top: 18px; }
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
    .source-mode { margin-top: 12px; }
    .source-mode[hidden] { display: none; }
    .source-type-field { max-width: 280px; }
     .source-row .row-actions { display: flex; align-items: center; justify-content: space-between; }
     .source-row small { display: block; margin-top: 8px; }
     .user-sheet-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
     .user-sheet-toolbar button { margin: 0; }
     .user-sheet-toolbar small { margin-left: auto; }
     .batch-level-select { width: auto; min-width: 105px; margin: 0; padding: 9px 10px; }
     .user-search { flex: 1 1 220px; width: min(300px, 100%); min-width: 180px; padding: 9px 10px; }
     .table-wrap { max-height: calc(100vh - 210px); overflow: auto; border: 1px solid #4b5563; border-radius: 10px; background: #111827; }
     table.user-sheet { width: 100%; min-width: 100%; table-layout: fixed; border-collapse: separate; border-spacing: 0; font-size: 13px; }
     .user-sheet th, .user-sheet td { text-align: left; padding: 0; overflow: hidden; text-overflow: ellipsis; border-right: 1px solid #374151; border-bottom: 1px solid #374151; white-space: nowrap; background: #111827; }
     .user-sheet th { position: sticky; top: 0; z-index: 3; padding: 9px 12px 9px 8px; overflow: visible; color: #d1d5db; background: #273244; font-weight: 700; user-select: none; }
     .user-sheet th[data-sort-column] { cursor: pointer; }
     .user-sheet th[data-sort-column]:hover { background: #334155; color: #f8fafc; }
     .sort-indicator { display: inline-block; width: 14px; color: #4ade80; }
     .column-resizer { position: absolute; top: 0; right: -4px; width: 9px; height: 100%; z-index: 6; cursor: col-resize; touch-action: none; }
     .column-resizer::after { content: ''; position: absolute; top: 20%; bottom: 20%; left: 4px; width: 1px; background: #4b5563; }
     .column-resizer:hover::after, .column-resizer:focus::after, body.column-resizing .column-resizer::after { background: #22c55e; width: 2px; }
     body.column-resizing, body.column-resizing * { cursor: col-resize !important; user-select: none !important; }
     .user-sheet th:first-child, .user-sheet td:first-child { position: sticky; left: 0; z-index: 2; }
     .user-sheet th:first-child { z-index: 4; }
     .user-sheet td:first-child { padding: 8px; background: #172033; }
     .user-sheet .selection-cell { text-align: center; padding: 8px !important; }
     .user-sheet .selection-cell input { margin: 0; }
     .user-sheet td.readonly { padding: 8px; color: #cbd5e1; }
     .user-sheet td code { color: #c4b5fd; }
     .sheet-input, .sheet-select { width: 100%; min-width: 0; height: 36px; padding: 7px 8px; border: 0; border-radius: 0; background: transparent; }
     .sheet-input:focus, .sheet-select:focus { position: relative; z-index: 1; outline: 2px solid #22c55e; outline-offset: -2px; background: #0f172a; }
     .sheet-save, .sheet-delete { margin: 4px 2px; padding: 7px 10px; }
     .user-row.dirty td { background: #312e1f; }
     .user-row.dirty td:first-child { background: #3b321d; }
     .user-row.search-current td { box-shadow: inset 0 0 0 2px #38bdf8; }
     .user-row[hidden] { display: none; }
     button:disabled { cursor: not-allowed; opacity: .5; }
     #status { position: sticky; top: 0; z-index: 20; box-sizing: border-box; color: #86efac; background: #111827; }
     #status:not(:empty) { min-height: 44px; padding: 11px 28px; border-bottom: 1px solid #374151; }
     .hint { margin-top: 4px; }
     @media (max-width: 1200px) {
       .kpi-grid, .alert-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
       .analytics-grid { grid-template-columns: 1fr; }
       .analytics-card.span-2 { grid-column: auto; }
     }
     @media (max-width: 760px) {
       #dashboard { grid-template-columns: 148px minmax(0, 1fr); }
       .dashboard-sidebar { padding: 16px 10px 12px; }
       .sidebar-brand { padding: 0 6px 14px; }
       .sidebar-brand h1 { font-size: 17px; }
       .sidebar-brand small { display: none; }
       .tab-nav { padding: 14px 0; }
       .tab-button { padding: 11px 10px; }
       .tab-panel { padding: 18px; }
       .analytics-filter-bar { padding: 12px 18px; }
       .range-presets { flex-wrap: nowrap; width: 100%; overflow-x: auto; padding-bottom: 3px; }
       .range-button { flex: 0 0 auto; }
       .analytics-filter-select { flex: 1 1 140px; min-width: 0; }
       .analytics-heading { flex-direction: column; }
       .chart-heading { align-items: stretch; flex-direction: column; }
       .chart-controls { justify-content: flex-start; }
       .data-note { max-width: none; text-align: left; }
       .kpi-grid, .alert-kpis, .structure-host { grid-template-columns: 1fr; }
       .distribution-row { grid-template-columns: minmax(80px, 120px) minmax(80px, 1fr); }
       .distribution-values { grid-column: 1 / -1; text-align: left; }
       #status:not(:empty) { padding: 10px 18px; }
       .grid { grid-template-columns: 1fr; }
       .table-wrap { max-height: calc(100vh - 190px); }
     }
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
     <aside class="dashboard-sidebar">
       <div class="sidebar-brand">
         <h1>FireflyVPN</h1>
         <small>管理后台</small>
       </div>
       <nav class="tab-nav" role="tablist" aria-label="后台模块" aria-orientation="vertical">
         <button class="tab-button" role="tab" type="button" data-tab="overview">数据总览</button>
         <button class="tab-button" role="tab" type="button" data-tab="user-analysis">用户分析</button>
         <button class="tab-button" role="tab" type="button" data-tab="traffic-analysis">流量分析</button>
         <button class="tab-button" role="tab" type="button" data-tab="devices">设备与版本</button>
         <button class="tab-button" role="tab" type="button" data-tab="alerts">异常监控</button>
         <button class="tab-button" role="tab" type="button" data-tab="users">用户表单</button>
         <button class="tab-button" role="tab" type="button" data-tab="subscriptions">订阅线路</button>
         <button class="tab-button" role="tab" type="button" data-tab="app-settings">应用设置</button>
         <button class="tab-button" role="tab" type="button" data-tab="notice">公告</button>
         <button class="tab-button" role="tab" type="button" data-tab="app-update">版本更新</button>
       </nav>
       <button class="secondary sidebar-logout" type="button" onclick="logout()">退出登录</button>
     </aside>

     <div class="dashboard-workspace">
       <div id="status" role="status" aria-live="polite"></div>

   <div id="analyticsFilterBar" class="analytics-filter-bar" hidden>
     <div class="filter-row">
       <div class="range-presets" role="group" aria-label="时间范围">
         <button class="range-button" type="button" data-range="today">今日</button>
         <button class="range-button" type="button" data-range="yesterday">昨日</button>
         <button class="range-button active" type="button" data-range="7d">最近 7 天</button>
         <button class="range-button" type="button" data-range="30d">最近 30 天</button>
         <button class="range-button" type="button" data-range="month">本月</button>
         <button class="range-button" type="button" data-range="previous-month">上月</button>
         <button class="range-button" type="button" data-range="custom">自定义时间</button>
       </div>
       <select id="analyticsPlatformFilter" class="analytics-filter-select" aria-label="设备平台"><option value="">全部平台</option></select>
       <select id="analyticsSystemFilter" class="analytics-filter-select" aria-label="系统版本"><option value="">全部系统</option></select>
       <select id="analyticsVersionFilter" class="analytics-filter-select" aria-label="客户端版本"><option value="">全部版本</option></select>
       <button class="secondary filter-reset" type="button" onclick="resetAnalyticsFilters()">重置筛选</button>
     </div>
     <div id="customDateRange" class="custom-date-range" hidden>
       <label>开始日期<input id="analyticsStartDate" type="date"></label>
       <label>结束日期<input id="analyticsEndDate" type="date"></label>
       <button type="button" onclick="applyCustomAnalyticsRange()">应用</button>
     </div>
     <small id="analyticsFilterStatus">最近 7 天 · 全部用户</small>
   </div>

   <section class="card tab-panel analytics-panel" role="tabpanel" data-panel="overview">
     <div class="analytics-heading"><div><h2>数据总览</h2><p>用结果、趋势与异常快速判断当前运营状态。</p></div><button class="secondary" type="button" onclick="loadUsers()">刷新数据</button></div>
     <div id="overviewKpis" class="kpi-grid"></div>
     <div class="analytics-grid">
       <section class="analytics-card"><div class="chart-heading"><div><h3>用户活跃趋势</h3><small>独立查看 DAU、MAU 或粘性指标</small></div><div class="chart-controls"><select id="overviewActiveMetric" class="chart-select"><option value="dau">DAU</option><option value="mau">MAU</option><option value="ratio">DAU / MAU</option></select><select id="overviewActiveGrain" class="chart-select" aria-label="活跃趋势时间粒度"><option value="day">按日</option><option value="week">按周</option><option value="month">按月</option></select></div></div><div id="overviewActiveChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>新增用户趋势</h3><small>每日注册与 7 日移动平均</small></div></div><div id="overviewRegistrationChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>上传 / 下载流量趋势</h3><small>堆叠高度代表总流量</small></div></div><div id="overviewTrafficChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>需要关注</h3><small>自动识别明显偏离和版本风险</small></div><button class="text-button" type="button" onclick="activateTab('alerts')">查看全部</button></div><div id="overviewAlerts" class="alert-preview"></div></section>
     </div>
   </section>

   <section class="card tab-panel analytics-panel" role="tabpanel" data-panel="user-analysis">
     <div class="analytics-heading"><div><h2>用户分析</h2><p>关注活跃增长、注册质量和新老用户结构。</p></div></div>
     <div id="userAnalysisKpis" class="kpi-grid"></div>
     <div class="analytics-grid">
       <section class="analytics-card"><div class="chart-heading"><div><h3>用户活跃趋势</h3><small>支持指标与日、周、月粒度切换</small></div><div class="chart-controls"><select id="userActiveMetric" class="chart-select"><option value="dau">DAU</option><option value="mau">MAU</option><option value="ratio">DAU / MAU</option></select><select id="userActiveGrain" class="chart-select" aria-label="活跃趋势时间粒度"><option value="day">按日</option><option value="week">按周</option><option value="month">按月</option></select></div></div><div id="userActiveChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>新增用户趋势</h3><small>柱状图为每日注册，折线为 7 日平均</small></div></div><div id="userRegistrationChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>总用户累计增长</h3><small>基于现存账户注册日期回溯</small></div></div><div id="userCumulativeChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>今日活跃用户结构</h3><small>新注册用户与老用户质量对比</small></div></div><div id="newOldStructure" class="structure-host"></div></section>
     </div>
   </section>

   <section class="card tab-panel analytics-panel" role="tabpanel" data-panel="traffic-analysis">
     <div class="analytics-heading"><div><h2>流量分析</h2><p>判断用户增长与 VPN 流量增长是否匹配。</p></div><select id="trafficRangeMode" class="chart-select"><option value="24h">最近 24 小时</option><option value="7d" selected>最近 7 天</option><option value="30d">最近 30 天</option><option value="month">本月</option><option value="filtered">跟随顶部筛选</option></select></div>
     <div id="trafficKpis" class="kpi-grid"></div>
     <div class="analytics-grid">
       <section class="analytics-card span-2"><div class="chart-heading"><div><h3>上传 / 下载流量趋势</h3><small>堆叠面积图；总高度为总流量</small></div><div class="chart-legend"><span><i class="legend-upload"></i>上传</span><span><i class="legend-download"></i>下载</span></div></div><div id="trafficStackedChart" class="chart-host chart-host-large"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>DAU 人均流量趋势</h3><small>总流量 ÷ 当日活跃用户</small></div></div><div id="perCapitaTrafficChart" class="chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>流量健康度</h3><small>日均、7 日均、30 日均与月累计</small></div></div><div id="trafficHealth" class="stat-list"></div></section>
     </div>
   </section>

   <section class="card tab-panel analytics-panel" role="tabpanel" data-panel="devices">
     <div class="analytics-heading"><div><h2>设备与版本</h2><p>识别主要设备、系统版本和客户端升级风险。</p></div><small class="data-note">平台根据现有设备字段推断；系统版本当前仅有 Android 字段。</small></div>
     <div id="deviceKpis" class="kpi-grid"></div>
     <div class="analytics-grid">
       <section class="analytics-card"><div class="chart-heading"><div><h3>设备平台分布</h3><small>点击平台联动系统版本和型号</small></div></div><div id="platformDistribution" class="bar-chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>操作系统版本</h3><small id="systemVersionSubtitle">按用户规模和活跃率排序</small></div></div><div id="systemVersionDistribution" class="bar-chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>App 客户端版本</h3><small>覆盖率、DAU 与活跃率</small></div></div><div id="appVersionDistribution" class="bar-chart-host"></div></section>
       <section class="analytics-card"><div class="chart-heading"><div><h3>设备型号 TOP 10</h3><small>人均流量使用本月累计口径</small></div></div><div id="deviceModelTable" class="compact-table-wrap"></div></section>
     </div>
   </section>

   <section class="card tab-panel analytics-panel" role="tabpanel" data-panel="alerts">
     <div class="analytics-heading"><div><h2>异常监控</h2><p>自动提示增长、活跃、流量和版本分布中的异常变化。</p></div><button class="secondary" type="button" onclick="loadUsers()">重新检测</button></div>
     <div id="alertKpis" class="kpi-grid alert-kpis"></div>
     <div class="analytics-card"><div class="chart-heading"><div><h3>异常事件</h3><small>点击事件可跳转到对应分析模块</small></div><select id="alertSeverityFilter" class="chart-select"><option value="">全部严重程度</option><option value="critical">严重</option><option value="warning">警告</option><option value="attention">关注</option><option value="normal">正常</option></select></div><div id="alertList" class="alert-list"></div></div>
   </section>

   <section class="card tab-panel" role="tabpanel" data-panel="users">
     <h2>用户表单</h2>
     <div class="user-sheet-toolbar">
       <button class="secondary" type="button" onclick="loadUsers()">刷新</button>
       <button id="saveAllUsers" type="button" onclick="saveAllUsers()" disabled>保存全部修改</button>
       <button class="secondary" type="button" onclick="exportUsersCsv()">导出详细表格（CSV）</button>
       <button class="secondary" type="button" onclick="resetUserColumnWidths()">重置列宽</button>
       <input id="userSearch" class="user-search" type="search" placeholder="搜索账户、昵称、设备或版本" oninput="applyUserSearch()" onkeydown="handleUserSearchKey(event)">
       <button class="secondary" type="button" onclick="locateUserSearch(1)">定位下一个</button>
       <button class="secondary" type="button" onclick="clearUserSearch()">清除</button>
       <select id="batchLevel" class="batch-level-select" title="批量修改等级">
         <option value="">批量等级</option>
         <option value="0">Lv0（封禁）</option>
         <option value="1">Lv1</option>
         <option value="2">Lv2</option>
         <option value="3">Lv3</option>
         <option value="4">Lv4</option>
         <option value="5">Lv5</option>
         <option value="6">Lv6</option>
       </select>
       <button id="batchSetLevelButton" type="button" onclick="batchSetLevel()" disabled>批量改等级</button>
       <button id="batchBanUsersButton" class="danger" type="button" onclick="batchBanUsers()" disabled>批量封禁</button>
       <button id="batchDeleteUsersButton" class="danger" type="button" onclick="batchDeleteUsers()" disabled>批量删除</button>
       <small id="userSearchStatus"></small>
       <small id="userSelectionStatus"></small>
     </div>
     <div id="usersTable" class="table-wrap"></div>
     <small class="hint">可编辑昵称、等级、品牌、型号、Android 和 App 版本；活跃、流量及时间字段为只读明细。支持批量修改、封禁与删除，黄色行表示尚未保存。</small>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="subscriptions">
    <h2>订阅线路</h2>
    <div id="subscriptionRows"></div>
     <button class="secondary" onclick="addSource()">添加线路</button>
     <small class="hint">每条线路只能选择一种来源：外部接口或纯文本节点。外部接口可设置缓存时间；纯文本节点不使用缓存。还可以按账户等级和最低应用版本限制下发。</small>
     <button type="button" onclick="pushSection('subscriptions')">推送</button>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="app-settings">
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

  <section class="card tab-panel" role="tabpanel" data-panel="notice">
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

  <section class="card tab-panel" role="tabpanel" data-panel="app-update">
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
   <div id="chartTooltip" class="chart-tooltip" hidden></div>
   </div>
   </div>
 </main>
<script>
 let currentState = null;
 let adminToken = '';
 let currentUsersData = null;
 const dirtyUserIds = new Set();
 const selectedUserIds = new Set();
 let savingAllUsers = false;
 let userSearchMatches = [];
 let userSearchIndex = -1;
 let userSortKey = '';
 let userSortDirection = 'desc';
 let analyticsRange = '7d';
 let analyticsCustomStart = '';
 let analyticsCustomEnd = '';
 let selectedDevicePlatform = '';
 let analyticsAlerts = [];
 let deviceModelSortKey = 'users';
 let deviceModelSortDirection = 'desc';
 const ANALYTICS_TABS = ['overview', 'user-analysis', 'traffic-analysis', 'devices', 'alerts'];
 const USER_COLUMN_WIDTHS_KEY = 'firefly:user-sheet-column-widths:v1';
 const USER_SHEET_COLUMNS = [
   {key: 'select', label: '选择', width: 48, min: 44},
   {key: 'accountId', label: '账户哈希', width: 135, min: 90},
   {key: 'nickname', label: '昵称', width: 110, min: 70},
   {key: 'level', label: '等级', width: 80, min: 65},
   {key: 'brand', label: '品牌', width: 90, min: 60},
   {key: 'model', label: '型号', width: 120, min: 70},
   {key: 'androidVersion', label: 'Android', width: 85, min: 65},
   {key: 'appVersion', label: 'App 版本', width: 85, min: 65},
   {key: 'activeToday', label: '今日活跃', width: 70, min: 60},
   {key: 'activeMonth', label: '本月活跃', width: 70, min: 60},
   {key: 'trafficTodayUpload', label: '今日上传', width: 90, min: 65},
   {key: 'trafficTodayDownload', label: '今日下载', width: 90, min: 65},
   {key: 'trafficToday', label: '今日总流量', width: 90, min: 65},
   {key: 'trafficMonthUpload', label: '本月上传', width: 90, min: 65},
   {key: 'trafficMonthDownload', label: '本月下载', width: 90, min: 65},
   {key: 'trafficMonth', label: '本月总流量', width: 90, min: 65},
   {key: 'trafficTotalUpload', label: '累计上传', width: 90, min: 65},
   {key: 'trafficTotalDownload', label: '累计下载', width: 90, min: 65},
   {key: 'trafficTotal', label: '累计总流量', width: 90, min: 65},
   {key: 'createdAt', label: '注册时间', width: 140, min: 90},
   {key: 'lastSeenAt', label: '最后访问', width: 140, min: 90},
   {key: 'lastReportAt', label: '最后上报', width: 140, min: 90},
   {key: 'actions', label: '操作', width: 130, min: 100}
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
 function cleanText(value) { return typeof value === 'string' ? value.trim() : ''; }
 function headers() { return {'content-type':'application/json','authorization':'Bearer ' + adminToken}; }
 function show(message, error) { statusEl.textContent = message; statusEl.style.color = error ? '#fca5a5' : '#86efac'; }
 function showLoginError(message) { loginStatusEl.textContent = message || ''; }
  function adminErrorMessage(error, fallback) {
    if (error === 'invalid_batch_user_update') return '批量修改参数无效，最多选择 100 个用户，等级范围为 Lv0～Lv6';
    if (error === 'invalid_batch_user_delete') return '批量删除参数无效，最多选择 100 个用户';
    if (error === 'subscription_must_be_plaintext_nodes') return '订阅必须返回可识别的明文节点，不能使用已加密接口';
    if (error === 'invalid_min_app_version') return '最低应用版本格式无效，请填写类似 2.40 的数字版本';
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
     activateTab('overview');
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
  const analyticsActive = ANALYTICS_TABS.includes(name);
  el('analyticsFilterBar').hidden = !analyticsActive;
  if (analyticsActive && currentUsersData) renderAnalyticsDashboard();
}
document.querySelectorAll('.tab-button').forEach(function(button) {
  button.onclick = function() {
    activateTab(button.dataset.tab);
    window.scrollTo({top: 0, behavior: 'auto'});
  };
});
document.querySelectorAll('[data-range]').forEach(function(button) {
  button.onclick = function() { setAnalyticsRange(button.dataset.range); };
});
el('analyticsPlatformFilter').onchange = function() {
  selectedDevicePlatform = el('analyticsPlatformFilter').value;
  populateAnalyticsDimensionFilters();
  renderAnalyticsDashboard();
};
el('analyticsSystemFilter').onchange = function() {
  populateAnalyticsDimensionFilters();
  renderAnalyticsDashboard();
};
el('analyticsVersionFilter').onchange = renderAnalyticsDashboard;
el('overviewActiveMetric').onchange = renderAnalyticsDashboard;
el('overviewActiveGrain').onchange = renderAnalyticsDashboard;
el('userActiveMetric').onchange = renderAnalyticsDashboard;
el('userActiveGrain').onchange = renderAnalyticsDashboard;
el('trafficRangeMode').onchange = renderTrafficAnalysis;
el('alertSeverityFilter').onchange = renderAlerts;
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
    const sortable = column.key !== 'select' && column.key !== 'actions';
    const sortAttributes = sortable
      ? ' data-sort-column="' + column.key + '" tabindex="0" aria-sort="none" title="Click to sort"'
      : '';
    const heading = column.key === 'select'
      ? '<input type="checkbox" data-select-all-users title="全选当前用户" aria-label="全选当前用户">'
      : esc(column.label);
    return '<th data-user-column-header="' + column.key + '"' + sortAttributes + '>' + heading +
      (sortable ? '<span class="sort-indicator" aria-hidden="true"></span>' : '') +
      '<span class="column-resizer" data-resize-column="' + column.key +
      '" role="separator" tabindex="0" title="拖动调整列宽，双击恢复默认宽度"></span></th>';
  }).join('');
}
function userSortValue(row, columnKey) {
  const accountId = row.dataset.userRow || '';
  const user = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users.find(function(item) { return item.id === accountId; })
    : null;
  if (!user) return accountId;
  const field = function(name, fallback) {
    const control = row.querySelector('[data-user-field="' + name + '"]');
    return control ? control.value.trim() : fallback;
  };
  switch (columnKey) {
    case 'accountId': return user.id;
    case 'nickname': return field('nickname', user.nickname);
    case 'level': return Number(field('level', user.level)) || 0;
    case 'brand': return field('brand', user.brand);
    case 'model': return field('model', user.model);
    case 'androidVersion': return field('androidVersion', user.androidVersion);
    case 'appVersion': return field('appVersion', user.appVersion);
    case 'activeToday': return user.activeToday ? 1 : 0;
    case 'activeMonth': return user.activeThisMonth ? 1 : 0;
    case 'trafficTodayUpload': return Number(user.today && user.today.uploadMb) || 0;
    case 'trafficTodayDownload': return Number(user.today && user.today.downloadMb) || 0;
    case 'trafficToday': return Number(user.today && user.today.totalMb) || 0;
    case 'trafficMonthUpload': return Number(user.month && user.month.uploadMb) || 0;
    case 'trafficMonthDownload': return Number(user.month && user.month.downloadMb) || 0;
    case 'trafficMonth': return Number(user.month && user.month.totalMb) || 0;
    case 'trafficTotalUpload': return Number(user.totalUploadMb) || 0;
    case 'trafficTotalDownload': return Number(user.totalDownloadMb) || 0;
    case 'trafficTotal': return Number(user.totalMb) || 0;
    case 'createdAt': return user.createdAt || '';
    case 'lastSeenAt': return user.lastSeenAt || '';
    case 'lastReportAt': return user.lastReportAt || '';
    default: return '';
  }
}
function compareUserSortValues(left, right, columnKey) {
  const numericColumns = [
    'level', 'activeToday', 'activeMonth',
    'trafficTodayUpload', 'trafficTodayDownload', 'trafficToday',
    'trafficMonthUpload', 'trafficMonthDownload', 'trafficMonth',
    'trafficTotalUpload', 'trafficTotalDownload', 'trafficTotal',
  ];
  if (numericColumns.includes(columnKey)) return Number(left) - Number(right);

  const leftText = String(left == null ? '' : left);
  const rightText = String(right == null ? '' : right);
  if (!leftText && rightText) return 1;
  if (leftText && !rightText) return -1;
  if (!leftText && !rightText) return 0;

  const dateColumns = ['createdAt', 'lastSeenAt', 'lastReportAt'];
  if (dateColumns.includes(columnKey)) {
    const leftTime = Date.parse(leftText);
    const rightTime = Date.parse(rightText);
    if (Number.isFinite(leftTime) && Number.isFinite(rightTime)) return leftTime - rightTime;
  }
  return leftText.localeCompare(rightText, 'zh-CN', {numeric: true, sensitivity: 'base'});
}
function updateUserSortIndicators(table) {
  if (!table) return;
  table.querySelectorAll('[data-sort-column]').forEach(function(header) {
    const active = header.dataset.sortColumn === userSortKey;
    const indicator = header.querySelector('.sort-indicator');
    header.setAttribute('aria-sort', active
      ? (userSortDirection === 'desc' ? 'descending' : 'ascending')
      : 'none');
    header.title = active
      ? (userSortDirection === 'desc' ? 'Descending; click to switch to ascending' : 'Ascending; click to switch to descending')
      : 'Click to sort descending';
    if (indicator) indicator.textContent = active ? (userSortDirection === 'desc' ? '↓' : '↑') : '';
  });
}
function applyUserSort() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table || !userSortKey) return;
  const body = table.querySelector('tbody');
  if (!body) return;
  const rows = Array.from(body.querySelectorAll('[data-user-row]'));
  rows.map(function(row, index) {
    return {row: row, index: index};
  }).sort(function(left, right) {
    const result = compareUserSortValues(
      userSortValue(left.row, userSortKey),
      userSortValue(right.row, userSortKey),
      userSortKey,
    );
    return (userSortDirection === 'desc' ? -result : result) || left.index - right.index;
  }).forEach(function(item) {
    body.appendChild(item.row);
  });
  updateUserSortIndicators(table);
}
function toggleUserSort(columnKey) {
  if (userSortKey === columnKey) {
    userSortDirection = userSortDirection === 'desc' ? 'asc' : 'desc';
  } else {
    userSortKey = columnKey;
    userSortDirection = 'desc';
  }
  applyUserSort();
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
function updateUserSelectionUi() {
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'));
  const visibleRows = rows.filter(function(row) { return !row.hidden; });
  const selectedVisible = visibleRows.filter(function(row) {
    return selectedUserIds.has(row.dataset.userRow);
  });
  const selectAll = el('usersTable').querySelector('[data-select-all-users]');
  if (selectAll) {
    selectAll.checked = visibleRows.length > 0 && selectedVisible.length === visibleRows.length;
    selectAll.indeterminate = selectedVisible.length > 0 && selectedVisible.length < visibleRows.length;
  }
  el('usersTable').querySelectorAll('[data-user-select]').forEach(function(control) {
    control.checked = selectedUserIds.has(control.dataset.userSelect);
  });
  const status = el('userSelectionStatus');
  if (status) status.textContent = selectedUserIds.size ? '已选 ' + selectedUserIds.size + ' 个用户' : '';
  updateUserToolbar();
}
function toggleUserSelection(accountId, isSelected) {
  if (isSelected) selectedUserIds.add(accountId);
  else selectedUserIds.delete(accountId);
  updateUserSelectionUi();
}
function toggleSelectAllUsers() {
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'))
    .filter(function(row) { return !row.hidden; });
  const shouldSelect = rows.some(function(row) { return !selectedUserIds.has(row.dataset.userRow); });
  rows.forEach(function(row) {
    if (shouldSelect) selectedUserIds.add(row.dataset.userRow);
    else selectedUserIds.delete(row.dataset.userRow);
  });
  updateUserSelectionUi();
}
function attachUserColumnResizers() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table) return;
  updateUserSheetWidth(table);
  updateUserSortIndicators(table);
  table.querySelectorAll('[data-sort-column]').forEach(function(header) {
    header.onclick = function(event) {
      if (event.target.closest('[data-resize-column]')) return;
      toggleUserSort(header.dataset.sortColumn);
    };
    header.onkeydown = function(event) {
      if (event.target.closest('[data-resize-column]')) return;
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      toggleUserSort(header.dataset.sortColumn);
    };
  });
  const selectAll = table.querySelector('[data-select-all-users]');
  if (selectAll) selectAll.onchange = toggleSelectAllUsers;
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
  applyUserSort();
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
  updateUserSelectionUi();
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
function activityRate(active, total) {
  if (!total) return '0%';
  return (Math.round((Number(active) || 0) / total * 1000) / 10) + '%';
}
function trafficBreakdown(usage) {
  const value = usage || {};
  return '上传 ' + formatMegabytes(value.uploadMb) +
    ' · 下载 ' + formatMegabytes(value.downloadMb);
}
function formatCount(value) {
  return new Intl.NumberFormat('zh-CN').format(Math.max(0, Math.round(Number(value) || 0)));
}
function formatPercentRatio(value) {
  const ratio = Number(value);
  if (!Number.isFinite(ratio)) return '--';
  const percentage = ratio * 100;
  const rounded = Math.round(percentage * 10) / 10;
  return rounded + '%';
}
function changeRate(current, previous) {
  const currentValue = Number(current) || 0;
  const previousValue = Number(previous) || 0;
  if (previousValue === 0) return currentValue === 0 ? 0 : null;
  return (currentValue - previousValue) / Math.abs(previousValue);
}
function formatSignedChange(change) {
  if (!Number.isFinite(change)) return '--';
  const value = Math.round(Math.abs(change) * 1000) / 10;
  return (change > 0 ? '↑ ' : change < 0 ? '↓ ' : '→ ') + value + '%';
}
function renderKpiGrid(containerId, items) {
  const container = el(containerId);
  if (!container) return;
  container.innerHTML = (items || []).map(function(item) {
    const change = Number(item.change);
    const hasChange = item.change !== null && item.change !== undefined && Number.isFinite(change);
    const trendClass = !hasChange || change === 0
      ? 'trend-flat'
      : change > 0 ? 'trend-up' : 'trend-down';
    const trendText = hasChange ? formatSignedChange(change) : '--';
    return '<article class="kpi-card">' +
      '<div class="kpi-topline"><span class="kpi-label">' + esc(item.label) + '</span>' +
      '<span class="trend-pill ' + trendClass + '">' + esc(trendText) + '</span></div>' +
      '<strong class="kpi-value">' + esc(item.value) + '</strong>' +
      '<small class="kpi-comparison">' + esc(item.comparison || '暂无对比基准') + '</small>' +
      '</article>';
  }).join('');
}
function analyticsDateAdd(day, offset) {
  const date = new Date(String(day || '') + 'T00:00:00Z');
  if (!Number.isFinite(date.getTime())) return day;
  date.setUTCDate(date.getUTCDate() + Number(offset || 0));
  return date.toISOString().slice(0, 10);
}
function analyticsMonthStart(day) {
  return String(day || '').slice(0, 7) + '-01';
}
function analyticsPreviousMonth(day) {
  const start = new Date(analyticsMonthStart(day) + 'T00:00:00Z');
  start.setUTCMonth(start.getUTCMonth() - 1);
  const previousStart = start.toISOString().slice(0, 10);
  const end = new Date(start);
  end.setUTCMonth(end.getUTCMonth() + 1);
  end.setUTCDate(0);
  return {start: previousStart, end: end.toISOString().slice(0, 10)};
}
function analyticsDailyData() {
  const analytics = currentUsersData && currentUsersData.analytics;
  return analytics && Array.isArray(analytics.daily) ? analytics.daily : [];
}
function analyticsHourlyData() {
  const analytics = currentUsersData && currentUsersData.analytics;
  return analytics && Array.isArray(analytics.hourly) ? analytics.hourly : [];
}
function analyticsToday() {
  return currentUsersData && currentUsersData.date
    ? currentUsersData.date
    : (analyticsDailyData().slice(-1)[0] || {}).date || '';
}
function dailyRecord(day) {
  return analyticsDailyData().find(function(item) { return item.date === day; }) || {
    date: day, dau: 0, mau: 0, registrations: 0,
    uploadMb: 0, downloadMb: 0, totalMb: 0,
  };
}
function analyticsRangeBounds(range) {
  const today = analyticsToday();
  if (!today) return {start: '', end: ''};
  if (range === 'today') return {start: today, end: today};
  if (range === 'yesterday') {
    const yesterday = analyticsDateAdd(today, -1);
    return {start: yesterday, end: yesterday};
  }
  if (range === '30d') return {start: analyticsDateAdd(today, -29), end: today};
  if (range === 'month') return {start: analyticsMonthStart(today), end: today};
  if (range === 'previous-month') return analyticsPreviousMonth(today);
  if (range === 'custom') return {start: analyticsCustomStart, end: analyticsCustomEnd};
  return {start: analyticsDateAdd(today, -6), end: today};
}
function dailyDataForRange(range) {
  const bounds = analyticsRangeBounds(range || analyticsRange);
  return analyticsDailyData().filter(function(item) {
    return (!bounds.start || item.date >= bounds.start) && (!bounds.end || item.date <= bounds.end);
  });
}
function sumDailyData(items) {
  return (items || []).reduce(function(result, item) {
    result.registrations += Number(item.registrations) || 0;
    result.uploadMb += Number(item.uploadMb) || 0;
    result.downloadMb += Number(item.downloadMb) || 0;
    result.totalMb += Number(item.totalMb) || 0;
    return result;
  }, {registrations: 0, uploadMb: 0, downloadMb: 0, totalMb: 0});
}
function averageOf(items, property) {
  if (!items || items.length === 0) return 0;
  return items.reduce(function(total, item) { return total + (Number(item[property]) || 0); }, 0) / items.length;
}
function movingAverage(items, property, windowSize) {
  return (items || []).map(function(item, index) {
    const slice = items.slice(Math.max(0, index - windowSize + 1), index + 1);
    return {
      label: item.date,
      value: averageOf(slice, property),
    };
  });
}
function devicePlatform(user) {
  const text = [user.brand, user.model, user.androidVersion].join(' ').toLowerCase();
  if (/iphone|ipad|\bios\b|apple/.test(text)) return 'iOS';
  if (/windows|win32|win64/.test(text)) return 'Windows';
  if (/macos|macbook|imac|darwin/.test(text)) return 'macOS';
  if (/linux|ubuntu|debian|fedora/.test(text)) return 'Linux';
  if (cleanText(user.androidVersion) || /android/.test(text)) return 'Android';
  return '其他';
}
function deviceSystemVersion(user) {
  const platform = devicePlatform(user);
  if (platform === 'Android') {
    return cleanText(user.androidVersion) ? 'Android ' + cleanText(user.androidVersion) : 'Android 未知';
  }
  return platform + ' 未知';
}
function selectedAnalyticsUsers() {
  const users = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users
    : [];
  const platform = el('analyticsPlatformFilter').value;
  const system = el('analyticsSystemFilter').value;
  const version = el('analyticsVersionFilter').value;
  return users.filter(function(user) {
    const userVersion = cleanText(user.appVersion) || '未知版本';
    return (!platform || devicePlatform(user) === platform) &&
      (!system || deviceSystemVersion(user) === system) &&
      (!version || userVersion === version);
  });
}
function replaceSelectOptions(control, values, allLabel) {
  if (!control) return;
  const previous = control.value;
  control.innerHTML = '<option value="">' + esc(allLabel) + '</option>' + values.map(function(value) {
    return '<option value="' + esc(value) + '">' + esc(value) + '</option>';
  }).join('');
  control.value = values.includes(previous) ? previous : '';
}
function populateAnalyticsDimensionFilters() {
  const users = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users
    : [];
  const platformControl = el('analyticsPlatformFilter');
  const platformPrevious = selectedDevicePlatform || platformControl.value;
  const platforms = Array.from(new Set(users.map(devicePlatform))).sort();
  replaceSelectOptions(platformControl, platforms, '全部平台');
  platformControl.value = platforms.includes(platformPrevious) ? platformPrevious : '';
  selectedDevicePlatform = platformControl.value;

  const platformUsers = users.filter(function(user) {
    return !platformControl.value || devicePlatform(user) === platformControl.value;
  });
  const systems = Array.from(new Set(platformUsers.map(deviceSystemVersion))).sort();
  replaceSelectOptions(el('analyticsSystemFilter'), systems, '全部系统');
  const system = el('analyticsSystemFilter').value;
  const versionUsers = platformUsers.filter(function(user) {
    return !system || deviceSystemVersion(user) === system;
  });
  const versions = Array.from(new Set(versionUsers.map(function(user) {
    return cleanText(user.appVersion) || '未知版本';
  }))).sort(compareVersionLabels).reverse();
  replaceSelectOptions(el('analyticsVersionFilter'), versions, '全部版本');
}
function compareVersionLabels(left, right) {
  const leftParts = String(left || '').replace(/^v/i, '').split('.').map(Number);
  const rightParts = String(right || '').replace(/^v/i, '').split('.').map(Number);
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (leftParts[index] || 0) - (rightParts[index] || 0);
    if (difference) return difference;
  }
  return String(left).localeCompare(String(right), 'zh-CN', {numeric: true});
}
function setAnalyticsRange(range) {
  analyticsRange = range || '7d';
  document.querySelectorAll('[data-range]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.range === analyticsRange);
  });
  el('customDateRange').hidden = analyticsRange !== 'custom';
  if (analyticsRange === 'custom') {
    const fallback = analyticsRangeBounds('30d');
    if (!analyticsCustomStart) analyticsCustomStart = fallback.start;
    if (!analyticsCustomEnd) analyticsCustomEnd = fallback.end;
    el('analyticsStartDate').value = analyticsCustomStart;
    el('analyticsEndDate').value = analyticsCustomEnd;
    return;
  }
  renderAnalyticsDashboard();
}
function applyCustomAnalyticsRange() {
  const start = el('analyticsStartDate').value;
  const end = el('analyticsEndDate').value;
  if (!start || !end || start > end) {
    show('自定义时间范围无效', true);
    return;
  }
  analyticsCustomStart = start;
  analyticsCustomEnd = end;
  renderAnalyticsDashboard();
}
function resetAnalyticsFilters() {
  selectedDevicePlatform = '';
  el('analyticsPlatformFilter').value = '';
  el('analyticsSystemFilter').value = '';
  el('analyticsVersionFilter').value = '';
  populateAnalyticsDimensionFilters();
  setAnalyticsRange('7d');
}
function analyticsRangeLabel() {
  const labels = {
    today: '今日', yesterday: '昨日', '7d': '最近 7 天', '30d': '最近 30 天',
    month: '本月', 'previous-month': '上月', custom: '自定义时间',
  };
  if (analyticsRange === 'custom') {
    return (analyticsCustomStart || '--') + ' 至 ' + (analyticsCustomEnd || '--');
  }
  return labels[analyticsRange] || labels['7d'];
}
function updateAnalyticsFilterStatus() {
  const filters = [
    el('analyticsPlatformFilter').value,
    el('analyticsSystemFilter').value,
    el('analyticsVersionFilter').value,
  ].filter(Boolean);
  el('analyticsFilterStatus').textContent = analyticsRangeLabel() + ' · ' +
    (filters.length ? filters.join(' · ') : '全部用户') +
    ' · 历史趋势保留近 120 天' +
    (filters.length ? ' · 设备筛选应用于用户结构与设备版本' : '');
}
function chartEmpty(containerId, message, type) {
  const container = el(containerId);
  if (!container) return;
  container.innerHTML = '<div class="' + (type || 'empty-state') + '">' + esc(message || '暂无数据') + '</div>';
}
function chartNiceMaximum(value) {
  const number = Math.max(0, Number(value) || 0);
  if (number <= 1) return 1;
  const power = Math.pow(10, Math.floor(Math.log10(number)));
  return Math.ceil(number / power * 1.15 * 2) / 2 * power;
}
function chartXAxisLabels(points, xAt, height, padBottom) {
  const count = points.length;
  const step = Math.max(1, Math.ceil(count / 6));
  return points.map(function(point, index) {
    if (index % step !== 0 && index !== count - 1) return '';
    const rawLabel = String(point.label || '');
    const label = rawLabel.length >= 10
      ? rawLabel.slice(5, 10).replace('-', '/')
      : rawLabel;
    return '<text class="chart-axis-label" x="' + xAt(index) + '" y="' + (height - padBottom + 22) + '" text-anchor="middle">' + esc(label) + '</text>';
  }).join('');
}
function renderLineChart(containerId, points, options) {
  const container = el(containerId);
  if (!container) return;
  if (!points || points.length === 0) return chartEmpty(containerId, '当前筛选范围暂无趋势数据');
  const width = 720;
  const height = options && options.large ? 310 : 250;
  const pad = {left: 58, right: 18, top: 18, bottom: 38};
  const values = points.map(function(point) { return Number(point.value) || 0; });
  const maximum = options && Number.isFinite(options.maximum)
    ? options.maximum
    : chartNiceMaximum(Math.max.apply(null, values));
  const xAt = function(index) {
    return pad.left + (points.length === 1 ? (width - pad.left - pad.right) / 2 : index / (points.length - 1) * (width - pad.left - pad.right));
  };
  const yAt = function(value) {
    return pad.top + (1 - Math.max(0, Number(value) || 0) / (maximum || 1)) * (height - pad.top - pad.bottom);
  };
  const formatter = options && options.formatter ? options.formatter : formatCount;
  let grid = '';
  for (let index = 0; index <= 4; index += 1) {
    const y = pad.top + index / 4 * (height - pad.top - pad.bottom);
    const tick = maximum * (1 - index / 4);
    grid += '<line class="chart-grid-line" x1="' + pad.left + '" y1="' + y + '" x2="' + (width - pad.right) + '" y2="' + y + '"></line>' +
      '<text class="chart-axis-label" x="' + (pad.left - 8) + '" y="' + (y + 4) + '" text-anchor="end">' + esc(formatter(tick)) + '</text>';
  }
  const path = points.map(function(point, index) {
    return (index ? 'L' : 'M') + xAt(index) + ',' + yAt(point.value);
  }).join(' ');
  const pointsHtml = points.map(function(point, index) {
    const tooltip = point.tooltip || (point.label + '\\n' + formatter(point.value));
    return '<circle class="chart-point" cx="' + xAt(index) + '" cy="' + yAt(point.value) + '" r="3.5" data-tooltip="' + esc(tooltip) + '"><title>' + esc(tooltip) + '</title></circle>';
  }).join('');
  container.innerHTML = '<svg class="chart-svg" viewBox="0 0 ' + width + ' ' + height + '" role="img" aria-label="' + esc((options && options.label) || '趋势图') + '">' +
    grid + '<path class="chart-line" d="' + path + '"></path>' + pointsHtml +
    chartXAxisLabels(points, xAt, height, pad.bottom) + '</svg>';
}
function renderBarLineChart(containerId, items, property) {
  const container = el(containerId);
  if (!container) return;
  if (!items || items.length === 0) return chartEmpty(containerId, '当前筛选范围暂无注册数据');
  const averages = movingAverage(items, property, 7);
  const width = 720;
  const height = 250;
  const pad = {left: 55, right: 18, top: 18, bottom: 38};
  const maximum = chartNiceMaximum(Math.max.apply(null, items.map(function(item, index) {
    return Math.max(Number(item[property]) || 0, averages[index].value);
  })));
  const plotWidth = width - pad.left - pad.right;
  const barWidth = Math.max(2, Math.min(24, plotWidth / items.length * .62));
  const xAt = function(index) { return pad.left + (index + .5) / items.length * plotWidth; };
  const yAt = function(value) { return pad.top + (1 - (Number(value) || 0) / maximum) * (height - pad.top - pad.bottom); };
  let grid = '';
  for (let index = 0; index <= 4; index += 1) {
    const y = pad.top + index / 4 * (height - pad.top - pad.bottom);
    grid += '<line class="chart-grid-line" x1="' + pad.left + '" y1="' + y + '" x2="' + (width - pad.right) + '" y2="' + y + '"></line>';
  }
  const bars = items.map(function(item, index) {
    const value = Number(item[property]) || 0;
    const tooltip = item.date + '\\n新增 ' + formatCount(value) + '\\n7 日均值 ' + (Math.round(averages[index].value * 10) / 10);
    return '<rect class="chart-bar" x="' + (xAt(index) - barWidth / 2) + '" y="' + yAt(value) + '" width="' + barWidth + '" height="' + Math.max(0, height - pad.bottom - yAt(value)) + '" rx="2" data-tooltip="' + esc(tooltip) + '"><title>' + esc(tooltip) + '</title></rect>';
  }).join('');
  const line = averages.map(function(point, index) {
    return (index ? 'L' : 'M') + xAt(index) + ',' + yAt(point.value);
  }).join(' ');
  const axisPoints = items.map(function(item) { return {label: item.date}; });
  container.innerHTML = '<svg class="chart-svg" viewBox="0 0 ' + width + ' ' + height + '" role="img" aria-label="新增用户趋势">' +
    grid + bars + '<path class="chart-line-secondary" d="' + line + '"></path>' +
    chartXAxisLabels(axisPoints, xAt, height, pad.bottom) + '</svg>';
}
function areaBetweenPath(points, xAt, yAt, lowerProperty, upperProperty) {
  if (!points.length) return '';
  const upper = points.map(function(point, index) {
    return (index ? 'L' : 'M') + xAt(index) + ',' + yAt(point[upperProperty]);
  }).join(' ');
  const lower = points.slice().reverse().map(function(point, reverseIndex) {
    const index = points.length - reverseIndex - 1;
    return 'L' + xAt(index) + ',' + yAt(lowerProperty ? point[lowerProperty] : 0);
  }).join(' ');
  return upper + ' ' + lower + ' Z';
}
function renderStackedAreaChart(containerId, items, options) {
  const container = el(containerId);
  if (!container) return;
  if (!items || items.length === 0) return chartEmpty(containerId, '当前筛选范围暂无流量数据');
  const points = items.map(function(item) {
    const upload = Number(item.uploadMb) || 0;
    const download = Number(item.downloadMb) || 0;
    return {
      label: item.label || item.date || item.timestamp,
      uploadMb: upload,
      downloadMb: download,
      totalMb: upload + download,
    };
  });
  const width = 720;
  const height = options && options.large ? 310 : 250;
  const pad = {left: 62, right: 18, top: 18, bottom: 38};
  const maximum = chartNiceMaximum(Math.max.apply(null, points.map(function(point) { return point.totalMb; })));
  const xAt = function(index) {
    return pad.left + (points.length === 1 ? (width - pad.left - pad.right) / 2 : index / (points.length - 1) * (width - pad.left - pad.right));
  };
  const yAt = function(value) { return pad.top + (1 - (Number(value) || 0) / maximum) * (height - pad.top - pad.bottom); };
  let grid = '';
  for (let index = 0; index <= 4; index += 1) {
    const y = pad.top + index / 4 * (height - pad.top - pad.bottom);
    const tick = maximum * (1 - index / 4);
    grid += '<line class="chart-grid-line" x1="' + pad.left + '" y1="' + y + '" x2="' + (width - pad.right) + '" y2="' + y + '"></line>' +
      '<text class="chart-axis-label" x="' + (pad.left - 8) + '" y="' + (y + 4) + '" text-anchor="end">' + esc(formatMegabytes(tick)) + '</text>';
  }
  const hitWidth = Math.max(8, (width - pad.left - pad.right) / points.length);
  const hitAreas = points.map(function(point, index) {
    const tooltip = point.label + '\\n上传 ' + formatMegabytes(point.uploadMb) + '\\n下载 ' + formatMegabytes(point.downloadMb) + '\\n总计 ' + formatMegabytes(point.totalMb);
    return '<rect x="' + (xAt(index) - hitWidth / 2) + '" y="' + pad.top + '" width="' + hitWidth + '" height="' + (height - pad.top - pad.bottom) + '" fill="transparent" data-tooltip="' + esc(tooltip) + '"><title>' + esc(tooltip) + '</title></rect>';
  }).join('');
  container.innerHTML = '<svg class="chart-svg" viewBox="0 0 ' + width + ' ' + height + '" role="img" aria-label="流量趋势">' +
    grid +
    '<path class="chart-area-download" d="' + areaBetweenPath(points, xAt, yAt, '', 'downloadMb') + '"></path>' +
    '<path class="chart-area-upload" d="' + areaBetweenPath(points, xAt, yAt, 'downloadMb', 'totalMb') + '"></path>' +
    hitAreas + chartXAxisLabels(points, xAt, height, pad.bottom) + '</svg>';
}
function currentMonthDailyData() {
  const prefix = analyticsToday().slice(0, 7);
  return analyticsDailyData().filter(function(item) { return item.date.slice(0, 7) === prefix; });
}
function previousMonthComparableData() {
  const today = analyticsToday();
  const previous = analyticsPreviousMonth(today);
  const dayOfMonth = Math.max(1, Number(today.slice(8)) || 1);
  const comparableEnd = analyticsDateAdd(previous.start, dayOfMonth - 1);
  const end = comparableEnd > previous.end ? previous.end : comparableEnd;
  return analyticsDailyData().filter(function(item) {
    return item.date >= previous.start && item.date <= end;
  });
}
function previousSevenCompleteDays() {
  const today = analyticsToday();
  const start = analyticsDateAdd(today, -7);
  const end = analyticsDateAdd(today, -1);
  return analyticsDailyData().filter(function(item) { return item.date >= start && item.date <= end; });
}
function overviewKpiItems() {
  const data = currentUsersData || {};
  const users = Array.isArray(data.users) ? data.users : [];
  const todayKey = analyticsToday();
  const today = dailyRecord(todayKey);
  const yesterday = dailyRecord(analyticsDateAdd(todayKey, -1));
  const month = sumDailyData(currentMonthDailyData());
  const previousMonth = sumDailyData(previousMonthComparableData());
  const totalUsers = users.length;
  const previousTotalUsers = Math.max(0, totalUsers - (Number(today.registrations) || 0));
  const todayRatio = today.mau ? today.dau / today.mau : 0;
  const yesterdayRatio = yesterday.mau ? yesterday.dau / yesterday.mau : 0;
  return [
    {label:'总用户数', value:formatCount(totalUsers), change:changeRate(totalUsers, previousTotalUsers), comparison:'较昨日净增 ' + formatCount(today.registrations)},
    {label:'DAU', value:formatCount(today.dau), change:changeRate(today.dau, yesterday.dau), comparison:'较昨日 ' + formatCount(yesterday.dau)},
    {label:'MAU', value:formatCount(today.mau), change:changeRate(today.mau, yesterday.mau), comparison:'昨日口径 ' + formatCount(yesterday.mau)},
    {label:'DAU / MAU', value:formatPercentRatio(todayRatio), change:changeRate(todayRatio, yesterdayRatio), comparison:'昨日 ' + formatPercentRatio(yesterdayRatio)},
    {label:'今日注册用户', value:formatCount(today.registrations), change:changeRate(today.registrations, yesterday.registrations), comparison:'昨日 ' + formatCount(yesterday.registrations)},
    {label:'本月注册用户', value:formatCount(month.registrations), change:changeRate(month.registrations, previousMonth.registrations), comparison:'上月同期 ' + formatCount(previousMonth.registrations)},
    {label:'今日总流量', value:formatMegabytes(today.totalMb), change:changeRate(today.totalMb, yesterday.totalMb), comparison:'昨日 ' + formatMegabytes(yesterday.totalMb)},
    {label:'本月总流量', value:formatMegabytes(month.totalMb), change:changeRate(month.totalMb, previousMonth.totalMb), comparison:'上月同期 ' + formatMegabytes(previousMonth.totalMb)},
  ];
}
function activitySeriesByGrain(items, grain) {
  if (!items || grain === 'day') return items || [];
  const groups = new Map();
  items.forEach(function(item) {
    let key;
    if (grain === 'month') {
      key = item.date.slice(0, 7);
    } else {
      const date = new Date(item.date + 'T00:00:00Z');
      const mondayOffset = (date.getUTCDay() + 6) % 7;
      key = analyticsDateAdd(item.date, -mondayOffset);
    }
    const group = groups.get(key) || [];
    group.push(item);
    groups.set(key, group);
  });
  return Array.from(groups.entries()).map(function(entry) {
    const group = entry[1];
    const last = group[group.length - 1] || {};
    return {
      date: entry[0],
      label: grain === 'month'
        ? entry[0]
        : entry[0] + ' 至 ' + (last.date || entry[0]),
      dau: averageOf(group, 'dau'),
      mau: Number(last.mau) || 0,
    };
  });
}
function activeMetricPoints(items, metric) {
  return (items || []).map(function(item) {
    const value = metric === 'ratio'
      ? (item.mau ? item.dau / item.mau : 0)
      : Number(item[metric]) || 0;
    return {
      label: item.label || item.date,
      value,
      tooltip: (item.label || item.date) + '\\n' + (metric === 'ratio' ? 'DAU / MAU ' + formatPercentRatio(value) : metric.toUpperCase() + ' ' + formatCount(value)),
    };
  });
}
function renderActiveMetricChart(containerId, selectorId, grainSelectorId, series) {
  const metric = el(selectorId).value || 'dau';
  const grain = el(grainSelectorId).value || 'day';
  renderLineChart(containerId, activeMetricPoints(activitySeriesByGrain(series, grain), metric), {
    label: metric === 'ratio' ? 'DAU / MAU 趋势' : metric.toUpperCase() + ' 趋势',
    maximum: metric === 'ratio' ? 1 : undefined,
    formatter: metric === 'ratio' ? formatPercentRatio : formatCount,
  });
}
function renderRegistrationTrend(containerId, series) {
  renderBarLineChart(containerId, series, 'registrations');
}
function cumulativeUserPoints(series) {
  const users = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users
    : [];
  return (series || []).map(function(item) {
    const count = users.filter(function(user) {
      return cleanText(user.createdAt).slice(0, 10) <= item.date;
    }).length;
    return {label:item.date, value:count, tooltip:item.date + '\\n累计用户 ' + formatCount(count)};
  });
}
function renderNewOldStructure() {
  const container = el('newOldStructure');
  const users = selectedAnalyticsUsers();
  const today = analyticsToday();
  const active = users.filter(function(user) { return user.activeToday; });
  const newUsers = active.filter(function(user) { return cleanText(user.createdAt).slice(0, 10) === today; });
  const oldUsers = active.filter(function(user) { return cleanText(user.createdAt).slice(0, 10) !== today; });
  const usage = function(list) {
    return list.reduce(function(total, user) { return total + (Number(user.today && user.today.totalMb) || 0); }, 0);
  };
  const card = function(label, list) {
    const share = active.length ? list.length / active.length : 0;
    const perCapita = list.length ? usage(list) / list.length : 0;
    return '<article class="structure-card"><small>' + esc(label) + '</small>' +
      '<strong>' + formatCount(list.length) + '</strong>' +
      '<span>占比 ' + formatPercentRatio(share) + '</span><small>人均流量 ' + formatMegabytes(perCapita) + '</small></article>';
  };
  container.innerHTML = card('今日新注册活跃用户', newUsers) + card('老用户', oldUsers);
}
function renderOverview() {
  const series = dailyDataForRange();
  renderKpiGrid('overviewKpis', overviewKpiItems());
  renderActiveMetricChart('overviewActiveChart', 'overviewActiveMetric', 'overviewActiveGrain', series);
  renderRegistrationTrend('overviewRegistrationChart', series);
  renderStackedAreaChart('overviewTrafficChart', series, {});
  renderAlertPreview();
}
function renderUserAnalysis() {
  const series = dailyDataForRange();
  const today = dailyRecord(analyticsToday());
  const yesterday = dailyRecord(analyticsDateAdd(analyticsToday(), -1));
  const month = sumDailyData(currentMonthDailyData());
  const previousMonth = sumDailyData(previousMonthComparableData());
  renderKpiGrid('userAnalysisKpis', [
    {label:'今日注册',value:formatCount(today.registrations),change:changeRate(today.registrations,yesterday.registrations),comparison:'昨日 ' + formatCount(yesterday.registrations)},
    {label:'昨日注册',value:formatCount(yesterday.registrations),change:null,comparison:'作为今日对比基准'},
    {label:'本月累计注册',value:formatCount(month.registrations),change:changeRate(month.registrations,previousMonth.registrations),comparison:'上月同期 ' + formatCount(previousMonth.registrations)},
    {label:'当前用户规模',value:formatCount((currentUsersData.users || []).length),change:null,comparison:'现存账户总量'},
  ]);
  renderActiveMetricChart('userActiveChart', 'userActiveMetric', 'userActiveGrain', series);
  renderRegistrationTrend('userRegistrationChart', series);
  renderLineChart('userCumulativeChart', cumulativeUserPoints(series), {label:'累计用户增长',formatter:formatCount});
  renderNewOldStructure();
}
function trafficSeriesForMode() {
  const mode = el('trafficRangeMode').value || '7d';
  if (mode === '24h') {
    return analyticsHourlyData().slice(-24).map(function(item) {
      return {
        label: cleanText(item.timestamp).slice(5, 13).replace('T', ' '),
        uploadMb: Number(item.uploadMb) || 0,
        downloadMb: Number(item.downloadMb) || 0,
        totalMb: Number(item.totalMb) || 0,
        dau: Number(item.activeUsers) || 0,
      };
    });
  }
  if (mode === 'filtered') return dailyDataForRange();
  return dailyDataForRange(mode === 'month' ? 'month' : mode);
}
function perCapitaPoints(items) {
  return (items || []).map(function(item) {
    const active = Number(item.dau || item.activeUsers) || 0;
    const value = active ? (Number(item.totalMb) || 0) / active : 0;
    return {
      label: item.label || item.date || item.timestamp,
      value,
      tooltip: (item.label || item.date || item.timestamp) + '\\n人均流量 ' + formatMegabytes(value),
    };
  });
}
function renderTrafficHealth(series) {
  const container = el('trafficHealth');
  const daily = analyticsDailyData();
  const today = analyticsToday();
  const last7 = daily.filter(function(item) { return item.date >= analyticsDateAdd(today, -6) && item.date <= today; });
  const last30 = daily.filter(function(item) { return item.date >= analyticsDateAdd(today, -29) && item.date <= today; });
  const selected = sumDailyData(series);
  const rows = [
    ['当前范围日均流量', formatMegabytes(series.length ? selected.totalMb / series.length : 0)],
    ['7 日平均', formatMegabytes(averageOf(last7, 'totalMb'))],
    ['30 日平均', formatMegabytes(averageOf(last30, 'totalMb'))],
    ['本月累计流量', formatMegabytes(sumDailyData(currentMonthDailyData()).totalMb)],
  ];
  container.innerHTML = rows.map(function(row) {
    return '<div class="stat-row"><span>' + esc(row[0]) + '</span><strong>' + esc(row[1]) + '</strong></div>';
  }).join('');
}
function renderTrafficAnalysis() {
  if (!currentUsersData) return;
  const today = dailyRecord(analyticsToday());
  const yesterday = dailyRecord(analyticsDateAdd(analyticsToday(), -1));
  const month = sumDailyData(currentMonthDailyData());
  const previousMonth = sumDailyData(previousMonthComparableData());
  const allTime = (currentUsersData.totals && currentUsersData.totals.allTime) || {};
  const perCapita = today.dau ? today.totalMb / today.dau : 0;
  const previousPerCapita = yesterday.dau ? yesterday.totalMb / yesterday.dau : 0;
  renderKpiGrid('trafficKpis', [
    {label:'今日上传流量',value:formatMegabytes(today.uploadMb),change:changeRate(today.uploadMb,yesterday.uploadMb),comparison:'昨日 ' + formatMegabytes(yesterday.uploadMb)},
    {label:'今日下载流量',value:formatMegabytes(today.downloadMb),change:changeRate(today.downloadMb,yesterday.downloadMb),comparison:'昨日 ' + formatMegabytes(yesterday.downloadMb)},
    {label:'今日总流量',value:formatMegabytes(today.totalMb),change:changeRate(today.totalMb,yesterday.totalMb),comparison:'昨日 ' + formatMegabytes(yesterday.totalMb)},
    {label:'DAU 人均流量',value:formatMegabytes(perCapita),change:changeRate(perCapita,previousPerCapita),comparison:'昨日 ' + formatMegabytes(previousPerCapita)},
    {label:'本月上传流量',value:formatMegabytes(month.uploadMb),change:changeRate(month.uploadMb,previousMonth.uploadMb),comparison:'上月同期 ' + formatMegabytes(previousMonth.uploadMb)},
    {label:'本月下载流量',value:formatMegabytes(month.downloadMb),change:changeRate(month.downloadMb,previousMonth.downloadMb),comparison:'上月同期 ' + formatMegabytes(previousMonth.downloadMb)},
    {label:'本月总流量',value:formatMegabytes(month.totalMb),change:changeRate(month.totalMb,previousMonth.totalMb),comparison:'上月同期 ' + formatMegabytes(previousMonth.totalMb)},
    {label:'历史累计流量',value:formatMegabytes(allTime.totalMb),change:null,comparison:trafficBreakdown(allTime)},
  ]);
  const series = trafficSeriesForMode();
  renderStackedAreaChart('trafficStackedChart', series, {large:true});
  renderLineChart('perCapitaTrafficChart', perCapitaPoints(series), {label:'人均流量趋势',formatter:formatMegabytes});
  renderTrafficHealth(series);
}
function groupUsersBy(users, labelFactory) {
  const groups = new Map();
  (users || []).forEach(function(user) {
    const label = labelFactory(user) || '未知';
    const group = groups.get(label) || {label:label, users:0, dau:0, monthMb:0};
    group.users += 1;
    group.dau += user.activeToday ? 1 : 0;
    group.monthMb += Number(user.month && user.month.totalMb) || 0;
    groups.set(label, group);
  });
  return Array.from(groups.values()).map(function(group) {
    group.activeRate = group.users ? group.dau / group.users : 0;
    group.perCapitaMb = group.users ? group.monthMb / group.users : 0;
    return group;
  });
}
function renderDistribution(containerId, groups, options) {
  const container = el(containerId);
  if (!container) return;
  if (!groups || groups.length === 0) return chartEmpty(containerId, '当前筛选条件下暂无分布数据');
  const sorted = groups.slice().sort(function(left, right) { return right.users - left.users; });
  const total = sorted.reduce(function(sum, group) { return sum + group.users; }, 0);
  const maximum = Math.max.apply(null, sorted.map(function(group) { return group.users; })) || 1;
  container.innerHTML = sorted.slice(0, (options && options.limit) || 10).map(function(group) {
    const share = total ? group.users / total : 0;
    const clickable = options && options.clickable;
    const label = clickable
      ? '<button class="distribution-label" type="button" data-distribution-value="' + esc(group.label) + '">' + esc(group.label) + '</button>'
      : '<button class="distribution-label" type="button" disabled>' + esc(group.label) + '</button>';
    return '<div class="distribution-row">' + label +
      '<div class="distribution-track"><span class="distribution-fill" style="width:' + Math.max(2, group.users / maximum * 100) + '%"></span></div>' +
      '<div class="distribution-values">' + formatCount(group.users) + ' · ' + formatPercentRatio(share) +
      '<span class="distribution-meta">DAU ' + formatCount(group.dau) + ' · 活跃率 ' + formatPercentRatio(group.activeRate) + '</span></div></div>';
  }).join('');
  if (options && options.clickable) {
    container.querySelectorAll('[data-distribution-value]').forEach(function(button) {
      button.onclick = function() {
        const label = button.dataset.distributionValue;
        const value = options.valueFactory ? options.valueFactory(label) : label;
        const filterId = options.filterId || 'analyticsPlatformFilter';
        const control = el(filterId);
        if (!control) return;
        control.value = value;
        if (filterId === 'analyticsPlatformFilter') selectedDevicePlatform = value;
        if (filterId === 'analyticsPlatformFilter' || filterId === 'analyticsSystemFilter') {
          populateAnalyticsDimensionFilters();
        }
        renderAnalyticsDashboard();
      };
    });
  }
}
function latestAppVersion(users) {
  const configured = currentState && currentState.appUpdate && cleanText(currentState.appUpdate.version);
  if (configured) return configured.replace(/^v/i, '');
  const versions = (users || []).map(function(user) { return cleanText(user.appVersion).replace(/^v/i, ''); }).filter(Boolean);
  return versions.sort(compareVersionLabels).slice(-1)[0] || '';
}
function renderDeviceModelTable(groups) {
  const container = el('deviceModelTable');
  if (!groups || groups.length === 0) return chartEmpty('deviceModelTable', '当前筛选条件下暂无设备型号数据');
  const direction = deviceModelSortDirection === 'desc' ? -1 : 1;
  const sorted = groups.slice().sort(function(left, right) {
    const leftValue = deviceModelSortKey === 'model' ? left.label : Number(left[deviceModelSortKey]) || 0;
    const rightValue = deviceModelSortKey === 'model' ? right.label : Number(right[deviceModelSortKey]) || 0;
    if (deviceModelSortKey === 'model') return String(leftValue).localeCompare(String(rightValue), 'zh-CN', {numeric:true}) * direction;
    return (leftValue - rightValue) * direction;
  }).slice(0, 10);
  const total = groups.reduce(function(sum, group) { return sum + group.users; }, 0);
  const header = function(label, key) {
    return '<button class="text-button" type="button" data-model-sort="' + key + '">' + label + (deviceModelSortKey === key ? (deviceModelSortDirection === 'desc' ? ' ↓' : ' ↑') : '') + '</button>';
  };
  container.innerHTML = '<table class="compact-table"><thead><tr><th>排名</th><th>' + header('设备型号','model') + '</th><th>系统</th><th>' + header('用户数量','users') + '</th><th>' + header('活跃用户','dau') + '</th><th>占比</th><th>' + header('人均流量','perCapitaMb') + '</th></tr></thead><tbody>' +
    sorted.map(function(group, index) {
      return '<tr><td>' + (index + 1) + '</td><td>' + esc(group.label) + '</td><td>' + esc(group.system || '未知') + '</td><td>' + formatCount(group.users) + '</td><td>' + formatCount(group.dau) + '</td><td>' + formatPercentRatio(total ? group.users / total : 0) + '</td><td>' + formatMegabytes(group.perCapitaMb) + '</td></tr>';
    }).join('') + '</tbody></table>';
  container.querySelectorAll('[data-model-sort]').forEach(function(button) {
    button.onclick = function() { sortDeviceModels(button.dataset.modelSort); };
  });
}
function sortDeviceModels(key) {
  if (deviceModelSortKey === key) deviceModelSortDirection = deviceModelSortDirection === 'desc' ? 'asc' : 'desc';
  else { deviceModelSortKey = key; deviceModelSortDirection = 'desc'; }
  renderDevices();
}
function renderDevices() {
  if (!currentUsersData) return;
  const allUsers = Array.isArray(currentUsersData.users) ? currentUsersData.users : [];
  const users = selectedAnalyticsUsers();
  const latest = latestAppVersion(allUsers);
  const latestUsers = allUsers.filter(function(user) { return cleanText(user.appVersion).replace(/^v/i, '') === latest; });
  const oldActive = allUsers.filter(function(user) {
    return user.activeToday && latest && cleanText(user.appVersion).replace(/^v/i, '') !== latest;
  });
  const platforms = groupUsersBy(allUsers, devicePlatform);
  const android = platforms.find(function(group) { return group.label === 'Android'; });
  renderKpiGrid('deviceKpis', [
    {label:'已识别设备',value:formatCount(allUsers.length),change:null,comparison:'当前账户设备资料'},
    {label:'Android 占比',value:formatPercentRatio(allUsers.length && android ? android.users / allUsers.length : 0),change:null,comparison:android ? formatCount(android.users) + ' 个账户' : '暂无 Android 设备'},
    {label:'最新版覆盖率',value:formatPercentRatio(allUsers.length ? latestUsers.length / allUsers.length : 0),change:null,comparison:latest ? '最新版 v' + latest : '暂无版本数据'},
    {label:'旧版本活跃用户',value:formatCount(oldActive.length),change:null,comparison:'今日仍活跃且非最新版'},
  ]);
  renderDistribution('platformDistribution', groupUsersBy(allUsers, devicePlatform), {clickable:true,limit:6});
  renderDistribution('systemVersionDistribution', groupUsersBy(users, deviceSystemVersion), {
    clickable:true,
    filterId:'analyticsSystemFilter',
    limit:10,
  });
  el('systemVersionSubtitle').textContent = (el('analyticsPlatformFilter').value || '全部平台') + ' · 用户数量 / DAU / 活跃率';
  renderDistribution('appVersionDistribution', groupUsersBy(users, function(user) {
    return cleanText(user.appVersion) ? 'v' + cleanText(user.appVersion).replace(/^v/i, '') : '未知版本';
  }), {
    clickable:true,
    filterId:'analyticsVersionFilter',
    valueFactory:function(label) { return label === '未知版本' ? label : label.replace(/^v/i, ''); },
    limit:10,
  });
  const modelGroups = groupUsersBy(users, function(user) { return cleanText(user.model) || '未知型号'; });
  modelGroups.forEach(function(group) {
    const matched = users.find(function(user) { return (cleanText(user.model) || '未知型号') === group.label; });
    group.system = matched ? deviceSystemVersion(matched) : '未知';
  });
  renderDeviceModelTable(modelGroups);
}
function severityFromChange(change) {
  const magnitude = Math.abs(Number(change) || 0);
  if (magnitude >= .8) return 'critical';
  if (magnitude >= .5) return 'warning';
  if (magnitude >= .2) return 'attention';
  return 'normal';
}
function severityLabel(value) {
  return {critical:'严重',warning:'警告',attention:'关注',normal:'正常'}[value] || '正常';
}
function buildAnalyticsAlerts() {
  const todayKey = analyticsToday();
  const today = dailyRecord(todayKey);
  const yesterday = dailyRecord(analyticsDateAdd(todayKey, -1));
  const baselineDays = previousSevenCompleteDays();
  const alerts = [];
  const addMetricAlert = function(type, title, current, baseline, formatter, target) {
    const change = changeRate(current, baseline);
    if (!Number.isFinite(change) || Math.abs(change) < .2) return;
    alerts.push({
      type:type,
      title:title,
      current:formatter(current),
      baseline:formatter(baseline),
      change:change,
      time:todayKey,
      severity:severityFromChange(change),
      target:target,
    });
  };
  addMetricAlert('traffic','今日下载流量偏离 7 日均值',today.downloadMb,averageOf(baselineDays,'downloadMb'),formatMegabytes,'traffic-analysis');
  addMetricAlert('traffic','今日总流量偏离 7 日均值',today.totalMb,averageOf(baselineDays,'totalMb'),formatMegabytes,'traffic-analysis');
  addMetricAlert('growth','今日新增用户偏离 7 日均值',today.registrations,averageOf(baselineDays,'registrations'),formatCount,'user-analysis');
  addMetricAlert('activity','今日 DAU 偏离 7 日均值',today.dau,averageOf(baselineDays,'dau'),formatCount,'user-analysis');

  const dauChange = changeRate(today.dau, yesterday.dau);
  const trafficChange = changeRate(today.totalMb, yesterday.totalMb);
  if (Number.isFinite(dauChange) && Number.isFinite(trafficChange) && Math.abs(dauChange) < .1 && trafficChange > .4) {
    alerts.push({type:'mismatch',title:'DAU 基本不变但流量明显上涨',current:formatMegabytes(today.totalMb),baseline:formatMegabytes(yesterday.totalMb),change:trafficChange,time:todayKey,severity:severityFromChange(trafficChange),target:'traffic-analysis'});
  }
  const mauChange = changeRate(today.mau, yesterday.mau);
  if (Number.isFinite(mauChange) && Number.isFinite(dauChange) && Math.abs(mauChange) < .05 && dauChange < -.2) {
    alerts.push({type:'activity',title:'MAU 稳定但 DAU 明显下降',current:formatCount(today.dau),baseline:formatCount(yesterday.dau),change:dauChange,time:todayKey,severity:severityFromChange(dauChange),target:'user-analysis'});
  }

  const hourly = analyticsHourlyData();
  if (hourly.length >= 8) {
    const latestHour = hourly[hourly.length - 1];
    const previousHours = hourly.slice(Math.max(0, hourly.length - 25), -1);
    const hourlyBaseline = averageOf(previousHours, 'totalMb');
    const hourlyChange = changeRate(latestHour.totalMb, hourlyBaseline);
    if (Number.isFinite(hourlyChange) && hourlyChange > .5) {
      alerts.push({
        type:'traffic',
        title:'最近一小时流量明显高于 24 小时均值',
        current:formatMegabytes(latestHour.totalMb),
        baseline:formatMegabytes(hourlyBaseline),
        change:hourlyChange,
        time:cleanText(latestHour.timestamp) || todayKey,
        severity:severityFromChange(hourlyChange),
        target:'traffic-analysis',
      });
    }
  }

  const users = currentUsersData && Array.isArray(currentUsersData.users) ? currentUsersData.users : [];
  const latest = latestAppVersion(users);
  if (latest && users.length) {
    const latestCount = users.filter(function(user) { return cleanText(user.appVersion).replace(/^v/i, '') === latest; }).length;
    const coverage = latestCount / users.length;
    if (coverage < .8) {
      alerts.push({type:'version',title:'最新版覆盖率偏低',current:formatPercentRatio(coverage),baseline:'目标 80%',change:coverage / .8 - 1,time:todayKey,severity:coverage < .5 ? 'warning' : 'attention',target:'devices'});
    }
    const activeUsers = users.filter(function(user) { return user.activeToday; });
    const oldVersionGroups = groupUsersBy(activeUsers.filter(function(user) {
      return cleanText(user.appVersion).replace(/^v/i, '') !== latest;
    }), function(user) {
      return cleanText(user.appVersion) ? 'v' + cleanText(user.appVersion).replace(/^v/i, '') : '未知版本';
    }).sort(function(left, right) { return right.users - left.users; });
    const largestOldVersion = oldVersionGroups[0];
    const oldVersionShare = largestOldVersion && activeUsers.length
      ? largestOldVersion.users / activeUsers.length
      : 0;
    if (largestOldVersion && oldVersionShare >= .15) {
      alerts.push({
        type:'version',
        title:largestOldVersion.label + ' 仍有较多活跃用户',
        current:formatCount(largestOldVersion.users) + ' 人',
        baseline:'今日 DAU 的 ' + formatPercentRatio(oldVersionShare),
        change:oldVersionShare,
        time:todayKey,
        severity:oldVersionShare >= .3 ? 'warning' : 'attention',
        target:'devices',
      });
    }
  }
  const models = groupUsersBy(users, function(user) { return cleanText(user.model) || '未知型号'; });
  const averageModelTraffic = users.length
    ? users.reduce(function(total, user) { return total + (Number(user.month && user.month.totalMb) || 0); }, 0) / users.length
    : 0;
  const abnormalModel = models.filter(function(group) {
    return group.users >= 2 && averageModelTraffic > 0 && group.perCapitaMb > averageModelTraffic * 2.5;
  }).sort(function(left,right) { return right.perCapitaMb - left.perCapitaMb; })[0];
  if (abnormalModel) {
    alerts.push({type:'device',title:abnormalModel.label + ' 人均流量异常高',current:formatMegabytes(abnormalModel.perCapitaMb),baseline:'全体 ' + formatMegabytes(averageModelTraffic),change:abnormalModel.perCapitaMb / averageModelTraffic - 1,time:todayKey,severity:'warning',target:'devices'});
  }
  if (alerts.length === 0) {
    alerts.push({type:'normal',title:'当前未发现明显运营异常',current:'正常',baseline:'近 7 日基准',change:0,time:todayKey,severity:'normal',target:'overview'});
  }
  const order = {critical:0,warning:1,attention:2,normal:3};
  return alerts.sort(function(left,right) { return order[left.severity] - order[right.severity]; });
}
function alertItemHtml(alert) {
  return '<button class="alert-item" type="button" data-alert-target="' + esc(alert.target) + '">' +
    '<span class="severity-dot severity-' + alert.severity + '"></span>' +
    '<span class="alert-main"><strong>' + esc(alert.title) + '</strong><small>当前 ' + esc(alert.current) + ' · 对比 ' + esc(alert.baseline) + ' · ' + esc(alert.time) + '</small></span>' +
    '<span class="alert-value">' + esc(formatSignedChange(alert.change)) + '<small class="severity-badge severity-' + alert.severity + '">' + esc(severityLabel(alert.severity)) + '</small></span>' +
    '</button>';
}
function attachAlertNavigation(container) {
  if (!container) return;
  container.querySelectorAll('[data-alert-target]').forEach(function(button) {
    button.onclick = function() {
      activateTab(button.dataset.alertTarget || 'overview');
      window.scrollTo({top:0,behavior:'auto'});
    };
  });
}
function renderAlertPreview() {
  const container = el('overviewAlerts');
  if (!container) return;
  container.innerHTML = analyticsAlerts.slice(0, 4).map(alertItemHtml).join('');
  attachAlertNavigation(container);
}
function renderAlerts() {
  if (!currentUsersData) return;
  const counts = {critical:0,warning:0,attention:0,normal:0};
  analyticsAlerts.forEach(function(alert) { counts[alert.severity] += 1; });
  renderKpiGrid('alertKpis', [
    {label:'严重',value:formatCount(counts.critical),change:null,comparison:'需要立即处理'},
    {label:'警告',value:formatCount(counts.warning),change:null,comparison:'建议当天检查'},
    {label:'关注',value:formatCount(counts.attention),change:null,comparison:'持续观察变化'},
    {label:'正常',value:formatCount(counts.normal),change:null,comparison:'未发现明显偏离'},
  ]);
  const severity = el('alertSeverityFilter').value;
  const filtered = analyticsAlerts.filter(function(alert) { return !severity || alert.severity === severity; });
  const container = el('alertList');
  container.innerHTML = filtered.length ? filtered.map(alertItemHtml).join('') : '<div class="empty-state">当前筛选条件下没有异常事件</div>';
  attachAlertNavigation(container);
}
function renderAnalyticsDashboard() {
  if (!currentUsersData) return;
  updateAnalyticsFilterStatus();
  analyticsAlerts = buildAnalyticsAlerts();
  renderOverview();
  renderUserAnalysis();
  renderTrafficAnalysis();
  renderDevices();
  renderAlerts();
}
document.addEventListener('pointerover', function(event) {
  const target = event.target.closest && event.target.closest('[data-tooltip]');
  if (!target) return;
  const tooltip = el('chartTooltip');
  tooltip.textContent = target.dataset.tooltip || '';
  tooltip.hidden = false;
});
document.addEventListener('pointermove', function(event) {
  const tooltip = el('chartTooltip');
  if (tooltip && !tooltip.hidden) {
    tooltip.style.left = event.clientX + 'px';
    tooltip.style.top = event.clientY + 'px';
  }
});
document.addEventListener('pointerout', function(event) {
  const target = event.target.closest && event.target.closest('[data-tooltip]');
  if (target) el('chartTooltip').hidden = true;
});
function renderUsers(data) {
  data = data || {users: []};
  currentUsersData = data;
  populateAnalyticsDimensionFilters();
  renderAnalyticsDashboard();
  dirtyUserIds.clear();
  selectedUserIds.clear();
  const batchLevel = el('batchLevel');
  if (batchLevel) batchLevel.value = '';
  const rows = (data.users || []).map(function(user) {
    return '<tr class="user-row" data-user-row="' + esc(user.id) + '">' +
      '<td class="selection-cell"><input type="checkbox" data-user-select="' + esc(user.id) +
        '" aria-label="选择用户"></td>' +
      '<td><code title="' + esc(user.id) + '">' + esc(user.id.slice(0, 16) + '…') + '</code></td>' +
      '<td><input class="sheet-input" data-user-field="nickname" maxlength="24" value="' + esc(user.nickname) + '"></td>' +
      '<td><select class="sheet-select" data-user-field="level">' + userLevelOptions(user.level) + '</select></td>' +
      '<td><input class="sheet-input" data-user-field="brand" maxlength="80" value="' + esc(user.brand) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="model" maxlength="120" value="' + esc(user.model) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="androidVersion" maxlength="80" value="' + esc(user.androidVersion) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="appVersion" maxlength="40" value="' + esc(user.appVersion) + '"></td>' +
      '<td class="readonly">' + (user.activeToday ? '是' : '否') + '</td>' +
      '<td class="readonly">' + (user.activeThisMonth ? '是' : '否') + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.today && user.today.uploadMb) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.today && user.today.downloadMb) + '</td>' +
      '<td class="readonly">' + usageTotal(user.today) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.month && user.month.uploadMb) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.month && user.month.downloadMb) + '</td>' +
      '<td class="readonly">' + usageTotal(user.month) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.totalUploadMb) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.totalDownloadMb) + '</td>' +
      '<td class="readonly">' + usageTotal(user) + '</td>' +
      '<td class="readonly">' + esc(user.createdAt || '暂无') + '</td>' +
      '<td class="readonly">' + esc(user.lastSeenAt || '暂无') + '</td>' +
      '<td class="readonly">' + esc(user.lastReportAt || '暂无') + '</td>' +
      '<td><button class="sheet-save" type="button" data-save-user="' + esc(user.id) + '">保存</button>' +
        '<button class="sheet-delete danger" type="button" data-delete-user="' + esc(user.id) + '">删除</button></td>' +
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
  el('usersTable').querySelectorAll('[data-user-select]').forEach(function(control) {
    control.onchange = function() {
      toggleUserSelection(control.dataset.userSelect, control.checked);
    };
  });
  el('usersTable').querySelectorAll('[data-save-user]').forEach(function(button) {
    button.onclick = function() { saveUser(button.dataset.saveUser); };
  });
  el('usersTable').querySelectorAll('[data-delete-user]').forEach(function(button) {
    button.onclick = function() { deleteUser(button.dataset.deleteUser); };
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
  const hasSelection = selectedUserIds.size > 0;
  const batchLevel = el('batchLevel');
  const batchSetLevelButton = el('batchSetLevelButton');
  const batchBanUsersButton = el('batchBanUsersButton');
  const batchDeleteUsersButton = el('batchDeleteUsersButton');
  if (batchLevel) batchLevel.disabled = savingAllUsers || !hasSelection;
  if (batchSetLevelButton) batchSetLevelButton.disabled = savingAllUsers || !hasSelection;
  if (batchBanUsersButton) batchBanUsersButton.disabled = savingAllUsers || !hasSelection;
  if (batchDeleteUsersButton) batchDeleteUsersButton.disabled = savingAllUsers || !hasSelection;
}
function userRow(accountId) {
  return el('usersTable').querySelector('[data-user-row="' + accountId + '"]');
}
function userFieldValue(row, field) {
  const control = row && row.querySelector('[data-user-field="' + field + '"]');
  return control ? control.value.trim() : '';
}
function setAnalyticsRequestState(type, message) {
  const className = type === 'error' ? 'error-state' : 'loading-state';
  const content = '<div class="' + className + '">' + esc(message) + '</div>';
  document.querySelectorAll(
    '.analytics-panel .kpi-grid, .analytics-panel .chart-host, .analytics-panel .bar-chart-host, ' +
    '.analytics-panel .structure-host, .analytics-panel .stat-list, .analytics-panel .alert-preview, ' +
    '.analytics-panel .alert-list, .analytics-panel .compact-table-wrap'
  ).forEach(function(container) { container.innerHTML = content; });
}
async function loadUsers(force) {
  if (!force && dirtyUserIds.size > 0 && !confirm('刷新会丢弃尚未保存的修改，确定继续吗？')) {
    return false;
  }
  setAnalyticsRequestState('loading', '正在读取运营数据…');
  el('usersTable').innerHTML = '<div class="loading-state">正在读取用户详细表格…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users', {headers: headers()});
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      setAnalyticsRequestState('error', data.error || '运营数据读取失败');
      el('usersTable').innerHTML = '<div class="error-state">用户详细表格读取失败</div>';
      show(data.error || ('读取用户失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    renderUsers(data);
    return true;
  } catch (error) {
    setAnalyticsRequestState('error', '运营数据读取失败，请检查网络和 USERS_DB 绑定');
    el('usersTable').innerHTML = '<div class="error-state">用户详细表格读取失败</div>';
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
async function deleteUser(accountId) {
  const row = userRow(accountId);
  if (!row) return false;
  const user = currentUsersData && (currentUsersData.users || []).find(function(item) {
    return item.id === accountId;
  });
  if (dirtyUserIds.size > 0 && (dirtyUserIds.size > 1 || !dirtyUserIds.has(accountId))) {
    show('请先保存其他用户的修改，再删除用户', true);
    return false;
  }
  const displayName = user && user.nickname ? user.nickname : accountId.slice(0, 16) + '…';
  if (!confirm('确定删除用户“' + displayName + '”及其全部流量统计吗？此操作不可恢复。')) {
    return false;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(accountId), {
      method: 'DELETE',
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(data.error || ('用户删除失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    dirtyUserIds.delete(accountId);
    const refreshed = await loadUsers(true);
    show(refreshed ? '用户及其流量统计已删除' : '用户已删除，但用户列表刷新失败', !refreshed);
    return true;
  } catch (error) {
    show('用户删除失败，请检查网络', true);
    return false;
  }
}
function selectedUserAccountIds() {
  const accountIds = Array.from(selectedUserIds);
  if (accountIds.length === 0) {
    show('请先选择用户', true);
    return null;
  }
  if (accountIds.length > 100) {
    show('单次最多选择 100 个用户', true);
    return null;
  }
  return accountIds;
}
async function batchSetUserLevel(level, successMessage) {
  const accountIds = selectedUserAccountIds();
  if (!accountIds) return false;
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/batch', {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify({accountIds: accountIds, level: level})
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(adminErrorMessage(data.error, '批量修改失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    const refreshed = await loadUsers(true);
    show(refreshed ? successMessage : '批量修改成功，但用户列表刷新失败', !refreshed);
    return true;
  } catch (error) {
    show('批量修改失败，请检查网络', true);
    return false;
  }
}
async function batchSetLevel() {
  const rawLevel = el('batchLevel').value;
  const level = Number(rawLevel);
  if (rawLevel === '' || !Number.isInteger(level) || level < 0 || level > 6) {
    show('请选择要设置的等级', true);
    return;
  }
  if (level === 0 && !confirm('确定将选中的用户全部封禁吗？')) return;
  await batchSetUserLevel(level, '已批量修改用户等级');
}
async function batchBanUsers() {
  const accountIds = selectedUserAccountIds();
  if (!accountIds) return;
  if (!confirm('确定封禁选中的 ' + accountIds.length + ' 个用户吗？')) return;
  await batchSetUserLevel(0, '已批量封禁 ' + accountIds.length + ' 个用户');
}
async function batchDeleteUsers() {
  const accountIds = selectedUserAccountIds();
  if (!accountIds) return;
  if (dirtyUserIds.size > 0) {
    show('请先保存未保存的用户修改，再批量删除', true);
    return;
  }
  if (!confirm('确定永久删除选中的 ' + accountIds.length + ' 个用户及其全部流量统计吗？此操作不可恢复。')) {
    return;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/batch', {
      method: 'DELETE',
      headers: headers(),
      body: JSON.stringify({accountIds: accountIds})
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(adminErrorMessage(data.error, '批量删除失败（HTTP ' + response.status + '）'), true);
      return;
    }
    const refreshed = await loadUsers(true);
    show(refreshed ? '已批量删除 ' + (Number(data.changed) || 0) + ' 个用户' : '批量删除成功，但用户列表刷新失败', !refreshed);
  } catch (error) {
    show('批量删除失败，请检查网络', true);
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
    '注册时间', '最后访问', '最后上报'
  ];
  const records = users.map(function(user) {
    return [
      user.id,
      user.nickname,
      user.level,
      user.brand,
      user.model,
      user.androidVersion,
      user.appVersion,
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
  show('用户详细表格已导出，可直接使用 Excel 打开');
}
function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  const sourceType = source.content ? 'content' : 'url';
  row.innerHTML = '<div class="grid">' +
    '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
    '<div class="field source-type-field"><label>订阅来源<select data-field="sourceType">' +
      '<option value="url" ' + (sourceType === 'url' ? 'selected' : '') + '>外部接口</option>' +
      '<option value="content" ' + (sourceType === 'content' ? 'selected' : '') + '>纯文本节点</option>' +
      '</select></label></div>' +
    '<div class="field"><label>最低账户等级<select data-field="minLevel">' + levelOptions(source.minLevel == null ? 1 : source.minLevel) + '</select></label></div>' +
    '<div class="field"><label>最低应用版本<input data-field="minAppVersion" type="text" value="' + esc(source.minAppVersion) + '" placeholder="例如 2.40（留空不限制）"></label></div>' +
    '<div class="field"><label>后台备注（仅后台保存）<input data-field="remark" type="text" value="' + esc(source.remark) + '" placeholder="例如：备用线路、长期稳定"></label></div>' +
    '</div>' +
    '<div class="source-mode" data-source-mode="url"><div class="grid">' +
      '<div class="field"><label>外部明文订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/plain-sub"></label></div>' +
      '<div class="field"><label>接口缓存时间（分钟）<input data-field="cacheMinutes" type="number" min="0" max="10080" step="1" value="' + esc(source.cacheMinutes == null ? 30 : source.cacheMinutes) + '"></label></div>' +
    '</div><small>接口必须返回可识别的明文节点，由 Worker 中转并加密；缓存时间设为 0 会每次请求上游。</small></div>' +
    '<div class="source-mode" data-source-mode="content"><div class="field"><label>纯文本节点（每行一个）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div><small>保存后由 Worker 直接加密下发，不经过外部接口缓存。</small></div>' +
    '<label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
    '<div class="row-actions"><button class="danger" type="button">删除</button></div>';
  row.querySelector('.danger').onclick = function() { row.remove(); };
  row.querySelector('[data-field="sourceType"]').onchange = function() { updateSourceMode(row); };
  updateSourceMode(row);
  el('subscriptionRows').appendChild(row);
}
function updateSourceMode(row) {
  const type = row.querySelector('[data-field="sourceType"]').value;
  row.querySelectorAll('[data-source-mode]').forEach(function(panel) {
    const active = panel.dataset.sourceMode === type;
    panel.hidden = !active;
    panel.querySelectorAll('input, textarea, select').forEach(function(control) {
      control.disabled = !active;
    });
  });
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
    const sourceType = row.querySelector('[data-field="sourceType"]').value;
    const url = row.querySelector('[data-field="url"]').value.trim();
    const content = row.querySelector('[data-field="content"]').value.trim();
    return {
      name: row.querySelector('[data-field="name"]').value.trim() || '线路' + (index + 1),
      url: sourceType === 'url' ? url : '',
      enabled: row.querySelector('[data-field="enabled"]').checked,
      minLevel: Number(row.querySelector('[data-field="minLevel"]').value || 1),
      minAppVersion: row.querySelector('[data-field="minAppVersion"]').value.trim(),
      cacheMinutes: sourceType === 'url'
        ? Number(row.querySelector('[data-field="cacheMinutes"]').value || 0)
        : 0,
      content: sourceType === 'content' ? content : '',
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
    if (await loadUsers()) show('用户表单已刷新');
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
