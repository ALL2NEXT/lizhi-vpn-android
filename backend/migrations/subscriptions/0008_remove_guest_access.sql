-- Keep groups that did not allow formal accounts inaccessible until explicitly changed.
UPDATE subscription_groups
SET allowed_account_types_json = (
  SELECT json_group_array(value)
  FROM json_each(subscription_groups.allowed_account_types_json)
  WHERE value = 'formal'
)
WHERE json_valid(allowed_account_types_json);
