import type { RegistrationCodeRow } from "../../types/database.ts";
import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";

import { readJsonObject } from "../../http/request.ts";

const MAX_REGISTRATION_CODE_USES = 1000;

/* 管理员可按指定次数发放注册码，并查询每次成功使用对应的账户。 */
export function createAdminRegistrationCodeRoutes(deps: Pick<
  RouteDependencies,
  | "beijingTimestamp"
  | "cleanText"
  | "ensureUserSchema"
  | "json"
  | "randomRegistrationCode"
>) {
  const {
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    randomRegistrationCode,
  } = deps;

  return async function handleAdminRegistrationCodes(path: string, request: Request, env: Env) {
    const usageMatch = path.match(/^\/api\/registration-codes\/(\d+)\/usages$/);
    if (path !== "/api/registration-codes" && !usageMatch) return null;

    const db = await ensureUserSchema(env);
    if (usageMatch) {
      if (request.method !== "GET") return null;
      const codeId = Number(usageMatch[1]);
      const code = await db.prepare(
        "SELECT id, code FROM registration_codes_mb WHERE id = ?",
      ).bind(codeId).first<{ id: number; code: string }>();
      if (!code) return json({ ok: false, error: "registration_code_not_found" }, 404);

      const query = await db.prepare(
        `SELECT use_number, uid, used_at
           FROM registration_code_usages_mb
          WHERE registration_code_id = ?
          ORDER BY use_number ASC`,
      ).bind(codeId).all<{ use_number: number; uid: string; used_at: string }>();
      return json({
        ok: true,
        code: cleanText(code.code),
        usages: (query.results || []).map((row) => ({
          useNumber: Number(row.use_number),
          uid: cleanText(row.uid),
          usedAt: cleanText(row.used_at),
        })),
      });
    }

    if (request.method === "GET") {
      const requestUrl = new URL(request.url);
      const requestedLimit = Number(requestUrl.searchParams.get("limit"));
      const limit = Number.isInteger(requestedLimit)
        ? Math.min(Math.max(requestedLimit, 1), 500)
        : 100;
      const query = await db.prepare(
        `SELECT codes.id, codes.code, codes.created_at, codes.max_uses,
                codes.use_count, codes.used_at, codes.used_by_uid,
                COALESCE((
                  SELECT group_concat(uid, ', ')
                    FROM (
                      SELECT usage.uid
                        FROM registration_code_usages_mb AS usage
                       WHERE usage.registration_code_id = codes.id
                         AND usage.uid <> ''
                       ORDER BY usage.use_number
                       LIMIT 2
                    )
                ), '') AS preview_uids
           FROM registration_codes_mb AS codes
          ORDER BY codes.created_at DESC, codes.id DESC
          LIMIT ?`,
      ).bind(limit).all<RegistrationCodeRow>();
      return json({
        ok: true,
        codes: (query.results || []).map((row) => ({
          id: Number(row.id),
          code: cleanText(row.code),
          createdAt: cleanText(row.created_at),
          maxUses: Number(row.max_uses),
          useCount: Number(row.use_count),
          usedAt: cleanText(row.used_at),
          usedByUid: cleanText(row.used_by_uid),
          previewUids: cleanText(row.preview_uids),
          used: Number(row.use_count) >= Number(row.max_uses),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    if (request.method !== "POST") return null;

    const body = await readJsonObject(request);
    const requestedCount = Number(body && body.count);
    const requestedMaxUses = body && body.maxUses === undefined
      ? 1
      : Number(body && body.maxUses);
    if (!Number.isInteger(requestedCount) || requestedCount < 1 || requestedCount > 100) {
      return json({ ok: false, error: "invalid_registration_code_count" }, 400);
    }
    if (!Number.isInteger(requestedMaxUses) ||
        requestedMaxUses < 1 || requestedMaxUses > MAX_REGISTRATION_CODE_USES) {
      return json({ ok: false, error: "invalid_registration_code_max_uses" }, 400);
    }

    const codes = new Set<string>();
    while (codes.size < requestedCount) codes.add(randomRegistrationCode());
    const createdAt = beijingTimestamp();
    const statements = Array.from(codes, (code) => db.prepare(
      "INSERT INTO registration_codes_mb (code, created_at, max_uses) VALUES (?, ?, ?)",
    ).bind(code, createdAt, requestedMaxUses));
    await db.batch(statements);
    return json({
      ok: true,
      codes: Array.from(codes),
      createdAt,
      maxUses: requestedMaxUses,
    });
  };
}
