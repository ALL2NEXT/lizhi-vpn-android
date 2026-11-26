import type { DeviceRelationRow, DeviceInventoryRow, DeviceLinkRow, SecurityAccountRow } from "../../types/database.ts";
import type { DatabaseResult } from "../../storage/contracts.ts";

interface SecurityDevice {
  deviceId: string;
  brand: string;
  model: string;
  androidVersion: string;
  androidSdk: number;
  appVersion: string;
  current: boolean;
  blocked: boolean;
  firstBoundAt: string;
  lastBoundAt: string;
  lastSeenAt: string;
}

import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";
import { readUserIpLinks } from "../../services/user-ip-links.ts";
import { readJsonObject } from "../../http/request.ts";

/*
 * Device inventory and account security read routes.
 */

export function createAdminSecurityRoutes(deps: Pick<
  RouteDependencies,
  | "beijingTimestamp"
  | "cleanText"
  | "ensureUserSchema"
  | "json"
  | "normalizeAccountId"
  | "normalizeAndroidSdk"
  | "normalizeStoredUid"
  | "rowToUser"
>) {
  const {
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    normalizeAccountId,
    normalizeAndroidSdk,
    normalizeStoredUid,
    rowToUser,
  } = deps;

  return async function handleAdminSecurity(path: string, request: Request, env: Env) {
    if (path === "/api/security/client-reports" && request.method === "GET") {
      const db = await ensureUserSchema(env);
      const query = await db.prepare(
        `SELECT
           r.id,
           r.device_id,
           r.finding_code,
           r.first_reported_at,
           r.last_reported_at,
           r.report_count,
           d.brand,
           d.model,
           d.android_version,
           d.android_sdk,
           d.app_version,
           u.uid,
           u.nickname,
           EXISTS (
             SELECT 1 FROM device_bans_mb b
             WHERE b.device_row_id = d.id AND b.lifted_at IS NULL
           ) AS blocked,
           r.status
         FROM client_security_reports_mb r
         LEFT JOIN user_devices_mb d ON d.device_id = r.device_id
         LEFT JOIN users_mb u ON u.id = d.user_id
         WHERE r.status IN ('pending', 'banned')
         ORDER BY r.last_reported_at DESC, r.id DESC
         LIMIT 500`,
      ).all<{
        id: number;
        device_id: string;
        finding_code: string;
        first_reported_at: string;
        last_reported_at: string;
        report_count: number;
        brand: string | null;
        model: string | null;
        android_version: string | null;
        android_sdk: number | null;
        app_version: string | null;
        uid: string | null;
        nickname: string | null;
        blocked: number;
        status: string;
      }>();
      return json({
        ok: true,
        reports: (query.results || []).map((row) => ({
          id: Number(row.id),
          deviceId: normalizeAccountId(row.device_id),
          findingCode: cleanText(row.finding_code),
          firstReportedAt: cleanText(row.first_reported_at),
          lastReportedAt: cleanText(row.last_reported_at),
          reportCount: Number(row.report_count) || 0,
          brand: cleanText(row.brand),
          model: cleanText(row.model),
          androidVersion: cleanText(row.android_version),
          androidSdk: normalizeAndroidSdk(row.android_sdk),
          appVersion: cleanText(row.app_version),
          uid: normalizeStoredUid(row.uid) || null,
          nickname: cleanText(row.nickname),
          blocked: Number(row.blocked) === 1,
          status: cleanText(row.status),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

    const reportReviewMatch = path.match(/^\/api\/security\/client-reports\/(\d+)\/review$/);
    if (reportReviewMatch && request.method === "POST") {
      const reportId = Number(reportReviewMatch[1]);
      if (!Number.isSafeInteger(reportId) || reportId < 1) {
        return json({ ok: false, error: "invalid_security_report_id" }, 400);
      }
      const body = await readJsonObject(request);
      const action = cleanText(body && body.action);
      if (action !== "ban" && action !== "dismiss" && action !== "unban") {
        return json({ ok: false, error: "invalid_security_report_action" }, 400);
      }

      const db = await ensureUserSchema(env);
      const report = await db.prepare(
        "SELECT device_id, status, finding_code FROM client_security_reports_mb WHERE id = ?",
      ).bind(reportId).first<{ device_id: string; status: string; finding_code: string }>();
      if (!report) return json({ ok: false, error: "security_report_not_found" }, 404);
      const reportStatus = cleanText(report.status);
      const isBlacklistedPackage = cleanText(report.finding_code)
        .startsWith("blacklisted_package:");
      if (action === "unban") {
        if (reportStatus !== "banned") {
          return json({ ok: false, error: "security_report_not_banned" }, 409);
        }
        const device = await db.prepare(
          "SELECT id FROM user_devices_mb WHERE device_id = ?",
        ).bind(report.device_id).first<{ id: number }>();
        if (!device) return json({ ok: false, error: "reported_device_not_found" }, 409);
        const reviewedAt = beijingTimestamp();
        await db.batch([
          db.prepare(
            "UPDATE device_bans_mb SET lifted_at = ? " +
              "WHERE device_row_id = ? AND source_uid = 'native_security_review' " +
              "AND reason = 'client_security_report' AND lifted_at IS NULL",
          ).bind(reviewedAt, device.id),
          db.prepare(
            "UPDATE client_security_reports_mb SET status = 'dismissed', reviewed_at = ? " +
              "WHERE device_id = ? AND status = 'banned'",
          ).bind(reviewedAt, report.device_id),
        ]);
        return json({ ok: true, status: "unbanned" });
      }
      if (reportStatus !== "pending") {
        return json({ ok: false, error: "security_report_already_reviewed" }, 409);
      }

      const reviewedAt = beijingTimestamp();
      if (action === "dismiss") {
        const dismissReport = isBlacklistedPackage
          ? db.prepare(
            "UPDATE client_security_reports_mb SET status = 'dismissed', reviewed_at = ? " +
              "WHERE device_id = ? AND status = 'pending' " +
              "AND substr(finding_code, 1, length('blacklisted_package:')) = 'blacklisted_package:'",
          ).bind(reviewedAt, report.device_id)
          : db.prepare(
            "UPDATE client_security_reports_mb SET status = 'dismissed', reviewed_at = ? " +
              "WHERE id = ? AND status = 'pending'",
          ).bind(reviewedAt, reportId);
        await dismissReport.run();
        return json({ ok: true, status: "dismissed" });
      }

      const device = await db.prepare(
        "SELECT id FROM user_devices_mb WHERE device_id = ?",
      ).bind(report.device_id).first<{ id: number }>();
      if (!device) return json({ ok: false, error: "reported_device_not_found" }, 409);

      const markReportReviewed = isBlacklistedPackage
        ? db.prepare(
          "UPDATE client_security_reports_mb SET status = 'banned', reviewed_at = ? " +
            "WHERE device_id = ? AND status = 'pending' " +
            "AND substr(finding_code, 1, length('blacklisted_package:')) = 'blacklisted_package:'",
        ).bind(reviewedAt, report.device_id)
        : db.prepare(
          "UPDATE client_security_reports_mb SET status = 'banned', reviewed_at = ? " +
            "WHERE id = ? AND status = 'pending'",
        ).bind(reviewedAt, reportId);
      await db.batch([
        db.prepare(
          `INSERT INTO device_bans_mb (device_row_id, source_uid, reason, banned_at, lifted_at)
           VALUES (?, 'native_security_review', 'client_security_report', ?, NULL)
           ON CONFLICT(device_row_id, source_uid, reason) DO UPDATE SET
             banned_at = excluded.banned_at,
             lifted_at = NULL`,
        ).bind(device.id, reviewedAt),
        markReportReviewed,
      ]);
      return json({ ok: true, status: "banned" });
    }

    if (path === "/api/ip-relations" && request.method === "GET") {
      const url = new URL(request.url);
      try {
        return json(await readUserIpLinks(env, {
          ip: url.searchParams.get("ip") || undefined,
          uid: url.searchParams.get("uid") || undefined,
          q: url.searchParams.get("q") || undefined,
          direction: url.searchParams.get("direction") || undefined,
          limit: url.searchParams.has("limit") ? Number(url.searchParams.get("limit")) : undefined,
          offset: url.searchParams.has("offset") ? Number(url.searchParams.get("offset")) : undefined,
        }));
      } catch (error) {
        if (error instanceof Error && (error.message === "invalid_ip" || error.message === "invalid_uid")) {
          return json({ ok: false, error: error.message }, 400);
        }
        throw error;
      }
    }
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
           ) AS blocked
         FROM device_account_links_mb l
         JOIN user_devices_mb d ON d.id = l.device_row_id
         LEFT JOIN users_mb u ON u.id = l.user_id
         ORDER BY l.last_bound_at DESC, d.id DESC
         LIMIT ?`,
      ).bind(limit).all<DeviceRelationRow>();
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
          firstBoundAt: cleanText(row.first_bound_at),
          lastBoundAt: cleanText(row.last_bound_at),
          lastSeenAt: cleanText(row.last_seen_at),
        })),
        generatedAt: beijingTimestamp(),
      });
    }

  if (path === "/api/devices/batch" && request.method === "PATCH") {
      const body = await readJsonObject(request);
      const rawDeviceIds = body && body.deviceIds;
      const action = cleanText(body && body.action);
      if (!Array.isArray(rawDeviceIds) || rawDeviceIds.length === 0 || rawDeviceIds.length > 100 ||
          (action !== "ban" && action !== "unban")) {
        return json({ ok: false, error: "invalid_batch_device_update" }, 400);
      }
      const normalizedDeviceIds = rawDeviceIds.map((value: unknown) => normalizeAccountId(value));
      if (normalizedDeviceIds.some((deviceId: string) => !deviceId)) {
        return json({ ok: false, error: "invalid_batch_device_update" }, 400);
      }
      const deviceIds = Array.from(new Set(normalizedDeviceIds));
      const db = await ensureUserSchema(env);
      const changedAt = beijingTimestamp();
      let changedDeviceCount = 0;
      if (action === "ban") {
        const results = await db.batch(deviceIds.map((deviceId) => db.prepare(
          `INSERT INTO device_bans_mb
             (device_row_id, source_uid, reason, banned_at, lifted_at)
           SELECT id, 'admin_device_management', 'admin_device_ban', ?, NULL
           FROM user_devices_mb WHERE device_id = ?
           ON CONFLICT(device_row_id, source_uid, reason) DO UPDATE SET
             banned_at = excluded.banned_at,
             lifted_at = NULL`,
        ).bind(changedAt, deviceId)));
        changedDeviceCount = results.reduce((count, result) =>
          count + Number(result.meta && result.meta.changes || 0), 0);
      } else {
        const result = await db.prepare(
          `UPDATE device_bans_mb SET lifted_at = ?
           WHERE source_uid = 'admin_device_management'
             AND reason = 'admin_device_ban'
             AND lifted_at IS NULL
             AND device_row_id IN (
               SELECT id FROM user_devices_mb
               WHERE device_id IN (SELECT value FROM json_each(?))
             )`,
        ).bind(changedAt, JSON.stringify(deviceIds)).run();
        changedDeviceCount = Number(result.meta && result.meta.changes || 0);
      }
      return json({ ok: true, action, requestedDeviceCount: deviceIds.length, changedDeviceCount });
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
      ).bind(limit).all<DeviceInventoryRow>();
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
      const results = await db.batch([
        db.prepare(
          `SELECT
             u.id,
             u.uid,
             u.nickname,
             u.status,
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
            AND b.lifted_at IS NULL
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
             ) AS blocked
           FROM device_account_links_mb l
           JOIN user_devices_mb d ON d.id = l.device_row_id
           WHERE l.user_id IS NOT NULL
           ORDER BY l.last_bound_at DESC, d.id DESC`,
        ),
      ]);
      const accountResult = results[0] as DatabaseResult<SecurityAccountRow>;
      const deviceResult = results[1] as DatabaseResult<DeviceLinkRow>;
      const devicesByUser = new Map<number, SecurityDevice[]>();
      for (const row of deviceResult.results) {
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
          firstBoundAt: cleanText(row.first_bound_at),
          lastBoundAt: cleanText(row.last_bound_at),
          lastSeenAt: cleanText(row.last_seen_at),
        });
        devicesByUser.set(userId, devices);
      }
      return json({
        ok: true,
        accounts: accountResult.results.map((row) => {
          const user = rowToUser(row);
          return {
            uid: user.uid,
            nickname: user.nickname,
            email: user.email,
            accountType: user.accountType,
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
