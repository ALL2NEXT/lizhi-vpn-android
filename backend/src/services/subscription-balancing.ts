import type { Env } from "../types/env.ts";
import type { SubscriptionGroup, SubscriptionSource } from "../types/models.ts";

import { ensureUserSchema } from "./user-store.ts";

interface SubscriptionAssignmentRow {
  source_id: string;
  source_key: string;
  assigned_at: number;
}

interface AllocationCandidate {
  source: SubscriptionSource;
  sourceKey: string;
  sortOrder: number;
}

function contentSample(content: string) {
  if (content.length <= 512) return content;
  return `${content.slice(0, 256)}${content.slice(-256)}`;
}

async function sourceAssignmentKey(source: SubscriptionSource) {
  // 用上游地址或纯文本首尾摘要识别订阅，避免线路排序变化时误换用户分配。
  const identity = source.url
    ? `url\u0000${source.url.trim()}\u0000${source.name.trim()}`
    : `content\u0000${source.name.trim()}\u0000${source.content.length}\u0000${contentSample(source.content)}`;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(identity));
  return Array.from(new Uint8Array(digest).slice(0, 16), (byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("");
}

/** 按用户固定分配一条线路；容量检查和写入在同一条 D1 语句中完成。 */
export async function getOrAssignBalancedSubscription(
  env: Env,
  group: SubscriptionGroup,
  userId: number,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<SubscriptionSource | null> {
  const enabledSources = group.subscriptions.filter((source) =>
    source.enabled && (source.content || source.url)
  );
  if (!enabledSources.length) return null;

  const candidates: AllocationCandidate[] = await Promise.all(enabledSources.map(async (source, index) => ({
    source,
    sourceKey: await sourceAssignmentKey(source),
    sortOrder: index,
  })));
  const sourceByKey = new Map<string, SubscriptionSource>();
  candidates.forEach((candidate) => {
    if (!sourceByKey.has(candidate.sourceKey)) {
      sourceByKey.set(candidate.sourceKey, candidate.source);
    }
  });

  const db = await ensureUserSchema(env);
  const resetSeconds = group.resetPeriodDays * 24 * 60 * 60;
  const activeAfter = nowSeconds - resetSeconds;
  const existing = await db.prepare(
    `SELECT source_id, source_key, assigned_at
     FROM subscription_group_assignments_mb
     WHERE group_id = ? AND user_id = ?`,
  ).bind(group.id, userId).first<SubscriptionAssignmentRow>();
  if (existing && Number(existing.assigned_at) > activeAfter) {
    const assignedSource = sourceByKey.get(existing.source_key);
    if (assignedSource) return assignedSource;
  }

  const candidateJson = JSON.stringify(candidates.map((candidate) => ({
    id: candidate.source.id,
    key: candidate.sourceKey,
    order: candidate.sortOrder,
  })));
  const allocated = await db.prepare(
    `WITH candidate_sources AS (
       SELECT
         json_extract(value, '$.id') AS source_id,
         json_extract(value, '$.key') AS source_key,
         CAST(json_extract(value, '$.order') AS INTEGER) AS sort_order
       FROM json_each(?)
     ),
     current_assignment AS (
       SELECT source_id, source_key
       FROM subscription_group_assignments_mb
       WHERE group_id = ? AND user_id = ?
     ),
     active_counts AS (
       SELECT source_key, COUNT(*) AS assigned_count
       FROM subscription_group_assignments_mb
       WHERE group_id = ? AND assigned_at > ?
       GROUP BY source_key
     ),
     eligible_sources AS (
       SELECT
         candidate_sources.source_id,
         candidate_sources.source_key,
         candidate_sources.sort_order,
         COALESCE(active_counts.assigned_count, 0) AS assigned_count
       FROM candidate_sources
       LEFT JOIN active_counts ON active_counts.source_key = candidate_sources.source_key
       WHERE COALESCE(active_counts.assigned_count, 0) < ?
     ),
     selected_source AS (
       SELECT
         eligible_sources.source_id,
         eligible_sources.source_key
       FROM eligible_sources
       LEFT JOIN current_assignment ON 1 = 1
       ORDER BY
         CASE WHEN eligible_sources.source_key = current_assignment.source_key THEN 1 ELSE 0 END,
         eligible_sources.assigned_count,
         eligible_sources.sort_order
       LIMIT 1
     )
     INSERT INTO subscription_group_assignments_mb
       (group_id, user_id, source_id, source_key, assigned_at)
     SELECT ?, ?, selected_source.source_id, selected_source.source_key, ?
     FROM selected_source
     WHERE 1
     ON CONFLICT (group_id, user_id) DO UPDATE SET
       source_id = excluded.source_id,
       source_key = excluded.source_key,
       assigned_at = excluded.assigned_at
     WHERE subscription_group_assignments_mb.assigned_at <= ?
       OR NOT EXISTS (
         SELECT 1 FROM candidate_sources
         WHERE candidate_sources.source_key = subscription_group_assignments_mb.source_key
       )
     RETURNING source_id, source_key, assigned_at`,
  ).bind(
    candidateJson,
    group.id,
    userId,
    group.id,
    activeAfter,
    group.maxUsersPerSubscription,
    group.id,
    userId,
    nowSeconds,
    activeAfter,
  ).first<SubscriptionAssignmentRow>();

  if (allocated) return sourceByKey.get(allocated.source_key) || null;

  // 并发请求可能已替同一用户完成分配；读取落库结果以保证返回值稳定。
  const settled = await db.prepare(
    `SELECT source_id, source_key, assigned_at
     FROM subscription_group_assignments_mb
     WHERE group_id = ? AND user_id = ?`,
  ).bind(group.id, userId).first<SubscriptionAssignmentRow>();
  if (!settled || Number(settled.assigned_at) <= activeAfter) return null;
  return sourceByKey.get(settled.source_key) || null;
}
