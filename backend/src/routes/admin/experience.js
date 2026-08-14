/*
 * 管理员审核任务后，为用户发放自定义经验，并保留发放记录。
 */

export function createAdminExperienceRoutes(deps) {
  const {
    MAX_ACCOUNT_EXPERIENCE,
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    readD1User,
    readState,
    resolveUserIdentity,
  } = deps;

  return async function handleAdminExperience(path, request, env) {
    if (path === "/api/experience-awards" && request.method === "GET") {
      const db = await ensureUserSchema(env);
      const query = await db.prepare(
        `SELECT a.id, a.user_uid, a.experience_awarded, a.reason, a.created_at,
                u.nickname, u.status
         FROM user_experience_awards_mb a
         LEFT JOIN users_mb u ON u.id = a.user_id
         ORDER BY a.id DESC LIMIT 200`,
      ).all();
      return json({
        ok: true,
        awards: (query.results || []).map((row) => ({
          id: Number(row.id) || 0,
          uid: cleanText(row.user_uid),
          nickname: cleanText(row.nickname),
          experienceAwarded: Number(row.experience_awarded) || 0,
          reason: cleanText(row.reason),
          createdAt: cleanText(row.created_at),
          accountStatus: cleanText(row.status),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    if (path === "/api/experience-awards" && request.method === "POST") {
      const body = await request.json();
      const identity = await resolveUserIdentity(env, body && (body.uid || body.accountId));
      if (!identity) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }

      const experience = Number(body && body.experience);
      const reason = cleanText(body && body.reason).slice(0, 500);
      if (
        !Number.isSafeInteger(experience) ||
        experience < 1 ||
        experience > MAX_ACCOUNT_EXPERIENCE ||
        !reason
      ) {
        return json({ ok: false, error: "invalid_experience_award" }, 400);
      }

      const db = await ensureUserSchema(env);
      const state = await readState(env);
      const user = await readD1User(env, identity.userId, state.settings);
      if (!user) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      if (user.status === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }

      const inserted = await db.prepare(
        `INSERT INTO user_experience_awards_mb
         (user_id, user_uid, experience_awarded, reason, created_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).bind(
        identity.userId,
        identity.uid,
        experience,
        reason,
        beijingTimestamp(),
      ).run();
      if (!inserted.meta || Number(inserted.meta.changes) < 1) {
        return json({ ok: false, error: "experience_award_failed" }, 500);
      }

      const updated = await readD1User(env, identity.userId, state.settings);
      const experienceAwarded = Math.max(
        0,
        Number(updated && updated.experienceTotal) - Number(user.experienceTotal),
      );
      return json({
        ok: true,
        uid: identity.uid,
        experienceAwarded,
        account: updated,
      });
    }

    return null;
  };
}
