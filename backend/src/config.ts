import type { AppState } from "./types/models.ts";

/*
 * Runtime configuration and database bootstrap schema.
 * Keep deployment-independent values here so route code stays focused on behavior.
 */

export const STATE_KEY = "chimera:state:v1";
export const LEGACY_STATE_KEY = "fireflyvpn:state:v1";
export const SYSTEM_LOG_SETTINGS_KEY = "chimera:system-log-settings:v1";
export const AI_RISK_SETTINGS_KEY = "chimera:ai-risk-settings:v1";
export const MAX_MANAGED_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;
export const MAX_BATCH_USER_COUNT = 100;
export const MAX_TRAFFIC_BYTES = 1e15;
export const SUBSCRIPTION_CACHE_KEY_PREFIX = "chimera:subscription-cache:v1:";
export const SUBSCRIPTION_CONTENT_KEY_PREFIX = "chimera:subscription-content:v1:";
export const DEFAULT_SUBSCRIPTION_CACHE_MINUTES = 30;
export const MAX_SUBSCRIPTION_CACHE_MINUTES = 7 * 24 * 60;
export const PLAINTEXT_NODE_PATTERN = /(?:^|\n)\s*(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|masque|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+/i;
export const TEXT_ENCODER = new TextEncoder();
const REGISTRATION_CODE_USAGE_SCHEMA = `CREATE TABLE IF NOT EXISTS registration_code_usages_mb (
  id INTEGER PRIMARY KEY,
  registration_code_id INTEGER NOT NULL,
  use_number INTEGER NOT NULL CHECK (use_number > 0),
  user_id INTEGER,
  uid TEXT NOT NULL DEFAULT '',
  used_at TEXT NOT NULL,
  UNIQUE (registration_code_id, use_number),
  FOREIGN KEY (registration_code_id) REFERENCES registration_codes_mb(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
)`;
const REGISTRATION_CODE_USAGE_INDEX = `CREATE INDEX IF NOT EXISTS idx_registration_code_usages_mb_code
  ON registration_code_usages_mb(registration_code_id, use_number)`;
const REGISTRATION_CODE_USAGE_VALIDATE_TRIGGER = `CREATE TRIGGER IF NOT EXISTS trg_registration_code_usage_validate_mb
BEFORE INSERT ON registration_code_usages_mb
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM registration_codes_mb
     WHERE id = NEW.registration_code_id
       AND use_count < max_uses
       AND NEW.use_number = use_count + 1
  ) THEN RAISE(ABORT, 'registration_code_exhausted') END;
END`;
const REGISTRATION_CODE_USAGE_COUNT_TRIGGER = `CREATE TRIGGER IF NOT EXISTS trg_registration_code_usage_count_mb
AFTER INSERT ON registration_code_usages_mb
BEGIN
  UPDATE registration_codes_mb
     SET use_count = NEW.use_number,
         used_at = NEW.used_at,
         used_by_user_id = NEW.user_id,
         used_by_uid = NEW.uid
   WHERE id = NEW.registration_code_id;
END`;
const VIP_ACTIVATION_CODE_SCHEMA = `CREATE TABLE IF NOT EXISTS vip_activation_codes_mb (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 36500),
  created_at TEXT NOT NULL,
  used_at TEXT,
  used_by_user_id INTEGER,
  used_by_uid TEXT NOT NULL DEFAULT '',
  FOREIGN KEY (used_by_user_id) REFERENCES users_mb(id) ON DELETE SET NULL
)`;
const VIP_ACTIVATION_CODE_INDEX = `CREATE INDEX IF NOT EXISTS idx_vip_activation_codes_mb_status
  ON vip_activation_codes_mb(used_at, created_at DESC, id DESC)`;
const SYSTEM_LOG_PAYLOADS_SCHEMA = `CREATE TABLE IF NOT EXISTS system_log_payload_parts_mb (
  request_id TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  part_index INTEGER NOT NULL CHECK (part_index >= 0),
  part_count INTEGER NOT NULL CHECK (part_count > 0),
  content TEXT NOT NULL,
  PRIMARY KEY (request_id, part_index)
)`;
const SYSTEM_LOG_PAYLOADS_INDEX = `CREATE INDEX IF NOT EXISTS idx_system_log_payload_parts_occurred_mb
  ON system_log_payload_parts_mb(occurred_at)`;

const USER_IP_LINKS_SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS user_ip_links_mb (
    user_uid TEXT NOT NULL,
    ip TEXT NOT NULL,
    user_id INTEGER,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    last_device_id TEXT NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    subscription_count INTEGER NOT NULL DEFAULT 0 CHECK (subscription_count >= 0),
    login_count INTEGER NOT NULL DEFAULT 0 CHECK (login_count >= 0),
    PRIMARY KEY (user_uid, ip),
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_user_ip_links_mb_ip_seen
    ON user_ip_links_mb(ip, last_seen_at DESC, user_uid)`,
  `CREATE INDEX IF NOT EXISTS idx_user_ip_links_mb_user_seen
    ON user_ip_links_mb(user_uid, last_seen_at DESC, ip)`,
  `CREATE INDEX IF NOT EXISTS idx_user_ip_links_mb_seen
    ON user_ip_links_mb(last_seen_at DESC, user_uid, ip)`,
];
const SYSTEM_LOG_USER_INDEX = `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_user_occurred
  ON system_logs_mb(user_uid, occurred_at DESC, id DESC) WHERE user_uid <> ''`;
const SYSTEM_LOG_REQUEST_INDEX = `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_request_id
  ON system_logs_mb(request_id)`;
const SYSTEM_LOG_IP_INDEX = `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_ip_occurred
  ON system_logs_mb(ip, occurred_at DESC)
  WHERE category = 'request' AND user_uid <> '' AND device_id <> ''`;
const SECURITY_RISK_RULE_HITS_SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS security_risk_rule_hits_mb (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TEXT NOT NULL,
    rule_id TEXT NOT NULL,
    method TEXT NOT NULL,
    path TEXT NOT NULL,
    status INTEGER NOT NULL,
    ip TEXT NOT NULL,
    request_id TEXT NOT NULL DEFAULT ''
  )`,
  `CREATE INDEX IF NOT EXISTS idx_security_risk_rule_hits_mb_rule_time
    ON security_risk_rule_hits_mb(rule_id, occurred_at DESC, ip)`,
  `CREATE INDEX IF NOT EXISTS idx_security_risk_rule_hits_mb_ip_time
    ON security_risk_rule_hits_mb(ip, occurred_at DESC)`,
];

const NOTIFICATION_SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS notifications_mb (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 100),
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 10000),
    level TEXT NOT NULL DEFAULT 'info' CHECK (level IN ('info', 'important')),
    status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published', 'archived')),
    starts_at TEXT,
    expires_at TEXT,
    published_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK (expires_at IS NULL OR starts_at IS NULL OR expires_at > starts_at)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_notifications_mb_visibility
    ON notifications_mb(status, starts_at, expires_at, published_at DESC, id DESC)`,
  `CREATE TABLE IF NOT EXISTS notification_reads_mb (
    notification_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    read_at TEXT NOT NULL,
    PRIMARY KEY (notification_id, user_id),
    FOREIGN KEY (notification_id) REFERENCES notifications_mb(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_notification_reads_mb_user
    ON notification_reads_mb(user_id, notification_id)`,
];

const CLIENT_SECURITY_REPORTS_SCHEMA = `CREATE TABLE IF NOT EXISTS client_security_reports_mb (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  finding_code TEXT NOT NULL CHECK (length(finding_code) BETWEEN 1 AND 200),
  first_reported_at TEXT NOT NULL,
  last_reported_at TEXT NOT NULL,
  report_count INTEGER NOT NULL DEFAULT 1 CHECK (report_count > 0),
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'dismissed', 'banned')),
  reviewed_at TEXT,
  UNIQUE (device_id, finding_code)
)`;
const CLIENT_SECURITY_REPORTS_INDEX = `CREATE INDEX IF NOT EXISTS idx_client_security_reports_pending_mb
  ON client_security_reports_mb(status, last_reported_at DESC, id DESC)`;

const USER_EMAIL_REQUIRED_TRIGGERS = [
  `CREATE TRIGGER IF NOT EXISTS trg_users_require_email_insert_mb
   BEFORE INSERT ON users_mb WHEN NEW.email IS NULL OR trim(NEW.email) = ''
   BEGIN SELECT RAISE(ABORT, 'user_email_required'); END`,
  `CREATE TRIGGER IF NOT EXISTS trg_users_require_email_update_mb
   BEFORE UPDATE OF email ON users_mb WHEN NEW.email IS NULL OR trim(NEW.email) = ''
   BEGIN SELECT RAISE(ABORT, 'user_email_required'); END`,
];

export const USER_SCHEMA_VERSION = 28;
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
    identity_type TEXT NOT NULL DEFAULT 'human'
      CHECK (identity_type IN ('human', 'bot')),
    email TEXT NOT NULL COLLATE NOCASE CHECK (trim(email) <> ''),
    password_hash TEXT NOT NULL DEFAULT '',
    vip_expires_at TEXT,
    created_at TEXT NOT NULL,
    registration_ip TEXT NOT NULL DEFAULT '',
    created_day TEXT GENERATED ALWAYS AS (substr(created_at, 1, 10)) VIRTUAL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_users_mb_created_at
    ON users_mb(created_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_users_mb_created_day
    ON users_mb(created_day)`,
  `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_mb_email
    ON users_mb(email)
    WHERE email IS NOT NULL AND email <> ''`,
  ...USER_EMAIL_REQUIRED_TRIGGERS,
  `CREATE TABLE IF NOT EXISTS registration_codes_mb (
    id INTEGER PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 1000),
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    used_at TEXT,
    used_by_user_id INTEGER,
    used_by_uid TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (used_by_user_id) REFERENCES users_mb(id) ON DELETE SET NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_registration_codes_mb_status
    ON registration_codes_mb(use_count, max_uses, created_at DESC, id DESC)`,
  VIP_ACTIVATION_CODE_SCHEMA,
  VIP_ACTIVATION_CODE_INDEX,
  REGISTRATION_CODE_USAGE_SCHEMA,
  REGISTRATION_CODE_USAGE_INDEX,
  REGISTRATION_CODE_USAGE_VALIDATE_TRIGGER,
  REGISTRATION_CODE_USAGE_COUNT_TRIGGER,
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
  `CREATE TABLE IF NOT EXISTS subscription_group_assignments_mb (
    group_id TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    source_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    assigned_at INTEGER NOT NULL CHECK (assigned_at > 0),
    PRIMARY KEY (group_id, user_id),
    FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_subscription_group_assignments_capacity_mb
    ON subscription_group_assignments_mb(group_id, source_key, assigned_at)`,
  `CREATE TABLE IF NOT EXISTS system_logs_mb (
    id INTEGER PRIMARY KEY,
    occurred_at TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('request', 'outbound')),
    request_id TEXT NOT NULL DEFAULT '',
    actor TEXT NOT NULL DEFAULT '',
    method TEXT NOT NULL DEFAULT '',
    path TEXT NOT NULL DEFAULT '',
    status INTEGER,
    duration_ms INTEGER,
    ip TEXT NOT NULL DEFAULT '',
    user_uid TEXT NOT NULL DEFAULT '',
    device_id TEXT NOT NULL DEFAULT '',
    identity_source TEXT NOT NULL DEFAULT '',
    user_agent TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT ''
  )`,
  `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_occurred
    ON system_logs_mb(occurred_at DESC, id DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_category_occurred
    ON system_logs_mb(category, occurred_at DESC, id DESC)`,
  SYSTEM_LOG_REQUEST_INDEX,
  SYSTEM_LOG_PAYLOADS_SCHEMA,
  SYSTEM_LOG_PAYLOADS_INDEX,
  SYSTEM_LOG_USER_INDEX,
  SYSTEM_LOG_IP_INDEX,
  ...SECURITY_RISK_RULE_HITS_SCHEMA_STATEMENTS,
  ...NOTIFICATION_SCHEMA_STATEMENTS,
  ...USER_IP_LINKS_SCHEMA_STATEMENTS,
  CLIENT_SECURITY_REPORTS_SCHEMA,
  CLIENT_SECURITY_REPORTS_INDEX,
  `INSERT OR IGNORE INTO app_schema_mb (component, version, applied_at)
    VALUES ('users', ${USER_SCHEMA_VERSION}, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))`,
];
export const USER_SCHEMA_MIGRATIONS: Record<number, string[]> = {
  2: [],
  3: [],
  4: [
    `DROP TRIGGER IF EXISTS trg_user_usage_daily_insert_mb`,
    `DROP TRIGGER IF EXISTS trg_user_usage_daily_update_mb`,
    `DROP TABLE IF EXISTS user_usage_daily_mb`,
    `CREATE INDEX IF NOT EXISTS idx_user_usage_mb_day_user
      ON user_usage_mb(usage_day, user_id)`,
  ],
  5: [],
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
  8: [],
  9: [
    `CREATE TABLE IF NOT EXISTS registration_codes_mb (
      id INTEGER PRIMARY KEY,
      code TEXT NOT NULL UNIQUE,
      created_at TEXT NOT NULL,
      used_at TEXT,
      used_by_user_id INTEGER,
      used_by_uid TEXT NOT NULL DEFAULT '',
      FOREIGN KEY (used_by_user_id) REFERENCES users_mb(id) ON DELETE SET NULL
    )`,
    `CREATE INDEX IF NOT EXISTS idx_registration_codes_mb_status
      ON registration_codes_mb(used_at, created_at DESC, id DESC)`,
  ],
  10: [
    `DROP INDEX IF EXISTS idx_speed_share_results_mb_reported`,
    `DROP TABLE IF EXISTS speed_share_results_mb`,
  ],
  11: [
    `DROP TRIGGER IF EXISTS trg_user_reward_claim_award_mb`,
    `DROP TRIGGER IF EXISTS trg_user_experience_award_mb`,
    `DROP TABLE IF EXISTS user_reward_claims_mb`,
    `DROP TABLE IF EXISTS user_experience_awards_mb`,
    `ALTER TABLE users_mb DROP COLUMN experience_total`,
  ],
  12: [
    `PRAGMA defer_foreign_keys = ON`,
    `DROP TABLE IF EXISTS users_mb_new`,
    `CREATE TABLE users_mb_new (
      id INTEGER PRIMARY KEY,
      uid TEXT NOT NULL UNIQUE,
      nickname TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'banned')),
      email TEXT COLLATE NOCASE,
      password_hash TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL,
      created_day TEXT GENERATED ALWAYS AS (substr(created_at, 1, 10)) VIRTUAL
    )`,
    `INSERT INTO users_mb_new (
      id, uid, nickname, status, email, password_hash, created_at
    )
    SELECT
      id, uid, nickname, status, email, password_hash, created_at
    FROM users_mb`,
    `DROP TABLE users_mb`,
    `ALTER TABLE users_mb_new RENAME TO users_mb`,
    `CREATE INDEX IF NOT EXISTS idx_users_mb_created_at
      ON users_mb(created_at DESC)`,
    `CREATE INDEX IF NOT EXISTS idx_users_mb_created_day
      ON users_mb(created_day)`,
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_mb_email
      ON users_mb(email)
      WHERE email IS NOT NULL AND email <> ''`,
    `PRAGMA defer_foreign_keys = OFF`,
  ],
  13: [
    `CREATE TABLE IF NOT EXISTS subscription_group_assignments_mb (
      group_id TEXT NOT NULL,
      user_id INTEGER NOT NULL,
      source_id TEXT NOT NULL,
      source_key TEXT NOT NULL,
      assigned_at INTEGER NOT NULL CHECK (assigned_at > 0),
      PRIMARY KEY (group_id, user_id),
      FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
    )`,
    `CREATE INDEX IF NOT EXISTS idx_subscription_group_assignments_capacity_mb
      ON subscription_group_assignments_mb(group_id, source_key, assigned_at)`,
  ],
  14: [
    `CREATE TABLE IF NOT EXISTS system_logs_mb (
      id INTEGER PRIMARY KEY,
      occurred_at TEXT NOT NULL,
      category TEXT NOT NULL CHECK (category IN ('request', 'outbound')),
      level TEXT NOT NULL CHECK (level IN ('info', 'warning', 'error')),
      request_id TEXT NOT NULL DEFAULT '',
      actor TEXT NOT NULL DEFAULT '',
      method TEXT NOT NULL DEFAULT '',
      path TEXT NOT NULL DEFAULT '',
      status INTEGER,
      duration_ms INTEGER,
      ip TEXT NOT NULL DEFAULT '',
      user_agent TEXT NOT NULL DEFAULT '',
      message TEXT NOT NULL,
      details TEXT NOT NULL DEFAULT ''
    )`,
    `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_occurred
      ON system_logs_mb(occurred_at DESC, id DESC)`,
    `CREATE INDEX IF NOT EXISTS idx_system_logs_mb_category_occurred
      ON system_logs_mb(category, occurred_at DESC, id DESC)`,
  ],
  15: [
    `ALTER TABLE registration_codes_mb
      ADD COLUMN max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 1000)`,
    `ALTER TABLE registration_codes_mb
      ADD COLUMN use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0)`,
    REGISTRATION_CODE_USAGE_SCHEMA,
    REGISTRATION_CODE_USAGE_INDEX,
    REGISTRATION_CODE_USAGE_VALIDATE_TRIGGER,
    REGISTRATION_CODE_USAGE_COUNT_TRIGGER,
    // 旧版只保存 used_at，将历史已用注册码回填为第 1 次使用记录。
    `INSERT INTO registration_code_usages_mb (
      registration_code_id, use_number, user_id, uid, used_at
    )
    SELECT
      codes.id,
      1,
      codes.used_by_user_id,
      COALESCE(NULLIF(codes.used_by_uid, ''), users.uid, ''),
      codes.used_at
    FROM registration_codes_mb AS codes
    LEFT JOIN users_mb AS users ON users.id = codes.used_by_user_id
    WHERE codes.used_at IS NOT NULL`,
    `DROP INDEX IF EXISTS idx_registration_codes_mb_status`,
    `CREATE INDEX IF NOT EXISTS idx_registration_codes_mb_status
      ON registration_codes_mb(use_count, max_uses, created_at DESC, id DESC)`,
  ],
  16: [
    SYSTEM_LOG_PAYLOADS_SCHEMA,
    SYSTEM_LOG_PAYLOADS_INDEX,
  ],
  17: [
    `ALTER TABLE users_mb ADD COLUMN vip_expires_at TEXT`,
    VIP_ACTIVATION_CODE_SCHEMA,
    VIP_ACTIVATION_CODE_INDEX,
  ],
  18: [
    `ALTER TABLE users_mb ADD COLUMN identity_type TEXT NOT NULL DEFAULT 'unknown'
      CHECK (identity_type IN ('human', 'bot', 'unknown'))`,
  ],
  19: [
    `UPDATE users_mb SET identity_type = 'human'
      WHERE identity_type NOT IN ('human', 'bot')`,
  ],
  20: [
    ...USER_IP_LINKS_SCHEMA_STATEMENTS,
    `ALTER TABLE system_logs_mb ADD COLUMN user_uid TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE system_logs_mb ADD COLUMN device_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE system_logs_mb ADD COLUMN identity_source TEXT NOT NULL DEFAULT ''`,
    SYSTEM_LOG_USER_INDEX,
  ],
  21: [
    `ALTER TABLE system_logs_mb DROP COLUMN level`,
  ],
  22: [
    SYSTEM_LOG_REQUEST_INDEX,
  ],
  23: NOTIFICATION_SCHEMA_STATEMENTS,
  24: [SYSTEM_LOG_IP_INDEX, ...SECURITY_RISK_RULE_HITS_SCHEMA_STATEMENTS],
  25: [CLIENT_SECURITY_REPORTS_SCHEMA, CLIENT_SECURITY_REPORTS_INDEX],
  26: [
    `ALTER TABLE users_mb ADD COLUMN registration_ip TEXT NOT NULL DEFAULT ''`,
  ],
  27: [
    `DROP TRIGGER IF EXISTS trg_users_ban_devices_mb`,
    `DROP TRIGGER IF EXISTS trg_users_unban_devices_mb`,
    `UPDATE device_bans_mb
       SET lifted_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
     WHERE reason = 'account_banned' AND lifted_at IS NULL`,
  ],
  28: [
    // Preserve device and ban records; remove data that belongs exclusively to guests.
    `DELETE FROM user_usage_mb WHERE user_id IN (SELECT id FROM users_mb WHERE email IS NULL OR trim(email) = '')`,
    `DELETE FROM user_ip_links_mb WHERE user_uid IN (SELECT uid FROM users_mb WHERE email IS NULL OR trim(email) = '')`,
    `DELETE FROM device_account_links_mb WHERE user_uid IN (SELECT uid FROM users_mb WHERE email IS NULL OR trim(email) = '')`,
    `DELETE FROM users_mb WHERE email IS NULL OR trim(email) = ''`,
    ...USER_EMAIL_REQUIRED_TRIGGERS,
  ],
};
export function resolveAdminBasePath(value: unknown): string | null {
  const route = String(value || "").trim().replace(/^\/+|\/+$/g, "");
  return /^[A-Za-z0-9_-]{1,128}$/.test(route)
    ? `/${route}`
    : null;
}

export const DEFAULT_STATE: AppState = {
  version: 2,
  subscriptionVersions: {},
  subscriptionGroups: [],
  settings: {
    websiteUrl: "",
    feedbackEmail: "",
    feedbackUrl: "",
    githubUrl: "",
    telegramUrl: "",
    latestAppVersion: "",
    appDownloadUrl: "",
    // 后台可以关闭新用户注册。
    registrationEnabled: true,
    requireAdminRegistrationCode: false,
  },
};
