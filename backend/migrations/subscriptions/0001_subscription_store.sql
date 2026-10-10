CREATE TABLE subscription_store_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE subscription_groups (
  id TEXT PRIMARY KEY,
  group_order INTEGER NOT NULL,
  name TEXT NOT NULL,
  group_type TEXT NOT NULL,
  enabled INTEGER NOT NULL,
  allowed_statuses_json TEXT NOT NULL,
  allowed_account_types_json TEXT NOT NULL,
  update_enabled INTEGER NOT NULL,
  cache_minutes INTEGER NOT NULL,
  min_app_version TEXT NOT NULL,
  max_app_version TEXT NOT NULL,
  max_users_per_subscription INTEGER NOT NULL,
  reset_period_days INTEGER NOT NULL
);

CREATE TABLE subscription_sources (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  source_order INTEGER NOT NULL,
  name TEXT NOT NULL,
  source_type TEXT NOT NULL CHECK (source_type IN ('url', 'content')),
  url TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  content_key TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL,
  auto_rename INTEGER NOT NULL,
  remark TEXT NOT NULL DEFAULT '',
  FOREIGN KEY (group_id) REFERENCES subscription_groups(id) ON DELETE CASCADE
);

CREATE INDEX subscription_sources_group_order_idx
  ON subscription_sources(group_id, source_order, id);

CREATE INDEX subscription_sources_enabled_type_idx
  ON subscription_sources(enabled, source_type);
