/*
 * Runtime configuration and D1 bootstrap schema.
 * Keep deployment-independent values here so route code stays focused on behavior.
 */

export const STATE_KEY = "fireflyvpn:state:v1";
export const CLIENT_IP_HEADER = "X-Firefly-Client-IP";
export const MAX_MANAGED_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;
export const MAX_BATCH_USER_COUNT = 100;
export const MAX_SPEED_SHARE_RESULTS = 1;
export const MAX_TRAFFIC_BYTES = 1e15;
export const MAX_ACCOUNT_EXPERIENCE = 1e12;
export const ACCOUNT_BINDING_EXPERIENCE = 50;
export const DEFAULT_INVITATION_EXPERIENCE = 50;
export const DEFAULT_TELEGRAM_TASK_EXPERIENCE = 50;
export const DEFAULT_SPEED_SHARE_SMART_EXPERIENCE = 5;
export const DEFAULT_EXPERIENCE_THRESHOLDS = [100, 200, 400, 800, 1600];
export const SUBSCRIPTION_CACHE_KEY_PREFIX = "fireflyvpn:subscription-cache:v1:";
export const DEFAULT_SUBSCRIPTION_CACHE_MINUTES = 30;
export const MAX_SUBSCRIPTION_CACHE_MINUTES = 7 * 24 * 60;
export const PLAINTEXT_NODE_PATTERN = /(?:^|\n)\s*(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+/i;
export const PRIVATE_B64_ALPHABET =
  "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX";
export const TEXT_ENCODER = new TextEncoder();
export const USER_SCHEMA_VERSION = 8;
export const USER_SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS app_schema_mb (
    component TEXT PRIMARY KEY,
    version INTEGER NOT NULL CHECK (version > 0),
    applied_at TEXT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS users_mb (
    id INTEGER PRIMARY KEY,
    uid TEXT NOT NULL UNIQUE,
    nickname TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active'
      CHECK (status IN ('active', 'banned')),
    experience_total INTEGER NOT NULL DEFAULT 0
      CHECK (experience_total BETWEEN 0 AND 1000000000000),
    invite_code TEXT NOT NULL UNIQUE,
    invited_by_user_id INTEGER,
    invited_at TEXT,
    email TEXT COLLATE NOCASE,
    password_hash TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    created_day TEXT GENERATED ALWAYS AS (substr(created_at, 1, 10)) VIRTUAL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_users_mb_created_at
    ON users_mb(created_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_users_mb_created_day
    ON users_mb(created_day)`,
  `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_mb_email
    ON users_mb(email)
    WHERE email IS NOT NULL AND email <> ''`,
  `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_mb_invite_code
    ON users_mb(invite_code)
    WHERE invite_code IS NOT NULL AND invite_code <> ''`,
  `CREATE INDEX IF NOT EXISTS idx_users_mb_invited_by
    ON users_mb(invited_by_user_id)`,
  `CREATE TABLE IF NOT EXISTS user_devices_mb (
    id INTEGER PRIMARY KEY,
    user_id INTEGER,
    device_id TEXT NOT NULL UNIQUE,
    brand TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    android_version TEXT NOT NULL DEFAULT '',
    android_sdk INTEGER NOT NULL DEFAULT 0
      CHECK (android_sdk BETWEEN 0 AND 1000),
    app_version TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_user_devices_mb_user_seen
    ON user_devices_mb(user_id, last_seen_at DESC, id DESC)`,
  `CREATE TABLE IF NOT EXISTS device_account_links_mb (
    id INTEGER PRIMARY KEY,
    device_row_id INTEGER NOT NULL,
    user_id INTEGER,
    user_uid TEXT NOT NULL,
    first_bound_at TEXT NOT NULL,
    last_bound_at TEXT NOT NULL,
    UNIQUE (device_row_id, user_uid),
    FOREIGN KEY (device_row_id) REFERENCES user_devices_mb(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_device_account_links_mb_user
    ON device_account_links_mb(user_id, device_row_id)
    WHERE user_id IS NOT NULL`,
  `CREATE TABLE IF NOT EXISTS device_bans_mb (
    device_row_id INTEGER NOT NULL,
    source_uid TEXT NOT NULL,
    reason TEXT NOT NULL,
    banned_at TEXT NOT NULL,
    lifted_at TEXT,
    PRIMARY KEY (device_row_id, source_uid, reason),
    FOREIGN KEY (device_row_id) REFERENCES user_devices_mb(id) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_device_bans_mb_active
    ON device_bans_mb(device_row_id)
    WHERE lifted_at IS NULL`,
  `CREATE TRIGGER IF NOT EXISTS trg_users_ban_devices_mb
  AFTER UPDATE OF status ON users_mb
  WHEN NEW.status = 'banned'
  BEGIN
    INSERT INTO device_bans_mb
      (device_row_id, source_uid, reason, banned_at, lifted_at)
    SELECT
      device_row_id,
      NEW.uid,
      'account_banned',
      strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
      NULL
    FROM device_account_links_mb
    WHERE user_id = NEW.id
    ON CONFLICT(device_row_id, source_uid, reason) DO UPDATE SET
      banned_at = excluded.banned_at,
      lifted_at = NULL;
  END`,
  `CREATE TRIGGER IF NOT EXISTS trg_users_unban_devices_mb
  AFTER UPDATE OF status ON users_mb
  WHEN NEW.status = 'active'
  BEGIN
    UPDATE device_bans_mb
    SET lifted_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE source_uid = NEW.uid
      AND reason = 'account_banned'
      AND lifted_at IS NULL;
  END`,
  `CREATE TABLE IF NOT EXISTS user_usage_mb (
    user_id INTEGER,
    session_id TEXT NOT NULL CHECK (length(session_id) BETWEEN 1 AND 128),
    subscription_group_name TEXT NOT NULL DEFAULT '',
    started_at TEXT NOT NULL,
    ended_at TEXT NOT NULL,
    reported_at TEXT NOT NULL,
    usage_day TEXT GENERATED ALWAYS AS (substr(started_at, 1, 10)) VIRTUAL,
    upload_bytes INTEGER NOT NULL DEFAULT 0
      CHECK (upload_bytes BETWEEN 0 AND 1000000000000000),
    download_bytes INTEGER NOT NULL DEFAULT 0
      CHECK (download_bytes BETWEEN 0 AND 1000000000000000),
    PRIMARY KEY (user_id, session_id),
    CHECK (ended_at >= started_at),
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_user_usage_mb_user_ended
    ON user_usage_mb(user_id, ended_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_user_usage_mb_day_user
    ON user_usage_mb(usage_day, user_id)`,
  `CREATE TABLE IF NOT EXISTS user_reward_claims_mb (
    id INTEGER PRIMARY KEY,
    user_id INTEGER,
    user_uid TEXT NOT NULL,
    device_row_id INTEGER NOT NULL,
    reward_type TEXT NOT NULL,
    period_key TEXT NOT NULL,
    context_key TEXT NOT NULL DEFAULT '',
    claim_token TEXT NOT NULL UNIQUE,
    claimed_at TEXT NOT NULL,
    experience_awarded INTEGER NOT NULL DEFAULT 0
      CHECK (experience_awarded BETWEEN 0 AND 1000000000000),
    UNIQUE (user_id, reward_type, period_key, context_key),
    UNIQUE (device_row_id, reward_type, period_key, context_key),
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL,
    FOREIGN KEY (device_row_id) REFERENCES user_devices_mb(id) ON DELETE CASCADE
  )`,
  `CREATE TRIGGER IF NOT EXISTS trg_user_reward_claim_award_mb
  AFTER INSERT ON user_reward_claims_mb
  WHEN NEW.experience_awarded > 0
  BEGIN
    UPDATE users_mb
    SET experience_total = MIN(
      experience_total + NEW.experience_awarded,
      1000000000000
    )
    WHERE id = NEW.user_id AND status = 'active'
      AND email IS NOT NULL AND email <> '';
  END`,
  `CREATE TABLE IF NOT EXISTS user_experience_awards_mb (
    id INTEGER PRIMARY KEY,
    user_id INTEGER,
    user_uid TEXT NOT NULL,
    experience_awarded INTEGER NOT NULL
      CHECK (experience_awarded BETWEEN 1 AND 1000000000000),
    reason TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_user_experience_awards_created_at
    ON user_experience_awards_mb(created_at DESC, id DESC)`,
  `CREATE TRIGGER IF NOT EXISTS trg_user_experience_award_mb
  AFTER INSERT ON user_experience_awards_mb
  WHEN NEW.experience_awarded > 0
  BEGIN
    UPDATE users_mb
    SET experience_total = MIN(
      experience_total + NEW.experience_awarded,
      1000000000000
    )
    WHERE id = NEW.user_id AND status = 'active'
      AND email IS NOT NULL AND email <> '';
  END`,
  `CREATE TABLE IF NOT EXISTS speed_share_results_mb (
    subscription_group_name TEXT NOT NULL,
    node_id TEXT NOT NULL,
    province TEXT NOT NULL DEFAULT '',
    carrier TEXT NOT NULL DEFAULT '',
    server_host TEXT NOT NULL DEFAULT '',
    tcping_latency_ms INTEGER NOT NULL DEFAULT 0
      CHECK (tcping_latency_ms BETWEEN 0 AND 600000),

    url_test_latency_ms INTEGER NOT NULL DEFAULT 0
      CHECK (url_test_latency_ms BETWEEN 0 AND 600000),
    download_mbps REAL NOT NULL DEFAULT 0
      CHECK (download_mbps BETWEEN 0 AND 1000000),
    upload_mbps REAL NOT NULL DEFAULT 0
      CHECK (upload_mbps BETWEEN 0 AND 1000000),
    updated_at TEXT NOT NULL,
    last_reported_at TEXT NOT NULL,
    last_reporter_user_id INTEGER,
    report_count INTEGER NOT NULL DEFAULT 1 CHECK (report_count > 0),
    PRIMARY KEY (subscription_group_name, node_id, province, carrier),
    FOREIGN KEY (last_reporter_user_id)
      REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_speed_share_results_mb_reported
    ON speed_share_results_mb(last_reported_at DESC)`,
  `INSERT OR IGNORE INTO app_schema_mb (component, version, applied_at)
    VALUES ('users', ${USER_SCHEMA_VERSION}, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))`,
];
// 采用增量迁移，保证已存在的 D1 用户数据可以平滑增加邀请码字段。
export const USER_SCHEMA_MIGRATIONS = {
  2: [
    `ALTER TABLE users_mb ADD COLUMN invite_code TEXT`,
    `ALTER TABLE users_mb ADD COLUMN invited_by_user_id INTEGER`,
    `ALTER TABLE users_mb ADD COLUMN invited_at TEXT`,
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_mb_invite_code
      ON users_mb(invite_code)
      WHERE invite_code IS NOT NULL AND invite_code <> ''`,
    `CREATE INDEX IF NOT EXISTS idx_users_mb_invited_by
      ON users_mb(invited_by_user_id)`,
  ],
  3: [
    `CREATE TABLE IF NOT EXISTS user_experience_awards_mb (
      id INTEGER PRIMARY KEY,
      user_id INTEGER,
      user_uid TEXT NOT NULL,
      experience_awarded INTEGER NOT NULL
        CHECK (experience_awarded BETWEEN 1 AND 1000000000000),
      reason TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
    )`,
    `CREATE INDEX IF NOT EXISTS idx_user_experience_awards_created_at
      ON user_experience_awards_mb(created_at DESC, id DESC)`,
    `CREATE TRIGGER IF NOT EXISTS trg_user_experience_award_mb
    AFTER INSERT ON user_experience_awards_mb
    WHEN NEW.experience_awarded > 0
    BEGIN
      UPDATE users_mb
      SET experience_total = MIN(
        experience_total + NEW.experience_awarded,
        1000000000000
      )
      WHERE id = NEW.user_id AND status = 'active';
    END`,
  ],
  4: [
    `DROP TRIGGER IF EXISTS trg_user_usage_daily_insert_mb`,
    `DROP TRIGGER IF EXISTS trg_user_usage_daily_update_mb`,
    `DROP TABLE IF EXISTS user_usage_daily_mb`,
    `CREATE INDEX IF NOT EXISTS idx_user_usage_mb_day_user
      ON user_usage_mb(usage_day, user_id)`,
  ],
  5: [
    `ALTER TABLE speed_share_results_mb DROP COLUMN node_name`,
  ],
  6: [],
  7: [
    `DROP INDEX IF EXISTS idx_user_usage_mb_user_ended`,
    `DROP INDEX IF EXISTS idx_user_usage_mb_day_user`,
    `ALTER TABLE user_usage_mb RENAME TO user_usage_mb_legacy`,
    `CREATE TABLE user_usage_mb_new (
      user_id INTEGER,
      session_id TEXT NOT NULL CHECK (length(session_id) BETWEEN 1 AND 128),
      subscription_group_name TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL,
      ended_at TEXT NOT NULL,
      reported_at TEXT NOT NULL,
      usage_day TEXT GENERATED ALWAYS AS (substr(started_at, 1, 10)) VIRTUAL,
      upload_bytes INTEGER NOT NULL DEFAULT 0
        CHECK (upload_bytes BETWEEN 0 AND 1000000000000000),
      download_bytes INTEGER NOT NULL DEFAULT 0
        CHECK (download_bytes BETWEEN 0 AND 1000000000000000),
      PRIMARY KEY (user_id, session_id),
      CHECK (ended_at >= started_at),
      FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
    )`,
    `INSERT INTO user_usage_mb_new (
      user_id, session_id, subscription_group_name, started_at, ended_at,
      reported_at, upload_bytes, download_bytes
    )
    SELECT
      user_id, session_id, subscription_group_name, started_at, ended_at,
      reported_at, upload_bytes, download_bytes
    FROM user_usage_mb_legacy`,
    `DROP TABLE user_usage_mb_legacy`,
    `ALTER TABLE user_usage_mb_new RENAME TO user_usage_mb`,
    `CREATE INDEX IF NOT EXISTS idx_user_usage_mb_user_ended
      ON user_usage_mb(user_id, ended_at DESC)`,
    `CREATE INDEX IF NOT EXISTS idx_user_usage_mb_day_user
      ON user_usage_mb(usage_day, user_id)`,
  ],
  8: [
    `DROP TRIGGER IF EXISTS trg_user_reward_claim_award_mb`,
    `CREATE TRIGGER trg_user_reward_claim_award_mb
     AFTER INSERT ON user_reward_claims_mb
     WHEN NEW.experience_awarded > 0
     BEGIN
       UPDATE users_mb
       SET experience_total = MIN(
         experience_total + NEW.experience_awarded,
         1000000000000
       )
       WHERE id = NEW.user_id AND status = 'active'
         AND email IS NOT NULL AND email <> '';
     END`,
    `DROP TRIGGER IF EXISTS trg_user_experience_award_mb`,
    `CREATE TRIGGER trg_user_experience_award_mb
     AFTER INSERT ON user_experience_awards_mb
     WHEN NEW.experience_awarded > 0
     BEGIN
       UPDATE users_mb
       SET experience_total = MIN(
         experience_total + NEW.experience_awarded,
         1000000000000
       )
       WHERE id = NEW.user_id AND status = 'active'
         AND email IS NOT NULL AND email <> '';
     END`,
  ],
};
export const REWARD_TYPE_ACCOUNT_BINDING = "account_binding";
export const REWARD_TYPE_INVITATION = "invitation";
export const REWARD_TYPE_SPEED_SHARE = "speed_share";
export const REWARD_TYPE_TELEGRAM = "telegram";
export const REWARD_TYPE_ADMIN_MANUAL = "admin_manual";
export const REWARD_PERIOD_LIFETIME = "lifetime";
export function resolveAdminBasePath(value) {
  const route = String(value || "").trim().replace(/^\/+|\/+$/g, "");
  return /^[A-Za-z0-9_-]{1,128}$/.test(route)
    ? `/${route}`
    : null;
}

export const DEFAULT_STATE = {
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
    accountBindingExperience: ACCOUNT_BINDING_EXPERIENCE,
    invitationExperience: DEFAULT_INVITATION_EXPERIENCE,
    telegramGroupUrl: "",
    telegramJoinCode: "",
    telegramTaskExperience: DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    speedShareSmartExperience: DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
    aiAnalysis: {
      model: "gemini-flash-latest",
      thinkingLevel: "low",
      maxOutputTokens: 4096,
      analysisStyle: "standard",
      customInstruction: "",
    },
  },
};
