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
  identity_type TEXT NOT NULL DEFAULT 'human'
    CHECK (identity_type IN ('human', 'bot')),
  email TEXT COLLATE NOCASE,
  password_hash TEXT NOT NULL DEFAULT '',
  vip_expires_at TEXT,
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

CREATE TABLE registration_codes_mb (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 1000),
  use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
  used_at TEXT,
  used_by_user_id INTEGER,
  used_by_uid TEXT NOT NULL DEFAULT '',
  FOREIGN KEY (used_by_user_id) REFERENCES users_mb(id) ON DELETE SET NULL
);

CREATE INDEX idx_registration_codes_mb_status
ON registration_codes_mb(use_count, max_uses, created_at DESC, id DESC);

CREATE TABLE vip_activation_codes_mb (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 36500),
  created_at TEXT NOT NULL,
  used_at TEXT,
  used_by_user_id INTEGER,
  used_by_uid TEXT NOT NULL DEFAULT '',
  FOREIGN KEY (used_by_user_id) REFERENCES users_mb(id) ON DELETE SET NULL
);

CREATE INDEX idx_vip_activation_codes_mb_status
ON vip_activation_codes_mb(used_at, created_at DESC, id DESC);

CREATE TABLE registration_code_usages_mb (
  id INTEGER PRIMARY KEY,
  registration_code_id INTEGER NOT NULL,
  use_number INTEGER NOT NULL CHECK (use_number > 0),
  user_id INTEGER,
  uid TEXT NOT NULL DEFAULT '',
  used_at TEXT NOT NULL,
  UNIQUE (registration_code_id, use_number),
  FOREIGN KEY (registration_code_id) REFERENCES registration_codes_mb(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE SET NULL
);

CREATE INDEX idx_registration_code_usages_mb_code
ON registration_code_usages_mb(registration_code_id, use_number);

CREATE TRIGGER trg_registration_code_usage_validate_mb
BEFORE INSERT ON registration_code_usages_mb
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM registration_codes_mb
     WHERE id = NEW.registration_code_id
       AND use_count < max_uses
       AND NEW.use_number = use_count + 1
  ) THEN RAISE(ABORT, 'registration_code_exhausted') END;
END;

CREATE TRIGGER trg_registration_code_usage_count_mb
AFTER INSERT ON registration_code_usages_mb
BEGIN
  UPDATE registration_codes_mb
     SET use_count = NEW.use_number,
         used_at = NEW.used_at,
         used_by_user_id = NEW.user_id,
         used_by_uid = NEW.uid
   WHERE id = NEW.registration_code_id;
END;

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
);

CREATE INDEX idx_user_usage_mb_user_ended
ON user_usage_mb(user_id, ended_at DESC);

CREATE INDEX idx_user_usage_mb_day_user
ON user_usage_mb(usage_day, user_id);

CREATE TABLE subscription_group_assignments_mb (
  group_id TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  source_id TEXT NOT NULL,
  source_key TEXT NOT NULL,
  assigned_at INTEGER NOT NULL CHECK (assigned_at > 0),
  PRIMARY KEY (group_id, user_id),
  FOREIGN KEY (user_id) REFERENCES users_mb(id) ON DELETE CASCADE
);

CREATE INDEX idx_subscription_group_assignments_capacity_mb
ON subscription_group_assignments_mb(group_id, source_key, assigned_at);

CREATE TABLE system_logs_mb (
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
);

CREATE INDEX idx_system_logs_mb_occurred
ON system_logs_mb(occurred_at DESC, id DESC);

CREATE INDEX idx_system_logs_mb_category_occurred
ON system_logs_mb(category, occurred_at DESC, id DESC);

CREATE TABLE system_log_payload_parts_mb (
  request_id TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  part_index INTEGER NOT NULL CHECK (part_index >= 0),
  part_count INTEGER NOT NULL CHECK (part_count > 0),
  content TEXT NOT NULL,
  PRIMARY KEY (request_id, part_index)
);

CREATE INDEX idx_system_log_payload_parts_occurred_mb
ON system_log_payload_parts_mb(occurred_at);

CREATE INDEX idx_system_logs_mb_user_occurred
ON system_logs_mb(user_uid, occurred_at DESC, id DESC) WHERE user_uid <> '';

CREATE TABLE user_ip_links_mb (
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
);

CREATE INDEX idx_user_ip_links_mb_ip_seen
ON user_ip_links_mb(ip, last_seen_at DESC, user_uid);

CREATE INDEX idx_user_ip_links_mb_user_seen
ON user_ip_links_mb(user_uid, last_seen_at DESC, ip);

CREATE INDEX idx_user_ip_links_mb_seen
ON user_ip_links_mb(last_seen_at DESC, user_uid, ip);

INSERT INTO app_schema_mb (component, version, applied_at)
VALUES ('users', 20, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
