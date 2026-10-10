import type { Env } from "../types/env.ts";
import type { SubscriptionGroup, SubscriptionSource } from "../types/models.ts";

import {
  MAX_D1_INLINE_SUBSCRIPTION_BYTES,
  SUBSCRIPTION_CONTENT_KEY_PREFIX,
} from "../config.ts";
import { createSubscriptionAssignmentKey } from "./subscription-identity.ts";

const LEGACY_IMPORT_KEY = "legacy_kv_imported";
const MAX_JSON_BATCH_BYTES = 1_500_000;
interface GroupRow {
  id: string;
  group_order: number;
  name: string;
  group_type: string;
  enabled: number;
  allowed_statuses_json: string;
  allowed_account_types_json: string;
  allowed_membership_types_json: string;
  update_enabled: number;
  cache_minutes: number;
  min_app_version: string;
  max_app_version: string;
  max_users_per_subscription: number;
  reset_period_days: number;
}

interface SourceRow {
  id: string;
  group_id: string;
  source_order: number;
  name: string;
  source_type: string;
  url: string;
  content: string;
  content_key: string;
  assignment_key: string;
  enabled: number;
  auto_rename: number;
  deduplicate: number;
  remark: string;
}

function parseJsonArray(value: string, allowed: string[]) {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed)
      ? parsed.filter((item): item is string => typeof item === "string" && allowed.includes(item))
      : [];
  } catch {
    return [];
  }
}

function isLocalSource(source: SubscriptionSource) {
  return source.sourceType === "content" || Boolean(source.content);
}

function bytes(value: string) {
  return new TextEncoder().encode(value).byteLength;
}

async function contentHash(value: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (part) => part.toString(16).padStart(2, "0")).join("");
}

function jsonBatchStatements(
  db: D1Database,
  sql: string,
  rows: Array<Record<string, string | number>>,
  directStatement?: (row: Record<string, string | number>) => D1PreparedStatement,
) {
  const statements: D1PreparedStatement[] = [];
  let currentRows: Array<Record<string, string | number>> = [];
  let currentBytes = 2;
  const flush = () => {
    if (!currentRows.length) return;
    statements.push(db.prepare(sql).bind(JSON.stringify(currentRows)));
    currentRows = [];
    currentBytes = 2;
  };

  for (const row of rows) {
    const rowBytes = bytes(JSON.stringify(row));
    const separatorBytes = currentRows.length ? 1 : 0;
    if (currentRows.length && currentBytes + separatorBytes + rowBytes > MAX_JSON_BATCH_BYTES) {
      flush();
    }
    if (rowBytes + 2 > MAX_JSON_BATCH_BYTES) {
      if (!directStatement) throw new Error("subscription_metadata_row_too_large");
      flush();
      statements.push(directStatement(row));
      continue;
    }
    currentRows.push(row);
    currentBytes += (currentRows.length > 1 ? 1 : 0) + rowBytes;
  }
  flush();
  return statements;
}

async function subscriptionContentKey(source: SubscriptionSource, content: string) {
  return `${SUBSCRIPTION_CONTENT_KEY_PREFIX}${source.id}:${await contentHash(content)}`;
}

export async function hasImportedLegacySubscriptions(env: Env) {
  if (!env.SUBSCRIPTIONS_DB) return false;
  const row = await env.SUBSCRIPTIONS_DB.prepare(
    "SELECT value FROM subscription_store_meta WHERE key = ?",
  ).bind(LEGACY_IMPORT_KEY).first<{ value: string }>();
  return row?.value === "1";
}

