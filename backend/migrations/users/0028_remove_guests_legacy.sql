-- Backport for a legacy Worker that still uses schema v25. Keep the schema version
-- unchanged so that this Worker can continue operating until it is upgraded.
-- Back up the database before applying. Device records and bans are retained.
DELETE FROM user_usage_mb
WHERE user_id IN (SELECT id FROM users_mb WHERE email IS NULL OR trim(email) = '');

DELETE FROM user_ip_links_mb
WHERE user_uid IN (SELECT uid FROM users_mb WHERE email IS NULL OR trim(email) = '');

DELETE FROM device_account_links_mb
WHERE user_uid IN (SELECT uid FROM users_mb WHERE email IS NULL OR trim(email) = '');

DELETE FROM users_mb WHERE email IS NULL OR trim(email) = '';

CREATE TRIGGER IF NOT EXISTS trg_users_require_email_insert_mb
BEFORE INSERT ON users_mb WHEN NEW.email IS NULL OR trim(NEW.email) = ''
BEGIN SELECT RAISE(ABORT, 'user_email_required'); END;

CREATE TRIGGER IF NOT EXISTS trg_users_require_email_update_mb
BEFORE UPDATE OF email ON users_mb WHEN NEW.email IS NULL OR trim(NEW.email) = ''
BEGIN SELECT RAISE(ABORT, 'user_email_required'); END;
