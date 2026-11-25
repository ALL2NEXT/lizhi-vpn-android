-- 移除分组分类字段与订阅分配标识，统一返回分组内的启用订阅。
ALTER TABLE subscription_groups DROP COLUMN group_type;
ALTER TABLE subscription_groups DROP COLUMN max_users_per_subscription;
ALTER TABLE subscription_groups DROP COLUMN reset_period_days;
ALTER TABLE subscription_sources DROP COLUMN assignment_key;
