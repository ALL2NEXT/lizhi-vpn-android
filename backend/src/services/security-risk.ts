import type { Env } from "../types/env.ts";
import { resolveAdminBasePath } from "../config.ts";
import { beijingTimestamp } from "../domain/time.ts";
import { HONEYPOT_PATHS, isHoneypotPath } from "./honeypot.ts";
import { ensureUserSchema } from "./user-store.ts";

const HONEYPOT_ADMIN_RULE_ID = "honeypot-admin-path";
const HONEYPOT_ADMIN_RULE_NAME = "蜜罐后台地址访问";
const MAX_RULE_HIT_ROWS = 2000;
const MAX_ACCOUNT_ASSOCIATIONS = 5000;
const ACCOUNT_ASSOCIATION_DAYS = 30;
const ALLOWED_WINDOW_HOURS = new Set([24, 72, 168]);

interface RuleHitRow {
  ip: string;
  path: string;
  method: string;
  status: number | null;
  hit_count: number;
  first_seen_at: string;
  last_seen_at: string;
}

interface RuleHitAccountRow {
  ip: string;
  user_uid: string;
  account_status: string;
  request_count: number;
  last_seen_at: string;
}

function normalizeWindowHours(value: unknown) {
  const hours = Number(value);
  if (!ALLOWED_WINDOW_HOURS.has(hours)) throw new Error("invalid_security_risk_window");
  return hours;
}

function hitRowsCte(adminBasePath: string | null, windowStart: string) {
  const paths = HONEYPOT_PATHS.map(() => "?").join(", ");
  const skipAdminPath = adminBasePath ? "AND l.path <> ?" : "";
  const sql = `WITH all_hits AS (
    SELECT occurred_at, rule_id, method, path, status, ip, request_id
    FROM security_risk_rule_hits_mb
    WHERE rule_id = ? AND occurred_at >= ? AND ip <> ''
    UNION ALL
    SELECT l.occurred_at, '${HONEYPOT_ADMIN_RULE_ID}', upper(l.method), lower(l.path),
      l.status, l.ip, l.request_id
    FROM system_logs_mb l
    WHERE l.category = 'request' AND l.occurred_at >= ?
      AND lower(l.path) IN (${paths}) AND l.ip <> ''
      ${skipAdminPath}
      AND NOT EXISTS (
        SELECT 1 FROM security_risk_rule_hits_mb h
        WHERE l.request_id <> '' AND h.request_id = l.request_id
          AND h.rule_id = '${HONEYPOT_ADMIN_RULE_ID}'
      )
  )`;
  const bindings: Array<string | number> = [
    HONEYPOT_ADMIN_RULE_ID,
    windowStart,
    windowStart,
    ...HONEYPOT_PATHS,
    ...(adminBasePath ? [adminBasePath] : []),
  ];
  return { sql, bindings };
}

