
/*
 * FireflyVPN Cloudflare Worker 后端（单文件部署）
 *
 * 必需的 Cloudflare 配置：
 *   - KV 命名空间绑定：CONFIG
 *   - D1 数据库绑定：USERS_DB
 *   - Worker Secret：ADMIN_TOKEN
 *
 * 客户端公开接口：
 *   GET /api/client
 *   GET /api/client?refresh=1   // 刷新由客户端处理，接口仅兼容保留该参数
 *   GET /api/subscription/<id>?uid=<UID>  // 经过身份校验的 Worker 中转，所有托管线路在这里加密
 *   POST /api/account/login     // 使用邮箱和密码切换账户
 *   POST /api/account/delete    // 永久删除当前账户
 *   GET  /api/account/tasks?uid=<UID>
 *   GET  /api/account/tasks/speed-share-results?uid=<UID>
 *   POST /api/account/tasks/checkin
 *   POST /api/account/tasks/speed-share
 *   POST /api/account/tasks/telegram
 *   POST /api/debug/users       // 临时公开调试用户接口，测试结束后应移除
 *
 * 管理后台接口：
 *   GET  /<ADMIN_UUID>
 *   GET  /<ADMIN_UUID>/api/state
 *   PUT  /<ADMIN_UUID>/api/state
 *   GET  /<ADMIN_UUID>/api/users
 *   GET  /<ADMIN_UUID>/api/users/growth?period=day|week|month&start=YYYY-MM-DD&end=YYYY-MM-DD
 *   GET  /<ADMIN_UUID>/api/users/traffic?period=day|week|month&start=YYYY-MM-DD&end=YYYY-MM-DD
 *   DELETE /<ADMIN_UUID>/api/users/<UID>
 *   PATCH /<ADMIN_UUID>/api/users/batch       // 批量修改用户等级
 *   DELETE /<ADMIN_UUID>/api/users/batch      // 批量删除用户
 *   GET  /<ADMIN_UUID>/api/telegram-tasks
 *   PATCH /<ADMIN_UUID>/api/telegram-tasks/<id>
 *   GET  /<ADMIN_UUID>/api/speed-share-results
 */

