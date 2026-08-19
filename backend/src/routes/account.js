/*
 * Account routes.
 *
 * Handlers receive only the shared runtime dependencies they use.
 */

export function createAccountRoutes(deps) {
  const {
    MAX_SPEED_SHARE_RESULTS,
    ACCOUNT_BINDING_EXPERIENCE,
    DEFAULT_INVITATION_EXPERIENCE,
    DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
    REWARD_TYPE_ACCOUNT_BINDING,
    REWARD_TYPE_INVITATION,
    REWARD_TYPE_SPEED_SHARE,
    REWARD_TYPE_TELEGRAM,
    REWARD_PERIOD_LIFETIME,
    normalizeSpeedShareResult,
    speedShareRequestLocation,
    publicAccount,
    rewardClaimStatement,
    invitationRewardClaimStatement,
    readSpeedShareStatus,
    readTelegramTask,
    publicAdminSpeedShareResult,
    usersDb,
    ensureUserSchema,
    resolveDeviceIdentity,
    readDeviceContextFromDb,
    readD1User,
    attachDeviceToUser,
    registerOrTouchUser,
    normalizeNickname,
    normalizeExperience,
    normalizeInviteCode,
    usagePeriodKeys,
    beijingTimestamp,
    normalizeReportTimestamp,
    normalizeAccountId,
    normalizeAccountEmail,
    normalizeAccountPassword,
    normalizeTelegramJoinCode,
    hashAccountPassword,
    isEmailBoundAccount,
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
    if (!deviceId) {
      return json({ ok: false, error: "invalid_device_id" }, 400);
    }

    const now = new Date();
    await ensureUserSchema(env);
    const identity = await registerOrTouchUser(env, deviceId, body || {}, now);
    const state = await readState(env);
    const user = identity && identity.userId > 0
      ? await readD1User(env, identity.userId, state.settings)
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
    if (!deviceId || !email || !password) {
      return json({ ok: false, error: "invalid_bind_credentials" }, 400);
    }

    const db = await ensureUserSchema(env);
    const identity = await resolveDeviceIdentity(env, deviceId);
    if (!identity || (requestedUid && requestedUid !== identity.uid)) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const state = await readState(env);
    if (!state.settings.formalRegistrationEnabled) {
      return json({ ok: false, error: "formal_registration_closed" }, 409);
    }
    const user = await readD1User(env, identity.userId, state.settings);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    if (user.email) {
      return json({ ok: false, error: "account_already_bound" }, 409);
    }
    const emailOwner = await db.prepare(
      "SELECT id FROM users_mb WHERE email = ? AND id <> ?",
    ).bind(email, identity.userId).first();
    if (emailOwner) {
      return json({ ok: false, error: "email_already_bound" }, 409);
    }

    const passwordHash = await hashAccountPassword(password);
    const boundAt = beijingTimestamp();
    const reward = normalizeExperience(
      state.settings.accountBindingExperience,
      ACCOUNT_BINDING_EXPERIENCE,
    );
    const results = await db.batch([
      db.prepare(
        "UPDATE users_mb SET email = ?, password_hash = ? " +
          "WHERE id = ? AND (email IS NULL OR email = '')",

      ).bind(email, passwordHash, identity.userId),
      rewardClaimStatement(db, {
        userId: identity.userId,
        userUid: identity.uid,
        deviceRowId: identity.deviceRowId,
        rewardType: REWARD_TYPE_ACCOUNT_BINDING,
        periodKey: REWARD_PERIOD_LIFETIME,
        contextKey: "email-v1",
        claimedAt: boundAt,
        experienceAwarded: reward,
      }),
    ]);
    if (!results[0].meta || Number(results[0].meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const updated = await readD1User(env, identity.userId, state.settings);
    return json({
      ok: true,
      account: publicAccount(updated),
      experienceAwarded: reward,
    });
  }

  // 每个账户只能绑定一次上级邀请码，绑定关系用于统计邀请来源。
  if (path === "/api/account/invite" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const inviteCode = normalizeInviteCode(body && body.code);
    if (!deviceId || !inviteCode) {
      return json({ ok: false, error: "invalid_invite_code" }, 400);
    }

    const identity = await resolveDeviceIdentity(env, deviceId);
    if (!identity) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const db = await ensureUserSchema(env);
    const state = await readState(env);
    const user = await readD1User(env, identity.userId, state.settings);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.invitedByCode) {
      return json({ ok: false, error: "invite_already_bound" }, 409);
    }

    const inviter = await db.prepare(
      "SELECT id, uid, status, email FROM users_mb WHERE invite_code = ?",
    ).bind(inviteCode).first();
    if (!inviter || cleanText(inviter.status) !== "active") {
      return json({ ok: false, error: "invite_code_not_found" }, 404);
    }
    if (Number(inviter.id) === identity.userId) {
      return json({ ok: false, error: "invite_self_not_allowed" }, 409);
    }

    const invitedAt = beijingTimestamp();
    const reward = normalizeExperience(
      state.settings.invitationExperience,
      DEFAULT_INVITATION_EXPERIENCE,
    );
    const inviterCanEarnExperience = isEmailBoundAccount(inviter);
    const results = await db.batch([
      db.prepare(
        "UPDATE users_mb SET invited_by_user_id = ?, invited_at = ? " +
          "WHERE id = ? AND invited_by_user_id IS NULL",
      ).bind(Number(inviter.id), invitedAt, identity.userId),
      invitationRewardClaimStatement(db, {
        userId: Number(inviter.id),
        userUid: cleanText(inviter.uid),
        deviceRowId: identity.deviceRowId,
        rewardType: REWARD_TYPE_INVITATION,
        periodKey: REWARD_PERIOD_LIFETIME,
        contextKey: `invitee-${identity.userId}`,
        claimedAt: invitedAt,
        experienceAwarded: reward,
        inviteeUserId: identity.userId,
      }),
    ]);
    if (!results[0].meta || Number(results[0].meta.changes) < 1) {
      return json({ ok: false, error: "invite_already_bound" }, 409);
    }
    const updated = await readD1User(env, identity.userId, state.settings);
    return json({
      ok: true,
      account: publicAccount(updated),
      experienceAwarded: inviterCanEarnExperience ? reward : 0,
      rewardedUserUid: cleanText(inviter.uid),
    });
  }

  // 使用邮箱和密码切换账户，同时把当前设备转移到登录账户。
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

    const attached = await attachDeviceToUser(
      db,
      Number(row.id),
      deviceId,
      body || {},
      true,
    );
    if (!attached) return json({ ok: false, error: "device_banned" }, 403);
    const state = await readState(env);
    const account = await readD1User(env, Number(row.id), state.settings);
    return json({ ok: true, account: publicAccount(account) });
  }

  // 注销会删除账户数据，但设备身份、历史关联和设备级奖励记录会保留。
  if (path === "/api/account/delete" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const requestedUid = normalizeUid(body && body.uid);
    const password = typeof (body && body.password) === "string" ? body.password : "";
    if (!deviceId) {
      return json({ ok: false, error: "invalid_delete_request" }, 400);
    }

    const db = await ensureUserSchema(env);
    const identity = await resolveDeviceIdentity(env, deviceId);
    if (!identity || (requestedUid && requestedUid !== identity.uid)) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    const stored = await db.prepare(
      "SELECT email, password_hash FROM users_mb WHERE id = ?",
    ).bind(identity.userId).first();
    if (cleanText(stored && stored.email)) {
      if (!(await verifyAccountPassword(password, cleanText(stored.password_hash)))) {
        return json({ ok: false, error: "invalid_account_password" }, 401);
      }
    }

    const deleteResults = await db.batch([
      db.prepare(
        "UPDATE users_mb SET invited_by_user_id = NULL, invited_at = NULL " +
          "WHERE invited_by_user_id = ?",
      ).bind(identity.userId),
      db.prepare("DELETE FROM users_mb WHERE id = ?").bind(identity.userId),
    ]);
    if (!deleteResults[1].meta || Number(deleteResults[1].meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({ ok: true });
  }

  if (path === "/api/account/profile" && request.method === "PATCH") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const nickname = normalizeNickname(body && body.nickname);
    const identity = await resolveDeviceIdentity(env, deviceId);
    if (!identity || !nickname) {
      return json({ ok: false, error: "invalid_profile" }, 400);
    }

    const db = await ensureUserSchema(env);
    const result = await db.prepare(
      "UPDATE users_mb SET nickname = ? WHERE id = ?",
    ).bind(nickname, identity.userId).run();
    if (!result.meta || Number(result.meta.changes) < 1) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const state = await readState(env);
    const user = await readD1User(env, identity.userId, state.settings);
    return json({ ok: true, account: publicAccount(user) });
  }

  if (path === "/api/account/tasks" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const identity = await resolveDeviceIdentity(
      env,
      requestUrl.searchParams.get("deviceId"),
    );
    if (!identity) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const state = await readState(env);
    const user = await readD1User(env, identity.userId, state.settings);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    return json({
      ok: true,
      account: publicAccount(user),
      speedShare: await readSpeedShareStatus(
        env, identity.userId, identity.deviceRowId, state.settings),
      telegram: await readTelegramTask(
        env, identity.userId, identity.deviceRowId, state.settings),
    });
  }

  if (path === "/api/account/tasks/speed-share-results" && request.method === "GET") {
    const requestUrl = new URL(request.url);
    const identity = await resolveDeviceIdentity(
      env,
      requestUrl.searchParams.get("deviceId"),
    );
    if (!identity) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    const state = await readState(env);
    const user = await readD1User(env, identity.userId, state.settings);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    const db = await ensureUserSchema(env);
    const query = await db.prepare(
      "SELECT node_id, server_host, subscription_group_name, province, carrier, " +
        "tcping_latency_ms, url_test_latency_ms, download_mbps, " +
        "upload_mbps, updated_at, last_reported_at, report_count " +
        "FROM speed_share_results_mb ORDER BY last_reported_at DESC LIMIT 1000",
    ).all();
    const clientLocation = speedShareRequestLocation(request);
    return json({
      ok: true,
      clientProvince: clientLocation.province,
      clientCarrier: clientLocation.carrier,
      results: (query.results || []).map(publicAdminSpeedShareResult),
      generatedAt: beijingTimestamp(),
    });
  }

  if (path === "/api/account/tasks/speed-share" && request.method === "POST") {
    return handleSpeedShareSubmission(request, env);
  }

  if (path === "/api/account/tasks/telegram" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const telegramCode = normalizeTelegramJoinCode(body && body.code);
    const identity = await resolveDeviceIdentity(env, deviceId);
    if (!identity || !telegramCode) {
      return json({ ok: false, error: "invalid_telegram_task_code" }, 400);
    }

    const state = await readState(env);
    if (!state.settings.telegramGroupUrl || !state.settings.telegramJoinCode) {
      return json({ ok: false, error: "telegram_task_disabled" }, 409);
    }
    const db = await ensureUserSchema(env);

    const user = await readD1User(env, identity.userId, state.settings);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
      return json({ ok: false, error: "account_banned" }, 403);
    }
    if (!isEmailBoundAccount(user)) {
      return json({ ok: false, error: "email_binding_required" }, 403);
    }
    if (telegramCode !== state.settings.telegramJoinCode) {
      return json({ ok: false, error: "telegram_task_invalid_code" }, 403);
    }

    const claimedAt = beijingTimestamp();
    const reward = normalizeExperience(
      state.settings.telegramTaskExperience,
      DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    );
    const inserted = await rewardClaimStatement(db, {
      userId: identity.userId,
      userUid: identity.uid,
      deviceRowId: identity.deviceRowId,
      rewardType: REWARD_TYPE_TELEGRAM,
      periodKey: REWARD_PERIOD_LIFETIME,
      contextKey: "join-v1",
      claimedAt,
      experienceAwarded: reward,
    }).run();
    if (!inserted || !inserted.meta || Number(inserted.meta.changes) < 1) {
      return json({ ok: false, error: "telegram_task_already_claimed" }, 409);
    }
    const updated = await readD1User(env, identity.userId, state.settings);

    return json({
      ok: true,
      account: publicAccount(updated),
      telegram: await readTelegramTask(
        env, identity.userId, identity.deviceRowId, state.settings),
    });
  }

  if (path === "/api/account/report" && request.method === "POST") {
    const body = await request.json();
    const deviceId = normalizeAccountId(body && body.deviceId);
    const sessionId = cleanText(body && body.sessionId).slice(0, 128);
    const identity = await resolveDeviceIdentity(env, deviceId);
    if (!identity || !sessionId) {
      return json({ ok: false, error: "invalid_report" }, 400);
    }

    await ensureUserSchema(env);
    const user = await readD1User(env, identity.userId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.level === 0) {
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
    const subscriptionGroupName = cleanText(body && body.subscriptionGroupName).slice(0, 200);
    const uploadBytes = reportBytes(body && body.uploadBytes, body && body.uploadMb);
    const downloadBytes = reportBytes(body && body.downloadBytes, body && body.downloadMb);
    const reportedAt = beijingTimestamp(now);
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
      subscriptionGroupName,
      startedAt,
      endedAt,
      reportedAt,
      uploadBytes,
      downloadBytes,
    ).run();
    return json({ ok: true, stored: Number(stored.meta && stored.meta.changes) > 0 });
  }

  return json({ error: "account_route_not_found" }, 404);
}

