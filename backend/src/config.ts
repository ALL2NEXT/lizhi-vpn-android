import type { AppState } from "./types/models.ts";

/*
 * Runtime configuration and D1 bootstrap schema.
 * Keep deployment-independent values here so route code stays focused on behavior.
 */

export const STATE_KEY = "chimera:state:v1";
// Keep the previous protocol header during the client migration window.
export const LEGACY_STATE_KEY = "fireflyvpn:state:v1";
export const CLIENT_IP_HEADER = "X-Chimera-Client-IP";
export const LEGACY_CLIENT_IP_HEADER = "X-Firefly-Client-IP";
export const MAX_MANAGED_SUBSCRIPTION_BYTES = 2 * 1024 * 1024;
export const MAX_BATCH_USER_COUNT = 100;
export const MAX_TRAFFIC_BYTES = 1e15;
export const SUBSCRIPTION_CACHE_KEY_PREFIX = "chimera:subscription-cache:v1:";
export const DEFAULT_SUBSCRIPTION_CACHE_MINUTES = 30;
export const MAX_SUBSCRIPTION_CACHE_MINUTES = 7 * 24 * 60;
export const PLAINTEXT_NODE_PATTERN = /(?:^|\n)\s*(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+/i;
export const PRIVATE_B64_ALPHABET =
  "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX";
export const TEXT_ENCODER = new TextEncoder();
export const USER_SCHEMA_VERSION = 13;
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
};
export function resolveAdminBasePath(value: unknown): string | null {
  const route = String(value || "").trim().replace(/^\/+|\/+$/g, "");
  return /^[A-Za-z0-9_-]{1,128}$/.test(route)
    ? `/${route}`
    : null;
}

export const DEFAULT_STATE: AppState = {
  version: 2,
  subscriptionGroups: [],
  notice: null,
  appUpdate: null,
  settings: {
    websiteUrl: "",
    webNavigationGroups: [
      {
        title: "国内测速",
        items: [
          { title: "中科大测速", url: "https://test.ustc.edu.cn/" },
          { title: "南大测速", url: "https://test.nju.edu.cn/" },
        ],
      },
      {
        title: "国际测速",
        items: [
          { title: "奈飞测速", url: "https://fast.com/" },
          { title: "Google 测速", url: "https://fiber.google.com/speedtest/" },
        ],
      },
    ],
    feedbackEmail: "",
    feedbackUrl: "",
    githubUrl: "",
    telegramUrl: "",
    // 后台可关闭游客注册正式用户；默认保持现有注册流程开放。
    registrationEnabled: true,
    requireAdminRegistrationCode: false,
    debugInterface: null,
  },
};
