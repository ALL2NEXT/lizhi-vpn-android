CREATE TABLE subscription_runtime_cache (
  source_id TEXT PRIMARY KEY,
  source_url TEXT NOT NULL,
  content_cached_at INTEGER,
  traffic_json TEXT,
  traffic_fetched_at INTEGER
);
