/*
 * Device inventory and account security read routes.
 */

export function createAdminSecurityRoutes(deps) {
  const {
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    normalizeAccountId,
    normalizeAndroidSdk,
    normalizeStoredUid,
    readState,
    rowToUser,
  } = deps;

  return async function handleAdminSecurity(path, request, env) {
  if (path === "/api/device-relations" && request.method === "GET") {
      const requestUrl = new URL(request.url);
      const requestedLimit = Number(requestUrl.searchParams.get("limit"));
      const limit = Number.isInteger(requestedLimit)
        ? Math.min(Math.max(requestedLimit, 1), 1000)
        : 500;
      const db = await ensureUserSchema(env);
      const query = await db.prepare(
        `SELECT
           l.user_id,
           l.user_uid AS account_uid,
           u.nickname AS account_nickname,
           u.status AS account_status,
           d.device_id,
           d.brand,
           d.model,
           d.android_version,
           d.android_sdk,
           d.app_version,
           l.first_bound_at,
           l.last_bound_at,
           d.last_seen_at,
           CASE WHEN d.user_id = l.user_id THEN 1 ELSE 0 END AS is_current,
           EXISTS (
             SELECT 1 FROM device_bans_mb b
             WHERE b.device_row_id = d.id AND b.lifted_at IS NULL
           ) AS blocked,
           EXISTS (
             SELECT 1 FROM device_bans_mb b
             WHERE b.device_row_id = d.id
               AND b.source_uid = l.user_uid
               AND b.reason = 'account_banned'
               AND b.lifted_at IS NULL
           ) AS blocked_by_account
         FROM device_account_links_mb l
         JOIN user_devices_mb d ON d.id = l.device_row_id
         LEFT JOIN users_mb u ON u.id = l.user_id
         ORDER BY l.last_bound_at DESC, d.id DESC
         LIMIT ?`,
      ).bind(limit).all();
      return json({
        ok: true,
        relations: (query.results || []).map((row) => ({
          accountUid: normalizeStoredUid(row.account_uid),
          accountNickname: cleanText(row.account_nickname),
          accountStatus: cleanText(row.account_status) || "deleted",
          deviceId: normalizeAccountId(row.device_id),
          brand: cleanText(row.brand),
          model: cleanText(row.model),
          androidVersion: cleanText(row.android_version),
          androidSdk: normalizeAndroidSdk(row.android_sdk),
          appVersion: cleanText(row.app_version),
          current: Number(row.is_current) === 1,
          blocked: Number(row.blocked) === 1,
          blockedByAccount: Number(row.blocked_by_account) === 1,
          firstBoundAt: cleanText(row.first_bound_at),
          lastBoundAt: cleanText(row.last_bound_at),
          lastSeenAt: cleanText(row.last_seen_at),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

  if (path === "/api/devices" && request.method === "GET") {
      const requestUrl = new URL(request.url);
      const requestedLimit = Number(requestUrl.searchParams.get("limit"));
      const limit = Number.isInteger(requestedLimit)
        ? Math.min(Math.max(requestedLimit, 1), 1000)
        : 500;
      const db = await ensureUserSchema(env);
      const query = await db.prepare(
        `WITH link_stats AS (
           SELECT
             device_row_id,
             COUNT(*) AS historical_account_count,
             GROUP_CONCAT(user_uid) AS linked_uids
           FROM device_account_links_mb
           GROUP BY device_row_id
         ), active_bans AS (
           SELECT
             device_row_id,
             COUNT(*) AS active_ban_count,
             GROUP_CONCAT(source_uid || ':' || reason) AS active_bans
           FROM device_bans_mb
           WHERE lifted_at IS NULL
           GROUP BY device_row_id
         )
         SELECT
           d.device_id,
           d.brand,
           d.model,
           d.android_version,
           d.android_sdk,
           d.app_version,
           d.created_at,
           d.last_seen_at,
           u.uid AS current_uid,
           u.status AS current_account_status,
           COALESCE(l.historical_account_count, 0) AS historical_account_count,
           COALESCE(l.linked_uids, '') AS linked_uids,
           COALESCE(b.active_ban_count, 0) AS active_ban_count,
           COALESCE(b.active_bans, '') AS active_bans
         FROM user_devices_mb d
         LEFT JOIN users_mb u ON u.id = d.user_id
         LEFT JOIN link_stats l ON l.device_row_id = d.id
         LEFT JOIN active_bans b ON b.device_row_id = d.id
         ORDER BY d.last_seen_at DESC, d.id DESC
         LIMIT ?`,
      ).bind(limit).all();
      return json({
        ok: true,
        devices: (query.results || []).map((row) => ({
          deviceId: normalizeAccountId(row.device_id),
          currentUid: normalizeStoredUid(row.current_uid) || null,
          currentAccountStatus: cleanText(row.current_account_status) || null,
          blocked: Number(row.active_ban_count) > 0,
          activeBanCount: Number(row.active_ban_count) || 0,
          activeBans: cleanText(row.active_bans).split(",").filter(Boolean),
          historicalAccountCount: Number(row.historical_account_count) || 0,
          linkedUids: cleanText(row.linked_uids).split(",").filter(Boolean),
          brand: cleanText(row.brand),
          model: cleanText(row.model),
          androidVersion: cleanText(row.android_version),
          androidSdk: normalizeAndroidSdk(row.android_sdk),
          appVersion: cleanText(row.app_version),
          createdAt: cleanText(row.created_at),
          lastSeenAt: cleanText(row.last_seen_at),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    if (path === "/api/security/accounts" && request.method === "GET") {
      const db = await ensureUserSchema(env);
      const state = await readState(env);
      const results = await db.batch([
        db.prepare(
          `SELECT
             u.id,
             u.uid,
             u.nickname,
             u.status,
             u.experience_total,
             u.email,
             u.invite_code,
             inviter.invite_code AS invited_by_code,
             (SELECT COUNT(*) FROM users_mb invited WHERE invited.invited_by_user_id = u.id) AS invitee_count,
             u.created_at,
             COUNT(DISTINCT l.device_row_id) AS historical_device_count,
             COUNT(DISTINCT CASE WHEN b.device_row_id IS NOT NULL AND b.lifted_at IS NULL
               THEN l.device_row_id END)
               AS banned_device_count
           FROM users_mb u
           LEFT JOIN device_account_links_mb l ON l.user_id = u.id
           LEFT JOIN users_mb inviter ON inviter.id = u.invited_by_user_id
           LEFT JOIN device_bans_mb b
             ON b.device_row_id = l.device_row_id
            AND b.source_uid = u.uid
            AND b.reason = 'account_banned'
           GROUP BY u.id
           ORDER BY u.created_at DESC`,
        ),
        db.prepare(
          `SELECT
             l.user_id,
             l.user_uid,
             l.first_bound_at,
             l.last_bound_at,
             d.device_id,
             d.brand,
             d.model,
             d.android_version,
             d.android_sdk,
             d.app_version,
             d.last_seen_at,
             CASE WHEN d.user_id = l.user_id THEN 1 ELSE 0 END AS is_current,
             EXISTS (
               SELECT 1 FROM device_bans_mb b
               WHERE b.device_row_id = d.id AND b.lifted_at IS NULL
             ) AS blocked,
             EXISTS (
               SELECT 1 FROM device_bans_mb b
               WHERE b.device_row_id = d.id
                 AND b.source_uid = l.user_uid
                 AND b.reason = 'account_banned'
                 AND b.lifted_at IS NULL
             ) AS blocked_by_account
           FROM device_account_links_mb l
           JOIN user_devices_mb d ON d.id = l.device_row_id
           WHERE l.user_id IS NOT NULL
           ORDER BY l.last_bound_at DESC, d.id DESC`,
        ),
      ]);
      const devicesByUser = new Map();
      for (const row of results[1].results || []) {
        const userId = Number(row.user_id);
        const devices = devicesByUser.get(userId) || [];
        devices.push({
          deviceId: normalizeAccountId(row.device_id),
          brand: cleanText(row.brand),
          model: cleanText(row.model),
          androidVersion: cleanText(row.android_version),
          androidSdk: normalizeAndroidSdk(row.android_sdk),
          appVersion: cleanText(row.app_version),
          current: Number(row.is_current) === 1,
          blocked: Number(row.blocked) === 1,
          blockedByAccount: Number(row.blocked_by_account) === 1,
          firstBoundAt: cleanText(row.first_bound_at),
          lastBoundAt: cleanText(row.last_bound_at),
          lastSeenAt: cleanText(row.last_seen_at),
        });
        devicesByUser.set(userId, devices);
      }
      return json({
        ok: true,
        accounts: (results[0].results || []).map((row) => {
          const user = rowToUser(row, state.settings);
          return {
            uid: user.uid,
            nickname: user.nickname,
            email: user.email,
            status: user.status,
            level: user.level,
            experience: user.experience,
            experienceTotal: user.experienceTotal,
            inviteCode: user.inviteCode,
            invitedByCode: user.invitedByCode,
            inviteeCount: user.inviteeCount,
            createdAt: user.createdAt,
            historicalDeviceCount: Number(row.historical_device_count) || 0,
            bannedDeviceCount: Number(row.banned_device_count) || 0,
            devices: devicesByUser.get(Number(row.id)) || [],
          };
        }),
        generatedAt: beijingTimestamp(),
      });

    }

    return null;
  };
}
