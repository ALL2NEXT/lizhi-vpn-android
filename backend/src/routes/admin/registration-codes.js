/*
 * Administrator-issued one-time registration codes.
 */

export function createAdminRegistrationCodeRoutes(deps) {
  const {
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    randomRegistrationCode,
  } = deps;

  return async function handleAdminRegistrationCodes(path, request, env) {
    if (path !== "/api/registration-codes") return null;

    const db = await ensureUserSchema(env);
    if (request.method === "GET") {
      const requestUrl = new URL(request.url);
      const requestedLimit = Number(requestUrl.searchParams.get("limit"));
      const limit = Number.isInteger(requestedLimit)
        ? Math.min(Math.max(requestedLimit, 1), 500)
        : 100;
      const query = await db.prepare(
        `SELECT id, code, created_at, used_at, used_by_uid
           FROM registration_codes_mb
          ORDER BY created_at DESC, id DESC
          LIMIT ?`,
      ).bind(limit).all();
      return json({
        ok: true,
        codes: (query.results || []).map((row) => ({
          id: Number(row.id),
          code: cleanText(row.code),
          createdAt: cleanText(row.created_at),
          usedAt: cleanText(row.used_at),
          usedByUid: cleanText(row.used_by_uid),
          used: Boolean(row.used_at),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    if (request.method !== "POST") return null;

    const body = await request.json();
    const requestedCount = Number(body && body.count);
    if (!Number.isInteger(requestedCount) || requestedCount < 1 || requestedCount > 100) {
      return json({ ok: false, error: "invalid_registration_code_count" }, 400);
    }

    const codes = new Set();
    while (codes.size < requestedCount) codes.add(randomRegistrationCode());
    const createdAt = beijingTimestamp();
    const statements = Array.from(codes, (code) => db.prepare(
      "INSERT INTO registration_codes_mb (code, created_at) VALUES (?, ?)",
    ).bind(code, createdAt));
    await db.batch(statements);
    return json({
      ok: true,
      codes: Array.from(codes),
      createdAt,
    });
  };
}
