import type { UserRow } from "../types/database.ts";
import type { Env } from "../types/env.ts";
import type { RouteDependencies } from "../types/routes.ts";

import { observeRequestUser, readJsonObject } from "../http/request.ts";

const SECURITY_FINDING_CODES = new Set([
  "capture_detection_unavailable",
  "http_proxy_configured",
  "http_proxy_pac_configured",
  "cleartext_traffic_blocked",
  "http_proxy_route_blocked",
  "https_downgrade_blocked",
  "tls_validation_failed",
  "root_capabilities",
  "root_framework_artifact",
  "root_magisk_service",
  "root_mount",
  "root_mounts_unavailable",
  "root_selinux_context",
  "root_status_unavailable",
  "root_su_binary",
  "root_uid",
  "xposed_artifact",
  "xposed_class",
  "xposed_classloader",
  "xposed_classloader_unavailable",
  "xposed_hook_callbacks",
  "xposed_maps_unavailable",
  "xposed_method_cache",
  "xposed_stack",
  "lsposed_artifact",
  "lsposed_class",
  "lsposed_classloader",
  "lsposed_classloader_unavailable",
  "lsposed_maps_unavailable",
  "lsposed_stack",
  "art_method_native",
  "frida_dbus",
  "frida_fds_unavailable",
  "frida_maps",
  "frida_thread",
  "frida_listener",
  "frida_named_pipe",
  "frida_server_artifact",
  "frida_threads_unavailable",
  "instrumentation_thread_tracer",
  "instrumentation_tracer",
  "native_code_modified",
  "native_code_writable",
  "process_maps_unavailable",
  "tracer_status_unavailable",
  "lspatch_apk_asset",
  "lspatch_class",
  "lspatch_component",
  "lspatch_detection_unavailable",
  "lspatch_metadata",
  "lspatch_native",
  "lspatch_private_files",
  "npatch_apk_asset",
  "npatch_class",
  "npatch_component",
  "npatch_detection_unavailable",
  "npatch_metadata",
  "npatch_native",
  "npatch_private_files",
  "signature_unavailable",
  "signature_mismatch",
  "screenshot_protection_unavailable",
  "adb_enabled",
  "developer_options_enabled",
  "screenshot_capture",
  "native_library_unavailable",
  "native_scan_error",
]);
/*
 * Account routes.
 *
 * Accounts are exposed as one of three types: banned, guest, or formal.
 */

