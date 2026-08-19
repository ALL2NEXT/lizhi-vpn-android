/*
 * Dashboard growth, traffic and device read routes.
 */

export function createAdminAnalyticsRoutes(deps) {
  const {
    beijingTimestamp,
    buildUserGrowthPoints,
    buildUserActivityPoints,
    buildUserTrafficPoints,
    cleanText,
    ensureUserSchema,
    json,
    normalizeGrowthDateKey,
    normalizeGrowthPeriod,
    normalizeStoredUid,
    usagePeriodKeys,
  } = deps;

  return async function handleAdminAnalytics(path, request, env) {
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
         FROM user_usage_mb
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

    if (path === "/api/users/activity" && request.method === "GET") {
      const requestUrl = new URL(request.url);
      const period = normalizeGrowthPeriod(requestUrl.searchParams.get("period"));
      const startDate = normalizeGrowthDateKey(requestUrl.searchParams.get("start"));
      const endDate = normalizeGrowthDateKey(requestUrl.searchParams.get("end"));
      if (startDate === null || endDate === null) {
        return json({ ok: false, error: "invalid_activity_date_range" }, 400);
      }
      if (startDate && endDate && startDate > endDate) {
        return json({ ok: false, error: "invalid_activity_date_order" }, 400);
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
      const activityStatement = db.prepare(
        `SELECT usage_day AS date_key, user_id
         FROM user_usage_mb
         WHERE user_id IS NOT NULL
           ${filters.length > 0 ? `AND ${filters.join(" AND ")}` : ""}
         GROUP BY usage_day, user_id
         ORDER BY usage_day ASC`,
      );
      const query = filterValues.length > 0
        ? await activityStatement.bind(...filterValues).all()
        : await activityStatement.all();
      const points = buildUserActivityPoints(
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

  if (path === "/api/dashboard/devices" && request.method === "GET") {
      const db = await ensureUserSchema(env);
      const query = await db.prepare(
        `SELECT
           u.uid,
           u.invite_code,
           inviter.invite_code AS invited_by_code,
           (SELECT COUNT(*) FROM users_mb invited WHERE invited.invited_by_user_id = u.id) AS invitee_count,
           COALESCE(d.brand, '') AS brand,
           COALESCE(d.android_version, '') AS android_version,
           COALESCE(d.app_version, '') AS app_version
         FROM users_mb u
         LEFT JOIN user_devices_mb d ON d.id = (
           SELECT id FROM user_devices_mb
           WHERE user_id = u.id
           ORDER BY last_seen_at DESC, id DESC LIMIT 1
         )
         LEFT JOIN users_mb inviter ON inviter.id = u.invited_by_user_id
         ORDER BY u.created_at DESC`,
      ).all();
      return json({
        ok: true,
        users: (query.results || []).map((row) => ({
          uid: normalizeStoredUid(row.uid),
          inviteCode: cleanText(row.invite_code),
          invitedByCode: cleanText(row.invited_by_code),
          inviteeCount: Number(row.invitee_count) || 0,
          brand: cleanText(row.brand),
          androidVersion: cleanText(row.android_version),
          appVersion: cleanText(row.app_version),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    return null;
  };
}
