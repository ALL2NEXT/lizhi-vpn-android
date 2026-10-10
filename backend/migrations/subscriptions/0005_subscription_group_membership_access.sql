ALTER TABLE subscription_groups
  ADD COLUMN allowed_membership_types_json TEXT NOT NULL DEFAULT '["member","non_member"]';
