/*
 * Dashboard growth, traffic, device and speed-share read routes.
 */

export function createAdminAnalyticsRoutes(deps) {
  const {
    beijingTimestamp,
    buildUserGrowthPoints,
    buildUserTrafficPoints,
    cleanText,
    ensureUserSchema,
    json,
    normalizeGrowthDateKey,
    normalizeGrowthPeriod,
    normalizeStoredUid,
    publicAdminSpeedShareResult,
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

    return null;
  };
}