export async function loadSubscriptionGroups(env: Env, includeContent = false): Promise<SubscriptionGroup[] | null> {
  const db = env.SUBSCRIPTIONS_DB;
  if (!db || !(await hasImportedLegacySubscriptions(env))) return null;

  const sourceQuery = includeContent
    ? `SELECT id, group_id, source_order, name, source_type, url, content, content_key,
         assignment_key,
         enabled, auto_rename, deduplicate, remark
       FROM subscription_sources ORDER BY group_id, source_order, id`
    : `SELECT id, group_id, source_order, name, source_type, url, '' AS content, content_key,
         assignment_key,
         enabled, auto_rename, deduplicate, remark
       FROM subscription_sources ORDER BY group_id, source_order, id`;
  const [groupResult, sourceResult] = await db.batch([
    db.prepare(`SELECT id, group_order, name, group_type, enabled,
        allowed_statuses_json, allowed_account_types_json, allowed_membership_types_json,
        update_enabled, cache_minutes,
        min_app_version, max_app_version, max_users_per_subscription, reset_period_days
      FROM subscription_groups ORDER BY group_order, id`),
    db.prepare(sourceQuery),
  ]);

  const sourceRows = (sourceResult.results || []) as SourceRow[];
  const sourcesByGroup = new Map<string, SubscriptionSource[]>();
  for (const row of sourceRows) {
    const sources = sourcesByGroup.get(row.group_id) || [];
    sources.push({
      id: row.id,
      name: row.name,
      url: row.url,
      sourceType: row.source_type === "content" ? "content" : "url",
      contentKey: row.content_key || undefined,
      assignmentKey: row.assignment_key || undefined,
      enabled: row.enabled === 1,
      autoRename: row.auto_rename === 1,
      deduplicate: row.deduplicate === 1,
      content: row.content || "",
      remark: row.remark || "",
    });
    sourcesByGroup.set(row.group_id, sources);
  }

  const groups = ((groupResult.results || []) as GroupRow[]).map((row) => ({
    id: row.id,
    name: row.name,
    groupType: row.group_type === "load_balanced" ? "load_balanced" as const : "standard" as const,
    enabled: row.enabled === 1,
    allowedStatuses: parseJsonArray(row.allowed_statuses_json, ["normal", "banned"]) as SubscriptionGroup["allowedStatuses"],
    allowedAccountTypes: parseJsonArray(row.allowed_account_types_json, ["guest", "formal"]) as SubscriptionGroup["allowedAccountTypes"],
    allowedMembershipTypes: parseJsonArray(row.allowed_membership_types_json, ["member", "non_member"]) as SubscriptionGroup["allowedMembershipTypes"],
    updateEnabled: row.update_enabled === 1,
    cacheMinutes: row.cache_minutes,
    minAppVersion: row.min_app_version,
    maxAppVersion: row.max_app_version,
    maxUsersPerSubscription: row.max_users_per_subscription,
    resetPeriodDays: row.reset_period_days,
    subscriptions: sourcesByGroup.get(row.id) || [],
  }));
  if (includeContent && env.CONFIG) {
    const keyedSources = groups.flatMap((group) => group.subscriptions)
      .filter((source) => source.sourceType === "content" && source.contentKey);
    for (let offset = 0; offset < keyedSources.length; offset += 40) {
      await Promise.all(keyedSources.slice(offset, offset + 40).map(async (source) => {
        const content = await env.CONFIG!.get<string>(source.contentKey!);
        if (typeof content !== "string") throw new Error("subscription_content_unavailable");
        source.content = content;
      }));
    }
  }
  return groups;
}

