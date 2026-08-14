/*
 * Invitation relationship read route.
 */

export function createAdminInviteRoutes(deps) {
  const {
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    normalizeStoredUid,
  } = deps;

  return async function handleAdminInvites(path, request, env) {
    if (path !== "/api/invite-relations" || request.method !== "GET") return null;

    const requestUrl = new URL(request.url);
    const requestedLimit = Number(requestUrl.searchParams.get("limit"));
    const limit = Number.isInteger(requestedLimit)
      ? Math.min(Math.max(requestedLimit, 1), 1000)
      : 500;
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      `SELECT
         inviter.uid AS inviter_uid,
         inviter.nickname AS inviter_nickname,
         inviter.invite_code AS inviter_invite_code,
         inviter.status AS inviter_status,
         invitee.uid AS invitee_uid,
         invitee.nickname AS invitee_nickname,
         invitee.email AS invitee_email,
         invitee.status AS invitee_status,
         invitee.created_at AS invitee_created_at,
         invitee.invited_at AS invited_at
       FROM users_mb invitee
       INNER JOIN users_mb inviter ON inviter.id = invitee.invited_by_user_id
       WHERE invitee.invited_by_user_id IS NOT NULL
       ORDER BY invitee.invited_at DESC, invitee.id DESC
       LIMIT ?`,
    ).bind(limit).all();

    return json({
      ok: true,
      relations: (query.results || []).map((row) => ({
        inviterUid: normalizeStoredUid(row.inviter_uid),
        inviterNickname: cleanText(row.inviter_nickname),
        inviterInviteCode: cleanText(row.inviter_invite_code),
        inviterStatus: cleanText(row.inviter_status),
        inviteeUid: normalizeStoredUid(row.invitee_uid),
        inviteeNickname: cleanText(row.invitee_nickname),
        inviteeEmail: cleanText(row.invitee_email),
        inviteeStatus: cleanText(row.invitee_status),
        inviteeCreatedAt: cleanText(row.invitee_created_at),
        invitedAt: cleanText(row.invited_at),
      })),
      generatedAt: beijingTimestamp(),
    });
  };
}
