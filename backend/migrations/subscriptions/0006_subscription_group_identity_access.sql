ALTER TABLE subscription_groups
  ADD COLUMN allowed_identity_types_json TEXT NOT NULL DEFAULT '["human","bot","unknown"]';