const STATE_KEY = "fireflyvpn:state:v1";
const CLIENT_IP_HEADER = "X-Firefly-Client-IP";
const MAX_MANAGED_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;
const MAX_BATCH_USER_COUNT = 100;
const MAX_DEBUG_TRAFFIC_RECORDS = 400;
const MAX_SPEED_SHARE_RESULTS = 1000;
const MAX_TRAFFIC_BYTES = 1e15;
const MAX_ACCOUNT_EXPERIENCE = 1e12;
const ACCOUNT_BINDING_EXPERIENCE = 50;
const DEFAULT_DAILY_CHECKIN_EXPERIENCE = 10;
const DEFAULT_TELEGRAM_TASK_EXPERIENCE = 50;
const DEFAULT_SPEED_SHARE_SMART_EXPERIENCE = 5;
const DEFAULT_EXPERIENCE_THRESHOLDS = [100, 200, 400, 800, 1600];
const SUBSCRIPTION_CACHE_KEY_PREFIX = "fireflyvpn:subscription-cache:v1:";
const DEFAULT_SUBSCRIPTION_CACHE_MINUTES = 30;
const MAX_SUBSCRIPTION_CACHE_MINUTES = 7 * 24 * 60;
const PLAINTEXT_NODE_PATTERN = /(?:^|\n)\s*(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+/i;
const PRIVATE_B64_ALPHABET =
  "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX";
const TEXT_ENCODER = new TextEncoder();
// 修改此 UUID 可以更换私有管理后台地址。
const ADMIN_UUID = "3e9a0eed-6a29-4d16-91b1-3adae4bd88e6";
const ADMIN_BASE_PATH = `/${ADMIN_UUID}`;

const USER_SCHEMA_STATEMENTS = [
`CREATE TABLE IF NOT EXISTS users_mb (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uid TEXT NOT NULL UNIQUE,
  nickname TEXT NOT NULL,
  level INTEGER NOT NULL DEFAULT 1 CHECK (level BETWEEN 0 AND 6),
  experience INTEGER NOT NULL DEFAULT 0,
  email TEXT NOT NULL DEFAULT '',
  password_hash TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
)`,
`CREATE INDEX IF NOT EXISTS idx_users_mb_created_at ON users_mb(created_at DESC)`,
`CREATE TABLE IF NOT EXISTS user_devices_mb (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL UNIQUE,
  brand TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  android_version TEXT NOT NULL DEFAULT '',
  android_sdk INTEGER NOT NULL DEFAULT 0,
  app_version TEXT NOT NULL DEFAULT '',
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_user_devices_mb_user_id ON user_devices_mb(user_id)`,
`CREATE TABLE IF NOT EXISTS user_usage_mb (
  user_id INTEGER NOT NULL,
  session_id TEXT NOT NULL,
  subscription_group_name TEXT NOT NULL DEFAULT '',
  started_at TEXT NOT NULL,
  ended_at TEXT NOT NULL,
  upload_bytes INTEGER NOT NULL DEFAULT 0,
  download_bytes INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, session_id),
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_user_usage_mb_time
  ON user_usage_mb(user_id, ended_at DESC)`,
`CREATE TABLE IF NOT EXISTS telegram_task_submissions_mb (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL UNIQUE,
  telegram_nickname TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
  submitted_at TEXT NOT NULL,
  reviewed_at TEXT NOT NULL DEFAULT '',
  experience_awarded INTEGER NOT NULL DEFAULT 0,
  resubmit_allowed INTEGER NOT NULL DEFAULT 0 CHECK (resubmit_allowed IN (0, 1)),
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_telegram_task_submissions_mb_status
  ON telegram_task_submissions_mb(status, submitted_at DESC)`,
`CREATE TABLE IF NOT EXISTS daily_checkins_mb (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  checkin_date TEXT NOT NULL,
  checked_in_at TEXT NOT NULL,
  experience_awarded INTEGER NOT NULL DEFAULT 0,
  UNIQUE (user_id, checkin_date),
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_daily_checkins_mb_user_date
  ON daily_checkins_mb(user_id, checkin_date DESC)`,
`CREATE TABLE IF NOT EXISTS speed_share_results_mb (
  node_id TEXT PRIMARY KEY,
  node_name TEXT NOT NULL DEFAULT '',
  subscription_group_name TEXT NOT NULL DEFAULT '',
  tcping_latency_ms INTEGER NOT NULL DEFAULT 0,
  url_test_latency_ms INTEGER NOT NULL DEFAULT 0,
  download_mbps REAL NOT NULL DEFAULT 0,
  upload_mbps REAL NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  last_reported_at TEXT NOT NULL,
  report_count INTEGER NOT NULL DEFAULT 0
)`,
`CREATE INDEX IF NOT EXISTS idx_speed_share_results_mb_updated_at
  ON speed_share_results_mb(updated_at DESC)`,
`CREATE TABLE IF NOT EXISTS speed_share_daily_mb (
  user_id INTEGER NOT NULL,
  report_date TEXT NOT NULL,
  reported_at TEXT NOT NULL,
  PRIMARY KEY (user_id, report_date),
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
)`,
`CREATE INDEX IF NOT EXISTS idx_speed_share_daily_mb_user_date
  ON speed_share_daily_mb(user_id, report_date DESC)`,
];
const USER_SCHEMA_MIGRATIONS = [
  "ALTER TABLE users_mb ADD COLUMN experience INTEGER NOT NULL DEFAULT 0",
  "ALTER TABLE users_mb ADD COLUMN email TEXT NOT NULL DEFAULT ''",
  "ALTER TABLE users_mb ADD COLUMN password_hash TEXT NOT NULL DEFAULT ''",
  "ALTER TABLE telegram_task_submissions_mb ADD COLUMN resubmit_allowed INTEGER NOT NULL DEFAULT 0",
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
    experienceThresholds: DEFAULT_EXPERIENCE_THRESHOLDS,
    dailyCheckinExperience: DEFAULT_DAILY_CHECKIN_EXPERIENCE,
    telegramGroupUrl: "",
    telegramTaskExperience: DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    speedShareSmartExperience: DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
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
          headers: {
            "content-type": "text/html; charset=utf-8",
            "cache-control": "no-store, no-cache, must-revalidate",
          },
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

      // 临时公开调试接口；测试完成后应同时移除该路由和对应处理函数。
      if (path === "/api/debug/users" && request.method === "POST") {
        return handleDebugUserV2(request, env);
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
        refreshOnAppOpen: source.refreshOnAppOpen,
        url: `${origin}/api/subscription/${encodeURIComponent(source.id)}`,
      })),
    notice: visibleNotice(state.notice),
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

  const requestUrl = new URL(request.url);
  const reference = requestUrl.searchParams.get("uid") || requestUrl.searchParams.get("accountId");
  if (!normalizeUserReference(reference)) {
    return json({ ok: false, error: "uid_required" }, 401);
  }
  const access = await resolveAccountAccess(env, reference);
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
    const deviceId = normalizeAccountId(body && body.deviceId);
    if (!deviceId) {
      return json({ ok: false, error: "invalid_device_id" }, 400);
    }

    const now = new Date();
    await ensureUserSchema(env);
    await registerOrTouchUser(env, deviceId, body || {}, now);
    const user = await readD1User(env, deviceId);

    return json({ ok: true, account: publicAccount(user) });
  }

  if (path === "/api/account/bind" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const requestedUid = normalizeUid(body && body.uid);
    const email = normalizeAccountEmail(body && body.email);
    const password = normalizeAccountPassword(body && body.password);
    if (!deviceId || !email || !password) {
      return json({ ok: false, error: "invalid_bind_credentials" }, 400);
    }

    const db = await ensureUserSchema(env);
    const identity = await resolveUserIdentity(env, deviceId);
    if (!identity || (requestedUid && requestedUid !== identity.uid)) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    if (user.email) {
      return json({ ok: false, error: "account_already_bound" }, 409);
    }
    const emailOwner = await db.prepare(
      "SELECT id FROM users_mb WHERE email = ? AND id <> ?",
    ).bind(email, identity.userId).first();
    if (emailOwner) {
      return json({ ok: false, error: "email_already_bound" }, 409);
    }

    const passwordHash = await hashAccountPassword(password);
    const state = await readState(env);
    const progression = applyExperience(
      user.level,
      user.experience,
      ACCOUNT_BINDING_EXPERIENCE,
      state.settings.experienceThresholds,
    );
    const result = await db.prepare(
      "UPDATE users_mb SET email = ?, password_hash = ?, experience = ?, level = ? " +
        "WHERE id = ?",
    ).bind(
      email,
      passwordHash,
      progression.experience,
      progression.level,
      identity.userId,
    ).run();
    if (!result.meta || Number(result.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const updated = await readD1User(env, identity.userId);
    return json({ ok: true, account: publicAccount(updated) });
  }

  // 使用邮箱和密码切换账户，同时把当前设备转移到登录账户。
  if (path === "/api/account/login" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const email = normalizeAccountEmail(body && body.email);
    const password = normalizeAccountPassword(body && body.password);
    if (!deviceId || !email || !password) {
      return json({ ok: false, error: "invalid_login_credentials" }, 400);
    }

    const db = await ensureUserSchema(env);
    const row = await db.prepare(
      "SELECT id, uid, level, email, password_hash FROM users_mb WHERE email = ?",
    ).bind(email).first();
    if (!row || !(await verifyAccountPassword(password, cleanText(row.password_hash)))) {
      return json({ ok: false, error: "invalid_login_credentials" }, 401);
    }
    if (normalizeLevel(row.level, 0) === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }

    await attachDeviceToUser(db, Number(row.id), deviceId, body || {});
    const account = await readD1User(env, Number(row.id));
    return json({ ok: true, account: publicAccount(account) });
  }

  // 注销会删除账户、设备和流量数据；绑定账户必须再次提供当前密码。
  if (path === "/api/account/delete" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const requestedUid = normalizeUid(body && body.uid);
    const password = typeof (body && body.password) === "string" ? body.password : "";
    if (!deviceId) {
      return json({ ok: false, error: "invalid_delete_request" }, 400);
    }

    const db = await ensureUserSchema(env);
    const identity = await resolveUserIdentity(env, deviceId);
    if (!identity || (requestedUid && requestedUid !== identity.uid)) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    const stored = await db.prepare(
      "SELECT email, password_hash FROM users_mb WHERE id = ?",
    ).bind(identity.userId).first();
    if (cleanText(stored && stored.email)) {
      if (!(await verifyAccountPassword(password, cleanText(stored.password_hash)))) {
        return json({ ok: false, error: "invalid_account_password" }, 401);
      }
    }

    const result = await db.batch([
      db.prepare("DELETE FROM user_usage_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM telegram_task_submissions_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM daily_checkins_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM speed_share_daily_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM users_mb WHERE id = ?").bind(identity.userId),
    ]);
    const deleteResult = result[result.length - 1];
    if (!deleteResult || !deleteResult.meta || Number(deleteResult.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({ ok: true });
  }

  if (path === "/api/account/profile" && request.method === "PATCH") {
    const body = await request.json();
    const reference = body && (body.uid || body.accountId);
    const nickname = normalizeNickname(body && body.nickname);
    const identity = await resolveUserIdentity(env, reference);
    if (!identity || !nickname) {
      return json({ ok: false, error: "invalid_profile" }, 400);
    }

    const db = await ensureUserSchema(env);
    const result = await db.prepare(
      "UPDATE users_mb SET nickname = ? WHERE id = ?",
    ).bind(nickname, identity.userId).run();
    if (!result.meta || Number(result.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, identity.userId);
    return json({ ok: true, account: publicAccount(user) });
  }

  if (path === "/api/account/tasks" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const reference = requestUrl.searchParams.get("uid") || requestUrl.searchParams.get("accountId");
    const identity = await resolveUserIdentity(env, reference);
    if (!identity) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const state = await readState(env);
    return json({
      ok: true,
      account: publicAccount(user),
      checkin: await readDailyCheckin(env, identity.userId, state.settings),
      speedShare: await readSpeedShareStatus(env, identity.userId, state.settings),
      telegram: await readTelegramTask(env, identity.userId, state.settings),
    });
  }

  if (path === "/api/account/tasks/speed-share-results" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const reference = requestUrl.searchParams.get("uid") || requestUrl.searchParams.get("accountId");
    const identity = await resolveUserIdentity(env, reference);
    if (!identity) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      "SELECT node_id, node_name, subscription_group_name, " +
        "tcping_latency_ms, url_test_latency_ms, download_mbps, " +
        "upload_mbps, updated_at, last_reported_at, report_count " +
        "FROM speed_share_results_mb ORDER BY updated_at DESC LIMIT 1000",
    ).all();
    return json({
      ok: true,
      results: (query.results || []).map(publicAdminSpeedShareResult),
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/account/tasks/checkin" && request.method === "POST") {
    const body = await request.json();
    const reference = body && (body.uid || body.accountId);
    const identity = await resolveUserIdentity(env, reference);
    if (!identity) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }

    const state = await readState(env);
    const db = await ensureUserSchema(env);
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }

    const checkinDate = usagePeriodKeys(new Date()).day;
    const checkedInAt = beijingTimestamp();
    const reward = normalizeExperience(
      state.settings.dailyCheckinExperience,
      DEFAULT_DAILY_CHECKIN_EXPERIENCE,
    );
    const progression = applyExperience(
      user.level,
      user.experience,
      reward,
      state.settings.experienceThresholds,
    );
    const inserted = await db.prepare(
      `INSERT OR IGNORE INTO daily_checkins_mb
       (user_id, checkin_date, checked_in_at, experience_awarded)
       VALUES (?, ?, ?, ?)`,
    ).bind(identity.userId, checkinDate, checkedInAt, reward).run();
    if (!inserted || !inserted.meta || Number(inserted.meta.changes) < 1) {
      return json({
        ok: false,
        error: "daily_checkin_already_completed",
        account: publicAccount(user),
        checkin: await readDailyCheckin(env, identity.userId, state.settings),
      });
    }

    await db.prepare(
      "UPDATE users_mb SET experience = ?, level = ? WHERE id = ?",
    ).bind(progression.experience, progression.level, identity.userId).run();

    const updated = await readD1User(env, identity.userId);
    return json({
      ok: true,
      account: publicAccount(updated),
      checkin: await readDailyCheckin(env, identity.userId, state.settings),
    });
  }

  if (path === "/api/account/tasks/speed-share" && request.method === "POST") {
    return handleSpeedShareSubmission(request, env);
  }

  if (path === "/api/account/tasks/telegram" && request.method === "POST") {
    const body = await request.json();
    const reference = body && (body.uid || body.accountId);
    const telegramNickname = normalizeTelegramNickname(body && body.telegramNickname);
    const identity = await resolveUserIdentity(env, reference);
    if (!identity || !telegramNickname) {
      return json({ ok: false, error: "invalid_telegram_task_submission" }, 400);
    }

    const state = await readState(env);
    if (!state.settings.telegramGroupUrl) {
      return json({ ok: false, error: "telegram_task_disabled" }, 409);
    }
    const db = await ensureUserSchema(env);
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    const existing = await db.prepare(
      "SELECT status, resubmit_allowed FROM telegram_task_submissions_mb WHERE user_id = ?",
    ).bind(identity.userId).first();
    const existingStatus = cleanText(existing && existing.status);
    // 待审核期间禁止修改昵称或重复提交；只有后台“驳回”后才允许重新提交。
    const canResubmit = existingStatus === "rejected" &&
      Number(existing && existing.resubmit_allowed) === 1;
    if (existingStatus === "pending") {
      return json({ ok: false, error: "telegram_task_already_submitted" }, 409);
    }
    if (existingStatus === "approved") {
      return json({ ok: false, error: "telegram_task_already_approved" }, 409);
    }
    if (existingStatus === "rejected" && !canResubmit) {
      return json({ ok: false, error: "telegram_task_permanently_rejected" }, 409);
    }

    const submittedAt = beijingTimestamp();
    await db.prepare(
      `INSERT INTO telegram_task_submissions_mb
       (user_id, telegram_nickname, status, submitted_at, reviewed_at, experience_awarded, resubmit_allowed)
       VALUES (?, ?, 'pending', ?, '', 0, 0)
       ON CONFLICT(user_id) DO UPDATE SET
         telegram_nickname = excluded.telegram_nickname,
         status = 'pending',
         submitted_at = excluded.submitted_at,
         reviewed_at = '',
         experience_awarded = 0,
         resubmit_allowed = 0`,
    ).bind(identity.userId, telegramNickname, submittedAt).run();

    return json({
      ok: true,
      account: publicAccount(user),
      telegram: await readTelegramTask(env, identity.userId, state.settings),
    });
  }

  if (path === "/api/account/report" && request.method === "POST") {
    const body = await request.json();
    const reference = body && (body.uid || body.accountId);
    const sessionId = cleanText(body && body.sessionId).slice(0, 128);
    const identity = await resolveUserIdentity(env, reference);
    if (!identity || !sessionId) {
      return json({ ok: false, error: "invalid_report" }, 400);
    }

    await ensureUserSchema(env);
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }

    const now = new Date();
    const endedAt = normalizeReportTimestamp(body && body.endedAt, beijingTimestamp(now));
    const startedAt = normalizeReportTimestamp(body && body.startedAt, endedAt);
    if (!startedAt || !endedAt || Date.parse(startedAt) > Date.parse(endedAt)) {
      return json({ ok: false, error: "invalid_report_time" }, 400);
    }
    const db = usersDb(env);
    const subscriptionGroupName = cleanText(body && body.subscriptionGroupName).slice(0, 200);
    const uploadBytes = reportBytes(body && body.uploadBytes, body && body.uploadMb);
    const downloadBytes = reportBytes(body && body.downloadBytes, body && body.downloadMb);
    const inserted = await db.prepare(
      `INSERT OR IGNORE INTO user_usage_mb
       (user_id, session_id, subscription_group_name, started_at, ended_at, upload_bytes, download_bytes)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      identity.userId,
      sessionId,
      subscriptionGroupName,
      startedAt,
      endedAt,
      uploadBytes,
      downloadBytes,
    ).run();
    if (!inserted.meta || Number(inserted.meta.changes) < 1) {

      return json({ ok: true, duplicate: true });
    }

    await db.batch([
      db.prepare(`DELETE FROM user_usage_mb
        WHERE user_id = ? AND session_id NOT IN (
          SELECT session_id FROM user_usage_mb
          WHERE user_id = ? ORDER BY ended_at DESC LIMIT 200
        )`).bind(identity.userId, identity.userId),
    ]);

    return json({ ok: true });
  }

  return json({ error: "account_route_not_found" }, 404);
}

async function handleSpeedShareSubmission(request, env) {
  const body = await request.json();
  const reference = body && (body.uid || body.accountId);
  if (!Array.isArray(body && body.results) || body.results.length === 0 ||
      body.results.length > MAX_SPEED_SHARE_RESULTS) {
    return json({ ok: false, error: "invalid_speed_share_results" }, 400);
  }

  const identity = await resolveUserIdentity(env, reference);
  if (!identity) {
    return json({ ok: false, error: "account_not_found" }, 404);
  }
  const user = await readD1User(env, identity.userId);
  if (!user) {
    return json({ ok: false, error: "account_not_found" }, 404);
  }
  if (user.level === 0) {
    return json({ ok: false, error: "account_banned" }, 403);
  }

  let results;
  try {
    results = body.results.map(normalizeSpeedShareResult).filter(Boolean);
  } catch (error) {
    return json({
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    }, 400);
  }
  if (results.length === 0) {
    return json({ ok: false, error: "speed_share_results_empty" }, 400);
  }

  const state = await readState(env);
  const db = await ensureUserSchema(env);
  const now = new Date();
  const reportDate = usagePeriodKeys(now).day;
  const reportedAt = beijingTimestamp(now);
  const reward = normalizeExperience(
    state.settings.speedShareSmartExperience,
    DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  );
  const daily = await db.prepare(
    `INSERT OR IGNORE INTO speed_share_daily_mb
     (user_id, report_date, reported_at) VALUES (?, ?, ?)`,
  ).bind(identity.userId, reportDate, reportedAt).run();
  if (!daily || !daily.meta || Number(daily.meta.changes) < 1) {
    return json({
      ok: false,
      error: "speed_share_already_reported_today",
      speedShare: await readSpeedShareStatus(env, identity.userId, state.settings),
    });
  }

  const statements = results.map((item) => db.prepare(
    `INSERT INTO speed_share_results_mb
     (node_id, node_name, subscription_group_name, tcping_latency_ms,
      url_test_latency_ms, download_mbps, upload_mbps, updated_at,
      last_reported_at, report_count)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
     ON CONFLICT(node_id) DO UPDATE SET
       node_name = CASE WHEN excluded.node_name <> ''
         THEN excluded.node_name ELSE speed_share_results_mb.node_name END,
       subscription_group_name = CASE WHEN excluded.subscription_group_name <> ''
         THEN excluded.subscription_group_name
         ELSE speed_share_results_mb.subscription_group_name END,
       tcping_latency_ms = CASE
         WHEN excluded.tcping_latency_ms > 0 AND
           (speed_share_results_mb.tcping_latency_ms <= 0 OR
            excluded.tcping_latency_ms < speed_share_results_mb.tcping_latency_ms)
         THEN excluded.tcping_latency_ms
         ELSE speed_share_results_mb.tcping_latency_ms END,
       url_test_latency_ms = CASE
         WHEN excluded.url_test_latency_ms > 0 AND
           (speed_share_results_mb.url_test_latency_ms <= 0 OR
            excluded.url_test_latency_ms < speed_share_results_mb.url_test_latency_ms)
         THEN excluded.url_test_latency_ms
         ELSE speed_share_results_mb.url_test_latency_ms END,
       download_mbps = CASE
         WHEN excluded.download_mbps > speed_share_results_mb.download_mbps
         THEN excluded.download_mbps
         ELSE speed_share_results_mb.download_mbps END,
       upload_mbps = CASE
         WHEN excluded.upload_mbps > speed_share_results_mb.upload_mbps
         THEN excluded.upload_mbps
         ELSE speed_share_results_mb.upload_mbps END,
       updated_at = CASE WHEN
         (excluded.tcping_latency_ms > 0 AND
           (speed_share_results_mb.tcping_latency_ms <= 0 OR
            excluded.tcping_latency_ms < speed_share_results_mb.tcping_latency_ms)) OR
         (excluded.url_test_latency_ms > 0 AND
           (speed_share_results_mb.url_test_latency_ms <= 0 OR
            excluded.url_test_latency_ms < speed_share_results_mb.url_test_latency_ms)) OR
         excluded.download_mbps > speed_share_results_mb.download_mbps OR
         excluded.upload_mbps > speed_share_results_mb.upload_mbps
         THEN excluded.updated_at ELSE speed_share_results_mb.updated_at END,
       last_reported_at = excluded.last_reported_at,
       report_count = speed_share_results_mb.report_count + 1`,
  ).bind(
    item.nodeId,
    item.nodeName,
    item.subscriptionGroupName,
    item.tcpingLatencyMs,
    item.urlTestLatencyMs,
    item.downloadMbps,
    item.uploadMbps,
    reportedAt,
    reportedAt,
  ));
  for (let index = 0; index < statements.length; index += 100) {
    await db.batch(statements.slice(index, index + 100));
  }

  const progression = applyExperience(
    user.level,
    user.experience,
    reward,
    state.settings.experienceThresholds,
  );
  await db.prepare(
    "UPDATE users_mb SET experience = ?, level = ? WHERE id = ?",
  ).bind(progression.experience, progression.level, identity.userId).run();
  const updated = await readD1User(env, identity.userId);

  return json({
    ok: true,
    acceptedResults: results.length,
    account: publicAccount(updated),
    experienceAwarded: reward,
    speedShare: await readSpeedShareStatus(env, identity.userId, state.settings),
  });
}

function normalizeSpeedShareResult(value) {
  if (!plainObject(value)) throw new Error("invalid_speed_share_result");
  const nodeId = cleanText(value.nodeId || value.id).slice(0, 128);
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/.test(nodeId)) {
    throw new Error("invalid_speed_share_node_id");
  }
  const result = {
    nodeId,
    nodeName: cleanText(value.nodeName || value.name).slice(0, 200),
    subscriptionGroupName: cleanText(
      value.subscriptionGroupName || value.groupName,
    ).slice(0, 200),
    tcpingLatencyMs: sharedLatency(value.tcpingLatencyMs ?? value.tcpingLatency),
    urlTestLatencyMs: sharedLatency(value.urlTestLatencyMs ?? value.urlTestLatency),
    downloadMbps: sharedMbps(value.downloadMbps ?? value.download),
    uploadMbps: sharedMbps(value.uploadMbps ?? value.upload),
  };
  return result.tcpingLatencyMs > 0 || result.urlTestLatencyMs > 0 ||
    result.downloadMbps > 0 || result.uploadMbps > 0
    ? result
    : null;
}

function sharedLatency(value) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 && number <= 600000 ? number : 0;
}

function sharedMbps(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 && number <= 1000000
    ? Math.round(number * 1000) / 1000
    : 0;
}

async function handleDebugUserV2(request, env) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ ok: false, error: "invalid_json" }, 400);
  }
  if (!plainObject(body)) {
    return json({ ok: false, error: "debug_user_body_must_be_object" }, 400);
  }

  const db = await ensureUserSchema(env);
  const requestedUid = normalizeUid(body.uid) || normalizeUid(body.id);
  const requestedDeviceId = normalizeAccountId(body.deviceId) ||
    normalizeAccountId(body.id) || normalizeAccountId(body.accountId);
  let identity = requestedUid
    ? await resolveUserIdentity(env, requestedUid)
    : requestedDeviceId
      ? await resolveUserIdentity(env, requestedDeviceId)
      : null;
  let created = false;
  const deviceId = requestedDeviceId || randomDebugAccountId();

  if (!identity) {
    const uid = requestedUid || randomPublicUid();
    const createdAt = debugTimestamp(body.createdAt, beijingTimestamp(), "createdAt");
    const nickname = normalizeNickname(
      debugText(body.nickname, defaultNickname(uid), 24, "nickname"),
      defaultNickname(uid),
    );
    const level = debugInteger(body.level, 1, 0, 6, "level");
    await db.prepare(
      "INSERT INTO users_mb (uid, nickname, level, created_at) VALUES (?, ?, ?, ?)",
    ).bind(uid, nickname, level, createdAt).run();
    const user = await db.prepare("SELECT id, uid FROM users_mb WHERE uid = ?").bind(uid).first();
    if (!user) return json({ ok: false, error: "debug_user_create_failed" }, 500);
    await db.prepare(
      "INSERT INTO user_devices_mb " +
      "(user_id, device_id, brand, model, android_version, android_sdk, app_version) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).bind(
      Number(user.id),
      deviceId,
      debugText(body.brand, "", 80, "brand"),
      debugText(body.model, "", 120, "model"),
      debugText(body.androidVersion, "", 80, "androidVersion"),
      debugAndroidSdk(body.androidSdk, 0, "androidSdk"),
      debugText(body.appVersion, "", 40, "appVersion"),
    ).run();
    identity = { userId: Number(user.id), uid: normalizeUid(user.uid) };
    created = true;
  } else {
    const current = await readD1User(env, identity.userId);
    const nickname = normalizeNickname(
      debugText(body.nickname, current && current.nickname, 24, "nickname"),
      current ? current.nickname : defaultNickname(identity.uid),
    );
    const level = debugInteger(body.level, current ? current.level : 1, 0, 6, "level");
    const createdAt = debugTimestamp(
      body.createdAt,
      current && current.createdAt ? current.createdAt : beijingTimestamp(),
      "createdAt",
    );
    await db.prepare(
      "UPDATE users_mb SET nickname = ?, level = ?, created_at = ? WHERE id = ?",
    ).bind(nickname, level, createdAt, identity.userId).run();
    await db.prepare(
      "UPDATE user_devices_mb SET brand = ?, model = ?, android_version = ?, " +
      "android_sdk = ?, app_version = ? WHERE user_id = ?",
    ).bind(
      debugText(body.brand, current && current.brand, 80, "brand"),
      debugText(body.model, current && current.model, 120, "model"),
      debugText(body.androidVersion, current && current.androidVersion, 80, "androidVersion"),
      debugAndroidSdk(
        body.androidSdk,
        current && current.androidSdk,
        "androidSdk",
      ),
      debugText(body.appVersion, current && current.appVersion, 40, "appVersion"),
      identity.userId,
    ).run();
  }

  const now = new Date();
  const statements = [];
  if (parseFlag(body.replaceUsage, false)) {
    statements.push(db.prepare("DELETE FROM user_usage_mb WHERE user_id = ?").bind(identity.userId));
  }
  const traffic = debugTrafficHistory(body.traffic ?? body.trafficHistory, now);
  statements.push(...traffic.map((item) => debugTrafficSessionStatement(db, identity.userId, item)));
  if (statements.length > 0) await db.batch(statements);

  const { day, month } = usagePeriodKeys(now);
  const saved = await readDebugAdminUser(db, identity.userId, day, month);
  return json({ ok: true, created, replaced: !created, user: publicAdminUser(saved) });
}

async function readDebugAdminUser(db, userId, day, month) {
  return db.prepare(
    "SELECT u.*, d.brand AS device_brand, d.model AS device_model, " +
    "d.android_version AS device_android_version, d.android_sdk AS device_android_sdk, " +
    "d.app_version AS device_app_version, " +
    "COALESCE((SELECT SUM(upload_bytes) FROM user_usage_mb traffic " +
      "WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 10) = ?), 0) AS today_upload_bytes, " +
    "COALESCE((SELECT SUM(download_bytes) FROM user_usage_mb traffic " +
      "WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 10) = ?), 0) AS today_download_bytes, " +
    "CASE WHEN EXISTS (SELECT 1 FROM user_usage_mb traffic WHERE traffic.user_id = u.id " +
      "AND substr(traffic.started_at, 1, 10) = ?) THEN 1 ELSE 0 END AS active_today, " +
    "COALESCE((SELECT SUM(upload_bytes) FROM user_usage_mb traffic " +
      "WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 7) = ?), 0) AS month_upload_bytes, " +
    "COALESCE((SELECT SUM(download_bytes) FROM user_usage_mb traffic " +
      "WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 7) = ?), 0) AS month_download_bytes, " +
    "CASE WHEN EXISTS (SELECT 1 FROM user_usage_mb traffic WHERE traffic.user_id = u.id " +
      "AND substr(traffic.started_at, 1, 7) = ?) THEN 1 ELSE 0 END AS active_this_month, " +
    "(SELECT MAX(ended_at) FROM user_usage_mb traffic WHERE traffic.user_id = u.id) AS last_report_at, " +
    "COALESCE((SELECT SUM(upload_bytes) FROM user_usage_mb traffic WHERE traffic.user_id = u.id), 0) AS total_upload_bytes, " +
    "COALESCE((SELECT SUM(download_bytes) FROM user_usage_mb traffic WHERE traffic.user_id = u.id), 0) AS total_download_bytes " +
    "FROM users_mb u " +
    "LEFT JOIN user_devices_mb d ON d.id = (SELECT id FROM user_devices_mb WHERE user_id = u.id ORDER BY id DESC LIMIT 1) " +
    "WHERE u.id = ?",
  ).bind(day, day, day, month, month, month, userId).first();
}

function debugTrafficHistory(value, now) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > MAX_DEBUG_TRAFFIC_RECORDS) {
    throw new Error("invalid_debug_traffic");
  }

  const fallbackTimestamp = beijingTimestamp(now);
  value.forEach((item) => {
    if (!plainObject(item)) throw new Error("invalid_debug_traffic");
  });
  return value.map((item, index) => {
    const date = normalizeGrowthDateKey(item.date ?? item.day ?? item.periodKey);
    const today = usagePeriodKeys(now).day;
    const startedAt = normalizeReportTimestamp(
      item.startedAt ?? item.startAt ?? item.startTime ??
        (date ? `${date}T00:00:00+08:00` : undefined),
      fallbackTimestamp,
    );
    const endedAt = normalizeReportTimestamp(
      item.endedAt ?? item.endAt ?? item.endTime ??
        (date ? date === today ? fallbackTimestamp : `${date}T23:59:59+08:00` : undefined),
      fallbackTimestamp,
    );
    if (!startedAt || !endedAt || Date.parse(startedAt) > Date.parse(endedAt)) {
      throw new Error("invalid_debug_traffic_time");
    }
    if (Date.parse(endedAt) > now.getTime() + 60 * 1000) {
      throw new Error("invalid_debug_traffic_date");
    }
    return {
      sessionId: cleanText(item.sessionId) || `debug-${index + 1}-${startedAt}`,
      subscriptionGroupName: debugText(
        item.subscriptionGroupName ?? item.groupName ?? item.subscription,
        "",
        200,
        "subscriptionGroupName",
      ),
      startedAt,
      endedAt,
      uploadBytes: debugBytes(
        item.uploadBytes,
        item.uploadMb,
        "trafficUploadBytes",
      ),
      downloadBytes: debugBytes(
        item.downloadBytes,
        item.downloadMb,
        "trafficDownloadBytes",
      ),
    };
  });
}

function debugTrafficSessionStatement(db, userId, traffic) {
  return db.prepare(
    "INSERT OR IGNORE INTO user_usage_mb " +
    "(user_id, session_id, subscription_group_name, started_at, ended_at, upload_bytes, download_bytes) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?)",
  ).bind(
    userId,
    traffic.sessionId,
    traffic.subscriptionGroupName,
    traffic.startedAt,
    traffic.endedAt,
    traffic.uploadBytes,
    traffic.downloadBytes,
  );
}

function debugText(value, fallback, maxLength, field) {
  if (value === undefined) return fallback;
  if (value !== null && typeof value !== "string") {
    throw new Error(`invalid_debug_${field}`);
  }
  return cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, maxLength);
}

function debugInteger(value, fallback, minimum, maximum, field) {
  if (value === undefined) return fallback;
  const number = Number(value);
  if (!Number.isInteger(number) || number < minimum || number > maximum) {
    throw new Error(`invalid_debug_${field}`);
  }
  return number;
}

function debugMegabytes(value, fallback, field) {
  if (value === undefined) return fallback;
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0 || number > 1e9) {
    throw new Error(`invalid_debug_${field}`);
  }
  return Math.round(number * 1e6) / 1e6;
}

function debugBytes(value, legacyMegabytes, field) {
  if (value === undefined || value === null || value === "") {
    return megabytesToBytes(debugMegabytes(legacyMegabytes, 0, `${field}LegacyMb`));
  }
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0 || number > MAX_TRAFFIC_BYTES) {
    throw new Error(`invalid_debug_${field}`);
  }
  return Math.round(number);
}

function debugTimestamp(value, fallback, field) {
  if (value === undefined) return fallback;
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`invalid_debug_${field}`);
  }
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    throw new Error(`invalid_debug_${field}`);
  }
  return beijingTimestamp(date);
}

function debugNullableTimestamp(value, field) {
  if (value === null || value === "") return null;
  return debugTimestamp(value, null, field);
}

function randomDebugAccountId() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

function hasOwn(value, key) {
  return Object.prototype.hasOwnProperty.call(value, key);
}

async function handleAdmin(path, request, env) {
  if (path === "/api/users/growth" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const period = normalizeGrowthPeriod(requestUrl.searchParams.get("period"));
    const startDate = normalizeGrowthDateKey(requestUrl.searchParams.get("start"));
    const endDate = normalizeGrowthDateKey(requestUrl.searchParams.get("end"));
    if (startDate === null || endDate === null) {
      return json({ ok: false, error: "invalid_growth_date_range" }, 400);
    }
    if (startDate && endDate && startDate > endDate) {
      return json({ ok: false, error: "invalid_growth_date_order" }, 400);
    }
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      "SELECT created_at FROM users_mb WHERE created_at IS NOT NULL AND created_at <> '' ORDER BY created_at ASC",
    ).all();
    const points = buildUserGrowthPoints(
      query.results || [],
      period,
      new Date(),
      startDate,
      endDate,
    );

    return json({
      ok: true,
      period,
      startDate: startDate || null,
      endDate: endDate || null,
      points,
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/users/traffic" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const period = normalizeGrowthPeriod(requestUrl.searchParams.get("period"));
    const startDate = normalizeGrowthDateKey(requestUrl.searchParams.get("start"));
    const endDate = normalizeGrowthDateKey(requestUrl.searchParams.get("end"));
    if (startDate === null || endDate === null) {
      return json({ ok: false, error: "invalid_traffic_date_range" }, 400);
    }
    if (startDate && endDate && startDate > endDate) {
      return json({ ok: false, error: "invalid_traffic_date_order" }, 400);
    }

    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      `SELECT substr(started_at, 1, 10) AS date_key,
              SUM(upload_bytes) AS upload_bytes,
              SUM(download_bytes) AS download_bytes
       FROM user_usage_mb
       WHERE started_at IS NOT NULL
         AND started_at <> ''
         AND (? = '' OR substr(started_at, 1, 10) >= ?)
         AND (? = '' OR substr(started_at, 1, 10) <= ?)
       GROUP BY substr(started_at, 1, 10)
       ORDER BY substr(started_at, 1, 10) ASC`,
    ).bind(
      startDate || "",
      startDate || "",
      endDate || "",
      endDate || "",
    ).all();
    const points = buildUserTrafficPoints(
      query.results || [],
      period,
      new Date(),
      startDate,
      endDate,
    );

    return json({
      ok: true,
      period,
      startDate: startDate || null,
      endDate: endDate || null,
      points,
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/telegram-tasks" && request.method === "GET") {
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      `SELECT t.id, t.user_id, t.telegram_nickname, t.status, t.submitted_at,
              t.reviewed_at, t.experience_awarded, t.resubmit_allowed,
              u.uid, u.nickname, u.level, u.experience
       FROM telegram_task_submissions_mb t
       JOIN users_mb u ON u.id = t.user_id
       ORDER BY CASE
                  WHEN t.status = 'pending' THEN 0
                  WHEN t.status = 'rejected' AND t.resubmit_allowed = 1 THEN 1
                  ELSE 2
                END,
                t.submitted_at DESC`,
    ).all();
    return json({
      ok: true,
      tasks: (query.results || []).map(publicAdminTelegramTask),
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/speed-share-results" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const requestedLimit = Number(requestUrl.searchParams.get("limit"));
    const limit = Number.isInteger(requestedLimit)
      ? Math.min(Math.max(requestedLimit, 1), 1000)
      : 500;
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      "SELECT node_id, node_name, subscription_group_name, " +
        "tcping_latency_ms, url_test_latency_ms, download_mbps, " +
        "upload_mbps, updated_at, last_reported_at, report_count " +
        "FROM speed_share_results_mb ORDER BY updated_at DESC LIMIT ?",
    ).bind(limit).all();
    return json({
      ok: true,
      results: (query.results || []).map(publicAdminSpeedShareResult),
      generatedAt: beijingTimestamp(),
    });
  }

  const telegramTaskMatch = path.match(/^\/api\/telegram-tasks\/(\d+)$/);
  if (telegramTaskMatch && request.method === "PATCH") {
    const body = await request.json();
    const action = cleanText(body && (body.action || body.status)).toLowerCase();
    if (action !== "approve" && action !== "return" && action !== "reject") {
      return json({ ok: false, error: "invalid_telegram_task_action" }, 400);
    }
    try {
      const task = await reviewTelegramTask(env, Number(telegramTaskMatch[1]), action);
      return json({ ok: true, task });
    } catch (error) {
      return json({
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }, 409);
    }
  }

  if (path === "/api/users" && request.method === "GET") {
    const db = await ensureUserSchema(env);
    const { day, month } = usagePeriodKeys(new Date());
    const query = await db.prepare(`SELECT
      u.*,
      d.brand AS device_brand,
      d.model AS device_model,
      d.android_version AS device_android_version,
      d.android_sdk AS device_android_sdk,
      d.app_version AS device_app_version,
      COALESCE((SELECT SUM(upload_bytes) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 10) = ?), 0) AS today_upload_bytes,
      COALESCE((SELECT SUM(download_bytes) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 10) = ?), 0) AS today_download_bytes,
      CASE WHEN EXISTS (
        SELECT 1 FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 10) = ?
      ) THEN 1 ELSE 0 END AS active_today,
      COALESCE((SELECT SUM(upload_bytes) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 7) = ?), 0) AS month_upload_bytes,
      COALESCE((SELECT SUM(download_bytes) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 7) = ?), 0) AS month_download_bytes,
      CASE WHEN EXISTS (
        SELECT 1 FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id AND substr(traffic.started_at, 1, 7) = ?
      ) THEN 1 ELSE 0 END AS active_this_month,
      (SELECT MAX(ended_at) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id) AS last_report_at,
      COALESCE((SELECT SUM(upload_bytes) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id), 0) AS total_upload_bytes,
      COALESCE((SELECT SUM(download_bytes) FROM user_usage_mb traffic
        WHERE traffic.user_id = u.id), 0) AS total_download_bytes
      FROM users_mb u
      LEFT JOIN user_devices_mb d ON d.id = (
        SELECT id FROM user_devices_mb WHERE user_id = u.id ORDER BY id DESC LIMIT 1
      )
      ORDER BY u.created_at DESC`).bind(day, day, day, month, month, month).all();
    const publicUsers = (query.results || []).map(publicAdminUser);

    return json({
      ok: true,
      date: day,
      month,
      users: publicUsers,
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/users/batch" && request.method === "PATCH") {
    const body = await request.json();
    const accountIds = normalizeBatchUserReferences(body && (body.uids || body.accountIds));
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
    const identities = (await Promise.all(
      accountIds.map((reference) => resolveUserIdentity(env, reference)),
    )).filter(Boolean);
    const timestamp = beijingTimestamp();
    const result = await db.batch(identities.map((identity) => db.prepare(
      "UPDATE users_mb SET level = ? WHERE id = ?",
    ).bind(level, identity.userId)));
    const changed = result.reduce(
      (total, item) => total + Number(item && item.meta && item.meta.changes || 0),
      0,
    );
    return json({ ok: true, accountIds, level, changed });
  }

  if (path === "/api/users/batch" && request.method === "DELETE") {
    const body = await request.json();
    const accountIds = normalizeBatchUserReferences(body && (body.uids || body.accountIds));
    if (accountIds.length === 0 || accountIds.length > MAX_BATCH_USER_COUNT) {
      return json({ ok: false, error: "invalid_batch_user_delete" }, 400);
    }

    const db = await ensureUserSchema(env);
    const identities = (await Promise.all(
      accountIds.map((reference) => resolveUserIdentity(env, reference)),
    )).filter(Boolean);
    const statements = [];
    for (const identity of identities) {
      statements.push(
        db.prepare("DELETE FROM user_usage_mb WHERE user_id = ?").bind(identity.userId),
        db.prepare("DELETE FROM telegram_task_submissions_mb WHERE user_id = ?").bind(identity.userId),
        db.prepare("DELETE FROM daily_checkins_mb WHERE user_id = ?").bind(identity.userId),
        db.prepare("DELETE FROM speed_share_daily_mb WHERE user_id = ?").bind(identity.userId),
        db.prepare("DELETE FROM users_mb WHERE id = ?").bind(identity.userId),
      );
    }
    const result = await db.batch(statements);
    const changed = identities.reduce(
      (total, _identity, index) => total + Number(
        result[index * 5 + 4] && result[index * 5 + 4].meta
          ? result[index * 5 + 4].meta.changes
          : 0,
      ),
      0,
    );
    return json({ ok: true, accountIds, changed });
  }

  const userMatch = path.match(/^\/api\/users\/([^/]+)$/);
  if (userMatch && request.method === "DELETE") {
    const identity = await resolveUserIdentity(env, decodeURIComponent(userMatch[1]));
    if (!identity) return json({ ok: false, error: "account_not_found" }, 404);
    const db = await ensureUserSchema(env);
    const result = await db.batch([
      db.prepare("DELETE FROM user_usage_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM telegram_task_submissions_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM daily_checkins_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM speed_share_daily_mb WHERE user_id = ?").bind(identity.userId),
      db.prepare("DELETE FROM users_mb WHERE id = ?").bind(identity.userId),
    ]);
    const deleteResult = result[result.length - 1];
    if (!deleteResult || !deleteResult.meta || Number(deleteResult.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({ ok: true, uid: identity.uid });
  }

  if (userMatch && request.method === "PATCH") {
    const identity = await resolveUserIdentity(env, decodeURIComponent(userMatch[1]));
    if (!identity) return json({ ok: false, error: "account_not_found" }, 404);
    const body = await request.json();
    const userAssignments = [];
    const userValues = [];
    const deviceAssignments = [];
    const deviceValues = [];
    const db = await ensureUserSchema(env);

    if (Object.prototype.hasOwnProperty.call(body || {}, "nickname")) {
      const nickname = normalizeNickname(body.nickname);
      if (!nickname) return json({ ok: false, error: "invalid_nickname" }, 400);
      userAssignments.push("nickname = ?");
      userValues.push(nickname);
    }
    if (Object.prototype.hasOwnProperty.call(body || {}, "level")) {
      const level = Number(body.level);
      if (!Number.isInteger(level) || level < 0 || level > 6) {
        return json({ ok: false, error: "invalid_level" }, 400);
      }
      userAssignments.push("level = ?");
      userValues.push(level);
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
      deviceAssignments.push(`${column} = ?`);
      deviceValues.push(fieldValue);
    }
    if (userAssignments.length === 0 && deviceAssignments.length === 0) {
      return json({ ok: false, error: "no_user_changes" }, 400);
    }
    const statements = [];
    if (userAssignments.length > 0) {
      statements.push(db.prepare(
        `UPDATE users_mb SET ${userAssignments.join(", ")} WHERE id = ?`,
      ).bind(...userValues, identity.userId));
    }
    if (deviceAssignments.length > 0) {
      statements.push(db.prepare(
        `UPDATE user_devices_mb SET ${deviceAssignments.join(", ")} WHERE user_id = ?`,
      ).bind(...deviceValues, identity.userId));
    }
    const updateResult = await db.batch(statements);
    const changed = updateResult.reduce(
      (total, item) => total + Number(item && item.meta && item.meta.changes || 0),
      0,
    );
    if (changed < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({ ok: true, user: publicAccount(await readD1User(env, identity.userId)) });
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
    uid: user.uid,
    nickname: user.nickname,
    level: normalizeLevel(user.level),
    experience: normalizeExperience(user.experience),
    email: cleanText(user.email),
    brand: user.brand,
    model: user.model,
    androidVersion: user.androidVersion,
    androidSdk: normalizeAndroidSdk(user.androidSdk),
    appVersion: user.appVersion,
  };
}

async function readDailyCheckin(env, userId, settings) {
  const db = await ensureUserSchema(env);
  const checkinDate = usagePeriodKeys(new Date()).day;
  const row = await db.prepare(
    `SELECT checked_in_at, experience_awarded
     FROM daily_checkins_mb
     WHERE user_id = ? AND checkin_date = ?`,
  ).bind(userId, checkinDate).first();
  return {
    enabled: true,
    date: checkinDate,
    rewardExperience: normalizeExperience(
      settings && settings.dailyCheckinExperience,
      DEFAULT_DAILY_CHECKIN_EXPERIENCE,
    ),
    status: row ? "checked_in" : "not_checked_in",
    checkedInAt: cleanText(row && row.checked_in_at),
    experienceAwarded: normalizeExperience(row && row.experience_awarded),
  };
}

async function readSpeedShareStatus(env, userId, settings = {}) {
  const db = await ensureUserSchema(env);
  const reportDate = usagePeriodKeys(new Date()).day;
  const daily = await db.prepare(
    "SELECT reported_at FROM speed_share_daily_mb " +
      "WHERE user_id = ? AND report_date = ?",
  ).bind(userId, reportDate).first();
  const latest = await db.prepare(
    "SELECT MAX(last_reported_at) AS last_reported_at FROM speed_share_results_mb",
  ).first();
  const count = await db.prepare(
    "SELECT COUNT(*) AS retained_count FROM speed_share_results_mb",
  ).first();
  return {
    enabled: true,
    smartReportedToday: Boolean(daily),
    smartReportedAt: cleanText(daily && daily.reported_at),
    smartRewardExperience: normalizeExperience(
      settings && settings.speedShareSmartExperience,
      DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
    ),
    lastReportedAt: cleanText(latest && latest.last_reported_at),
    retainedResultCount: Number(count && count.retained_count) || 0,
  };
}

async function readTelegramTask(env, userId, settings) {
  const db = await ensureUserSchema(env);
  const row = await db.prepare(
    `SELECT telegram_nickname, status, submitted_at, reviewed_at,
            experience_awarded, resubmit_allowed
     FROM telegram_task_submissions_mb WHERE user_id = ?`,
  ).bind(userId).first();
  return {
    enabled: Boolean(settings && settings.telegramGroupUrl),
    groupUrl: cleanText(settings && settings.telegramGroupUrl),
    rewardExperience: normalizeExperience(
      settings && settings.telegramTaskExperience,
      DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    ),
    status: publicTelegramTaskStatus(row),
    telegramNickname: cleanText(row && row.telegram_nickname),
    submittedAt: cleanText(row && row.submitted_at),
    reviewedAt: cleanText(row && row.reviewed_at),
    experienceAwarded: normalizeExperience(row && row.experience_awarded),
  };
}

function publicAdminUser(row) {
  const todayUpload = bytesToMegabytes(row.today_upload_bytes);
  const todayDownload = bytesToMegabytes(row.today_download_bytes);
  const monthUpload = bytesToMegabytes(row.month_upload_bytes);
  const monthDownload = bytesToMegabytes(row.month_download_bytes);
  const totalUpload = bytesToMegabytes(row.total_upload_bytes);
  const totalDownload = bytesToMegabytes(row.total_download_bytes);
  return {
    ...publicAccount(rowToUser(row)),
    createdAt: cleanText(row.created_at),
    lastReportAt: cleanText(row.last_report_at) || null,
    totalUploadMb: totalUpload,
    totalDownloadMb: totalDownload,
    totalMb: totalUpload + totalDownload,
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

function publicAdminTelegramTask(row) {
  return {
    id: Number(row.id),
    uid: normalizeStoredUid(row.uid),
    nickname: cleanText(row.nickname),
    level: normalizeLevel(row.level),
    experience: normalizeExperience(row.experience),
    telegramNickname: cleanText(row.telegram_nickname),
    status: publicTelegramTaskStatus(row),
    submittedAt: cleanText(row.submitted_at),
    reviewedAt: cleanText(row.reviewed_at),
    experienceAwarded: normalizeExperience(row.experience_awarded),
  };
}

function publicAdminSpeedShareResult(row) {
  return {
    nodeId: cleanText(row.node_id),
    nodeName: cleanText(row.node_name),
    subscriptionGroupName: cleanText(row.subscription_group_name),
    tcpingLatencyMs: sharedLatency(row.tcping_latency_ms),
    urlTestLatencyMs: sharedLatency(row.url_test_latency_ms),
    downloadMbps: sharedMbps(row.download_mbps),
    uploadMbps: sharedMbps(row.upload_mbps),
    updatedAt: cleanText(row.updated_at),
    lastReportedAt: cleanText(row.last_reported_at),
    reportCount: Number(row.report_count) || 0,
  };
}

function publicTelegramTaskStatus(row) {
  const status = cleanText(row && row.status);
  return status === "rejected" && Number(row && row.resubmit_allowed) === 1
    ? "returned"
    : status || "not_submitted";
}

async function readTelegramTaskRow(env, taskId) {
  const db = await ensureUserSchema(env);
  return db.prepare(
    `SELECT t.id, t.user_id, t.telegram_nickname, t.status, t.submitted_at,
            t.reviewed_at, t.experience_awarded, t.resubmit_allowed,
            u.uid, u.nickname, u.level, u.experience
     FROM telegram_task_submissions_mb t
     JOIN users_mb u ON u.id = t.user_id
     WHERE t.id = ?`,
  ).bind(taskId).first();
}

async function reviewTelegramTask(env, taskId, action) {
  const db = await ensureUserSchema(env);
  const row = await readTelegramTaskRow(env, taskId);
  if (!row) throw new Error("telegram_task_not_found");
  const currentStatus = cleanText(row.status);
  const resubmitAllowed = Number(row.resubmit_allowed) === 1;

  // “驳回”允许用户修改后重新提交，“永久拒绝”则锁定该任务。
  if (currentStatus === "approved") {
    throw new Error("telegram_task_already_approved");
  }
  if (currentStatus === "rejected" && !resubmitAllowed) {
    throw new Error("telegram_task_permanently_rejected");
  }

  if (action === "return") {
    await db.prepare(
      "UPDATE telegram_task_submissions_mb SET status = 'rejected', reviewed_at = ?, " +
        "resubmit_allowed = 1 WHERE id = ?",
    ).bind(beijingTimestamp(), taskId).run();
    return publicAdminTelegramTask(await readTelegramTaskRow(env, taskId));
  }

  if (action === "reject") {
    await db.prepare(
      "UPDATE telegram_task_submissions_mb SET status = 'rejected', reviewed_at = ?, " +
        "resubmit_allowed = 0 WHERE id = ?",
    ).bind(beijingTimestamp(), taskId).run();
    return publicAdminTelegramTask(await readTelegramTaskRow(env, taskId));
  }

  if (currentStatus !== "pending") {
    throw new Error("telegram_task_not_pending");
  }

  const state = await readState(env);
  const reward = normalizeExperience(
    state.settings.telegramTaskExperience,
    DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  );
  const progression = applyExperience(
    normalizeLevel(row.level),
    normalizeExperience(row.experience),
    reward,
    state.settings.experienceThresholds,
  );
  const reviewedAt = beijingTimestamp();
  await db.batch([
    db.prepare(
      "UPDATE users_mb SET experience = ?, level = ? WHERE id = ?",
    ).bind(progression.experience, progression.level, Number(row.user_id)),
    db.prepare(
      "UPDATE telegram_task_submissions_mb SET status = 'approved', reviewed_at = ?, " +
        "experience_awarded = ?, resubmit_allowed = 0 WHERE id = ?",
    ).bind(reviewedAt, reward, taskId),
  ]);
  return publicAdminTelegramTask(await readTelegramTaskRow(env, taskId));
}

function usersDb(env) {
  if (!env.USERS_DB) throw new Error("Missing D1 binding USERS_DB");
  return env.USERS_DB;
}

async function ensureUserSchema(env) {
  const db = usersDb(env);
  if (userSchemaReady) return db;
  await db.batch(USER_SCHEMA_STATEMENTS.map((statement) => db.prepare(statement)));
  for (const statement of USER_SCHEMA_MIGRATIONS) {
    try {
      await db.prepare(statement).run();
    } catch (error) {
      if (!String(error && error.message || error).toLowerCase().includes("duplicate column name")) {
        throw error;
      }
    }
  }
  await db.batch([
    db.prepare("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_mb_email ON users_mb(email) WHERE email <> ''"),
  ]);
  userSchemaReady = true;
  return db;
}

function normalizeUserReference(value) {
  return normalizeUid(value) || normalizeAccountId(value);
}

async function resolveUserIdentity(env, reference) {
  const normalized = normalizeUserReference(reference);
  if (!normalized) return null;
  const db = await ensureUserSchema(env);
  let row = null;
  if (normalizeUid(normalized)) {
    row = await db.prepare("SELECT id AS user_id, uid FROM users_mb WHERE uid = ?")
      .bind(normalized).first();
  } else {
    row = await db.prepare(
      "SELECT u.id AS user_id, u.uid FROM users_mb u " +
      "JOIN user_devices_mb d ON d.user_id = u.id WHERE d.device_id = ?",
    ).bind(normalized).first();
  }
  return row
    ? { userId: Number(row.user_id), uid: normalizeStoredUid(row.uid) }
    : null;
}

async function resolveAccountAccess(env, reference) {
  const identity = await resolveUserIdentity(env, reference);
  if (!identity) return { level: 0, appVersion: "" };
  const db = await ensureUserSchema(env);
  const row = await db.prepare(
    "SELECT u.level, COALESCE(d.app_version, '') AS app_version " +
    "FROM users_mb u LEFT JOIN user_devices_mb d ON d.id = (" +
    "SELECT id FROM user_devices_mb WHERE user_id = u.id ORDER BY id DESC LIMIT 1" +
    ") WHERE u.id = ?",
  ).bind(identity.userId).first();
  return row
    ? {
      level: normalizeLevel(row.level, 0),
      appVersion: cleanText(row.app_version),
    }
    : { level: 0, appVersion: "" };
}

async function readD1User(env, reference) {
  const db = await ensureUserSchema(env);
  const identity = Number.isInteger(Number(reference)) && Number(reference) > 0
    ? { userId: Number(reference) }
    : await resolveUserIdentity(env, reference);
  if (!identity) return null;
  const row = await db.prepare(
    "SELECT u.*, d.brand AS device_brand, d.model AS device_model, " +
    "d.android_version AS device_android_version, d.android_sdk AS device_android_sdk, " +
    "d.app_version AS device_app_version " +
    "FROM users_mb u LEFT JOIN user_devices_mb d ON d.id = (" +
    "SELECT id FROM user_devices_mb WHERE user_id = u.id ORDER BY id DESC LIMIT 1" +
    ") WHERE u.id = ?",
  ).bind(identity.userId).first();
  return row ? rowToUser(row) : null;
}

function rowToUser(row) {
  const uid = normalizeStoredUid(row.uid);
  return {
    uid,
    nickname: normalizeNickname(row.nickname, defaultNickname(uid)),
    level: normalizeLevel(row.level),
    experience: normalizeExperience(row.experience),
    email: cleanText(row.email),
    brand: cleanText(row.device_brand ?? row.brand),
    model: cleanText(row.device_model ?? row.model),
    androidVersion: cleanText(row.device_android_version ?? row.android_version),
    androidSdk: normalizeAndroidSdk(row.device_android_sdk ?? row.android_sdk),
    appVersion: cleanText(row.device_app_version ?? row.app_version),
    createdAt: cleanText(row.created_at),
  };
}

async function attachDeviceToUser(db, userId, deviceId, profile) {
  const brand = cleanText(profile && profile.brand).slice(0, 80);
  const model = cleanText(profile && profile.model).slice(0, 120);
  const androidVersion = cleanText(profile && profile.androidVersion).slice(0, 80);
  const androidSdk = normalizeAndroidSdk(profile && profile.androidSdk);
  const appVersion = cleanText(profile && profile.appVersion).slice(0, 40);

  await db.prepare(
    "DELETE FROM user_devices_mb WHERE device_id = ? AND user_id <> ?",
  ).bind(deviceId, userId).run();
  const existing = await db.prepare(
    "SELECT id FROM user_devices_mb WHERE device_id = ? AND user_id = ?",
  ).bind(deviceId, userId).first();
  if (existing) {
    await db.prepare(
      "UPDATE user_devices_mb SET " +
        "brand = CASE WHEN ? <> '' THEN ? ELSE brand END, " +
        "model = CASE WHEN ? <> '' THEN ? ELSE model END, " +
        "android_version = CASE WHEN ? <> '' THEN ? ELSE android_version END, " +
        "android_sdk = CASE WHEN ? > 0 THEN ? ELSE android_sdk END, " +
        "app_version = CASE WHEN ? <> '' THEN ? ELSE app_version END " +
        "WHERE id = ?",
    ).bind(
      brand, brand, model, model, androidVersion, androidVersion,
      androidSdk, androidSdk, appVersion, appVersion, Number(existing.id),
    ).run();
    return;
  }

  await db.prepare(
    "INSERT INTO user_devices_mb " +
      "(user_id, device_id, brand, model, android_version, android_sdk, app_version) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?)",
  ).bind(
    userId,
    deviceId,
    brand,
    model,
    androidVersion,
    androidSdk,
    appVersion,
  ).run();
}

async function registerOrTouchUser(env, deviceId, profile, now) {
  const db = await ensureUserSchema(env);
  const brand = cleanText(profile.brand).slice(0, 80);
  const model = cleanText(profile.model).slice(0, 120);
  const androidVersion = cleanText(profile.androidVersion).slice(0, 80);
  const androidSdk = normalizeAndroidSdk(profile.androidSdk);
  const appVersion = cleanText(profile.appVersion).slice(0, 40);
  const existing = await db.prepare(
    "SELECT u.id AS user_id, u.uid FROM users_mb u " +
    "JOIN user_devices_mb d ON d.user_id = u.id WHERE d.device_id = ?",
  ).bind(deviceId).first();
  if (existing) {
    await db.prepare(
      "UPDATE user_devices_mb SET " +
      "brand = CASE WHEN ? <> '' THEN ? ELSE brand END, " +
      "model = CASE WHEN ? <> '' THEN ? ELSE model END, " +
      "android_version = CASE WHEN ? <> '' THEN ? ELSE android_version END, " +
      "android_sdk = CASE WHEN ? > 0 THEN ? ELSE android_sdk END, " +
      "app_version = CASE WHEN ? <> '' THEN ? ELSE app_version END " +
      "WHERE device_id = ?",
    ).bind(
      brand, brand, model, model, androidVersion, androidVersion,
      androidSdk, androidSdk, appVersion, appVersion, deviceId,
    ).run();
    return;
  }

  const uid = randomPublicUid();
  await db.prepare(
    "INSERT INTO users_mb (uid, nickname, level, created_at) VALUES (?, ?, 1, ?)",
  ).bind(uid, defaultNickname(uid), beijingTimestamp(now)).run();
  const user = await db.prepare("SELECT id FROM users_mb WHERE uid = ?").bind(uid).first();
  if (!user) throw new Error("account_create_failed");
  await db.prepare(
    "INSERT INTO user_devices_mb " +
    "(user_id, device_id, brand, model, android_version, android_sdk, app_version) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?)",
  ).bind(Number(user.id), deviceId, brand, model, androidVersion, androidSdk, appVersion).run();
}

function normalizeNickname(value, fallback = "") {
  const nickname = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 24);
  return nickname || fallback;
}

function normalizeLevel(value, fallback = 1) {
  const level = Number(value);
  return Number.isInteger(level) && level >= 0 && level <= 6 ? level : fallback;
}

function normalizeExperience(value, fallback = 0) {
  const experience = Number(value);
  if (Number.isInteger(experience) && experience >= 0 && experience <= MAX_ACCOUNT_EXPERIENCE) {
    return experience;
  }
  const fallbackExperience = Number(fallback);
  return Number.isInteger(fallbackExperience) && fallbackExperience >= 0
    ? Math.min(fallbackExperience, MAX_ACCOUNT_EXPERIENCE)
    : 0;
}

function normalizeExperienceThresholds(value) {
  const values = Array.isArray(value) ? value : [];
  return DEFAULT_EXPERIENCE_THRESHOLDS.map((fallback, index) => {
    const threshold = Number(values[index]);
    return Number.isInteger(threshold) && threshold > 0 && threshold <= MAX_ACCOUNT_EXPERIENCE
      ? threshold
      : fallback;
  });
}

function applyExperience(levelValue, experienceValue, gainedValue, thresholds) {
  let level = normalizeLevel(levelValue, 0);
  let experience = normalizeExperience(experienceValue);
  if (level === 0) return { level: 0, experience };

  experience = Math.min(
    experience + normalizeExperience(gainedValue),
    MAX_ACCOUNT_EXPERIENCE,
  );
  const normalizedThresholds = normalizeExperienceThresholds(thresholds);
  while (level < 6) {
    const required = normalizedThresholds[level - 1];
    if (experience < required) break;
    experience -= required;
    level += 1;
  }
  return { level, experience };
}

function normalizeAndroidSdk(value, fallback = 0) {
  const sdk = Number(value);
  if (Number.isInteger(sdk) && sdk >= 0 && sdk <= 1000) return sdk;
  const fallbackSdk = Number(fallback);
  return Number.isInteger(fallbackSdk) && fallbackSdk >= 0 && fallbackSdk <= 1000
    ? fallbackSdk
    : 0;
}

function debugAndroidSdk(value, fallback, field) {
  if (value === undefined || value === null || value === "") {
    return normalizeAndroidSdk(fallback);
  }
  return debugInteger(value, 0, 0, 1000, field);
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

function normalizeGrowthPeriod(value) {
  return value === "week" || value === "month" ? value : "day";
}

function normalizeGrowthDateKey(value) {
  const dateKey = cleanText(value);
  if (!dateKey) return "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) return null;
  const date = dayKeyToUtcDate(dateKey);
  return utcDateKey(date) === dateKey ? dateKey : null;
}

function buildUserGrowthPoints(
  rows,
  period,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const createdAt = new Date(cleanText(row && row.created_at));
    if (!Number.isFinite(createdAt.getTime())) return null;
    return {
      day: usagePeriodKeys(createdAt).day,
      key: userGrowthPeriodKey(createdAt, period),
    };
  }).filter(Boolean);

  if (records.length === 0) return [];
  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const points = [];
  const counts = new Map();
  let totalUsers = 0;
  records.forEach((record) => {
    if (record.day < startDay) totalUsers += 1;
    if (record.day >= startDay && record.day <= endDay) {
      counts.set(record.key, (counts.get(record.key) || 0) + 1);
    }
  });
  let key = firstKey;

  while (key <= lastKey) {
    const newUsers = counts.get(key) || 0;
    totalUsers += newUsers;
    points.push({ key, newUsers, totalUsers });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

function buildUserTrafficPoints(
  rows,
  period,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const day = normalizeGrowthDateKey(row && row.date_key);
    if (!day) return null;
    return {
      day,
      key: userGrowthPeriodKey(dayKeyToUtcDate(day), period),
      uploadBytes: boundedBytes(row && row.upload_bytes),
      downloadBytes: boundedBytes(row && row.download_bytes),
    };
  }).filter(Boolean);

  if (records.length === 0) return [];
  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const counts = new Map();
  records.forEach((record) => {
    if (record.day < startDay || record.day > endDay) return;
    const current = counts.get(record.key) || { uploadBytes: 0, downloadBytes: 0 };
    current.uploadBytes += record.uploadBytes;
    current.downloadBytes += record.downloadBytes;
    counts.set(record.key, current);
  });

  const points = [];
  let key = firstKey;
  while (key <= lastKey) {
    const current = counts.get(key) || { uploadBytes: 0, downloadBytes: 0 };
    const uploadMb = bytesToMegabytes(current.uploadBytes);
    const downloadMb = bytesToMegabytes(current.downloadBytes);
    points.push({
      key,
      uploadMb,
      downloadMb,
      totalMb: Math.round((uploadMb + downloadMb) * 1e6) / 1e6,
    });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

function userGrowthPeriodKey(date, period) {
  const { day, month } = usagePeriodKeys(date);
  if (period === "month") return month;
  if (period === "week") return mondayOfDayKey(day);
  return day;
}

function mondayOfDayKey(dayKey) {
  const date = dayKeyToUtcDate(dayKey);
  const daysSinceMonday = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - daysSinceMonday);
  return utcDateKey(date);
}

function nextUserGrowthPeriodKey(key, period) {
  if (period === "month") {
    const [year, month] = key.split("-").map(Number);
    const date = new Date(Date.UTC(year, month, 1));
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
  }
  const date = dayKeyToUtcDate(key);
  date.setUTCDate(date.getUTCDate() + (period === "week" ? 7 : 1));
  return utcDateKey(date);
}

function dayKeyToUtcDate(dayKey) {
  const [year, month, day] = dayKey.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day));
}

function utcDateKey(date) {
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
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

function normalizeReportTimestamp(value, fallback = "") {
  if (value === undefined || value === null || value === "") return fallback;
  const date = typeof value === "number"
    ? new Date(value)
    : new Date(String(value));
  return Number.isFinite(date.getTime()) ? beijingTimestamp(date) : null;
}

function normalizeAccountId(value) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

function normalizeAccountEmail(value) {
  const email = cleanText(value).toLowerCase();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
    ? email
    : "";
}

function normalizeAccountPassword(value) {
  if (typeof value !== "string") return "";
  return value.length >= 8 && value.length <= 128 ? value : "";
}

function normalizeTelegramNickname(value) {
  const nickname = cleanText(value).replace(/^@+/, "@");
  return nickname.length >= 1 && nickname.length <= 64 ? nickname : "";
}

async function hashAccountPassword(password) {
  const iterations = 100000;
  const salt = new Uint8Array(16);
  crypto.getRandomValues(salt);
  const key = await crypto.subtle.importKey(
    "raw",
    TEXT_ENCODER.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations,
      hash: "SHA-256",
    },
    key,
    256,
  );
  return `pbkdf2-sha256$${iterations}$${base64EncodeBytes(salt)}$${base64EncodeBytes(new Uint8Array(derived))}`;
}

async function verifyAccountPassword(password, storedHash) {
  const parts = String(storedHash || "").split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 10000 || iterations > 1000000) {
    return false;
  }

  let salt;
  let expected;
  try {
    salt = base64DecodeBytes(parts[2]);
    expected = base64DecodeBytes(parts[3]);
  } catch (_) {
    return false;
  }
  if (salt.length === 0 || expected.length === 0) return false;

  const key = await crypto.subtle.importKey(
    "raw",
    TEXT_ENCODER.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations, hash: "SHA-256" },
    key,
    expected.length * 8,
  );
  return constantTimeBytesEqual(new Uint8Array(derived), expected);
}

function base64EncodeBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64DecodeBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function constantTimeBytesEqual(left, right) {
  const length = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < length; index += 1) {
    difference |= (left[index] || 0) ^ (right[index] || 0);
  }
  return difference === 0;
}

function normalizeUid(value) {
  const uid = cleanText(value).toLowerCase();
  return /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(uid)
    ? uid
    : "";
}

function normalizeStoredUid(value) {
  return normalizeUid(value) || normalizeAccountId(value);
}

function randomPublicUid() {
  return crypto.randomUUID().toLowerCase();
}

function normalizeBatchUserReferences(value) {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.map(normalizeUserReference).filter(Boolean)));
}

function boundedMegabytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number * 1e6) / 1e6, 1e9)
    : 0;
}

function boundedBytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number), MAX_TRAFFIC_BYTES)
    : 0;
}

function megabytesToBytes(value) {
  return boundedBytes(boundedMegabytes(value) * 1024 * 1024);
}

function bytesToMegabytes(value) {
  return boundedMegabytes(boundedBytes(value) / (1024 * 1024));
}

function reportBytes(bytesValue, legacyMegabytesValue) {
  if (bytesValue !== undefined && bytesValue !== null && bytesValue !== "") {
    return boundedBytes(bytesValue);
  }
  return megabytesToBytes(legacyMegabytesValue);
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
        refreshOnAppOpen: parseFlag(
          item.refreshOnAppOpen ?? item.openAppImmediateUpdate ?? item.updateOnAppOpen,
          true,
        ),
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
      refreshOnAppOpen: item.refreshOnAppOpen,
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
  const startAt = normalizeNoticeTimestamp(value.startAt, "start_at");
  const endAt = normalizeNoticeTimestamp(value.endAt, "end_at");
  if (startAt && endAt && Date.parse(startAt) > Date.parse(endAt)) {
    throw new Error("invalid_notice_time_order");
  }
  return {
    hasNotice: parseFlag(value.hasNotice, false),
    title: cleanText(value.title),
    content: cleanText(value.content),
    noticeId: cleanText(value.noticeId),
    showOnce: parseFlag(value.showOnce, true),
    startAt,
    endAt,
  };
}

function normalizeNoticeTimestamp(value, field) {
  const text = cleanText(value);
  if (!text) return "";
  const localMinute = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(text);
  const localSecond = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(text);
  const candidate = localMinute
    ? `${text}:00+08:00`
    : localSecond
      ? `${text}+08:00`
      : text;
  const date = new Date(candidate);
  if (!Number.isFinite(date.getTime())) {
    throw new Error(`invalid_notice_${field}`);
  }
  return beijingTimestamp(date);
}

function visibleNotice(notice, now = Date.now()) {
  if (!notice || !notice.hasNotice) return null;
  const startAt = Date.parse(notice.startAt || "");
  const endAt = Date.parse(notice.endAt || "");
  if (Number.isFinite(startAt) && now < startAt) return null;
  if (Number.isFinite(endAt) && now > endAt) return null;
  return notice;
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
    experienceThresholds: normalizeExperienceThresholds(settings.experienceThresholds),
    dailyCheckinExperience: normalizeExperience(
      settings.dailyCheckinExperience,
      DEFAULT_DAILY_CHECKIN_EXPERIENCE,
    ),
    telegramGroupUrl: normalizeTelegramGroupUrl(settings.telegramGroupUrl),
    telegramTaskExperience: normalizeExperience(
      settings.telegramTaskExperience,
      DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    ),
    speedShareSmartExperience: normalizeExperience(
      settings.speedShareSmartExperience,
      DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
    ),
  };
}

function normalizeTelegramGroupUrl(value) {
  const url = cleanText(value).slice(0, 500);
  return /^(?:https?:\/\/|tg:\/\/)/i.test(url) ? url : "";
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
  const token = cleanText(env.ADMIN_TOKEN);
  const authorization = cleanText(request.headers.get("authorization"));
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
     .loading-state, .error-state { display: flex; min-height: 160px; align-items: center; justify-content: center; padding: 18px; color: #94a3b8; text-align: center; }
     .error-state { color: #fca5a5; }
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
     .dashboard-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
     .dashboard-heading h2 { margin-bottom: 6px; }
     .dashboard-heading p { margin: 0; }
     .dashboard-heading button { flex: 0 0 auto; margin: 0; }
     .growth-card { margin-bottom: 14px; padding: 18px; background: #1f2937; border: 1px solid #374151; border-radius: 14px; }
     .growth-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
     .growth-toolbar h3 { margin: 0 0 4px; color: #f8fafc; font-size: 16px; }
     .growth-toolbar small { display: block; }
     .growth-range-controls { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 8px; margin-top: 12px; }
     .growth-range-field { display: flex; flex-direction: column; gap: 4px; color: #aab4c5; font-size: 12px; }
     .growth-range-field input { width: 150px; padding: 8px 10px; }
     .growth-range-controls button { margin: 0; padding: 8px 12px; }
     .growth-period-switch { display: flex; flex-wrap: wrap; gap: 6px; }
     .growth-period-button { margin: 0; padding: 8px 12px; background: #374151; color: #cbd5e1; }
     .growth-period-button.active { background: #22c55e; color: #052e16; }
     .growth-chart-host { min-height: 360px; margin-top: 16px; overflow-x: auto; }
     .growth-chart { display: block; width: 100%; min-width: 680px; height: 330px; }
     .growth-summary { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 2px; color: #cbd5e1; font-size: 13px; }
     .growth-summary strong { color: #f8fafc; font-size: 16px; }
     .growth-legend { display: inline-flex; align-items: center; gap: 6px; }
     .growth-legend i { width: 18px; height: 3px; border-radius: 999px; background: #38bdf8; }
     .traffic-chart-host { min-height: 360px; margin-top: 16px; overflow-x: auto; }
     .traffic-chart { display: block; width: 100%; min-width: 680px; height: 330px; }
     .traffic-legend { display: inline-flex; align-items: center; gap: 6px; }
     .traffic-legend i { width: 18px; height: 3px; border-radius: 999px; }
     .traffic-upload i { background: #22c55e; }
     .traffic-download i { background: #a78bfa; }
     .traffic-total i { background: #f59e0b; }
     .growth-empty { min-height: 300px; border: 1px dashed #4b5563; border-radius: 12px; }
     .distribution-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
     .distribution-card { min-width: 0; padding: 18px; background: #1f2937; border: 1px solid #374151; border-radius: 14px; }
     .distribution-card h3 { margin: 0 0 4px; color: #f8fafc; font-size: 16px; }
     .distribution-card > small { display: block; min-height: 40px; }
     .distribution-list { display: grid; gap: 8px; margin-top: 16px; }
     .distribution-total { padding-bottom: 8px; color: #f8fafc; border-bottom: 1px solid #374151; font-size: 13px; }
     .distribution-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; min-width: 0; padding: 8px 0; color: #cbd5e1; font-size: 13px; }
     .distribution-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
     .distribution-value { color: #94a3b8; font-variant-numeric: tabular-nums; white-space: nowrap; }
     .distribution-empty { min-height: 120px; border: 1px dashed #4b5563; border-radius: 12px; }
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
    .source-row { border: 1px solid #4b5563; border-radius: 10px; padding: 14px; margin: 10px 0; }
    .source-section { padding: 12px; background: #172033; border: 1px solid #374151; border-radius: 8px; }
    .source-section + .source-section { margin-top: 12px; }
    .source-section-title { margin: 0 0 10px; color: #f8fafc; font-size: 14px; }
    .source-identity-grid { grid-template-columns: repeat(2, minmax(220px, 1fr)); }
    .source-permissions-grid { align-items: end; }
    .source-permissions-grid .check { min-height: 42px; margin: 0; padding: 0 10px; background: #111827; border: 1px solid #4b5563; border-radius: 8px; }
    .source-details-grid { margin-bottom: 12px; }
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
      .user-tables { display: grid; gap: 14px; }
      .user-table-card { min-width: 0; padding: 14px; background: #111827; border: 1px solid #374151; border-radius: 10px; }
      .user-table-card h3 { margin: 0 0 8px; color: #f8fafc; font-size: 16px; }
      .user-table-meta { margin-bottom: 10px; color: #94a3b8; font-size: 12px; }
      .user-table-scroll { max-height: 520px; overflow: auto; border: 1px solid #374151; border-radius: 8px; }
      table.user-data-table { width: max-content; min-width: 100%; border-collapse: collapse; font-size: 12px; }
      .user-data-table th, .user-data-table td { padding: 8px; text-align: left; vertical-align: top; border-right: 1px solid #374151; border-bottom: 1px solid #374151; white-space: pre-wrap; word-break: break-word; }
      .user-data-table th { position: sticky; top: 0; z-index: 1; color: #d1d5db; background: #273244; white-space: nowrap; }
      .user-data-table td { min-width: 110px; color: #e5e7eb; }
      .user-data-table td:first-child, .user-data-table th:first-child { min-width: 42px; color: #94a3b8; }
      .user-table-empty { padding: 18px; color: #94a3b8; border: 1px dashed #4b5563; border-radius: 8px; }
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
      .task-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
      .task-toolbar button { margin: 0; }
      .task-list { display: grid; gap: 12px; }
      .task-card { padding: 14px; background: #111827; border: 1px solid #374151; border-radius: 10px; }
      .task-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
      .task-card h3 { margin: 0 0 4px; color: #f8fafc; font-size: 15px; }
      .task-card-meta { color: #94a3b8; font-size: 12px; }
      .task-card dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 16px; margin: 12px 0 0; }
      .task-card dt { color: #94a3b8; font-size: 12px; }
      .task-card dd { margin: 2px 0 0; color: #e5e7eb; word-break: break-word; }
      .task-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
      .task-actions button { margin: 0; }
      .task-status { display: inline-block; padding: 4px 8px; border-radius: 999px; color: #d1fae5; background: #14532d; font-size: 12px; white-space: nowrap; }
      .task-status.pending { color: #fef3c7; background: #78350f; }
      .task-status.returned { color: #dbeafe; background: #1e3a8a; }
      .task-status.rejected { color: #fee2e2; background: #7f1d1d; }
      button:disabled { cursor: not-allowed; opacity: .5; }
     #status { position: sticky; top: 0; z-index: 20; box-sizing: border-box; color: #86efac; background: #111827; }
     #status:not(:empty) { min-height: 44px; padding: 11px 28px; border-bottom: 1px solid #374151; }
     .hint { margin-top: 4px; }
     @media (max-width: 1200px) {
       .distribution-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
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
       #status:not(:empty) { padding: 10px 18px; }
       .grid { grid-template-columns: 1fr; }
       .dashboard-heading { align-items: stretch; flex-direction: column; }
       .dashboard-heading button { align-self: flex-start; }
       .growth-toolbar { flex-direction: column; }
       .growth-range-controls { align-items: stretch; }
       .growth-range-field, .growth-range-field input { width: 100%; }
       .growth-period-switch { align-self: flex-start; }
       .distribution-grid { grid-template-columns: 1fr; }
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
         <button class="tab-button" role="tab" type="button" data-tab="data-dashboard">数据看板</button>
         <button class="tab-button" role="tab" type="button" data-tab="subscriptions">订阅线路</button>
         <button class="tab-button" role="tab" type="button" data-tab="telegram-tasks">TG任务审核</button>
          <button class="tab-button" role="tab" type="button" data-tab="app-settings">应用设置</button>
         <button class="tab-button" role="tab" type="button" data-tab="notice">公告</button>
         <button class="tab-button" role="tab" type="button" data-tab="app-update">版本更新</button>
       </nav>
       <button class="secondary sidebar-logout" type="button" onclick="logout()">退出登录</button>
     </aside>

     <div class="dashboard-workspace">
       <div id="status" role="status" aria-live="polite"></div>

   <section class="card tab-panel" role="tabpanel" data-panel="data-dashboard">
     <div class="dashboard-heading">
       <div>
         <h2>数据看板</h2>
         <p>基于当前已注册账户，查看设备厂商、Android 系统和 App 版本分布。</p>
       </div>
       <button class="secondary" type="button" onclick="loadUsers()">刷新数据</button>
     </div>
    <article class="growth-card">
      <div class="growth-toolbar">
        <div>
          <h3>用户增长</h3>
          <small>按日、按周、按月统计注册新增用户，折线显示累计用户数量。</small>
          <div class="growth-range-controls" aria-label="用户增长时间范围">
            <label class="growth-range-field">开始日期<input id="growthStartDate" type="date"></label>
            <label class="growth-range-field">结束日期<input id="growthEndDate" type="date"></label>
            <button class="secondary" type="button" onclick="applyGrowthRange()">应用范围</button>
            <button class="secondary" type="button" onclick="clearGrowthRange()">全部时间</button>
          </div>
        </div>
        <div class="growth-period-switch" role="group" aria-label="用户增长统计粒度">
          <button class="growth-period-button active" type="button" data-growth-period="day" onclick="setGrowthPeriod('day')">按日</button>
          <button class="growth-period-button" type="button" data-growth-period="week" onclick="setGrowthPeriod('week')">按周</button>
          <button class="growth-period-button" type="button" data-growth-period="month" onclick="setGrowthPeriod('month')">按月</button>
        </div>
      </div>
      <div id="userGrowthChart" class="growth-chart-host"><div class="loading-state">正在读取用户增长数据…</div></div>
      <div id="userGrowthSummary" class="growth-summary" aria-live="polite"></div>
    </article>
    <article class="growth-card">
      <div class="growth-toolbar">
        <div>
          <h3>流量消耗</h3>
          <small>上传和下载使用堆叠柱状图，总流量使用折线显示。</small>
          <div class="growth-range-controls" aria-label="流量统计时间范围">
            <label class="growth-range-field">开始日期<input id="trafficStartDate" type="date"></label>
            <label class="growth-range-field">结束日期<input id="trafficEndDate" type="date"></label>
            <button class="secondary" type="button" onclick="applyTrafficRange()">应用范围</button>
            <button class="secondary" type="button" onclick="clearTrafficRange()">全部时间</button>
          </div>
        </div>
        <div class="growth-period-switch" role="group" aria-label="流量统计粒度">
          <button class="growth-period-button active" type="button" data-traffic-period="day" onclick="setTrafficPeriod('day')">按日</button>
          <button class="growth-period-button" type="button" data-traffic-period="week" onclick="setTrafficPeriod('week')">按周</button>
          <button class="growth-period-button" type="button" data-traffic-period="month" onclick="setTrafficPeriod('month')">按月</button>
        </div>
      </div>
      <div id="trafficChart" class="traffic-chart-host"><div class="loading-state">正在读取流量数据…</div></div>
      <div id="trafficSummary" class="growth-summary" aria-live="polite"></div>
    </article>
     <div class="distribution-grid">
       <article class="distribution-card">
         <h3>设备厂商</h3>
         <small>相同厂商的大小写和常见子品牌会自动合并。</small>
         <div id="brandDistribution" class="distribution-list"><div class="loading-state">正在读取厂商数据…</div></div>
       </article>
       <article class="distribution-card">
         <h3>Android 系统版本</h3>
         <small>按账户最近一次登记的 Android 版本统计。</small>
         <div id="androidDistribution" class="distribution-list"><div class="loading-state">正在读取系统版本…</div></div>
       </article>
       <article class="distribution-card">
         <h3>App 软件版本</h3>
         <small>按账户最近一次登记的 FireflyVPN 版本统计。</small>
         <div id="appVersionDistribution" class="distribution-list"><div class="loading-state">正在读取软件版本…</div></div>
       </article>
     </div>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="subscriptions">
    <h2>订阅线路</h2>
    <div id="subscriptionRows"></div>
     <button class="secondary" onclick="addSource()">添加线路</button>
     <small class="hint">每条线路只能选择一种来源：外部接口或纯文本节点。外部接口可设置缓存时间；纯文本节点不使用缓存。还可以按账户等级和最低应用版本限制下发。</small>
     <button type="button" onclick="pushSection('subscriptions')">推送</button>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="telegram-tasks">
    <div class="task-toolbar">
      <div>
        <h2>TG 加群任务审核</h2>
        <p>客户端提交 TG 昵称后，在这里人工确认是否已加入群组。</p>
      </div>
      <button class="secondary" type="button" onclick="loadTelegramTasks()">刷新审核列表</button>
    </div>
    <div id="telegramTasks" class="task-list"><div class="loading-state">正在读取 TG 任务…</div></div>
  </section>

  <section class="card tab-panel" role="tabpanel" data-panel="app-settings">
    <h2>应用基础设置</h2>
    <div class="grid">
      <div class="field"><label>官方网站<input id="websiteUrl" type="url" placeholder="https://example.com"></label></div>
      <div class="field"><label>反馈邮箱<input id="feedbackEmail" type="email" placeholder="support@example.com"></label></div>
      <div class="field"><label>问题反馈链接<input id="feedbackUrl" type="url" placeholder="https://github.com/.../issues"></label></div>
       <div class="field"><label>项目源码链接<input id="githubUrl" type="url" placeholder="https://github.com/.../.../"></label></div>
       <div class="field"><label>后台请求超时（毫秒）<input id="nodeRequestTimeoutMs" type="number" min="5000" max="60000" step="1000"></label></div>
       <div class="field"><label>每日签到经验奖励<input id="dailyCheckinExperience" type="number" min="0" step="1"></label></div>
       <div class="field"><label>TG 群连接<input id="telegramGroupUrl" type="text" placeholder="https://t.me/your_group"></label></div>
       <div class="field"><label>TG 任务经验奖励<input id="telegramTaskExperience" type="number" min="0" step="1"></label></div>
       <div class="field"><label>智能测速共享经验奖励<input id="speedShareSmartExperience" type="number" min="0" step="1"></label></div>
      <div class="field"><label>Lv1 → Lv2 所需经验<input id="experienceThresholdLv2" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv2 → Lv3 所需经验<input id="experienceThresholdLv3" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv3 → Lv4 所需经验<input id="experienceThresholdLv4" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv4 → Lv5 所需经验<input id="experienceThresholdLv5" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv5 → Lv6 所需经验<input id="experienceThresholdLv6" type="number" min="1" step="1"></label></div>
     </div>
     <small class="hint">绑定邮箱可获得经验，测速共享成功后也会发放经验；达到对应门槛后自动升级。Lv0 不会获得经验。经验门槛是每个等级区间所需的经验，超出经验会保留到下一级。</small>
     <button type="button" onclick="pushSection('app-settings')">推送</button>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="notice">
    <h2>公告</h2>
    <label class="check"><input id="noticeEnabled" type="checkbox">显示公告</label>
    <div class="grid">
      <div class="field"><label>公告标题<input id="noticeTitle" type="text"></label></div>
      <div class="field"><label>公告 ID<input id="noticeId" type="text" placeholder="notice_20260806"></label></div>
      <div class="field"><label>开始显示时间<input id="noticeStartAt" type="datetime-local"></label></div>
      <div class="field"><label>结束显示时间<input id="noticeEndAt" type="datetime-local"></label></div>
    </div>
    <div class="field"><label>公告内容<textarea id="noticeContent" placeholder="支持普通文本和 HTML"></textarea></label></div>
    <label class="check"><input id="noticeShowOnce" type="checkbox">每个公告 ID 只显示一次</label>
    <small class="hint">时间按北京时间执行。开始时间留空表示立即显示，结束时间留空表示永不过期。</small>
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
   </div>
   </div>
 </main>
<script>
  let currentState = null;
  let adminToken = '';
  let currentUsersData = null;
  let growthPeriod = 'day';
 let growthRequestId = 0;
 let trafficPeriod = 'day';
 let trafficRequestId = 0;
 const dirtyUserIds = new Set();
 const selectedUserIds = new Set();
 let savingAllUsers = false;
 let userSearchMatches = [];
 let userSearchIndex = -1;
 let userSortKey = '';
 let userSortDirection = 'desc';
 const USER_COLUMN_WIDTHS_KEY = 'firefly:user-sheet-column-widths:v1';
 const USER_SHEET_COLUMNS = [
   {key: 'select', label: '选择', width: 48, min: 44},
   {key: 'accountId', label: '用户 UID', width: 150, min: 110},
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
 function headers() { return {'content-type':'application/json','authorization':'Bearer ' + adminToken}; }
 function show(message, error) { statusEl.textContent = message; statusEl.style.color = error ? '#fca5a5' : '#86efac'; }
 function showLoginError(message) { loginStatusEl.textContent = message || ''; }
 function parseResponseJson(responseText) {
   try { return JSON.parse(responseText); } catch (_) { return {}; }
 }
 function adminApiUrl(path) {
   return new URL(ADMIN_API_BASE + path, window.location.origin).toString();
 }
 function adminErrorMessage(error, fallback) {
    if (error === 'invalid_batch_user_update') return '批量修改参数无效，最多选择 100 个用户，等级范围为 Lv0～Lv6';
     if (error === 'invalid_batch_user_delete') return '批量删除参数无效，最多选择 100 个用户';
     if (error === 'telegram_task_not_found') return 'TG 任务不存在';
     if (error === 'telegram_task_permanently_rejected') return '该 TG 任务已被永久拒绝，不能再次提交';
     if (error === 'telegram_task_not_pending') return '该 TG 任务当前不在待审核状态';
     if (error === 'telegram_task_already_approved') return '该 TG 任务已经通过，不能重复处理';
     if (error === 'invalid_telegram_task_action') return 'TG 任务处理动作无效';
    if (error === 'subscription_must_be_plaintext_nodes') return '订阅必须返回可识别的明文节点，不能使用已加密接口';
    if (error === 'invalid_min_app_version') return '最低应用版本格式无效，请填写类似 2.40 的数字版本';
   if (error === 'external_subscription_unreachable') return '外部明文订阅接口无法访问';
   if (error === 'invalid_notice_start_at') return '公告开始时间格式无效';
   if (error === 'invalid_notice_end_at') return '公告结束时间格式无效';
   if (error === 'invalid_notice_time_order') return '公告结束时间不能早于开始时间';
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
     const response = await fetch(adminApiUrl('/state'), {
       method: 'GET',
       headers: headers(),
       cache: 'no-store'
     });
     const responseText = await response.text();
     const data = parseResponseJson(responseText);
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
       if (response.status === 404) {
         return showLoginError('后台接口路径不存在，请确认使用当前管理地址');
       }
       if (response.status >= 500) {
         return showLoginError('后台配置读取失败，请检查 CONFIG 绑定和 Worker 日志');
       }
       return showLoginError('密码错误或后台地址无效（HTTP ' + response.status + '）');
     }
     currentState = data;
     // 认证成功后立即进入后台；用户列表、图表等模块失败不应阻止登录。
     loginGate.hidden = true;
     el('dashboard').hidden = false;
     try {
       fillState(data);
       activateTab('data-dashboard');
     } catch (error) {
       show('后台已登录，但页面初始化失败，请刷新页面', true);
       console.error('admin dashboard initialization failed', error);
     }
     const usersLoaded = await loadUsers();
     show(usersLoaded ? '登录成功' : '已进入后台，但用户数据读取失败', !usersLoaded);
     passwordInput.value = '';
     showLoginError('');
   } catch (error) {
     adminToken = '';
     showLoginError('无法连接后台，请检查网络或确认管理地址正确');
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
  button.onclick = function() {
    activateTab(button.dataset.tab);
    if (button.dataset.tab === 'telegram-tasks') loadTelegramTasks();
    window.scrollTo({top: 0, behavior: 'auto'});
  };
 });
 function telegramTaskStatusText(status) {
   if (status === 'pending') return '待审核';
   if (status === 'approved') return '已通过';
   if (status === 'returned') return '已驳回，可重新提交';
   if (status === 'rejected') return '已拒绝，不可再次提交';
   return '未提交';
 }
 async function loadTelegramTasks() {
   const host = el('telegramTasks');
   if (!host) return;
   host.innerHTML = '<div class="loading-state">正在读取 TG 任务…</div>';
   try {
     const response = await fetch(ADMIN_API_BASE + '/telegram-tasks', {headers: headers()});
     const responseText = await response.text();
     let data = {};
     try { data = JSON.parse(responseText); } catch (_) {}
     if (!response.ok) throw new Error(data.error || 'telegram_tasks_load_failed');
     const tasks = Array.isArray(data.tasks) ? data.tasks : [];
     if (tasks.length === 0) {
       host.innerHTML = '<div class="user-table-empty">暂无 TG 加群任务提交</div>';
       return;
     }
     host.innerHTML = tasks.map(function(task) {
       const status = String(task.status || '');
       const actions = status !== 'pending'
         ? ''
         : '<div class="task-actions">' +
           '<button type="button" onclick="reviewTelegramTask(' + Number(task.id) + ', \\'approve\\')">通过并发放经验</button>' +
           '<button class="secondary" type="button" onclick="reviewTelegramTask(' + Number(task.id) + ', \\'return\\')">驳回，可重新提交</button>' +
           '<button class="danger" type="button" onclick="reviewTelegramTask(' + Number(task.id) + ', \\'reject\\')">永久拒绝</button>' +
           '</div>';
       return '<article class="task-card">' +
         '<div class="task-card-header"><div><h3>' + esc(task.nickname || task.uid) + '</h3>' +
         '<div class="task-card-meta">UID：' + esc(task.uid) + ' · 提交时间：' + esc(task.submittedAt || '-') + '</div></div>' +
         '<span class="task-status ' + esc(status) + '">' + telegramTaskStatusText(status) + '</span></div>' +
         '<dl><div><dt>TG 昵称</dt><dd>' + esc(task.telegramNickname) + '</dd></div>' +
         '<div><dt>账户等级</dt><dd>Lv' + Number(task.level || 0) + '</dd></div>' +
         '<div><dt>已奖励经验</dt><dd>' + Number(task.experienceAwarded || 0) + '</dd></div></dl>' +
         (task.reviewedAt ? '<div class="task-card-meta">处理时间：' + esc(task.reviewedAt) + '</div>' : '') +
         actions + '</article>';
     }).join('');
   } catch (error) {
     host.innerHTML = '<div class="error-state">TG 任务读取失败</div>';
     show('TG 任务读取失败，请检查网络', true);
   }
 }
 async function reviewTelegramTask(taskId, action) {
   const message = action === 'approve'
     ? '确定已核实该用户加入 TG 群，并发放经验奖励吗？'
     : action === 'return'
       ? '确定驳回该 TG 加群任务，并允许用户修改后重新提交吗？'
       : '确定永久拒绝该 TG 加群任务吗？拒绝后用户无法再次提交。';
   if (!confirm(message)) return;
   try {
     const response = await fetch(ADMIN_API_BASE + '/telegram-tasks/' + encodeURIComponent(taskId), {
       method: 'PATCH',
       headers: headers(),
       body: JSON.stringify({action: action})
     });
     const responseText = await response.text();
     let data = {};
     try { data = JSON.parse(responseText); } catch (_) {}
     if (!response.ok) {
       show(adminErrorMessage(data.error, 'TG 任务处理失败（HTTP ' + response.status + '）'), true);
       return;
     }
     await loadTelegramTasks();
     show(action === 'approve'
       ? 'TG 任务已通过并发放经验'
       : action === 'return'
         ? 'TG 任务已驳回，用户可重新提交'
         : 'TG 任务已永久拒绝');
   } catch (error) {
     show('TG 任务处理失败，请检查网络', true);
   }
 }
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
    ? currentUsersData.users.find(function(item) { return item.uid === accountId; })
    : null;
  if (!user) return accountId;
  const field = function(name, fallback) {
    const control = row.querySelector('[data-user-field="' + name + '"]');
    return control ? control.value.trim() : fallback;
  };
  switch (columnKey) {
    case 'accountId': return user.uid;
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

  const dateColumns = ['createdAt', 'lastReportAt'];
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
// 统一常见厂商及其子品牌，避免同一厂商因大小写或品牌写法不同被拆分。
function normalizedBrand(value) {
  const raw = String(value == null ? '' : value).trim();
  if (!raw) return '未知厂商';
  const brand = raw.toLowerCase().replace(/[\\s_-]+/g, '');
  if (/^(oppo|oneplus|一加|realme|真我)/.test(brand)) return 'OPPO';
  if (/^(vivo|iqoo)/.test(brand)) return 'vivo';
  if (/^(xiaomi|redmi|poco|mi|小米)/.test(brand)) return '小米';
  if (/^(huawei|华为)/.test(brand)) return '华为';
  if (/^(honor|荣耀)/.test(brand)) return '荣耀';
  if (/^(samsung|三星)/.test(brand)) return '三星';
  if (/^(google|pixel)/.test(brand)) return 'Google';
  if (/^(motorola|moto|lenovo|联想)/.test(brand)) return '联想 / Motorola';
  if (/^(zte|nubia|红魔|中兴)/.test(brand)) return '中兴 / nubia';
  if (/^(meizu|魅族)/.test(brand)) return '魅族';
  if (/^(sony|索尼)/.test(brand)) return 'Sony';
  if (/^(asus|rog|华硕)/.test(brand)) return '华硕';
  if (/^(nothing)/.test(brand)) return 'Nothing';
  return raw;
}
function normalizedAndroidVersion(value) {
  const version = String(value == null ? '' : value).trim().replace(/^android\\s*/i, '');
  return version ? 'Android ' + version : '未知版本';
}
function normalizedAppVersion(value) {
  const version = String(value == null ? '' : value).trim().replace(/^v/i, '');
  return version ? 'v' + version : '未知版本';
}
function distributionForUsers(users, labelForUser) {
  const groups = new Map();
  users.forEach(function(user) {
    const label = labelForUser(user);
    const key = label.toLocaleLowerCase('zh-CN');
    const current = groups.get(key);
    if (current) current.count++;
    else groups.set(key, {label: label, count: 1});
  });
  return Array.from(groups.values()).sort(function(left, right) {
    return right.count - left.count || left.label.localeCompare(right.label, 'zh-CN', {
      numeric: true,
      sensitivity: 'base'
    });
  });
}
function growthPointLabel(key, period) {
  const parts = key.split('-').map(Number);
  if (period === 'month') return parts[0] + '年' + parts[1] + '月';
  return parts[1] + '月' + parts[2] + '日';
}
function renderGrowthChart(data) {
  const host = el('userGrowthChart');
  const summary = el('userGrowthSummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      return {
        key: String(point.key || ''),
        newUsers: Math.max(0, Number(point.newUsers) || 0),
        totalUsers: Math.max(0, Number(point.totalUsers) || 0)
      };
    }).filter(function(point) { return /^\\d{4}-\\d{2}(-\\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">暂无可统计的注册数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 54};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.totalUsers);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.totalUsers).toFixed(2);
  }).join(' ');
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#374151" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#94a3b8" font-size="12">' + value + '</text>';
  }).join('');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#94a3b8" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.totalUsers).toFixed(2) +
      '" r="4" fill="#38bdf8" stroke="#0f172a" stroke-width="2"><title>' +
      esc(label + '：累计 ' + point.totalUsers + '，新增 ' + point.newUsers) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="growth-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('用户增长折线图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + (padding.top + plotHeight) + '" x2="' +
    (width - padding.right) + '" y2="' + (padding.top + plotHeight) + '" stroke="#64748b" stroke-width="1" />' +
    '<path d="' + linePath + '" fill="none" stroke="#38bdf8" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const latest = points[points.length - 1];
    summary.innerHTML = '<span>当前累计 <strong>' + latest.totalUsers + '</strong> 个用户</span>' +
      '<span>最近' + (period === 'day' ? '一天' : period === 'week' ? '一周' : '一月') +
      '新增 <strong>' + latest.newUsers + '</strong> 个</span>' +
      '<span class="growth-legend"><i></i>累计用户</span>';
  }
}
function renderTrafficChart(data) {
  const host = el('trafficChart');
  const summary = el('trafficSummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      const uploadMb = Math.max(0, Number(point.uploadMb) || 0);
      const downloadMb = Math.max(0, Number(point.downloadMb) || 0);
      return {
        key: String(point.key || ''),
        uploadMb: uploadMb,
        downloadMb: downloadMb,
        totalMb: uploadMb + downloadMb
      };
    }).filter(function(point) { return /^\\d{4}-\\d{2}(-\\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">当前范围暂无流量数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 68};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.totalMb);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#374151" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#94a3b8" font-size="12">' + esc(formatMegabytes(value)) + '</text>';
  }).join('');
  const groupWidth = plotWidth / Math.max(points.length, 1);
  const barWidth = Math.max(8, Math.min(36, groupWidth * 0.58));
  const baseline = yFor(0);
  const bars = points.map(function(point, index) {
    const x = xFor(index) - barWidth / 2;
    const downloadY = yFor(point.downloadMb);
    const totalY = yFor(point.totalMb);
    const downloadHeight = Math.max(0, baseline - downloadY);
    const uploadHeight = Math.max(0, downloadY - totalY);
    const label = growthPointLabel(point.key, period);
    return '<rect x="' + x.toFixed(2) + '" y="' + downloadY.toFixed(2) +
      '" width="' + barWidth.toFixed(2) + '" height="' + downloadHeight.toFixed(2) +
      '" rx="2" fill="#a78bfa"><title>' + esc(label + '：下载 ' + formatMegabytes(point.downloadMb)) +
      '</title></rect>' +
      '<rect x="' + x.toFixed(2) + '" y="' + totalY.toFixed(2) +
      '" width="' + barWidth.toFixed(2) + '" height="' + uploadHeight.toFixed(2) +
      '" rx="2" fill="#22c55e"><title>' + esc(label + '：上传 ' + formatMegabytes(point.uploadMb)) +
      '</title></rect>';
  }).join('');
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.totalMb).toFixed(2);
  }).join(' ');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#94a3b8" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.totalMb).toFixed(2) +
      '" r="4" fill="#f59e0b" stroke="#0f172a" stroke-width="2"><title>' +
      esc(label + '：总流量 ' + formatMegabytes(point.totalMb)) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="traffic-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('流量消耗图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + baseline + '" x2="' + (width - padding.right) +
    '" y2="' + baseline + '" stroke="#64748b" stroke-width="1" />' +
    bars +
    '<path d="' + linePath + '" fill="none" stroke="#f59e0b" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const totalUpload = points.reduce(function(total, point) { return total + point.uploadMb; }, 0);
    const totalDownload = points.reduce(function(total, point) { return total + point.downloadMb; }, 0);
    summary.innerHTML = '<span>范围上传 <strong>' + formatMegabytes(totalUpload) + '</strong></span>' +
      '<span>范围下载 <strong>' + formatMegabytes(totalDownload) + '</strong></span>' +
      '<span>范围总流量 <strong>' + formatMegabytes(totalUpload + totalDownload) + '</strong></span>' +
      '<span class="traffic-legend traffic-upload"><i></i>上传</span>' +
      '<span class="traffic-legend traffic-download"><i></i>下载</span>' +
      '<span class="traffic-legend traffic-total"><i></i>总流量</span>';
  }
}
function growthTodayDateKey() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(new Date()).reduce(function(result, part) {
    if (part.type !== 'literal') result[part.type] = part.value;
    return result;
  }, {});
  return parts.year + '-' + parts.month + '-' + parts.day;
}
function initializeGrowthRange() {
  const today = growthTodayDateKey();
  ['growthStartDate', 'growthEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyGrowthRange() {
  const start = value('growthStartDate');
  const end = value('growthEndDate');
  if (start && end && start > end) {
    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadGrowthChart(growthPeriod);
}
function clearGrowthRange() {
  setValue('growthStartDate', '');
  setValue('growthEndDate', '');
  loadGrowthChart(growthPeriod);
}
function growthRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('growthStartDate');
  const end = value('growthEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function initializeTrafficRange() {
  const today = growthTodayDateKey();
  ['trafficStartDate', 'trafficEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyTrafficRange() {
  const start = value('trafficStartDate');
  const end = value('trafficEndDate');
  if (start && end && start > end) {
    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadTrafficChart(trafficPeriod);
}
function clearTrafficRange() {
  setValue('trafficStartDate', '');
  setValue('trafficEndDate', '');
  loadTrafficChart(trafficPeriod);
}
function trafficRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('trafficStartDate');
  const end = value('trafficEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function setTrafficPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  trafficPeriod = period;
  document.querySelectorAll('[data-traffic-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.trafficPeriod === period);
  });
  loadTrafficChart(period);
}
async function loadTrafficChart(period) {
  const host = el('trafficChart');
  const summary = el('trafficSummary');
  const requestId = ++trafficRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取流量数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/traffic?' + trafficRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('流量数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === trafficRequestId) renderTrafficChart(data);
  } catch (error) {
    if (requestId !== trafficRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">流量数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
function setGrowthPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  growthPeriod = period;
  document.querySelectorAll('[data-growth-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.growthPeriod === period);
  });
  loadGrowthChart(period);
}
async function loadGrowthChart(period) {
  const host = el('userGrowthChart');
  const summary = el('userGrowthSummary');
  const requestId = ++growthRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取用户增长数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/growth?' + growthRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('用户增长数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === growthRequestId) renderGrowthChart(data);
  } catch (error) {
    if (requestId !== growthRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">用户增长数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
 function setDashboardState(className, message) {
   ['brandDistribution', 'androidDistribution', 'appVersionDistribution', 'userGrowthChart', 'trafficChart'].forEach(function(id) {
    const host = el(id);
    if (host) host.innerHTML = '<div class="' + className + '">' + esc(message) + '</div>';
  });
  const summary = el('userGrowthSummary');
  if (summary) summary.textContent = '';
  const trafficSummary = el('trafficSummary');
  if (trafficSummary) trafficSummary.textContent = '';
}
 function renderDistribution(hostId, groups, total) {
   const host = el(hostId);
   if (!host) return;
   if (!total || groups.length === 0) {
     host.innerHTML = '<div class="loading-state distribution-empty">暂无可统计的账户数据</div>';
     return;
   }
   const rows = groups.map(function(group) {
     const percent = Math.round(group.count / total * 1000) / 10;
     return '<div class="distribution-row" title="' + esc(group.label) + '：' + group.count +
       ' 个账户，占 ' + percent + '%">' +
       '<span class="distribution-label">' + esc(group.label) + '</span>' +
       '<span class="distribution-value">' + group.count + ' 个 · ' + percent + '%</span></div>';
   }).join('');
   host.innerHTML = '<div class="distribution-total">共 ' + total + ' 个账户</div>' + rows;
 }
 function renderDashboard(data) {
   const users = data && Array.isArray(data.users) ? data.users : [];
   renderDistribution('brandDistribution', distributionForUsers(users, function(user) {
     return normalizedBrand(user.brand);
   }), users.length);
   renderDistribution('androidDistribution', distributionForUsers(users, function(user) {
     return normalizedAndroidVersion(user.androidVersion);
   }), users.length);
   renderDistribution('appVersionDistribution', distributionForUsers(users, function(user) {
     return normalizedAppVersion(user.appVersion);
   }), users.length);
 }
const USER_TABLE_DEFINITIONS = [
  {
    key: 'users_mb',
    title: 'users_mb（用户表）',
    columns: ['id', 'uid', 'nickname', 'level', 'experience', 'email', 'created_at'],
  },
  {
    key: 'user_devices_mb',
    title: 'user_devices_mb（设备表）',
    columns: ['id', 'user_id', 'device_id', 'brand', 'model', 'android_version', 'android_sdk', 'app_version'],
  },
  {
    key: 'user_usage_mb',
    title: 'user_usage_mb（流量统计表，Bytes）',
    columns: [
      'user_id',
      'session_id',
      'subscription_group_name',
      'started_at',
      'ended_at',
      'upload_bytes',
      'download_bytes',
    ],
  },
];
function rawUserTableValue(value) {
  if (value === null) return 'NULL';
  if (value === undefined) return '';
  return String(value);
}
function renderRawUserTable(definition, rows) {
  const values = Array.isArray(rows) ? rows : [];
  const head = '<tr><th>#</th>' + definition.columns.map(function(column) {
    return '<th>' + esc(column) + '</th>';
  }).join('') + '</tr>';
  const body = values.map(function(row, index) {
    return '<tr><td>' + (index + 1) + '</td>' + definition.columns.map(function(column) {
      return '<td>' + esc(rawUserTableValue(row && row[column])) + '</td>';
    }).join('') + '</tr>';
  }).join('');
  const content = values.length
    ? '<div class="user-table-scroll"><table class="user-data-table"><thead>' + head +
      '</thead><tbody>' + body + '</tbody></table></div>'
    : '<div class="user-table-empty">当前表暂无数据</div>';
  return '<article class="user-table-card"><h3>' + esc(definition.title) + '</h3>' +
    '<div class="user-table-meta">共 ' + values.length + ' 条记录</div>' + content + '</article>';
}
function renderUserTables(tables) {
  const host = el('usersTable');
  if (!host) return;
  const source = tables && typeof tables === 'object' ? tables : {};
  host.innerHTML = USER_TABLE_DEFINITIONS.map(function(definition) {
    return renderRawUserTable(definition, source[definition.key]);
  }).join('');
}
function renderUsers(data) {
  data = data || {users: []};
  currentUsersData = data;
  renderDashboard(data);
  return;
  dirtyUserIds.clear();
  selectedUserIds.clear();
  const batchLevel = el('batchLevel');
  if (batchLevel) batchLevel.value = '';
  const rows = (data.users || []).map(function(user) {
    return '<tr class="user-row" data-user-row="' + esc(user.uid) + '">' +
      '<td class="selection-cell"><input type="checkbox" data-user-select="' + esc(user.uid) +
        '" aria-label="选择用户"></td>' +
      '<td><code title="' + esc(user.uid) + '">' + esc(user.uid.slice(0, 16) + '…') + '</code></td>' +
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
      '<td class="readonly">' + esc(user.lastReportAt || '暂无') + '</td>' +
      '<td><button class="sheet-save" type="button" data-save-user="' + esc(user.uid) + '">保存</button>' +
        '<button class="sheet-delete danger" type="button" data-delete-user="' + esc(user.uid) + '">删除</button></td>' +
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
async function loadUsers(force) {
  setDashboardState('loading-state', '正在读取看板数据…');
  try {
    const response = await fetch(ADMIN_API_BASE + '/users', {headers: headers()});
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      setDashboardState('error-state', '数据看板读取失败');
      show(data.error || ('读取用户失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    renderUsers(data);
    loadGrowthChart(growthPeriod);
    loadTrafficChart(trafficPeriod);
    return true;
  } catch (error) {
    setDashboardState('error-state', '数据看板读取失败');
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
    return item.uid === accountId;
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
    '注册时间', '最后上报'
  ];
  const records = users.map(function(user) {
    return [
      user.uid,
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
  row.innerHTML = '<section class="source-section source-identity-section">' +
    '<div class="grid source-identity-grid">' +
      '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
      '<div class="field"><label>后台备注（仅后台保存）<input data-field="remark" type="text" value="' + esc(source.remark) + '" placeholder="例如：备用线路、长期稳定"></label></div>' +
    '</div>' +
    '</section>' +
    '<section class="source-section source-permissions-section">' +
      '<h3 class="source-section-title">线路权限控制</h3>' +
      '<div class="grid source-permissions-grid">' +
        '<div class="field"><label>最低账户等级<select data-field="minLevel">' + levelOptions(source.minLevel == null ? 1 : source.minLevel) + '</select></label></div>' +
        '<div class="field"><label>最低应用版本<input data-field="minAppVersion" type="text" value="' + esc(source.minAppVersion) + '" placeholder="例如 2.40（留空不限制）"></label></div>' +
        '<label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
      '</div>' +
    '</section>' +
    '<section class="source-section source-details-section">' +
      '<h3 class="source-section-title">订阅详细设定</h3>' +
      '<div class="grid source-details-grid">' +
        '<div class="field source-type-field"><label>订阅来源<select data-field="sourceType">' +
          '<option value="url" ' + (sourceType === 'url' ? 'selected' : '') + '>外部接口</option>' +
          '<option value="content" ' + (sourceType === 'content' ? 'selected' : '') + '>纯文本节点</option>' +
        '</select></label></div>' +
        '<label class="check"><input data-field="refreshOnAppOpen" type="checkbox" ' + (source.refreshOnAppOpen === false ? '' : 'checked') + '>打开app立即更新</label>' +
      '</div>' +
      '<div class="source-mode" data-source-mode="url"><div class="grid">' +
        '<div class="field"><label>外部明文订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/plain-sub"></label></div>' +
        '<div class="field"><label>接口缓存时间（分钟）<input data-field="cacheMinutes" type="number" min="0" max="10080" step="1" value="' + esc(source.cacheMinutes == null ? 30 : source.cacheMinutes) + '"></label></div>' +
      '</div><small>接口必须返回可识别的明文节点，由 Worker 中转并加密；缓存时间设为 0 会每次请求上游。</small></div>' +
      '<div class="source-mode" data-source-mode="content"><div class="field"><label>纯文本节点（每行一个）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div><small>保存后由 Worker 直接加密下发，不经过外部接口缓存。</small></div>' +
    '</section>' +
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
   setValue('dailyCheckinExperience', settings.dailyCheckinExperience == null ? 10 : settings.dailyCheckinExperience);
   setValue('telegramGroupUrl', settings.telegramGroupUrl);
   setValue('telegramTaskExperience', settings.telegramTaskExperience == null ? 50 : settings.telegramTaskExperience);
   setValue('speedShareSmartExperience', settings.speedShareSmartExperience == null ? 5 : settings.speedShareSmartExperience);
  const experienceThresholds = Array.isArray(settings.experienceThresholds)
    ? settings.experienceThresholds
    : [100, 200, 400, 800, 1600];
  ['experienceThresholdLv2', 'experienceThresholdLv3', 'experienceThresholdLv4',
    'experienceThresholdLv5', 'experienceThresholdLv6'].forEach(function(id, index) {
    setValue(id, experienceThresholds[index]);
  });
  const notice = currentState.notice || {};
  checked('noticeEnabled', Boolean(currentState.notice && notice.hasNotice !== false));
  setValue('noticeTitle', notice.title);
  setValue('noticeId', notice.noticeId);
  setValue('noticeStartAt', noticeDateTimeValue(notice.startAt));
  setValue('noticeEndAt', noticeDateTimeValue(notice.endAt));
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
function noticeDateTimeValue(data) {
  const text = String(data == null ? '' : data).trim();
  const match = text.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return match ? match[1] : '';
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
      refreshOnAppOpen: row.querySelector('[data-field="refreshOnAppOpen"]').checked,
      remark: row.querySelector('[data-field="remark"]').value.trim()
    };
  }).filter(function(item) { return item.url || item.content; });
  const notice = el('noticeEnabled').checked ? {
    hasNotice: true,
    title: value('noticeTitle'),
    content: value('noticeContent'),
    noticeId: value('noticeId'),
    showOnce: el('noticeShowOnce').checked,
    startAt: value('noticeStartAt'),
    endAt: value('noticeEndAt')
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
       nodeRequestTimeoutMs: Number(value('nodeRequestTimeoutMs') || 25000),
       dailyCheckinExperience: Number(value('dailyCheckinExperience') || 0),
       telegramGroupUrl: value('telegramGroupUrl'),
       telegramTaskExperience: Number(value('telegramTaskExperience') || 0),
       speedShareSmartExperience: Number(value('speedShareSmartExperience') || 0),
       experienceThresholds: [
        Number(value('experienceThresholdLv2') || 100),
        Number(value('experienceThresholdLv3') || 200),
        Number(value('experienceThresholdLv4') || 400),
        Number(value('experienceThresholdLv5') || 800),
        Number(value('experienceThresholdLv6') || 1600)
      ]
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
initializeGrowthRange();
initializeTrafficRange();
addSource();
</script>
</body>
</html>`;
}
