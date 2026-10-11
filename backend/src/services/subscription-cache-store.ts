import type { Env } from "../types/env.ts";

export interface SubscriptionCacheMetadata {
  sourceUrl: string;
  contentCachedAt: number | null;
  trafficFetchedAt: number | null;
  traffic: unknown;
}

interface SubscriptionCacheRow {
  source_url: string;
  content_cached_at: number | null;
  traffic_fetched_at: number | null;
  traffic_json: string | null;
}

function timestamp(value: unknown) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

export async function loadSubscriptionCacheMetadata(env: Env, sourceId: string) {
  if (!env.SUBSCRIPTIONS_DB) return null;
  const row = await env.SUBSCRIPTIONS_DB.prepare(
    `SELECT source_url, content_cached_at, traffic_fetched_at, traffic_json
      FROM subscription_runtime_cache WHERE source_id = ?`,
  ).bind(sourceId).first<SubscriptionCacheRow>();
  if (!row) return null;

  let traffic: unknown = null;
  if (row.traffic_json) {
    try {
      traffic = JSON.parse(row.traffic_json);
    } catch {
      traffic = null;
    }
  }
  return {
    sourceUrl: row.source_url,
    contentCachedAt: timestamp(row.content_cached_at),
    trafficFetchedAt: timestamp(row.traffic_fetched_at),
    traffic,
  } satisfies SubscriptionCacheMetadata;
}

export async function saveSubscriptionCacheMetadata(
  env: Env,
  sourceId: string,
  sourceUrl: string,
  update: {
    contentCachedAt?: number;
    trafficFetchedAt?: number;
    traffic?: unknown;
  },
) {
  if (!env.SUBSCRIPTIONS_DB) return;
  const hasContentTimestamp = update.contentCachedAt !== undefined;
  const hasTraffic = update.trafficFetchedAt !== undefined;
  const trafficJson = hasTraffic && update.traffic != null
    ? JSON.stringify(update.traffic)
    : null;
  await env.SUBSCRIPTIONS_DB.prepare(
    `INSERT INTO subscription_runtime_cache
      (source_id, source_url, content_cached_at, traffic_json, traffic_fetched_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(source_id) DO UPDATE SET
       content_cached_at = CASE
         WHEN subscription_runtime_cache.source_url <> excluded.source_url OR ? = 1
           THEN excluded.content_cached_at
         ELSE subscription_runtime_cache.content_cached_at
       END,
       traffic_json = CASE
         WHEN subscription_runtime_cache.source_url <> excluded.source_url OR ? = 1
           THEN excluded.traffic_json
         ELSE subscription_runtime_cache.traffic_json
       END,
       traffic_fetched_at = CASE
         WHEN subscription_runtime_cache.source_url <> excluded.source_url OR ? = 1
           THEN excluded.traffic_fetched_at
         ELSE subscription_runtime_cache.traffic_fetched_at
       END,
       source_url = excluded.source_url`,
  ).bind(
    sourceId,
    sourceUrl,
    hasContentTimestamp ? timestamp(update.contentCachedAt) : null,
    trafficJson,
    hasTraffic ? timestamp(update.trafficFetchedAt) : null,
    hasContentTimestamp ? 1 : 0,
    hasTraffic ? 1 : 0,
    hasTraffic ? 1 : 0,
  ).run();
}
