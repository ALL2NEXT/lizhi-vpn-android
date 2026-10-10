ALTER TABLE subscription_sources
  ADD COLUMN deduplicate INTEGER NOT NULL DEFAULT 1;
