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
  const conditions: string[] = [];
  const bindings: string[] = [];
  if (ip) {
    conditions.push("l.ip = ?");
    bindings.push(ip);
  }
  if (uid) {
    conditions.push("l.user_uid = ?");
    bindings.push(uid);
  }
  const where = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
  const db = await ensureUserSchema(env);
  const results = await db.batch([
    db.prepare(
      `SELECT COUNT(*) AS relation_count, COUNT(DISTINCT l.ip) AS ip_count,
         COUNT(DISTINCT l.user_uid) AS user_count, SUM(l.request_count) AS request_count
       FROM user_ip_links_mb l ${where}`,
    ).bind(...bindings),
    db.prepare(
      `SELECT l.*, u.nickname, u.status AS account_status
       FROM user_ip_links_mb l LEFT JOIN users_mb u ON u.id = l.user_id
       ${where}
       ORDER BY l.last_seen_at DESC, l.user_uid ASC, l.ip ASC LIMIT ? OFFSET ?`,
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
