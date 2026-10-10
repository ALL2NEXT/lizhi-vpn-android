ALTER TABLE subscription_groups
  ADD COLUMN encryption_mode TEXT NOT NULL DEFAULT 'encrypted';
