-- Preserve restrictions from older groups before removing their obsolete type field.
UPDATE subscription_groups
SET enabled = 0
WHERE CASE
  WHEN json_valid(allowed_account_types_json) THEN CASE
    WHEN json_type(allowed_account_types_json) = 'array' THEN NOT EXISTS (
      SELECT 1 FROM json_each(subscription_groups.allowed_account_types_json)
      WHERE value = 'formal'
    )
    ELSE 1
  END
  ELSE 1
END;

ALTER TABLE subscription_groups DROP COLUMN allowed_account_types_json;
