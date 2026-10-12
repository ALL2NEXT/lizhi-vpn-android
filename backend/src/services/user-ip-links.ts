import type { Env } from "../types/env.ts";
import type { RequestUserIdentity } from "../types/models.ts";
import { normalizeAccountId, normalizeStoredUid } from "../domain/account.ts";
import { normalizeClientIp } from "../domain/ip.ts";
import { beijingTimestamp } from "../domain/time.ts";
import { ensureUserSchema } from "./user-store.ts";

interface UserIpLinkRow {
  user_uid: string;
  ip: string;
  nickname: string | null;
  account_status: string | null;
  first_seen_at: string;
  last_seen_at: string;
  last_device_id: string;
  request_count: number;
  subscription_count: number;
  login_count: number;
}

export async function writeUserIpLink(
  env: Env,
  identity: RequestUserIdentity | null,
  request: { ip: string; occurredAt: string; path: string; status: number },
) {
  // 失败请求和未验证的登录目标不能建立账户关联。
  if (!env.USERS_DB || !identity || request.status < 200 || request.status >= 300) return;
  const ip = normalizeClientIp(request.ip);
  const uid = normalizeStoredUid(identity.uid);
  const deviceId = normalizeAccountId(identity.deviceId);
  if (!ip || !uid || !deviceId || !Number.isInteger(identity.userId) || identity.userId <= 0) return;
  try {
    const db = await ensureUserSchema(env);
    await db.prepare(
      `INSERT INTO user_ip_links_mb (
         user_id, user_uid, ip, first_seen_at, last_seen_at, last_device_id,
         request_count, subscription_count, login_count
       ) VALUES ((SELECT id FROM users_mb WHERE id = ? AND uid = ?), ?, ?, ?, ?, ?, 1, ?, ?)
       ON CONFLICT(user_uid, ip) DO UPDATE SET
         user_id = excluded.user_id,
         first_seen_at = MIN(user_ip_links_mb.first_seen_at, excluded.first_seen_at),
         last_device_id = CASE WHEN excluded.last_seen_at >= user_ip_links_mb.last_seen_at
           THEN excluded.last_device_id ELSE user_ip_links_mb.last_device_id END,
         last_seen_at = MAX(user_ip_links_mb.last_seen_at, excluded.last_seen_at),
         request_count = user_ip_links_mb.request_count + 1,
         subscription_count = user_ip_links_mb.subscription_count + excluded.subscription_count,
         login_count = user_ip_links_mb.login_count + excluded.login_count`,
    ).bind(
      identity.userId, uid, uid, ip, request.occurredAt, request.occurredAt, deviceId,
      request.path.startsWith("/api/subscription/") ? 1 : 0,
      identity.source === "login" ? 1 : 0,
    ).run();
  } catch {
    console.error(JSON.stringify({ message: "user_ip_link_write_failed" }));
  }
}

export async function readUserIpLinks(
  env: Env,
  filters: { ip?: string; uid?: string; limit?: number; offset?: number } = {},
) {
  const ip = normalizeClientIp(filters.ip);
  const uid = normalizeStoredUid(filters.uid);
  if (filters.ip?.trim() && !ip) throw new Error("invalid_ip");
  if (filters.uid?.trim() && !uid) throw new Error("invalid_uid");
  const limit = Number.isFinite(filters.limit)
    ? Math.min(200, Math.max(1, Math.floor(filters.limit!))) : 50;
  const offset = Number.isFinite(filters.offset)
    ? Math.min(1_000_000, Math.max(0, Math.floor(filters.offset!))) : 0;
  const conditions = [
    "category = 'request'",
    "status BETWEEN 200 AND 299",
    "ip <> ''",
    "user_uid <> ''",
    "device_id <> ''",
  ];
  const bindings: string[] = [];
  if (ip) {
    conditions.push("ip = ?");
    bindings.push(ip);
  }
  if (uid) {
    conditions.push("user_uid = ?");
    bindings.push(uid);
  }
  const eligibleLogs = `eligible_logs AS (
    SELECT id, ip, user_uid, device_id, occurred_at, path, identity_source
    FROM system_logs_mb
    WHERE ${conditions.join(" AND ")}
  )`;
  const aggregatedLogs = `aggregated_logs AS (
    SELECT ip, user_uid, MIN(occurred_at) AS first_seen_at,
      MAX(occurred_at) AS last_seen_at, COUNT(*) AS request_count,
      SUM(CASE WHEN path LIKE '/api/subscription/%' THEN 1 ELSE 0 END) AS subscription_count,
      SUM(CASE WHEN identity_source = 'login' THEN 1 ELSE 0 END) AS login_count
    FROM eligible_logs
    GROUP BY ip, user_uid
  )`;
  const db = await ensureUserSchema(env);
  const results = await db.batch([
    db.prepare(
      `WITH ${eligibleLogs}, ${aggregatedLogs}
       SELECT COUNT(*) AS relation_count, COUNT(DISTINCT ip) AS ip_count,
         COUNT(DISTINCT user_uid) AS user_count, SUM(request_count) AS request_count
       FROM aggregated_logs`,
    ).bind(...bindings),
    db.prepare(
      `WITH ${eligibleLogs}, ${aggregatedLogs}, latest_devices AS (
         SELECT ip, user_uid, device_id,
           ROW_NUMBER() OVER (
             PARTITION BY ip, user_uid ORDER BY occurred_at DESC, id DESC
           ) AS device_rank
         FROM eligible_logs
       )
       SELECT aggregated_logs.ip, aggregated_logs.user_uid, users_mb.nickname,
         users_mb.status AS account_status, aggregated_logs.first_seen_at,
         aggregated_logs.last_seen_at, latest_devices.device_id AS last_device_id,
         aggregated_logs.request_count, aggregated_logs.subscription_count,
         aggregated_logs.login_count
       FROM aggregated_logs
       LEFT JOIN latest_devices ON latest_devices.ip = aggregated_logs.ip
         AND latest_devices.user_uid = aggregated_logs.user_uid
         AND latest_devices.device_rank = 1
       LEFT JOIN users_mb ON users_mb.uid = aggregated_logs.user_uid
       ORDER BY aggregated_logs.last_seen_at DESC, aggregated_logs.user_uid ASC,
         aggregated_logs.ip ASC LIMIT ? OFFSET ?`,
    ).bind(...bindings, limit, offset),
  ]);
  const stats = results[0].results?.[0] as Record<string, number | null> | undefined;
  const rows = results[1].results as unknown as UserIpLinkRow[] | undefined;
  return {
    ok: true,
    total: Number(stats?.relation_count) || 0,
    limit,
    offset,
    stats: {
      ipCount: Number(stats?.ip_count) || 0,
      userCount: Number(stats?.user_count) || 0,
      requestCount: Number(stats?.request_count) || 0,
    },
    relations: (rows || []).map((row) => ({
      ip: row.ip,
      userUid: row.user_uid,
      nickname: row.nickname || "",
      accountStatus: row.account_status || "deleted",
      firstSeenAt: row.first_seen_at,
      lastSeenAt: row.last_seen_at,
      lastDeviceId: row.last_device_id,
      requestCount: Number(row.request_count),
      subscriptionCount: Number(row.subscription_count),
      loginCount: Number(row.login_count),
    })),
    generatedAt: beijingTimestamp(),
  };
}