/** 记录蜜罐路径命中；仅保存命中时间、IP、方法和路径，不保存请求正文或凭据。 */
export async function recordHoneypotRuleHit(
  env: Env,
  hit: { method: string; path: string; status: number; ip: string; requestId: string; occurredAt: string },
) {
  if (!env.USERS_DB || !isHoneypotPath(hit.path)) return;
  const originalPath = String(hit.path || "");
  const adminBasePath = resolveAdminBasePath(env.ADMIN_PATH);
  if (adminBasePath && originalPath === adminBasePath) return;
  const path = originalPath.toLowerCase().slice(0, 300);
  const ip = String(hit.ip || "").slice(0, 64);
  if (!ip) return;

  try {
    const db = await ensureUserSchema(env);
    await db.prepare(
      `INSERT INTO security_risk_rule_hits_mb (
         occurred_at, rule_id, method, path, status, ip, request_id
       ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      hit.occurredAt || beijingTimestamp(),
      HONEYPOT_ADMIN_RULE_ID,
      String(hit.method || "GET").toUpperCase().slice(0, 16),
      path,
      Number.isInteger(hit.status) ? hit.status : 500,
      ip,
      String(hit.requestId || "").slice(0, 96),
    ).run();
  } catch (error) {
    console.error(JSON.stringify({
      message: "security_risk_rule_hit_write_failed",
      error: error instanceof Error ? error.message : String(error),
    }));
  }
}

/** 查询直接规则命中，并将同 IP 的已识别账户作为关联线索返回。 */
export async function readSecurityRuleHits(env: Env, requestedWindowHours: unknown = 24) {
  const windowHours = normalizeWindowHours(requestedWindowHours);
  const now = Date.now();
  const windowStart = beijingTimestamp(new Date(now - windowHours * 60 * 60 * 1000));
  const accountWindowStart = beijingTimestamp(new Date(now - ACCOUNT_ASSOCIATION_DAYS * 24 * 60 * 60 * 1000));
  const adminBasePath = resolveAdminBasePath(env.ADMIN_PATH);
  const { sql: hitCte, bindings } = hitRowsCte(adminBasePath, windowStart);
  const db = await ensureUserSchema(env);
  const [hitResult, accountResult] = await Promise.all([
    db.prepare(
      `${hitCte}
       SELECT ip, path, method, status, COUNT(*) AS hit_count,
         MIN(occurred_at) AS first_seen_at, MAX(occurred_at) AS last_seen_at
       FROM all_hits
       WHERE occurred_at >= ? AND ip <> ''
       GROUP BY ip, path, method, status
       ORDER BY last_seen_at DESC, hit_count DESC
       LIMIT ?`,
    ).bind(...bindings, windowStart, MAX_RULE_HIT_ROWS + 1).all<RuleHitRow>(),
    db.prepare(
      `${hitCte}, hit_ips AS (
         SELECT DISTINCT ip FROM all_hits WHERE occurred_at >= ? AND ip <> ''
       )
       SELECT l.ip, l.user_uid, COALESCE(MAX(u.status), 'deleted') AS account_status,
         COUNT(*) AS request_count, MAX(l.occurred_at) AS last_seen_at
       FROM hit_ips h
       JOIN system_logs_mb l ON l.ip = h.ip
       LEFT JOIN users_mb u ON u.uid = l.user_uid
       WHERE l.category = 'request' AND l.occurred_at >= ?
         AND l.status BETWEEN 200 AND 299 AND l.user_uid <> '' AND l.device_id <> ''
       GROUP BY l.ip, l.user_uid
       ORDER BY last_seen_at DESC, request_count DESC
       LIMIT ?`,
    ).bind(...bindings, windowStart, accountWindowStart, MAX_ACCOUNT_ASSOCIATIONS + 1).all<RuleHitAccountRow>(),
  ]);

  const hitRows = hitResult.results || [];
  const accountRows = accountResult.results || [];
  const hitsTruncated = hitRows.length > MAX_RULE_HIT_ROWS;
  const accountsTruncated = accountRows.length > MAX_ACCOUNT_ASSOCIATIONS;
  const hitsByIp = new Map<string, {
    ip: string;
    ruleId: string;
    ruleName: string;
    hitCount: number;
    firstSeenAt: string;
    lastSeenAt: string;
    paths: Array<{ method: string; path: string; status: number | null; hitCount: number; firstSeenAt: string; lastSeenAt: string }>;
    accounts: Array<{ uid: string; status: string; requestCount: number; lastSeenAt: string }>;
  }>();

  for (const row of hitRows.slice(0, MAX_RULE_HIT_ROWS)) {
    const ip = String(row.ip || "");
    if (!ip) continue;
    let hit = hitsByIp.get(ip);
    if (!hit) {
      hit = {
        ip,
        ruleId: HONEYPOT_ADMIN_RULE_ID,
        ruleName: HONEYPOT_ADMIN_RULE_NAME,
        hitCount: 0,
        firstSeenAt: String(row.first_seen_at || ""),
        lastSeenAt: String(row.last_seen_at || ""),
        paths: [],
        accounts: [],
      };
      hitsByIp.set(ip, hit);
    }
    const hitCount = Math.max(0, Number(row.hit_count) || 0);
    hit.hitCount += hitCount;
    const firstSeenAt = String(row.first_seen_at || "");
    const lastSeenAt = String(row.last_seen_at || "");
    if (firstSeenAt && (!hit.firstSeenAt || firstSeenAt < hit.firstSeenAt)) hit.firstSeenAt = firstSeenAt;
    if (lastSeenAt && lastSeenAt > hit.lastSeenAt) hit.lastSeenAt = lastSeenAt;
    hit.paths.push({
      method: String(row.method || ""),
      path: String(row.path || ""),
      status: row.status === null ? null : Number(row.status),
      hitCount,
      firstSeenAt,
      lastSeenAt,
    });
  }

  for (const row of accountRows.slice(0, MAX_ACCOUNT_ASSOCIATIONS)) {
    const hit = hitsByIp.get(String(row.ip || ""));
    const uid = String(row.user_uid || "");
    if (!hit || !uid) continue;
    hit.accounts.push({
      uid,
      status: String(row.account_status || "deleted"),
      requestCount: Math.max(0, Number(row.request_count) || 0),
      lastSeenAt: String(row.last_seen_at || ""),
    });
  }

  const hits = Array.from(hitsByIp.values())
    .map((hit) => ({
      ...hit,
      paths: hit.paths.sort((left, right) => right.lastSeenAt.localeCompare(left.lastSeenAt)),
      accounts: hit.accounts.sort((left, right) => right.lastSeenAt.localeCompare(left.lastSeenAt)),
    }))
    .sort((left, right) => right.lastSeenAt.localeCompare(left.lastSeenAt));

  return {
    ok: true,
    windowHours,
    windowStart,
    generatedAt: beijingTimestamp(),
    ruleHits: hits,
    coverage: {
      ruleHitRowsTruncated: hitsTruncated,
      accountRowsTruncated: accountsTruncated,
      accountAssociationDays: ACCOUNT_ASSOCIATION_DAYS,
      accountAssociationNote: "同 IP 关联账户来自近期成功识别请求，仅为关联线索，不代表该账户访问过蜜罐地址。",
    },
  };
}

export async function pruneSecurityRuleHits(env: Env) {
  if (!env.USERS_DB) return 0;
  const db = await ensureUserSchema(env);
  const cutoff = beijingTimestamp(new Date(Date.now() - ACCOUNT_ASSOCIATION_DAYS * 24 * 60 * 60 * 1000));
  const result = await db.prepare(
    "DELETE FROM security_risk_rule_hits_mb WHERE occurred_at < ?",
  ).bind(cutoff).run();
  return Number(result.meta?.changes) || 0;
}
