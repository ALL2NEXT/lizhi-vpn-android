/*
 * Account routes.
 *
 * Accounts are exposed as one of three types: banned, guest, or formal.
 */

export function createAccountRoutes(deps) {
  const {
    publicAccount,
    usersDb,
    ensureUserSchema,
    resolveDeviceIdentity,
    readDeviceContextFromDb,
    readD1User,
    readD1UserWithTraffic,
    attachDeviceToUser,
    registerOrTouchUser,
    normalizeNickname,
    normalizeRegistrationCode,
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

  async function handleAccount(path, request, env) {
    if (path === "/api/account/register" && request.method === "POST") {
      const body = await request.json();
      const deviceId = normalizeAccountId(body && body.deviceId);
      if (!deviceId) return json({ ok: false, error: "invalid_device_id" }, 400);

      const now = new Date();
      await ensureUserSchema(env);
      const identity = await registerOrTouchUser(env, deviceId, body || {}, now);
      const user = identity && identity.userId > 0
        ? await readD1UserWithTraffic(env, identity.userId, undefined, now)
        : null;
      if (!user) {
        if (identity && identity.deviceBanned) {
          return json({ ok: false, error: "device_banned", deviceBlocked: true }, 403);
        }
        throw new Error("account_create_failed");
      }
      user.deviceBlocked = Boolean(identity.deviceBanned);

      return json({
        ok: true,
        account: publicAccount(user),
        deviceBlocked: Boolean(identity.deviceBanned),
      });
    }

    if (path === "/api/account/bind" && request.method === "POST") {
      const body = await request.json();
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
      const user = await readD1User(env, identity.userId);
      if (!user) return json({ ok: false, error: "account_not_found" }, 404);
      if (user.accountType === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      if (user.email) {
        return json({ ok: false, error: "account_already_bound" }, 409);
      }
      const emailOwner = await db.prepare(
        "SELECT id FROM users_mb WHERE email = ? AND id <> ?",
      ).bind(email, identity.userId).first();
      if (emailOwner) return json({ ok: false, error: "email_already_bound" }, 409);

      if (registrationCode) {
        const codeRow = await db.prepare(
          "SELECT id FROM registration_codes_mb WHERE code = ? AND used_at IS NULL",
        ).bind(registrationCode).first();
        if (!codeRow) return json({ ok: false, error: "invalid_registration_code" }, 409);
      }

      const passwordHash = await hashAccountPassword(password);
      const boundAt = beijingTimestamp();
      const bindingStatements = [
        db.prepare(
          "UPDATE users_mb SET email = ?, password_hash = ? " +
            "WHERE id = ? AND (email IS NULL OR email = '')",
        ).bind(email, passwordHash, identity.userId),
      ];
      if (registrationCode) {
        bindingStatements.push(
          db.prepare(
            "UPDATE registration_codes_mb " +
              "SET used_at = ?, used_by_user_id = ?, used_by_uid = ? " +
              "WHERE code = ? AND used_at IS NULL",
          ).bind(boundAt, identity.userId, identity.uid, registrationCode),
        );
      }

      const results = await db.batch(bindingStatements);
      if (!results[0].meta || Number(results[0].meta.changes) < 1) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      if (registrationCode &&
          (!results[1].meta || Number(results[1].meta.changes) < 1)) {
        return json({ ok: false, error: "invalid_registration_code" }, 409);
      }

      const updated = await readD1UserWithTraffic(env, identity.userId);
      return json({ ok: true, account: publicAccount(updated) });
    }

    if (path === "/api/account/login" && request.method === "POST") {
      const body = await request.json();
      const deviceId = normalizeAccountId(body && body.deviceId);
      const email = normalizeAccountEmail(body && body.email);
      const password = normalizeAccountPassword(body && body.password);
      if (!deviceId || !email || !password) {
        return json({ ok: false, error: "invalid_login_credentials" }, 400);
      }

      const db = await ensureUserSchema(env);
      const currentDevice = await readDeviceContextFromDb(db, deviceId);
      if (currentDevice &&
          (currentDevice.deviceBanned || currentDevice.userStatus === "banned")) {
        return json({ ok: false, error: "device_banned" }, 403);
      }
      const row = await db.prepare(
        "SELECT id, uid, status, email, password_hash FROM users_mb WHERE email = ?",
      ).bind(email).first();
      if (!row || !(await verifyAccountPassword(password, cleanText(row.password_hash)))) {
        return json({ ok: false, error: "invalid_login_credentials" }, 401);
      }
      if (cleanText(row.status) === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }

      const attached = await attachDeviceToUser(db, Number(row.id), deviceId, body || {}, true);
      if (!attached) return json({ ok: false, error: "device_banned" }, 403);
      return json({
        ok: true,
        account: publicAccount(await readD1UserWithTraffic(env, Number(row.id))),
      });
    }

    if (path === "/api/account/delete" && request.method === "POST") {
      const body = await request.json();
      const deviceId = normalizeAccountId(body && body.deviceId);
      const requestedUid = normalizeUid(body && body.uid);
      const password = typeof (body && body.password) === "string" ? body.password : "";
      if (!deviceId) return json({ ok: false, error: "invalid_delete_request" }, 400);

      const db = await ensureUserSchema(env);
      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity || (requestedUid && requestedUid !== identity.uid)) {
        return json({ ok: false, error: "account_not_found" }, 404);
      }
      const user = await readD1User(env, identity.userId);
      if (!user) return json({ ok: false, error: "account_not_found" }, 404);
      if (user.accountType === "banned") {
        return json({ ok: false, error: "account_banned" }, 403);
      }
      const stored = await db.prepare(
        "SELECT email, password_hash FROM users_mb WHERE id = ?",
      ).bind(identity.userId).first();
      if (cleanText(stored && stored.email) &&
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
      const body = await request.json();
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
        account: publicAccount(await readD1UserWithTraffic(env, identity.userId)),
      });
    }

    if (path === "/api/account/report" && request.method === "POST") {
      const body = await request.json();
      const deviceId = normalizeAccountId(body && body.deviceId);
      const sessionId = cleanText(body && body.sessionId).slice(0, 128);
      const identity = await resolveDeviceIdentity(env, deviceId);
      if (!identity || !sessionId) return json({ ok: false, error: "invalid_report" }, 400);

      const user = await readD1User(env, identity.userId);
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
