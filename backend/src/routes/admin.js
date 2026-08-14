/*
 * Admin routes.
 *
 * Handlers receive only the shared runtime dependencies they use.
 */

export function createAdminRoutes(deps) {
  const {
    MAX_BATCH_USER_COUNT,
    publicAccount,
    publicAdminUser,
    publicAdminSpeedShareResult,
    ensureUserSchema,
    resolveUserIdentity,
    readD1User,
    rowToUser,
    normalizeNickname,
    experienceTotalForLevel,
    normalizeAndroidSdk,
    usagePeriodKeys,
    normalizeGrowthPeriod,
    normalizeGrowthDateKey,
    buildUserGrowthPoints,
    buildUserTrafficPoints,
    beijingTimestamp,
    normalizeAccountId,
    normalizeStoredUid,
    normalizeBatchUserReferences,
    validateAndWarmSubscriptionSources,
    readState,
    writeState,
    normalizeState,
    cleanText,
    json,
  } = deps;

async function handleAdmin(path, request, env) {
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
           u.created_at,
           COUNT(DISTINCT l.device_row_id) AS historical_device_count,
           COUNT(DISTINCT CASE WHEN b.device_row_id IS NOT NULL AND b.lifted_at IS NULL
             THEN l.device_row_id END)
             AS banned_device_count
         FROM users_mb u
         LEFT JOIN device_account_links_mb l ON l.user_id = u.id
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
          createdAt: user.createdAt,
          historicalDeviceCount: Number(row.historical_device_count) || 0,
          bannedDeviceCount: Number(row.banned_device_count) || 0,
          devices: devicesByUser.get(Number(row.id)) || [],
        };
      }),
      generatedAt: beijingTimestamp(),
    });

  }

  if (path === "/api/users/growth" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const period = normalizeGrowthPeriod(requestUrl.searchParams.get("period"));
    const startDate = normalizeGrowthDateKey(requestUrl.searchParams.get("start"));
    const endDate = normalizeGrowthDateKey(requestUrl.searchParams.get("end"));
    if (startDate === null || endDate === null) {
      return json({ ok: false, error: "invalid_growth_date_range" }, 400);
    }
    if (startDate && endDate && startDate > endDate) {
      return json({ ok: false, error: "invalid_growth_date_order" }, 400);
    }
    const db = await ensureUserSchema(env);
    const now = new Date();
    const today = usagePeriodKeys(now).day;
    const lastDay = endDate && endDate < today ? endDate : today;
    const query = await db.prepare(
      `SELECT created_day AS date_key, COUNT(*) AS new_users
       FROM users_mb
       WHERE created_day <> '' AND created_day <= ?
       GROUP BY created_day
       ORDER BY created_day ASC`,
    ).bind(lastDay).all();
    const points = buildUserGrowthPoints(
      query.results || [],
      period,
      now,
      startDate,
      endDate,
    );

    return json({
      ok: true,
      period,
      startDate: startDate || null,
      endDate: endDate || null,
      points,
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/users/traffic" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const period = normalizeGrowthPeriod(requestUrl.searchParams.get("period"));
    const startDate = normalizeGrowthDateKey(requestUrl.searchParams.get("start"));
    const endDate = normalizeGrowthDateKey(requestUrl.searchParams.get("end"));
    if (startDate === null || endDate === null) {
      return json({ ok: false, error: "invalid_traffic_date_range" }, 400);
    }
    if (startDate && endDate && startDate > endDate) {
      return json({ ok: false, error: "invalid_traffic_date_order" }, 400);
    }

    const db = await ensureUserSchema(env);
    const filters = [];
    const filterValues = [];
    if (startDate) {
      filters.push("usage_day >= ?");
      filterValues.push(startDate);
    }
    if (endDate) {
      filters.push("usage_day <= ?");
      filterValues.push(endDate);
    }
    const trafficStatement = db.prepare(
      `SELECT usage_day AS date_key,
              SUM(upload_bytes) AS upload_bytes,
              SUM(download_bytes) AS download_bytes
       FROM user_usage_daily_mb
       ${filters.length > 0 ? `WHERE ${filters.join(" AND ")}` : ""}
       GROUP BY usage_day
       ORDER BY usage_day ASC`,
    );
    const query = filterValues.length > 0
      ? await trafficStatement.bind(...filterValues).all()
      : await trafficStatement.all();
    const points = buildUserTrafficPoints(
      query.results || [],
      period,
      new Date(),
      startDate,
      endDate,
    );

    return json({
      ok: true,
      period,
      startDate: startDate || null,
      endDate: endDate || null,
      points,
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/speed-share-results" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const requestedLimit = Number(requestUrl.searchParams.get("limit"));
    const limit = Number.isInteger(requestedLimit)
      ? Math.min(Math.max(requestedLimit, 1), 1000)
      : 500;
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      "SELECT node_id, node_name, server_host, subscription_group_name, province, carrier, " +
        "tcping_latency_ms, url_test_latency_ms, download_mbps, " +
        "upload_mbps, updated_at, last_reported_at, report_count " +
        "FROM speed_share_results_mb ORDER BY last_reported_at DESC LIMIT ?",
    ).bind(limit).all();
    return json({
      ok: true,
      results: (query.results || []).map(publicAdminSpeedShareResult),
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/dashboard/devices" && request.method === "GET") {
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      `SELECT
         u.uid,
         COALESCE(d.brand, '') AS brand,
         COALESCE(d.android_version, '') AS android_version,
         COALESCE(d.app_version, '') AS app_version
       FROM users_mb u
       LEFT JOIN user_devices_mb d ON d.id = (
         SELECT id FROM user_devices_mb
         WHERE user_id = u.id
         ORDER BY last_seen_at DESC, id DESC LIMIT 1
       )
       ORDER BY u.created_at DESC`,
    ).all();
    return json({
      ok: true,
      users: (query.results || []).map((row) => ({
        uid: normalizeStoredUid(row.uid),
        brand: cleanText(row.brand),
        androidVersion: cleanText(row.android_version),
        appVersion: cleanText(row.app_version),
      })),
      generatedAt: beijingTimestamp(),
    });
  }

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

  if (path === "/api/state" && request.method === "GET") {
    return json(await readState(env));
  }

  if (path === "/api/state" && request.method === "PUT") {
    const incoming = await request.json();
    try {
      const next = normalizeState(incoming);
      await validateAndWarmSubscriptionSources(env, next.subscriptions);
      next.updatedAt = beijingTimestamp();
      await writeState(env, next);
      return json({ ok: true, state: next });
    } catch (error) {
      return json({
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }, 400);
    }
  }

  return json({ error: "admin_route_not_found" }, 404);
}

  return { handleAdmin };
}