async function prepareSources(env: Env, groups: SubscriptionGroup[], importLegacyOnly: boolean) {
  const config = env.CONFIG;
  if (!config) throw new Error("Missing KV binding CONFIG");
  const db = env.SUBSCRIPTIONS_DB!;
  const pendingGuard = `EXISTS (SELECT 1 FROM subscription_store_meta
    WHERE key = '${LEGACY_IMPORT_KEY}' AND value = 'pending')`;
  const oldKeys = (await db.prepare(
    "SELECT content_key FROM subscription_sources WHERE content_key <> ''",
  ).all<{ content_key: string }>()).results.map((row) => row.content_key);
  const newKeys = new Set<string>();
  const groupRows: Array<Record<string, string | number>> = [];
  const sourceRows: Array<Record<string, string | number>> = [];

  for (const [groupIndex, group] of groups.entries()) {
    groupRows.push({
      id: group.id,
      group_order: groupIndex,
      name: group.name,
      group_type: group.groupType,
      // Retain the legacy D1 column for deployed databases; all subscriptions are plaintext.
      encryption_mode: "none",
      enabled: group.enabled ? 1 : 0,
      allowed_statuses_json: JSON.stringify(group.allowedStatuses),
      allowed_account_types_json: JSON.stringify(group.allowedAccountTypes),
      allowed_membership_types_json: JSON.stringify(group.allowedMembershipTypes),
      update_enabled: group.updateEnabled ? 1 : 0,
      cache_minutes: group.cacheMinutes,
      min_app_version: group.minAppVersion,
      max_app_version: group.maxAppVersion,
      max_users_per_subscription: group.maxUsersPerSubscription,
      reset_period_days: group.resetPeriodDays,
    });

    for (const [sourceIndex, source] of group.subscriptions.entries()) {
      const local = isLocalSource(source);
      let content = local ? source.content || "" : "";
      let contentKey = "";
      let assignmentKey = "";
      if (local && content) {
        assignmentKey = await createSubscriptionAssignmentKey({ ...source, assignmentKey: undefined });
        source.assignmentKey = assignmentKey;
        if (bytes(content) <= MAX_D1_INLINE_SUBSCRIPTION_BYTES) {
          contentKey = "";
          source.contentKey = undefined;
        } else {
          contentKey = await subscriptionContentKey(source, content);
          if (source.contentKey !== contentKey) await config.put(contentKey, content);
          source.contentKey = contentKey;
          newKeys.add(contentKey);
          content = "";
        }
      } else if (local && source.contentKey) {
        contentKey = source.contentKey;
        newKeys.add(contentKey);
        assignmentKey = source.assignmentKey || "";
      } else if (local) {
        source.contentKey = undefined;
        assignmentKey = source.assignmentKey || "";
      }
      sourceRows.push({
        id: source.id,
        group_id: group.id,
        source_order: sourceIndex,
        name: source.name,
        source_type: local ? "content" : "url",
        url: local ? "" : source.url,
        content,
        content_key: contentKey,
        assignment_key: assignmentKey,
        enabled: source.enabled ? 1 : 0,
        auto_rename: source.autoRename ? 1 : 0,
        deduplicate: source.deduplicate ? 1 : 0,
        remark: source.remark,
      });
    }
  }

  const groupInsertSql = `INSERT INTO subscription_groups
    (id, group_order, name, group_type, encryption_mode, enabled, allowed_statuses_json,
     allowed_account_types_json, allowed_membership_types_json, update_enabled, cache_minutes, min_app_version,
     max_app_version, max_users_per_subscription, reset_period_days)
    SELECT json_extract(j.value, '$.id'), json_extract(j.value, '$.group_order'),
      json_extract(j.value, '$.name'), json_extract(j.value, '$.group_type'),
      json_extract(j.value, '$.encryption_mode'),
      json_extract(j.value, '$.enabled'), json_extract(j.value, '$.allowed_statuses_json'),
      json_extract(j.value, '$.allowed_account_types_json'),
      json_extract(j.value, '$.allowed_membership_types_json'), json_extract(j.value, '$.update_enabled'),
      json_extract(j.value, '$.cache_minutes'), json_extract(j.value, '$.min_app_version'),
      json_extract(j.value, '$.max_app_version'), json_extract(j.value, '$.max_users_per_subscription'),
      json_extract(j.value, '$.reset_period_days')
    FROM json_each(?) AS j${importLegacyOnly ? ` WHERE ${pendingGuard}` : ""}`;
  const sourceInsertSql = `INSERT INTO subscription_sources
    (id, group_id, source_order, name, source_type, url, content, content_key,
     assignment_key, enabled, auto_rename, deduplicate, remark)
    SELECT json_extract(j.value, '$.id'), json_extract(j.value, '$.group_id'),
      json_extract(j.value, '$.source_order'), json_extract(j.value, '$.name'),
      json_extract(j.value, '$.source_type'), json_extract(j.value, '$.url'),
      json_extract(j.value, '$.content'), json_extract(j.value, '$.content_key'),
      json_extract(j.value, '$.assignment_key'), json_extract(j.value, '$.enabled'),
      json_extract(j.value, '$.auto_rename'), json_extract(j.value, '$.deduplicate'),
      json_extract(j.value, '$.remark')
    FROM json_each(?) AS j${importLegacyOnly ? ` WHERE ${pendingGuard}` : ""}`;
  const directSourceSql = importLegacyOnly
    ? `INSERT INTO subscription_sources
        (id, group_id, source_order, name, source_type, url, content, content_key,
         assignment_key, enabled, auto_rename, deduplicate, remark)
       SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE ${pendingGuard}`
    : `INSERT INTO subscription_sources
        (id, group_id, source_order, name, source_type, url, content, content_key,
         assignment_key, enabled, auto_rename, deduplicate, remark)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;
  const statements = [
    ...jsonBatchStatements(db, groupInsertSql, groupRows),
    ...jsonBatchStatements(db, sourceInsertSql, sourceRows, (row) => db.prepare(directSourceSql).bind(
      row.id,
      row.group_id,
      row.source_order,
      row.name,
      row.source_type,
      row.url,
      row.content,
      row.content_key,
      row.assignment_key,
      row.enabled,
      row.auto_rename,
      row.deduplicate,
      row.remark,
    )),
  ];
  return { statements, oldKeys, newKeys };
}

export async function saveSubscriptionGroups(
  env: Env,
  groups: SubscriptionGroup[],
  options: { importLegacyOnly?: boolean } = {},
) {
  const db = env.SUBSCRIPTIONS_DB;
  if (!db) return;
  const config = env.CONFIG;
  if (!config) throw new Error("Missing KV binding CONFIG");
  const importLegacyOnly = options.importLegacyOnly === true;
  const { statements, oldKeys, newKeys } = await prepareSources(env, groups, importLegacyOnly);
  const pendingGuard = `EXISTS (SELECT 1 FROM subscription_store_meta
    WHERE key = '${LEGACY_IMPORT_KEY}' AND value = 'pending')`;

  if (importLegacyOnly) {
    const guarded = [
      db.prepare(`INSERT OR IGNORE INTO subscription_store_meta(key, value)
        VALUES ('${LEGACY_IMPORT_KEY}', 'pending')`),
      db.prepare(`DELETE FROM subscription_sources WHERE ${pendingGuard}`),
      db.prepare(`DELETE FROM subscription_groups WHERE ${pendingGuard}`),
      ...statements,
      db.prepare(`UPDATE subscription_store_meta SET value = '1'
        WHERE key = '${LEGACY_IMPORT_KEY}' AND value = 'pending'`),
    ];
    await db.batch(guarded);
  } else {
    await db.batch([
      db.prepare("DELETE FROM subscription_sources"),
      db.prepare("DELETE FROM subscription_groups"),
      ...statements,
      db.prepare(`INSERT INTO subscription_store_meta(key, value) VALUES (?, '1')
        ON CONFLICT(key) DO UPDATE SET value = '1'`).bind(LEGACY_IMPORT_KEY),
    ]);
  }

  if (!importLegacyOnly) {
    for (const key of oldKeys) {
      if (key.startsWith(SUBSCRIPTION_CONTENT_KEY_PREFIX) && !newKeys.has(key)) {
        await config.delete(key);
      }
    }
  }
}

export async function readSubscriptionContent(env: Env, source: SubscriptionSource) {
  if (source.content) return source.content;
  if (source.sourceType !== "content") return "";
  if (!env.SUBSCRIPTIONS_DB) {
    throw new Error("subscription_content_unavailable");
  }
  if (source.contentKey) {
    const content = await env.CONFIG?.get<string>(source.contentKey);
    if (typeof content === "string") return content;
    throw new Error("subscription_content_unavailable");
  }
  const row = await env.SUBSCRIPTIONS_DB.prepare(
    "SELECT content FROM subscription_sources WHERE id = ? AND source_type = 'content'",
  ).bind(source.id).first<{ content: string }>();
  if (typeof row?.content === "string") return row.content;
  throw new Error("subscription_content_unavailable");
}
