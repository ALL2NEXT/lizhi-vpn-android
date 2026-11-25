-- 将现有分组更新策略复制到远程订阅，再移除分组级设置列。
ALTER TABLE subscription_sources
  ADD COLUMN update_enabled INTEGER NOT NULL DEFAULT 0;

ALTER TABLE subscription_sources
  ADD COLUMN cache_minutes INTEGER NOT NULL DEFAULT 30;

UPDATE subscription_sources
SET update_enabled = COALESCE((
      SELECT update_enabled FROM subscription_groups
      WHERE subscription_groups.id = subscription_sources.group_id
    ), 0),
    cache_minutes = COALESCE((
      SELECT cache_minutes FROM subscription_groups
      WHERE subscription_groups.id = subscription_sources.group_id
    ), 30)
WHERE source_type = 'url';

ALTER TABLE subscription_groups DROP COLUMN update_enabled;
ALTER TABLE subscription_groups DROP COLUMN cache_minutes;