async function handleSpeedShareSubmission(request, env) {
  const body = await request.json();
  const deviceId = normalizeAccountId(body && body.deviceId);
  if (!Array.isArray(body && body.results) || body.results.length === 0 ||
      body.results.length > MAX_SPEED_SHARE_RESULTS) {
    return json({ ok: false, error: "invalid_speed_share_results" }, 400);
  }

  const identity = await resolveDeviceIdentity(env, deviceId);
  if (!identity) {
    return json({ ok: false, error: "account_not_found" }, 404);
  }
  const state = await readState(env);
  const user = await readD1User(env, identity.userId, state.settings);
  if (!user) {
    return json({ ok: false, error: "account_not_found" }, 404);
  }
  if (user.level === 0) {
    return json({ ok: false, error: "account_banned" }, 403);
  }
  if (!isEmailBoundAccount(user)) {
    return json({ ok: false, error: "email_binding_required" }, 403);
  }

  const requestLocation = speedShareRequestLocation(request);
  let results;
  try {
    results = body.results
      .map((value) => normalizeSpeedShareResult(value, requestLocation))
      .filter(Boolean);
  } catch (error) {
    return json({
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    }, 400);
  }
  if (results.length === 0) {
    return json({ ok: false, error: "speed_share_results_empty" }, 400);
  }

  const db = await ensureUserSchema(env);
  const now = new Date();
  const reportHour = usagePeriodKeys(now).hour;
  const subscriptionGroupName = cleanText(results[0].subscriptionGroupName).slice(0, 200) || "__default__";
  const reportedAt = beijingTimestamp(now);
  const reward = normalizeExperience(
    state.settings.speedShareSmartExperience,
    DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  );
  const claimToken = crypto.randomUUID();
  const claim = rewardClaimStatement(db, {
    userId: identity.userId,
    userUid: identity.uid,
    deviceRowId: identity.deviceRowId,
    rewardType: REWARD_TYPE_SPEED_SHARE,
    periodKey: reportHour,
    contextKey: subscriptionGroupName,
    claimToken,
    claimedAt: reportedAt,
    experienceAwarded: reward,
  });
  const statements = results.map((item) => db.prepare(
    `INSERT INTO speed_share_results_mb
     (subscription_group_name, node_id, province, carrier, server_host,
      tcping_latency_ms, url_test_latency_ms, download_mbps, upload_mbps,
      updated_at, last_reported_at, last_reporter_user_id, report_count)
     SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1
     WHERE EXISTS (
       SELECT 1 FROM user_reward_claims_mb WHERE claim_token = ?
     )
     ON CONFLICT(subscription_group_name, node_id, province, carrier) DO UPDATE SET
       server_host = excluded.server_host,
       tcping_latency_ms = excluded.tcping_latency_ms,
       url_test_latency_ms = excluded.url_test_latency_ms,
       download_mbps = excluded.download_mbps,
       upload_mbps = excluded.upload_mbps,
       updated_at = excluded.updated_at,
       last_reported_at = excluded.last_reported_at,
       last_reporter_user_id = excluded.last_reporter_user_id,
       report_count = speed_share_results_mb.report_count + 1`,
  ).bind(
    subscriptionGroupName,
    item.nodeId,
    item.province,
    item.carrier,
    item.serverHost,
    item.tcpingLatencyMs,
    item.urlTestLatencyMs,
    item.downloadMbps,
    item.uploadMbps,
    reportedAt,
    reportedAt,
    identity.userId,
    claimToken,
  ));
  const batchResults = await db.batch([claim, ...statements]);
  const claimResult = batchResults[0];
  if (!claimResult || !claimResult.meta || Number(claimResult.meta.changes) < 1) {
    return json({
      ok: false,
      error: "speed_share_already_reported_this_hour_group",
      speedShare: await readSpeedShareStatus(
        env, identity.userId, identity.deviceRowId, state.settings),
    });
  }
  const updated = await readD1User(env, identity.userId, state.settings);

  return json({
    ok: true,
    acceptedResults: results.length,
    account: publicAccount(updated),
    experienceAwarded: reward,
    speedShare: await readSpeedShareStatus(
      env, identity.userId, identity.deviceRowId, state.settings),
  });
}

  return { handleAccount, handleSpeedShareSubmission };
}
