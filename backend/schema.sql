CREATE TABLE app_schema_mb (
  component TEXT PRIMARY KEY,
  version INTEGER NOT NULL CHECK (version > 0),
  applied_at TEXT NOT NULL
);

CREATE TABLE users_mb (
  id INTEGER PRIMARY KEY,
  uid TEXT NOT NULL UNIQUE,
  nickname TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'banned')),
  experience_total INTEGER NOT NULL DEFAULT 0
    CHECK (experience_total BETWEEN 0 AND 1000000000000),
  email TEXT COLLATE NOCASE,
  password_hash TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  created_day TEXT GENERATED ALWAYS AS (substr(created_at, 1, 10)) VIRTUAL
);

CREATE INDEX idx_users_mb_created_at
  ON users_mb(created_at DESC);

CREATE INDEX idx_users_mb_created_day
  ON users_mb(created_day);

CREATE UNIQUE INDEX idx_users_mb_email
  ON users_mb(email)
  WHERE email IS NOT NULL AND email <> '';

CREATE TABLE user_devices_mb (
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
);

CREATE INDEX idx_user_devices_mb_user_seen
  ON user_devices_mb(user_id, last_seen_at DESC, id DESC);

CREATE TABLE device_account_links_mb (
  id INTEGER PRIMARY KEY,
  device_row_id INTEGER NOT NULL,
  user_id INTEGER,
  user_uid TEXT NOT NULL,
  first_bound_at TEXT NOT NULL,
  last_bound_at TEXT NOT NULL,
  UNIQUE (device_row_id, user_uid),
  FOREIGN KEY (device_row_id) REFERENCES user_devices_mb(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
);

CREATE INDEX idx_device_account_links_mb_user
  ON device_account_links_mb(user_id, device_row_id)
  WHERE user_id IS NOT NULL;

CREATE TABLE device_bans_mb (
  device_row_id INTEGER NOT NULL,
  source_uid TEXT NOT NULL,
  reason TEXT NOT NULL,
  banned_at TEXT NOT NULL,
  lifted_at TEXT,
  PRIMARY KEY (device_row_id, source_uid, reason),
  FOREIGN KEY (device_row_id) REFERENCES user_devices_mb(id) ON DELETE CASCADE
);

CREATE INDEX idx_device_bans_mb_active
  ON device_bans_mb(device_row_id)
  WHERE lifted_at IS NULL;

CREATE TRIGGER trg_users_ban_devices_mb
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
END;

CREATE TRIGGER trg_users_unban_devices_mb
AFTER UPDATE OF status ON users_mb
WHEN NEW.status = 'active'
BEGIN
  UPDATE device_bans_mb
  SET lifted_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
  WHERE source_uid = NEW.uid
    AND reason = 'account_banned'
    AND lifted_at IS NULL;
END;

CREATE TABLE user_usage_mb (
  user_id INTEGER NOT NULL,
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
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_usage_mb_user_ended
  ON user_usage_mb(user_id, ended_at DESC);

CREATE INDEX idx_user_usage_mb_day_user
  ON user_usage_mb(usage_day, user_id);

CREATE TABLE user_reward_claims_mb (
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
);

CREATE TRIGGER trg_user_reward_claim_award_mb
AFTER INSERT ON user_reward_claims_mb
WHEN NEW.experience_awarded > 0
BEGIN
  UPDATE users_mb
  SET experience_total = MIN(
    experience_total + NEW.experience_awarded,
    1000000000000
  )
  WHERE id = NEW.user_id AND status = 'active';
END;

CREATE TABLE speed_share_results_mb (
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
  PRIMARY KEY (
    subscription_group_name,
    node_id,
    province,
    carrier
  ),
  FOREIGN KEY (last_reporter_user_id)
    REFERENCES users_mb(id) ON DELETE SET NULL
);

CREATE INDEX idx_speed_share_results_mb_reported
  ON speed_share_results_mb(last_reported_at DESC);

INSERT INTO app_schema_mb (component, version, applied_at)
VALUES ('users', 6, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