export function createAccountRoutes(deps: Pick<
  RouteDependencies,
  | "publicAccount"
  | "usersDb"
  | "ensureUserSchema"
  | "resolveDeviceIdentity"
  | "readDeviceContextFromDb"
  | "readUser"
  | "readUserWithTraffic"
  | "attachDeviceToUser"
  | "registerOrTouchUser"
  | "normalizeNickname"
  | "normalizeRegistrationCode"
  | "normalizeVipActivationCode"
  | "beijingTimestamp"
  | "normalizeReportTimestamp"
  | "normalizeAccountId"
  | "normalizeAccountEmail"
  | "normalizeAccountPassword"
  | "hashAccountPassword"
  | "verifyAccountPassword"
  | "normalizeUid"
  | "reportBytes"
  | "readState"
  | "cleanText"
  | "json"
>) {
  const {
    publicAccount,
    usersDb,
    ensureUserSchema,
    resolveDeviceIdentity,
    readDeviceContextFromDb,
    readUser,
    readUserWithTraffic,
    attachDeviceToUser,
    registerOrTouchUser,
    normalizeNickname,
    normalizeRegistrationCode,
    normalizeVipActivationCode,
    beijingTimestamp,
    normalizeReportTimestamp,
    normalizeAccountId,
    normalizeAccountEmail,
    normalizeAccountPassword,
    hashAccountPassword,
    verifyAccountPassword,
    normalizeUid,
    reportBytes,
    readState,
    cleanText,
    json,
  } = deps;

  async function handleAccount(path: string, request: Request, env: Env) {
    if (path === "/api/account/register" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      if (!deviceId) return json({ ok: false, error: "invalid_device_id" }, 400);

      const now = new Date();
      await ensureUserSchema(env);
      const identity = await registerOrTouchUser(
        env,
        deviceId,
        body || {},
        now,
        request.headers.get("CF-Connecting-IP") || "",
      );
      if (identity && identity.userStatus === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      if (identity && identity.deviceBanned) {
        return json({ ok: false, error: "device_banned", deviceBlocked: true }, 403);
      }
      const user = identity && identity.userId > 0
        ? await readUserWithTraffic(env, identity.userId, undefined, now)
        : null;
      if (!user || !identity) {
        throw new Error("account_create_failed");
      }
      user.deviceBlocked = Boolean(identity.deviceBanned);
      observeRequestUser(env, identity);

      return json({
        ok: true,
        account: publicAccount(user),
        deviceBlocked: Boolean(identity.deviceBanned),
      });
    }

    if (path === "/api/account/bind" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const requestedUid = normalizeUid(body && body.uid);
      const email = normalizeAccountEmail(body && body.email);
      const password = normalizeAccountPassword(body && body.password);
      const rawRegistrationCode = cleanText(body && body.registrationCode);
      const registrationCode = normalizeRegistrationCode(rawRegistrationCode);
      if (!deviceId || !email || !password) {
        return json({ ok: false, error: "invalid_bind_credentials" }, 400);
      }
      if (rawRegistrationCode && !registrationCode) {
        return json({ ok: false, error: "invalid_registration_code" }, 400);
      }

      const db = await ensureUserSchema(env);
      const currentDevice = await readDeviceContextFromDb(db, deviceId);
      if (currentDevice && currentDevice.userStatus === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      if (currentDevice && currentDevice.deviceBanned) {
        return json({ ok: false, error: "device_banned" }, 403);
      }
      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity || (requestedUid && requestedUid !== identity.uid)) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      const state = await readState(env);
      if (!state.settings.registrationEnabled) {
        return json({ ok: false, error: "registration_disabled" }, 409);
      }
      if (state.settings.requireAdminRegistrationCode && !registrationCode) {
        return json({ ok: false, error: "registration_code_required" }, 400);
      }
      const user = await readUser(env, identity.userId);
      if (!user) return json({ ok: false, error: "account_not_found" }, 404);
      if (user.accountType === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      if (user.email) {
        return json({ ok: false, error: "account_already_bound" }, 409);
      }
      const emailOwner = await db.prepare(
        "SELECT id FROM users_mb WHERE email = ? AND id <> ?",
      ).bind(email, identity.userId).first<Pick<UserRow, "id">>();
      if (emailOwner) return json({ ok: false, error: "email_already_bound" }, 409);

      if (registrationCode) {
        const codeRow = await db.prepare(
          "SELECT id FROM registration_codes_mb WHERE code = ? AND use_count < max_uses",
        ).bind(registrationCode).first<{ id: number }>();
        if (!codeRow) return json({ ok: false, error: "invalid_registration_code" }, 409);
      }

      const passwordHash = await hashAccountPassword(password);
      const boundAt = beijingTimestamp();
      const bindingStatements = [
        db.prepare(
          "UPDATE users_mb SET email = ?, password_hash = ? " +
            "WHERE id = ? AND (email IS NULL OR email = '') " +
            "AND (? IS NULL OR EXISTS (" +
              "SELECT 1 FROM registration_codes_mb " +
              "WHERE code = ? AND use_count < max_uses" +
            "))",
        ).bind(
          email,
          passwordHash,
          identity.userId,
          registrationCode || null,
          registrationCode || null,
        ),
      ];
      if (registrationCode) {
        bindingStatements.push(
          db.prepare(
            "INSERT INTO registration_code_usages_mb " +
              "(registration_code_id, use_number, user_id, uid, used_at) " +
              "SELECT id, use_count + 1, ?, ?, ? " +
              "FROM registration_codes_mb " +
              "WHERE code = ? AND use_count < max_uses AND changes() > 0",
          ).bind(identity.userId, identity.uid, boundAt, registrationCode),
        );
      }

      // 绑定账号和记录注册码使用次数放在同一批事务里，避免超限时只完成一半。
      const results = await db.batch(bindingStatements);
      if (!results[0].meta || Number(results[0].meta.changes) < 1) {
        if (registrationCode) {
          const availableCode = await db.prepare(
            "SELECT id FROM registration_codes_mb WHERE code = ? AND use_count < max_uses",
          ).bind(registrationCode).first<{ id: number }>();
          if (!availableCode) {
            return json({ ok: false, error: "invalid_registration_code" }, 409);
          }
        }
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      if (registrationCode &&
          (!results[1].meta || Number(results[1].meta.changes) < 1)) {
        return json({ ok: false, error: "invalid_registration_code" }, 409);
      }

      const updated = await readUserWithTraffic(env, identity.userId);
      return json({ ok: true, account: publicAccount(updated) });
    }

    if (path === "/api/account/login" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const email = normalizeAccountEmail(body && body.email);
      const password = normalizeAccountPassword(body && body.password);
      if (!deviceId || !email || !password) {
        return json({ ok: false, error: "invalid_login_credentials" }, 400);
      }

      const db = await ensureUserSchema(env);
      const currentDevice = await readDeviceContextFromDb(db, deviceId);
      if (currentDevice && currentDevice.deviceBanned) {
        return json({ ok: false, error: "device_banned" }, 403);
      }
      const row = await db.prepare(
        "SELECT id, uid, status, email, password_hash FROM users_mb WHERE email = ?",
      ).bind(email).first<Pick<UserRow, "id" | "uid" | "status" | "email" | "password_hash">>();
      if (!row || !(await verifyAccountPassword(password, cleanText(row.password_hash)))) {
        return json({ ok: false, error: "invalid_login_credentials" }, 401);
      }
      if (cleanText(row.status) === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }

      const attached = await attachDeviceToUser(db, Number(row.id), deviceId, body || {}, true);
      if (!attached) return json({ ok: false, error: "device_banned" }, 403);
      observeRequestUser(env, { userId: Number(row.id), uid: row.uid, deviceId }, "login");
      return json({
        ok: true,
        account: publicAccount(await readUserWithTraffic(env, Number(row.id))),
      });
    }

    if (path === "/api/account/activate-vip" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const code = normalizeVipActivationCode(body && body.code);
      if (!deviceId || !code) return json({ ok: false, error: "invalid_vip_activation_request" }, 400);

      const db = await ensureUserSchema(env);
      const currentDevice = await readDeviceContextFromDb(db, deviceId);
      if (!currentDevice || currentDevice.userId <= 0) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      if (currentDevice.userStatus === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      if (currentDevice.deviceBanned) return json({ ok: false, error: "device_banned" }, 403);
      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity) return json({ ok: false, error: "account_not_found" }, 404);

      const user = await db.prepare(
        "SELECT status FROM users_mb WHERE id = ?",
      ).bind(identity.userId).first<{ status: string }>();
      if (!user) return json({ ok: false, error: "account_not_found" }, 404);
      if (cleanText(user.status) !== "active") {
        return json({ ok: false, error: "account_banned" }, 403);
      }

      const card = await db.prepare(
        "SELECT duration_days FROM vip_activation_codes_mb WHERE code = ? AND used_at IS NULL",
      ).bind(code).first<{ duration_days: number }>();
      if (!card) return json({ ok: false, error: "invalid_or_used_vip_card" }, 409);

      const now = new Date();
      const usedAt = beijingTimestamp(now);
      const results = await db.batch([
        db.prepare(
          `UPDATE vip_activation_codes_mb
              SET used_at = ?, used_by_user_id = ?, used_by_uid = ?
            WHERE code = ? AND used_at IS NULL
              AND EXISTS (SELECT 1 FROM users_mb WHERE id = ? AND status = 'active')`,
        ).bind(usedAt, identity.userId, identity.uid, code, identity.userId),
        db.prepare(
          `UPDATE users_mb
              SET vip_expires_at = strftime(
                '%Y-%m-%dT%H:%M:%S+08:00',
                MAX(COALESCE(unixepoch(vip_expires_at), 0), unixepoch(?)) + ? * 86400,
                'unixepoch', '+8 hours'
              )
            WHERE id = ? AND status = 'active' AND changes() > 0`,
        ).bind(usedAt, Number(card.duration_days), identity.userId),
      ]);
      if (!results[0].meta || Number(results[0].meta.changes) < 1) {
        return json({ ok: false, error: "invalid_or_used_vip_card" }, 409);
      }
      if (!results[1].meta || Number(results[1].meta.changes) < 1) {
        throw new Error("vip_activation_update_failed");
      }

      return json({
        ok: true,
        account: publicAccount(await readUserWithTraffic(env, identity.userId)),
        addedDays: Number(card.duration_days),
      });
    }

    if (path === "/api/account/delete" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const requestedUid = normalizeUid(body && body.uid);
      const password = typeof body.password === "string" ? body.password : "";
      if (!deviceId) return json({ ok: false, error: "invalid_delete_request" }, 400);

      const db = await ensureUserSchema(env);
      const identity = await readDeviceContextFromDb(db, deviceId);
      if (!identity || (requestedUid && requestedUid !== identity.uid)) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      if (identity.userStatus === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      if (identity.deviceBanned) {
        return json({ ok: false, error: "device_banned" }, 403);
      }
      const user = await readUser(env, identity.userId);
      if (!user) return json({ ok: false, error: "account_not_found" }, 404);
      if (user.accountType === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      const stored = await db.prepare(
        "SELECT email, password_hash FROM users_mb WHERE id = ?",
      ).bind(identity.userId).first<Pick<UserRow, "email" | "password_hash">>();
      if (stored && cleanText(stored.email) &&
          !(await verifyAccountPassword(password, cleanText(stored.password_hash)))) {
        return json({ ok: false, error: "invalid_account_password" }, 401);
      }

      const deleteResult = await db.prepare("DELETE FROM users_mb WHERE id = ?")
        .bind(identity.userId).run();
      if (!deleteResult.meta || Number(deleteResult.meta.changes) < 1) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      return json({ ok: true });
    }

    if (path === "/api/account/profile" && request.method === "PATCH") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const nickname = normalizeNickname(body && body.nickname);
      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity || !nickname) return json({ ok: false, error: "invalid_profile" }, 400);

      const db = await ensureUserSchema(env);
      const result = await db.prepare(
        "UPDATE users_mb SET nickname = ? WHERE id = ?",
      ).bind(nickname, identity.userId).run();
      if (!result.meta || Number(result.meta.changes) < 1) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      return json({
        ok: true,
        account: publicAccount(await readUserWithTraffic(env, identity.userId)),
      });
    }

    if (path === "/api/account/security-report" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const rawCodes = body && body.codes;
      if (!deviceId || !Array.isArray(rawCodes) || rawCodes.length < 1 || rawCodes.length > 20) {
        return json({ ok: false, error: "invalid_security_report" }, 400);
      }

      const codes = Array.from(new Set(rawCodes.map((value) => cleanText(value).slice(0, 200))));
      const invalidCode = codes.find((code) => {
        if (SECURITY_FINDING_CODES.has(code)) return false;
        if (!code.startsWith("blacklisted_package:")) return true;
        const packageName = code.slice("blacklisted_package:".length);
        return !/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(packageName);
      });
      if (codes.length === 0 || invalidCode) {
        return json({ ok: false, error: "invalid_security_finding_code" }, 400);
      }

      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity) return json({ ok: false, error: "device_not_registered" }, 404);

      const reportedAt = beijingTimestamp();
      const db = usersDb(env);
      await db.batch([
        db.prepare("UPDATE users_mb SET identity_type = 'bot' WHERE id = ?")
          .bind(identity.userId),
        ...codes.map((code) => db.prepare(
          `INSERT INTO client_security_reports_mb (
             device_id, finding_code, first_reported_at, last_reported_at, report_count, status
           ) VALUES (?, ?, ?, ?, 1, 'pending')
           ON CONFLICT(device_id, finding_code) DO UPDATE SET
             last_reported_at = excluded.last_reported_at,
             report_count = MIN(client_security_reports_mb.report_count + 1, 1000000000),
             status = CASE
               WHEN client_security_reports_mb.status = 'banned' THEN 'banned'
               ELSE 'pending'
             END,
             reviewed_at = CASE
               WHEN client_security_reports_mb.status = 'banned'
                 THEN client_security_reports_mb.reviewed_at
               ELSE NULL
             END`,
        ).bind(deviceId, code, reportedAt, reportedAt)),
      ]);
      return json({ ok: true, acceptedCount: codes.length, identityType: "bot" });
    }

    if (path === "/api/account/report" && request.method === "POST") {
      const body = await readJsonObject(request);
      const deviceId = normalizeAccountId(body && body.deviceId);
      const sessionId = cleanText(body && body.sessionId).slice(0, 128);
      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity || !sessionId) return json({ ok: false, error: "invalid_report" }, 400);

      const user = await readUser(env, identity.userId);
      if (!user) return json({ ok: false, error: "account_not_found" }, 404);
      if (user.accountType === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }

      const now = new Date();
      const endedAt = normalizeReportTimestamp(body && body.endedAt, beijingTimestamp(now));
      const startedAt = normalizeReportTimestamp(body && body.startedAt, endedAt);
      if (
        !startedAt ||
        !endedAt ||
        Date.parse(startedAt) > Date.parse(endedAt) ||
        Date.parse(endedAt) > now.getTime() + 5 * 60 * 1000
      ) {
        return json({ ok: false, error: "invalid_report_time" }, 400);
      }

      const db = usersDb(env);
      const stored = await db.prepare(
        `INSERT INTO user_usage_mb
         (user_id, session_id, subscription_group_name, started_at, ended_at,
          reported_at, upload_bytes, download_bytes)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, session_id) DO UPDATE SET
           subscription_group_name = CASE
             WHEN excluded.subscription_group_name <> ''
             THEN excluded.subscription_group_name
             ELSE user_usage_mb.subscription_group_name END,
           ended_at = MAX(user_usage_mb.ended_at, excluded.ended_at),
           reported_at = excluded.reported_at,
           upload_bytes = MAX(user_usage_mb.upload_bytes, excluded.upload_bytes),
           download_bytes = MAX(user_usage_mb.download_bytes, excluded.download_bytes)`,
      ).bind(
        identity.userId,
        sessionId,
        cleanText(body && body.subscriptionGroupName).slice(0, 200),
        startedAt,
        endedAt,
        beijingTimestamp(now),
        reportBytes(body && body.uploadBytes, body && body.uploadMb),
        reportBytes(body && body.downloadBytes, body && body.downloadMb),
      ).run();
      return json({ ok: true, stored: Number(stored.meta && stored.meta.changes) > 0 });
    }

    return json({ error: "account_route_not_found" }, 404);
  }

  return { handleAccount };
}
