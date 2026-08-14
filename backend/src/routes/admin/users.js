/*
 * User list, batch operations and individual account maintenance.
 */

export function createAdminUserRoutes(deps) {
  const {
    MAX_BATCH_USER_COUNT,
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    experienceTotalForLevel,
    json,
    normalizeBatchUserReferences,
    normalizeNickname,
    publicAccount,
    publicAdminUser,
    readD1User,
    readState,
    resolveUserIdentity,
    usagePeriodKeys,
  } = deps;

  return async function handleAdminUsers(path, request, env) {
  if (path === "/api/users" && request.method === "GET") {
      const db = await ensureUserSchema(env);
      const { day, month } = usagePeriodKeys(new Date());
      const query = await db.prepare(`WITH usage_stats AS (
        SELECT
          user_id,
          SUM(CASE WHEN usage_day = ? THEN upload_bytes ELSE 0 END) AS today_upload_bytes,
          SUM(CASE WHEN usage_day = ? THEN download_bytes ELSE 0 END) AS today_download_bytes,
          MAX(CASE WHEN usage_day = ? THEN 1 ELSE 0 END) AS active_today,
          SUM(CASE WHEN substr(usage_day, 1, 7) = ? THEN upload_bytes ELSE 0 END) AS month_upload_bytes,
          SUM(CASE WHEN substr(usage_day, 1, 7) = ? THEN download_bytes ELSE 0 END) AS month_download_bytes,
          MAX(CASE WHEN substr(usage_day, 1, 7) = ? THEN 1 ELSE 0 END) AS active_this_month,
          MAX(last_ended_at) AS last_report_at,
          SUM(upload_bytes) AS total_upload_bytes,
          SUM(download_bytes) AS total_download_bytes
        FROM user_usage_daily_mb
        GROUP BY user_id
      )
      SELECT
        u.*,
        d.brand AS device_brand,
        d.model AS device_model,
        d.android_version AS device_android_version,
        d.android_sdk AS device_android_sdk,
        d.app_version AS device_app_version,
        COALESCE(s.today_upload_bytes, 0) AS today_upload_bytes,
        COALESCE(s.today_download_bytes, 0) AS today_download_bytes,
        COALESCE(s.active_today, 0) AS active_today,
        COALESCE(s.month_upload_bytes, 0) AS month_upload_bytes,
        COALESCE(s.month_download_bytes, 0) AS month_download_bytes,
        COALESCE(s.active_this_month, 0) AS active_this_month,
        s.last_report_at AS last_report_at,
        COALESCE(s.total_upload_bytes, 0) AS total_upload_bytes,
        COALESCE(s.total_download_bytes, 0) AS total_download_bytes
        FROM users_mb u
        LEFT JOIN user_devices_mb d ON d.id = (
          SELECT id FROM user_devices_mb WHERE user_id = u.id
          ORDER BY last_seen_at DESC, id DESC LIMIT 1
        )
        LEFT JOIN usage_stats s ON s.user_id = u.id
        ORDER BY u.created_at DESC`).bind(day, day, day, month, month, month).all();
      const state = await readState(env);
      const publicUsers = (query.results || []).map((row) =>
        publicAdminUser(row, state.settings));

      return json({
        ok: true,
        date: day,
        month,
        users: publicUsers,
        generatedAt: beijingTimestamp(),
      });
    }

    if (path === "/api/users/batch" && request.method === "PATCH") {
      const body = await request.json();
      const accountIds = normalizeBatchUserReferences(body && (body.uids || body.accountIds));
      const level = Number(body && body.level);
      if (
        accountIds.length === 0 ||
        accountIds.length > MAX_BATCH_USER_COUNT ||
        !Number.isInteger(level) ||
        level < 0 ||
        level > 6
      ) {
        return json({ ok: false, error: "invalid_batch_user_update" }, 400);
      }

      const db = await ensureUserSchema(env);
      const state = await readState(env);
      const status = level === 0 ? "banned" : "active";
      const experienceTotal = experienceTotalForLevel(level, state.settings.experienceThresholds);
      const result = await db.prepare(
        `WITH refs(value) AS (SELECT value FROM json_each(?)),
         target_users(user_id) AS (
           SELECT id FROM users_mb WHERE uid IN (SELECT value FROM refs)
           UNION
           SELECT user_id FROM user_devices_mb WHERE device_id IN (SELECT value FROM refs)
         )
         UPDATE users_mb
         SET status = ?,
             experience_total = CASE WHEN ? = 'active' THEN ? ELSE experience_total END
         WHERE id IN (SELECT user_id FROM target_users)`,
      ).bind(JSON.stringify(accountIds), status, status, experienceTotal).run();
      const changed = Number(result.meta && result.meta.changes) || 0;
      return json({ ok: true, accountIds, level, changed });
    }

    if (path === "/api/users/batch" && request.method === "DELETE") {
      const body = await request.json();
      const accountIds = normalizeBatchUserReferences(body && (body.uids || body.accountIds));
      if (accountIds.length === 0 || accountIds.length > MAX_BATCH_USER_COUNT) {
        return json({ ok: false, error: "invalid_batch_user_delete" }, 400);
      }

      const db = await ensureUserSchema(env);
      const result = await db.prepare(
        `WITH refs(value) AS (SELECT value FROM json_each(?)),
         target_users(user_id) AS (
           SELECT id FROM users_mb WHERE uid IN (SELECT value FROM refs)
           UNION
           SELECT user_id FROM user_devices_mb WHERE device_id IN (SELECT value FROM refs)
         )
         DELETE FROM users_mb WHERE id IN (SELECT user_id FROM target_users)`,
      ).bind(JSON.stringify(accountIds)).run();
      const changed = Number(result.meta && result.meta.changes) || 0;
      return json({ ok: true, accountIds, changed });

    }

    const userBanMatch = path.match(/^\/api\/users\/([^/]+)\/ban$/);
    if (userBanMatch && (request.method === "POST" || request.method === "DELETE")) {
      const identity = await resolveUserIdentity(env, decodeURIComponent(userBanMatch[1]));
      if (!identity) return json({ ok: false, error: "account_not_found" }, 404);
      const db = await ensureUserSchema(env);
      const status = request.method === "POST" ? "banned" : "active";
      const result = await db.prepare(
        "UPDATE users_mb SET status = ? WHERE id = ?",
      ).bind(status, identity.userId).run();
      if (!result.meta || Number(result.meta.changes) < 1) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      const deviceStats = await db.prepare(
        `SELECT
           COUNT(DISTINCT l.device_row_id) AS historical_device_count,
           COUNT(DISTINCT CASE WHEN own_ban.device_row_id IS NOT NULL
             THEN l.device_row_id END) AS account_banned_device_count,
           COUNT(DISTINCT CASE WHEN active_ban.device_row_id IS NOT NULL
             THEN l.device_row_id END) AS blocked_device_count
         FROM device_account_links_mb l
         LEFT JOIN device_bans_mb own_ban
           ON own_ban.device_row_id = l.device_row_id
          AND own_ban.source_uid = ?
          AND own_ban.reason = 'account_banned'
          AND own_ban.lifted_at IS NULL
         LEFT JOIN device_bans_mb active_ban
           ON active_ban.device_row_id = l.device_row_id
          AND active_ban.lifted_at IS NULL
         WHERE l.user_id = ?`,
      ).bind(identity.uid, identity.userId).first();
      return json({
        ok: true,
        uid: identity.uid,
        status,
        historicalDeviceCount: Number(deviceStats && deviceStats.historical_device_count) || 0,
        accountBannedDeviceCount:
          Number(deviceStats && deviceStats.account_banned_device_count) || 0,
        blockedDeviceCount: Number(deviceStats && deviceStats.blocked_device_count) || 0,
      });
    }

    const userMatch = path.match(/^\/api\/users\/([^/]+)$/);
    if (userMatch && request.method === "DELETE") {
      const identity = await resolveUserIdentity(env, decodeURIComponent(userMatch[1]));
      if (!identity) return json({ ok: false, error: "account_not_found" }, 404);
      const db = await ensureUserSchema(env);
      const deleteResult = await db.prepare(
        "DELETE FROM users_mb WHERE id = ?",
      ).bind(identity.userId).run();
      if (!deleteResult.meta || Number(deleteResult.meta.changes) < 1) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      return json({ ok: true, uid: identity.uid });
    }

    if (userMatch && request.method === "PATCH") {
      const identity = await resolveUserIdentity(env, decodeURIComponent(userMatch[1]));
      if (!identity) return json({ ok: false, error: "account_not_found" }, 404);
      const body = await request.json();
      const userAssignments = [];
      const userValues = [];
      const deviceAssignments = [];
      const deviceValues = [];
      const db = await ensureUserSchema(env);

      if (Object.prototype.hasOwnProperty.call(body || {}, "nickname")) {
        const nickname = normalizeNickname(body.nickname);
        if (!nickname) return json({ ok: false, error: "invalid_nickname" }, 400);
        userAssignments.push("nickname = ?");
        userValues.push(nickname);
      }
      if (Object.prototype.hasOwnProperty.call(body || {}, "level")) {
        const level = Number(body.level);
        if (!Number.isInteger(level) || level < 0 || level > 6) {
          return json({ ok: false, error: "invalid_level" }, 400);
        }
        const state = await readState(env);
        userAssignments.push("status = ?");
        userValues.push(level === 0 ? "banned" : "active");
        if (level > 0) {
          userAssignments.push("experience_total = ?");
          userValues.push(experienceTotalForLevel(level, state.settings.experienceThresholds));
        }
      }
      const editableTextFields = [
        ["brand", "brand", 80],
        ["model", "model", 120],
        ["androidVersion", "android_version", 80],
        ["appVersion", "app_version", 40],
      ];
      for (const [property, column, maxLength] of editableTextFields) {
        if (!Object.prototype.hasOwnProperty.call(body || {}, property)) continue;
        const fieldValue = cleanText(body[property]).slice(0, maxLength);
        deviceAssignments.push(`${column} = ?`);
        deviceValues.push(fieldValue);
      }
      if (userAssignments.length === 0 && deviceAssignments.length === 0) {
        return json({ ok: false, error: "no_user_changes" }, 400);
      }
      const statements = [];
      if (userAssignments.length > 0) {
        statements.push(db.prepare(
          `UPDATE users_mb SET ${userAssignments.join(", ")} WHERE id = ?`,
        ).bind(...userValues, identity.userId));
      }
      if (deviceAssignments.length > 0) {
        statements.push(db.prepare(
          `UPDATE user_devices_mb SET ${deviceAssignments.join(", ")}
           WHERE id = (
             SELECT id FROM user_devices_mb WHERE user_id = ?
             ORDER BY last_seen_at DESC, id DESC LIMIT 1
           )`,
        ).bind(...deviceValues, identity.userId));
      }
      const updateResult = await db.batch(statements);
      const changed = updateResult.reduce(
        (total, item) => total + Number(item && item.meta && item.meta.changes || 0),
        0,
      );
      if (changed < 1) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      const state = await readState(env);
      return json({
        ok: true,
        user: publicAccount(await readD1User(env, identity.userId, state.settings)),
      });
    }

    return null;
  };
}

