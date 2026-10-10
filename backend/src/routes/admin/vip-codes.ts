import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";

import { readJsonObject } from "../../http/request.ts";

const MAX_VIP_CODE_COUNT = 100;
const MAX_VIP_DURATION_DAYS = 36500;

/* 管理员发放单次兑换的 VIP 卡密，并查询发放和兑换状态。 */
export function createAdminVipCodeRoutes(deps: Pick<
  RouteDependencies,
  | "beijingTimestamp"
  | "cleanText"
  | "ensureUserSchema"
  | "json"
  | "randomRegistrationCode"
>) {
  const { beijingTimestamp, cleanText, ensureUserSchema, json, randomRegistrationCode } = deps;

  return async function handleAdminVipCodes(path: string, request: Request, env: Env) {
    if (path !== "/api/vip-codes") return null;
    const db = await ensureUserSchema(env);

    if (request.method === "GET") {
      const url = new URL(request.url);
      const requestedLimit = Number(url.searchParams.get("limit"));
      const limit = Number.isInteger(requestedLimit)
        ? Math.min(Math.max(requestedLimit, 1), 500)
        : 200;
      const query = await db.prepare(
        `SELECT id, code, duration_days, created_at, used_at, used_by_uid
           FROM vip_activation_codes_mb
          ORDER BY created_at DESC, id DESC
          LIMIT ?`,
      ).bind(limit).all<{
        id: number;
        code: string;
        duration_days: number;
        created_at: string;
        used_at: string | null;
        used_by_uid: string;
      }>();
      return json({
        ok: true,
        codes: (query.results || []).map((row) => ({
          id: Number(row.id),
          code: cleanText(row.code),
          durationDays: Number(row.duration_days),
          createdAt: cleanText(row.created_at),
          usedAt: cleanText(row.used_at),
          usedByUid: cleanText(row.used_by_uid),
          used: Boolean(row.used_at),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    if (request.method !== "POST") return null;
    const body = await readJsonObject(request);
    const count = Number(body && body.count);
    const durationDays = Number(body && body.durationDays);
    if (!Number.isInteger(count) || count < 1 || count > MAX_VIP_CODE_COUNT) {
      return json({ ok: false, error: "invalid_vip_code_count" }, 400);
    }
    if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > MAX_VIP_DURATION_DAYS) {
      return json({ ok: false, error: "invalid_vip_duration_days" }, 400);
    }

    const codes = new Set<string>();
    while (codes.size < count) codes.add(`VIP${randomRegistrationCode().slice(3)}`);
    const createdAt = beijingTimestamp();
    await db.batch(Array.from(codes, (code) => db.prepare(
      "INSERT INTO vip_activation_codes_mb (code, duration_days, created_at) VALUES (?, ?, ?)",
    ).bind(code, durationDays, createdAt)));
    return json({ ok: true, codes: Array.from(codes), durationDays, createdAt });
  };
}
