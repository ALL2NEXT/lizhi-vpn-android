/*
 * FireflyVPN Cloudflare Worker runtime.
 *
 * The public deployment entry remains ../serverless.js. Route handlers are
 * split by responsibility under ./routes, while this file owns the shared
 * runtime helpers and Worker composition.
 */

import * as config from "./config.js";
import { adminHtml } from "./admin-ui.js";
import { createClientRoutes } from "./routes/client.js";
import { createAccountRoutes } from "./routes/account.js";
import { createAdminRoutes } from "./routes/admin.js";

const {
  STATE_KEY,
  CLIENT_IP_HEADER,
  MAX_MANAGED_SUBSCRIPTION_BYTES,
  MAX_BATCH_USER_COUNT,
  MAX_SPEED_SHARE_RESULTS,
  MAX_TRAFFIC_BYTES,
  MAX_ACCOUNT_EXPERIENCE,
  ACCOUNT_BINDING_EXPERIENCE,
  DEFAULT_DAILY_CHECKIN_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  DEFAULT_EXPERIENCE_THRESHOLDS,
  SUBSCRIPTION_CACHE_KEY_PREFIX,
  DEFAULT_SUBSCRIPTION_CACHE_MINUTES,
  MAX_SUBSCRIPTION_CACHE_MINUTES,
  PLAINTEXT_NODE_PATTERN,
  PRIVATE_B64_ALPHABET,
  TEXT_ENCODER,
  USER_SCHEMA_VERSION,
  USER_SCHEMA_STATEMENTS,
  REWARD_TYPE_ACCOUNT_BINDING,
  REWARD_TYPE_DAILY_CHECKIN,
  REWARD_TYPE_SPEED_SHARE,
  REWARD_TYPE_TELEGRAM,
  REWARD_PERIOD_LIFETIME,
  ADMIN_UUID,
  ADMIN_BASE_PATH,
  DEFAULT_STATE,
} = config;

let userSchemaReady = false;
let userSchemaInitialization = null;

function normalizeSpeedShareResult(value, location = {}) {
  if (!plainObject(value)) throw new Error("invalid_speed_share_result");
  const nodeId = cleanText(value.nodeId || value.id).slice(0, 128);
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/.test(nodeId)) {
    throw new Error("invalid_speed_share_node_id");
  }
  const result = {
    nodeId,
    nodeName: cleanText(value.nodeName || value.name).slice(0, 200),
    serverHost: normalizeSpeedShareServerHost(
      value.serverHost || value.server || value.host,
    ),
    subscriptionGroupName: cleanText(
      value.subscriptionGroupName || value.groupName,
    ).slice(0, 200),
    tcpingLatencyMs: sharedLatency(value.tcpingLatencyMs ?? value.tcpingLatency),
    urlTestLatencyMs: sharedLatency(value.urlTestLatencyMs ?? value.urlTestLatency),
    downloadMbps: sharedMbps(value.downloadMbps ?? value.download),
    uploadMbps: sharedMbps(value.uploadMbps ?? value.upload),
    province: cleanText(location.province) || normalizeSpeedShareProvince(value.province),
    carrier: cleanText(location.carrier) || normalizeSpeedShareCarrier(value.carrier || value.operator),
  };
  return result.tcpingLatencyMs > 0 || result.urlTestLatencyMs > 0 ||
    result.downloadMbps > 0 || result.uploadMbps > 0
    ? result
    : null;
}

function speedShareRequestLocation(request) {
  const cf = request && request.cf && typeof request.cf === "object" ? request.cf : {};
  return {

    province: normalizeSpeedShareProvince(cf.region, cf.regionCode),
    carrier: normalizeSpeedShareCarrier(cf.asOrganization),
  };
}

function normalizeSpeedShareProvince(value, regionCode = "") {
  const text = cleanText(value).slice(0, 80);
  const code = cleanText(regionCode).toUpperCase().replace(/^CN-/, "");
  const codeNames = {
    AH: "安徽省", BJ: "北京市", CQ: "重庆市", FJ: "福建省", GS: "甘肃省",
    GD: "广东省", GX: "广西壮族自治区", GZ: "贵州省", HI: "海南省", HB: "河北省",
    HE: "河南省", HL: "黑龙江省", HN: "湖南省", HU: "湖北省", JL: "吉林省",
    JS: "江苏省", JX: "江西省", LN: "辽宁省", NM: "内蒙古自治区", NX: "宁夏回族自治区",
    QH: "青海省", SC: "四川省", SN: "陕西省", SD: "山东省", SH: "上海市", SX: "山西省",
    TJ: "天津市", XJ: "新疆维吾尔自治区", XZ: "西藏自治区", YN: "云南省", ZJ: "浙江省",
    HK: "香港特别行政区", MO: "澳门特别行政区", TW: "台湾省",
  };
  if (codeNames[code]) return codeNames[code];
  if (!text) return "";
  const aliases = {
    anhui: "安徽省", 安徽: "安徽省", beijing: "北京市", 北京: "北京市",
    chongqing: "重庆市", 重庆: "重庆市", fujian: "福建省", 福建: "福建省",
    gansu: "甘肃省", 甘肃: "甘肃省", guangdong: "广东省", 广东: "广东省",
    guangxi: "广西壮族自治区", 广西: "广西壮族自治区", guizhou: "贵州省", 贵州: "贵州省",
    hainan: "海南省", 海南: "海南省", hebei: "河北省", 河北: "河北省",
    henan: "河南省", 河南: "河南省", heilongjiang: "黑龙江省", 黑龙江: "黑龙江省",
    hunan: "湖南省", 湖南: "湖南省", hubei: "湖北省", 湖北: "湖北省",
    jilin: "吉林省", 吉林: "吉林省", jiangsu: "江苏省", 江苏: "江苏省",
    jiangxi: "江西省", 江西: "江西省", liaoning: "辽宁省", 辽宁: "辽宁省",
    "inner mongolia": "内蒙古自治区", 内蒙古: "内蒙古自治区", ningxia: "宁夏回族自治区", 宁夏: "宁夏回族自治区",
    qinghai: "青海省", 青海: "青海省", sichuan: "四川省", 四川: "四川省", shaanxi: "陕西省", 陕西: "陕西省",
    shandong: "山东省", 山东: "山东省", shanghai: "上海市", 上海: "上海市",
    shanxi: "山西省", 山西: "山西省", tianjin: "天津市", 天津: "天津市",
    xinjiang: "新疆维吾尔自治区", 新疆: "新疆维吾尔自治区", tibet: "西藏自治区", 西藏: "西藏自治区",
    yunnan: "云南省", 云南: "云南省", zhejiang: "浙江省", 浙江: "浙江省",
    "hong kong": "香港特别行政区", 香港: "香港特别行政区", macau: "澳门特别行政区", 澳门: "澳门特别行政区",
    taiwan: "台湾省", 台湾: "台湾省",
  };
  const normalized = text.toLowerCase();
  const alias = Object.keys(aliases).find((key) => normalized.includes(key));
  return alias ? aliases[alias] : text;
}

function normalizeSpeedShareCarrier(value) {
  const text = cleanText(value).slice(0, 120);
  if (!text) return "";
  const normalized = text.toLowerCase().replace(/[\s_-]+/g, "");
  if (/(中国电信|电信|chinatelecom|telecom|chinanet)/i.test(normalized)) return "电信";
  if (/(中国联通|联通|chinaunicom|unicom)/i.test(normalized)) return "联通";
  if (/(中国移动|移动|chinamobile|cmcc|cmnet|mobile)/i.test(normalized)) return "移动";
  if (/(中国广电|广电|chinabroadnet|broadnet)/i.test(normalized)) return "广电";
  return text;
}

function sharedLatency(value) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 && number <= 600000 ? number : 0;
}

function sharedMbps(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 && number <= 1000000
    ? Math.round(number * 1000) / 1000
    : 0;
}

function normalizeSpeedShareServerHost(value) {
  let host = cleanText(value).slice(0, 255);
  if (!host) return "";
  if (host.startsWith("[")) {
    const closingBracket = host.indexOf("]");
    if (closingBracket > 1) host = host.slice(1, closingBracket);
  } else {
    const firstColon = host.indexOf(":");
    const lastColon = host.lastIndexOf(":");
    if (firstColon === lastColon && lastColon > 0 &&
        /^\d+$/.test(host.slice(lastColon + 1))) {
      host = host.slice(0, lastColon);
    }
  }
  return host.trim().replace(/\.$/, "").toLowerCase();
}



function publicAccount(user) {
  return {
    uid: user.uid,
    nickname: user.nickname,
    level: normalizeLevel(user.level),
    experience: normalizeExperience(user.experience),
    email: cleanText(user.email),
    brand: user.brand,
    model: user.model,
    androidVersion: user.androidVersion,
    androidSdk: normalizeAndroidSdk(user.androidSdk),
    appVersion: user.appVersion,
    deviceBlocked: Boolean(user.deviceBlocked),
  };
}

function rewardClaimStatement(db, claim) {
  const claimToken = cleanText(claim && claim.claimToken) || crypto.randomUUID();
  return db.prepare(
    `INSERT OR IGNORE INTO user_reward_claims_mb
     (user_id, user_uid, device_row_id, reward_type, period_key, context_key,
      claim_token, claimed_at, experience_awarded)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    Number(claim.userId),
    normalizeStoredUid(claim.userUid),
    Number(claim.deviceRowId),
    cleanText(claim.rewardType),
    cleanText(claim.periodKey),
    cleanText(claim.contextKey),
    claimToken,
    cleanText(claim.claimedAt),
    normalizeExperience(claim.experienceAwarded),
  );
}

async function readDailyCheckin(env, userId, deviceRowId, settings) {
  const db = await ensureUserSchema(env);
  const checkinDate = usagePeriodKeys(new Date()).day;
  const row = await db.prepare(
    `SELECT claimed_at, experience_awarded
     FROM user_reward_claims_mb
     WHERE (user_id = ? OR device_row_id = ?)
       AND reward_type = ? AND period_key = ? AND context_key = ''`,
  ).bind(userId, deviceRowId, REWARD_TYPE_DAILY_CHECKIN, checkinDate).first();
  return {
    enabled: true,
    date: checkinDate,
    rewardExperience: normalizeExperience(
      settings && settings.dailyCheckinExperience,
      DEFAULT_DAILY_CHECKIN_EXPERIENCE,
    ),
    status: row ? "checked_in" : "not_checked_in",
    checkedInAt: cleanText(row && row.claimed_at),
    experienceAwarded: normalizeExperience(row && row.experience_awarded),
  };
}

async function readSpeedShareStatus(env, userId, deviceRowId, settings = {}) {
  const db = await ensureUserSchema(env);
  const reportHour = usagePeriodKeys(new Date()).hour;
  const hourlyGroups = await db.prepare(
    "SELECT COUNT(DISTINCT context_key) AS reported_group_count, " +
      "MAX(claimed_at) AS reported_at " +
      "FROM user_reward_claims_mb " +
      "WHERE (user_id = ? OR device_row_id = ?) AND reward_type = ? AND period_key = ?",
  ).bind(userId, deviceRowId, REWARD_TYPE_SPEED_SHARE, reportHour).first();
  const latest = await db.prepare(
    "SELECT MAX(last_reported_at) AS last_reported_at FROM speed_share_results_mb",
  ).first();
  const count = await db.prepare(
    "SELECT COUNT(*) AS retained_count FROM speed_share_results_mb",
  ).first();
  return {
    enabled: true,
    smartReportedThisHour: Number(hourlyGroups && hourlyGroups.reported_group_count) > 0,
    smartReportedGroupCount: Number(hourlyGroups && hourlyGroups.reported_group_count) || 0,
    smartReportedAt: cleanText(hourlyGroups && hourlyGroups.reported_at),
    smartRewardExperience: normalizeExperience(
      settings && settings.speedShareSmartExperience,
      DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
    ),
    lastReportedAt: cleanText(latest && latest.last_reported_at),
    retainedResultCount: Number(count && count.retained_count) || 0,
  };
}

async function readTelegramTask(env, userId, deviceRowId, settings) {
  const db = await ensureUserSchema(env);
  const row = await db.prepare(
    `SELECT claimed_at, experience_awarded
     FROM user_reward_claims_mb
     WHERE (user_id = ? OR device_row_id = ?)
       AND reward_type = ? AND period_key = ? AND context_key = ?`,
  ).bind(
    userId,

    deviceRowId,
    REWARD_TYPE_TELEGRAM,
    REWARD_PERIOD_LIFETIME,
    "join-v1",
  ).first();
  return {
    enabled: Boolean(settings && settings.telegramGroupUrl && settings.telegramJoinCode),
    groupUrl: cleanText(settings && settings.telegramGroupUrl),
    rewardExperience: normalizeExperience(
      settings && settings.telegramTaskExperience,
      DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    ),
    status: row ? "claimed" : "not_claimed",
    claimedAt: cleanText(row && row.claimed_at),
    experienceAwarded: normalizeExperience(row && row.experience_awarded),
  };
}

function publicAdminUser(row, settings) {
  const todayUpload = bytesToMegabytes(row.today_upload_bytes);
  const todayDownload = bytesToMegabytes(row.today_download_bytes);
  const monthUpload = bytesToMegabytes(row.month_upload_bytes);
  const monthDownload = bytesToMegabytes(row.month_download_bytes);
  const totalUpload = bytesToMegabytes(row.total_upload_bytes);
  const totalDownload = bytesToMegabytes(row.total_download_bytes);
  return {
    ...publicAccount(rowToUser(row, settings)),
    createdAt: cleanText(row.created_at),
    lastReportAt: cleanText(row.last_report_at) || null,
    totalUploadMb: totalUpload,
    totalDownloadMb: totalDownload,
    totalMb: totalUpload + totalDownload,
    activeToday: Number(row.active_today) === 1,
    activeThisMonth: Number(row.active_this_month) === 1,
    today: {
      uploadMb: todayUpload,
      downloadMb: todayDownload,
      totalMb: todayUpload + todayDownload,
    },
    month: {
      uploadMb: monthUpload,
      downloadMb: monthDownload,
      totalMb: monthUpload + monthDownload,
    },
  };
}

function publicAdminSpeedShareResult(row) {
  return {
    nodeId: cleanText(row.node_id),
    nodeName: cleanText(row.node_name),
    serverHost: cleanText(row.server_host),
    subscriptionGroupName: cleanText(row.subscription_group_name),
    province: cleanText(row.province),
    carrier: cleanText(row.carrier),
    tcpingLatencyMs: sharedLatency(row.tcping_latency_ms),
    urlTestLatencyMs: sharedLatency(row.url_test_latency_ms),
    downloadMbps: sharedMbps(row.download_mbps),
    uploadMbps: sharedMbps(row.upload_mbps),
    updatedAt: cleanText(row.updated_at),
    lastReportedAt: cleanText(row.last_reported_at),
    reportCount: Number(row.report_count) || 0,
  };
}

function usersDb(env) {
  if (!env.USERS_DB) throw new Error("Missing D1 binding USERS_DB");
  return env.USERS_DB;
}

async function ensureUserSchema(env) {
  const db = usersDb(env);
  if (userSchemaReady) return db;
  await initializeUserSchema(env);
  return db;
}

async function initializeUserSchema(env) {
  const db = usersDb(env);
  if (userSchemaReady) return USER_SCHEMA_VERSION;
  if (!userSchemaInitialization) {
    userSchemaInitialization = initializeUserSchemaDatabase(db);
  }
  try {
    const version = await userSchemaInitialization;
    userSchemaReady = true;
    return version;
  } catch (error) {
    userSchemaInitialization = null;
    throw error;
  }
}

async function initializeUserSchemaDatabase(db) {
  let schema;
  try {
    schema = await db.prepare(
      "SELECT version FROM app_schema_mb WHERE component = 'users'",
    ).first();
  } catch (error) {
    const message = String(error && error.message || error);
    if (!/no such table[^]*app_schema_mb/i.test(message)) throw error;
    schema = null;
  }

  if (schema && Number(schema.version) !== USER_SCHEMA_VERSION) {
    throw new Error("users_db_schema_version_mismatch");
  }
  if (schema) return Number(schema.version);

  const existing = await db.prepare(
    `SELECT name FROM sqlite_schema
     WHERE type = 'table' AND name IN (
       'users_mb',
       'user_devices_mb',
       'device_account_links_mb',
       'device_bans_mb',
       'user_usage_mb',
       'user_usage_daily_mb',
       'user_reward_claims_mb',
       'speed_share_results_mb'
     )
     LIMIT 1`,
  ).first();
  if (existing) {
    throw new Error("users_db_unversioned_schema_detected");
  }

  await db.batch(
    USER_SCHEMA_STATEMENTS.map((statement) => db.prepare(statement)),
  );
  const initialized = await db.prepare(
    "SELECT version FROM app_schema_mb WHERE component = 'users'",
  ).first();
  if (!initialized || Number(initialized.version) !== USER_SCHEMA_VERSION) {
    throw new Error("users_db_initialization_failed");
  }
  return Number(initialized.version);
}

function normalizeUserReference(value) {
  return normalizeUid(value) || normalizeAccountId(value);
}

async function resolveUserIdentity(env, reference) {
  const normalized = normalizeUserReference(reference);
  if (!normalized) return null;
  const db = await ensureUserSchema(env);
  let row = null;
  if (normalizeUid(normalized)) {
    row = await db.prepare("SELECT id AS user_id, uid FROM users_mb WHERE uid = ?")
      .bind(normalized).first();
  } else {
    row = await db.prepare(
      "SELECT u.id AS user_id, u.uid FROM users_mb u " +
      "JOIN user_devices_mb d ON d.user_id = u.id WHERE d.device_id = ?",
    ).bind(normalized).first();
  }
  return row
    ? { userId: Number(row.user_id), uid: normalizeStoredUid(row.uid) }
    : null;
}

async function resolveDeviceIdentity(env, value) {
  const context = await readDeviceContext(env, value);
  return context && !context.deviceBanned && context.userStatus === "active" &&
      context.userId > 0
    ? context
    : null;
}

async function readDeviceContext(env, value) {
  const deviceId = normalizeAccountId(value);
  if (!deviceId) return null;
  return readDeviceContextFromDb(await ensureUserSchema(env), deviceId);
}

async function readDeviceContextFromDb(db, deviceId) {
  const row = await db.prepare(
    `SELECT
       d.id AS device_row_id,
       d.device_id,
       d.user_id,
       d.brand,
       d.model,
       d.android_version,
       d.android_sdk,
       d.app_version,
       u.uid,
       u.status AS user_status,
       EXISTS (
         SELECT 1 FROM device_bans_mb b
         WHERE b.device_row_id = d.id AND b.lifted_at IS NULL
       ) AS device_banned,
       COALESCE((
         SELECT b.reason FROM device_bans_mb b
         WHERE b.device_row_id = d.id AND b.lifted_at IS NULL
         ORDER BY b.banned_at DESC LIMIT 1
       ), '') AS block_reason
     FROM user_devices_mb d
     LEFT JOIN users_mb u ON u.id = d.user_id
     WHERE d.device_id = ?`,
  ).bind(deviceId).first();
  if (!row) return null;
  const userId = Number(row.user_id);
  return {
    deviceRowId: Number(row.device_row_id),
    deviceId: normalizeAccountId(row.device_id),
    deviceBanned: Number(row.device_banned) === 1,
    blockReason: cleanText(row.block_reason),
    userId: Number.isInteger(userId) && userId > 0 ? userId : 0,
    uid: normalizeStoredUid(row.uid),
    userStatus: cleanText(row.user_status),
    brand: cleanText(row.brand),
    model: cleanText(row.model),
    androidVersion: cleanText(row.android_version),
    androidSdk: normalizeAndroidSdk(row.android_sdk),
    appVersion: cleanText(row.app_version),
  };
}

async function resolveAccountAccess(env, deviceId, settings) {
  const identity = await resolveDeviceIdentity(env, deviceId);
  if (!identity) return { level: 0, appVersion: "" };
  const db = await ensureUserSchema(env);
  const row = await db.prepare(
    "SELECT u.status, u.experience_total, COALESCE(d.app_version, '') AS app_version " +
      "FROM users_mb u JOIN user_devices_mb d ON d.user_id = u.id " +
      "WHERE u.id = ? AND d.device_id = ?",
  ).bind(identity.userId, identity.deviceId).first();
  const progress = accountProgress(
    row && row.experience_total,
    settings && settings.experienceThresholds,
    cleanText(row && row.status),
  );
  return row
    ? {
      level: progress.level,
      appVersion: cleanText(row.app_version),
    }
    : { level: 0, appVersion: "" };
}

async function readD1User(env, userId, settings) {
  const db = await ensureUserSchema(env);
  const normalizedUserId = Number(userId);
  if (!Number.isInteger(normalizedUserId) || normalizedUserId <= 0) return null;
  const row = await db.prepare(
    "SELECT u.*, d.brand AS device_brand, d.model AS device_model, " +
    "d.android_version AS device_android_version, d.android_sdk AS device_android_sdk, " +

    "d.app_version AS device_app_version " +
    "FROM users_mb u LEFT JOIN user_devices_mb d ON d.id = (" +
    "SELECT id FROM user_devices_mb WHERE user_id = u.id " +
    "ORDER BY last_seen_at DESC, id DESC LIMIT 1" +
    ") WHERE u.id = ?",
  ).bind(normalizedUserId).first();
  return row ? rowToUser(row, settings) : null;
}

function rowToUser(row, settings) {
  const uid = normalizeStoredUid(row.uid);
  const progress = accountProgress(
    row.experience_total,
    settings && settings.experienceThresholds,
    cleanText(row.status),
  );
  return {
    uid,
    nickname: normalizeNickname(row.nickname, defaultNickname(uid)),
    level: progress.level,
    experience: progress.experience,
    experienceTotal: progress.experienceTotal,
    status: progress.status,
    email: cleanText(row.email),
    brand: cleanText(row.device_brand ?? row.brand),
    model: cleanText(row.device_model ?? row.model),
    androidVersion: cleanText(row.device_android_version ?? row.android_version),
    androidSdk: normalizeAndroidSdk(row.device_android_sdk ?? row.android_sdk),
    appVersion: cleanText(row.device_app_version ?? row.app_version),
    createdAt: cleanText(row.created_at),
  };
}

async function attachDeviceToUser(db, userId, deviceId, profile, allowTransfer = false) {
  const brand = cleanText(profile && profile.brand).slice(0, 80);
  const model = cleanText(profile && profile.model).slice(0, 120);
  const androidVersion = cleanText(profile && profile.androidVersion).slice(0, 80);
  const androidSdk = normalizeAndroidSdk(profile && profile.androidSdk);
  const appVersion = cleanText(profile && profile.appVersion).slice(0, 40);
  const now = beijingTimestamp();
  const existing = await readDeviceContextFromDb(db, deviceId);
  if (existing && existing.deviceBanned) return false;
  const user = await db.prepare(
    "SELECT uid, status FROM users_mb WHERE id = ?",
  ).bind(userId).first();
  if (!user || cleanText(user.status) !== "active") return false;

  const results = await db.batch([
    db.prepare(
    "INSERT INTO user_devices_mb " +
      "(user_id, device_id, brand, model, android_version, android_sdk, app_version, " +
      "created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
      "ON CONFLICT(device_id) DO UPDATE SET " +
      "user_id = excluded.user_id, " +
      "brand = CASE WHEN excluded.brand <> '' THEN excluded.brand ELSE brand END, " +
      "model = CASE WHEN excluded.model <> '' THEN excluded.model ELSE model END, " +
      "android_version = CASE WHEN excluded.android_version <> '' " +
        "THEN excluded.android_version ELSE android_version END, " +
      "android_sdk = CASE WHEN excluded.android_sdk > 0 " +
        "THEN excluded.android_sdk ELSE android_sdk END, " +
      "app_version = CASE WHEN excluded.app_version <> '' " +
        "THEN excluded.app_version ELSE app_version END, " +
      "last_seen_at = excluded.last_seen_at " +
      "WHERE (? = 1 OR user_devices_mb.user_id IS NULL OR " +
        "user_devices_mb.user_id = excluded.user_id) " +
      "AND NOT EXISTS (" +
        "SELECT 1 FROM device_bans_mb b " +
        "WHERE b.device_row_id = user_devices_mb.id AND b.lifted_at IS NULL" +
      ")",
    ).bind(
    userId,
    deviceId,
    brand,
    model,
    androidVersion,
    androidSdk,
    appVersion,
    now,
    now,
    allowTransfer ? 1 : 0,
    ),
    db.prepare(
      `INSERT INTO device_account_links_mb
       (device_row_id, user_id, user_uid, first_bound_at, last_bound_at)
       SELECT d.id, u.id, u.uid, ?, ?
       FROM user_devices_mb d JOIN users_mb u ON u.id = d.user_id
       WHERE d.device_id = ? AND u.id = ?
         AND NOT EXISTS (
           SELECT 1 FROM device_bans_mb b
           WHERE b.device_row_id = d.id AND b.lifted_at IS NULL
         )
       ON CONFLICT(device_row_id, user_uid) DO UPDATE SET
         user_id = excluded.user_id,
         last_bound_at = excluded.last_bound_at`,
    ).bind(now, now, deviceId, userId),
  ]);
  return Number(results[0] && results[0].meta && results[0].meta.changes) > 0 &&
    Number(results[1] && results[1].meta && results[1].meta.changes) > 0;
}

async function registerOrTouchUser(env, deviceId, profile, now) {
  const db = await ensureUserSchema(env);
  const seenAt = beijingTimestamp(now);
  const existing = await readDeviceContextFromDb(db, deviceId);
  if (existing && (existing.deviceBanned || existing.userStatus === "banned")) {
    return existing;
  }
  if (existing && existing.userId > 0) {
    await attachDeviceToUser(db, existing.userId, deviceId, profile, false);
    return readDeviceContextFromDb(db, deviceId);
  }

  const uid = randomPublicUid();
  await db.prepare(
    "INSERT INTO users_mb (uid, nickname, created_at) VALUES (?, ?, ?)",
  ).bind(uid, defaultNickname(uid), seenAt).run();
  const created = await db.prepare(
    "SELECT id FROM users_mb WHERE uid = ?",
  ).bind(uid).first();
  if (!created) throw new Error("account_create_failed");
  const attached = await attachDeviceToUser(
    db,
    Number(created.id),
    deviceId,
    profile,
    false,
  );
  if (!attached) {
    await db.prepare("DELETE FROM users_mb WHERE id = ?").bind(Number(created.id)).run();
  }
  return readDeviceContextFromDb(db, deviceId);
}

function normalizeNickname(value, fallback = "") {
  const nickname = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 24);
  return nickname || fallback;
}

function normalizeLevel(value, fallback = 1) {
  const level = Number(value);
  return Number.isInteger(level) && level >= 0 && level <= 6 ? level : fallback;
}

function normalizeExperience(value, fallback = 0) {
  const experience = Number(value);
  if (Number.isInteger(experience) && experience >= 0 && experience <= MAX_ACCOUNT_EXPERIENCE) {
    return experience;
  }
  const fallbackExperience = Number(fallback);
  return Number.isInteger(fallbackExperience) && fallbackExperience >= 0
    ? Math.min(fallbackExperience, MAX_ACCOUNT_EXPERIENCE)
    : 0;
}

function normalizeExperienceThresholds(value) {
  const values = Array.isArray(value) ? value : [];
  return DEFAULT_EXPERIENCE_THRESHOLDS.map((fallback, index) => {
    const threshold = Number(values[index]);
    return Number.isInteger(threshold) && threshold > 0 && threshold <= MAX_ACCOUNT_EXPERIENCE
      ? threshold
      : fallback;
  });
}

function accountProgress(experienceTotalValue, thresholds, status = "active") {
  const experienceTotal = normalizeExperience(experienceTotalValue);
  if (status === "banned") {
    return { level: 0, experience: experienceTotal, experienceTotal, status: "banned" };
  }

  let level = 1;
  let experience = experienceTotal;
  const normalizedThresholds = normalizeExperienceThresholds(thresholds);
  while (level < 6) {
    const required = normalizedThresholds[level - 1];
    if (experience < required) break;
    experience -= required;
    level += 1;
  }
  return { level, experience, experienceTotal, status: "active" };
}

function experienceTotalForLevel(levelValue, thresholds) {
  const level = normalizeLevel(levelValue, 1);
  if (level <= 1) return 0;
  return normalizeExperienceThresholds(thresholds)
    .slice(0, Math.min(level - 1, 5))
    .reduce((total, threshold) => total + threshold, 0);
}

function normalizeAndroidSdk(value, fallback = 0) {
  const sdk = Number(value);
  if (Number.isInteger(sdk) && sdk >= 0 && sdk <= 1000) return sdk;
  const fallbackSdk = Number(fallback);
  return Number.isInteger(fallbackSdk) && fallbackSdk >= 0 && fallbackSdk <= 1000
    ? fallbackSdk
    : 0;
}

function defaultNickname(accountId) {
  return `流萤用户${cleanText(accountId).slice(-6).toUpperCase()}`;
}

function usagePeriodKeys(date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {});
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, hour: `${day}T${parts.hour}`, month: `${parts.year}-${parts.month}` };
}

function normalizeGrowthPeriod(value) {
  return value === "week" || value === "month" ? value : "day";
}

function normalizeGrowthDateKey(value) {
  const dateKey = cleanText(value);
  if (!dateKey) return "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) return null;
  const date = dayKeyToUtcDate(dateKey);
  return utcDateKey(date) === dateKey ? dateKey : null;
}

function buildUserGrowthPoints(
  rows,
  period,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const day = normalizeGrowthDateKey(row && row.date_key);
    const count = Number(row && row.new_users);
    if (!day || !Number.isInteger(count) || count <= 0) return null;
    return {
      day,
      key: userGrowthPeriodKey(dayKeyToUtcDate(day), period),
      count,
    };
  }).filter(Boolean);

  if (records.length === 0) return [];

  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const points = [];
  const counts = new Map();
  let totalUsers = 0;
  records.forEach((record) => {
    if (record.day < startDay) totalUsers += record.count;
    if (record.day >= startDay && record.day <= endDay) {
      counts.set(record.key, (counts.get(record.key) || 0) + record.count);
    }
  });
  let key = firstKey;

  while (key <= lastKey) {
    const newUsers = counts.get(key) || 0;
    totalUsers += newUsers;
    points.push({ key, newUsers, totalUsers });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

function buildUserTrafficPoints(
  rows,
  period,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const day = normalizeGrowthDateKey(row && row.date_key);
    if (!day) return null;
    return {
      day,
      key: userGrowthPeriodKey(dayKeyToUtcDate(day), period),
      uploadBytes: boundedBytes(row && row.upload_bytes),
      downloadBytes: boundedBytes(row && row.download_bytes),
    };
  }).filter(Boolean);

  if (records.length === 0) return [];
  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const counts = new Map();
  records.forEach((record) => {
    if (record.day < startDay || record.day > endDay) return;
    const current = counts.get(record.key) || { uploadBytes: 0, downloadBytes: 0 };
    current.uploadBytes += record.uploadBytes;
    current.downloadBytes += record.downloadBytes;
    counts.set(record.key, current);
  });

  const points = [];
  let key = firstKey;
  while (key <= lastKey) {
    const current = counts.get(key) || { uploadBytes: 0, downloadBytes: 0 };
    const uploadMb = bytesToMegabytes(current.uploadBytes);
    const downloadMb = bytesToMegabytes(current.downloadBytes);
    points.push({
      key,
      uploadMb,
      downloadMb,
      totalMb: Math.round((uploadMb + downloadMb) * 1e6) / 1e6,
    });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

function userGrowthPeriodKey(date, period) {
  const { day, month } = usagePeriodKeys(date);
  if (period === "month") return month;
  if (period === "week") return mondayOfDayKey(day);
  return day;
}

function mondayOfDayKey(dayKey) {
  const date = dayKeyToUtcDate(dayKey);
  const daysSinceMonday = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - daysSinceMonday);
  return utcDateKey(date);
}

function nextUserGrowthPeriodKey(key, period) {
  if (period === "month") {
    const [year, month] = key.split("-").map(Number);
    const date = new Date(Date.UTC(year, month, 1));
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
  }
  const date = dayKeyToUtcDate(key);
  date.setUTCDate(date.getUTCDate() + (period === "week" ? 7 : 1));
  return utcDateKey(date);
}

function dayKeyToUtcDate(dayKey) {
  const [year, month, day] = dayKey.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day));
}

function utcDateKey(date) {
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}

function beijingTimestamp(date = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`;
}

function normalizeReportTimestamp(value, fallback = "") {
  if (value === undefined || value === null || value === "") return fallback;
  const date = typeof value === "number"
    ? new Date(value)
    : new Date(String(value));
  return Number.isFinite(date.getTime()) ? beijingTimestamp(date) : null;
}

function normalizeAccountId(value) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

function normalizeAccountEmail(value) {
  const email = cleanText(value).toLowerCase();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
    ? email
    : "";
}

function normalizeAccountPassword(value) {
  if (typeof value !== "string") return "";
  return value.length >= 8 && value.length <= 128 ? value : "";
}

function normalizeTelegramJoinCode(value) {
  const code = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "");
  return code.length >= 1 && code.length <= 128 ? code : "";
}

async function hashAccountPassword(password) {
  const iterations = 100000;
  const salt = new Uint8Array(16);
  crypto.getRandomValues(salt);
  const key = await crypto.subtle.importKey(
    "raw",
    TEXT_ENCODER.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations,
      hash: "SHA-256",
    },
    key,
    256,
  );
  return `pbkdf2-sha256$${iterations}$${base64EncodeBytes(salt)}$${base64EncodeBytes(new Uint8Array(derived))}`;
}

async function verifyAccountPassword(password, storedHash) {
  const parts = String(storedHash || "").split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 10000 || iterations > 1000000) {
    return false;
  }

  let salt;
  let expected;
  try {
    salt = base64DecodeBytes(parts[2]);
    expected = base64DecodeBytes(parts[3]);
  } catch (_) {
    return false;
  }
  if (salt.length === 0 || expected.length === 0) return false;

  const key = await crypto.subtle.importKey(
    "raw",
    TEXT_ENCODER.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations, hash: "SHA-256" },
    key,
    expected.length * 8,
  );
  return constantTimeBytesEqual(new Uint8Array(derived), expected);
}

function base64EncodeBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64DecodeBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function constantTimeBytesEqual(left, right) {
  const length = Math.max(left.length, right.length);

  let difference = left.length ^ right.length;
  for (let index = 0; index < length; index += 1) {
    difference |= (left[index] || 0) ^ (right[index] || 0);
  }
  return difference === 0;
}

function normalizeUid(value) {
  const uid = cleanText(value).toLowerCase();
  return /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(uid)
    ? uid
    : "";
}

function normalizeStoredUid(value) {
  return normalizeUid(value) || normalizeAccountId(value);
}

function randomPublicUid() {
  return crypto.randomUUID().toLowerCase();
}

function normalizeBatchUserReferences(value) {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.map(normalizeUserReference).filter(Boolean)));
}

function boundedMegabytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number * 1e6) / 1e6, 1e9)
    : 0;
}

function boundedBytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number), MAX_TRAFFIC_BYTES)
    : 0;
}

function megabytesToBytes(value) {
  return boundedBytes(boundedMegabytes(value) * 1024 * 1024);
}

function bytesToMegabytes(value) {
  return boundedMegabytes(boundedBytes(value) / (1024 * 1024));
}

function reportBytes(bytesValue, legacyMegabytesValue) {
  if (bytesValue !== undefined && bytesValue !== null && bytesValue !== "") {
    return boundedBytes(bytesValue);
  }
  return megabytesToBytes(legacyMegabytesValue);
}

function normalizeManagedContent(value) {
  if (typeof value !== "string") return "";
  const content = value.replace(/\r\n?/g, "\n").trim();
  if (TEXT_ENCODER.encode(content).length > MAX_MANAGED_SUBSCRIPTION_BYTES) {
    throw new Error("managed_subscription_too_large");
  }
  return content;
}

function normalizeSubscriptionCacheMinutes(value) {
  if (value === undefined || value === null || value === "") {
    return DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
  }
  const minutes = Number(value);
  return Number.isFinite(minutes)
    ? Math.min(Math.max(Math.round(minutes), 0), MAX_SUBSCRIPTION_CACHE_MINUTES)
    : DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
}

function normalizeMinimumAppVersion(value) {
  const version = cleanText(value).replace(/^v/i, "").slice(0, 40);
  if (!version) return "";
  if (!/^\d+(?:\.\d+)*$/.test(version)) {
    throw new Error("invalid_min_app_version");
  }
  return version;
}

function appVersionParts(value) {
  const version = cleanText(value).replace(/^v/i, "");
  if (!/^\d+(?:\.\d+)*$/.test(version)) return null;
  return version.split(".").map((part) => Number(part));
}

function isAppVersionAtLeast(currentVersion, minimumVersion) {
  const current = appVersionParts(currentVersion);
  const minimum = appVersionParts(minimumVersion);
  if (!current || !minimum) return false;
  const length = Math.max(current.length, minimum.length);
  for (let index = 0; index < length; index += 1) {
    const currentPart = current[index] || 0;
    const minimumPart = minimum[index] || 0;
    if (currentPart !== minimumPart) return currentPart > minimumPart;
  }
  return true;
}

function assertPlainSubscriptionContent(value) {
  const content = normalizeManagedContent(value);
  if (!content || !PLAINTEXT_NODE_PATTERN.test(content)) {
    throw new Error("subscription_must_be_plaintext_nodes");
  }
  return content;
}

function subscriptionCacheKey(source) {
  return `${SUBSCRIPTION_CACHE_KEY_PREFIX}${source.id}`;
}

async function readExternalSubscription(env, source) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  if (!source || !source.url) throw new Error("external_subscription_url_required");

  const cacheMinutes = normalizeSubscriptionCacheMinutes(source.cacheMinutes);
  const cacheKey = subscriptionCacheKey(source);
  const now = Date.now();
  if (cacheMinutes > 0) {
    const cached = await env.CONFIG.get(cacheKey, "json");
    const cachedAt = Number(cached && cached.cachedAt);
    const freshUntil = cachedAt + cacheMinutes * 60 * 1000;
    if (
      cached &&
      cached.url === source.url &&
      Number.isFinite(cachedAt) &&
      freshUntil > now
    ) {
      return assertPlainSubscriptionContent(cached.content);
    }
  }

  let response;
  try {
    response = await fetch(source.url, {
      method: "GET",
      headers: {
        accept: "text/plain, */*;q=0.1",
        "user-agent": "FireflyVPN-Worker/1.0",
      },
      redirect: "follow",
    });
  } catch (_) {
    throw new Error("external_subscription_unreachable");
  }
  if (!response.ok) {
    throw new Error(`external_subscription_http_${response.status}`);
  }

  const content = assertPlainSubscriptionContent(await response.text());
  if (cacheMinutes > 0) {
    const cacheSeconds = cacheMinutes * 60;
    await env.CONFIG.put(cacheKey, JSON.stringify({
      url: source.url,
      content,
      cachedAt: now,
    }), { expirationTtl: Math.max(cacheSeconds, 60) });
  } else {
    await env.CONFIG.delete(cacheKey);
  }
  return content;
}

async function validateAndWarmSubscriptionSources(env, subscriptions) {
  for (const source of subscriptions) {
    if (source.content) {
      assertPlainSubscriptionContent(source.content);
    } else if (source.url) {
      await readExternalSubscription(env, source);
    }
  }
}

function privateBase64Encode(value) {
  const bytes = TEXT_ENCODER.encode(value);
  let result = "";
  let chunk = "";

  for (let i = 0; i < bytes.length; i += 3) {
    const remaining = bytes.length - i;
    const a = bytes[i];
    const b = remaining > 1 ? bytes[i + 1] : 0;
    const c = remaining > 2 ? bytes[i + 2] : 0;
    const block = (a << 16) | (b << 8) | c;

    chunk += PRIVATE_B64_ALPHABET[(block >>> 18) & 0x3f];
    chunk += PRIVATE_B64_ALPHABET[(block >>> 12) & 0x3f];
    chunk += remaining > 1
      ? PRIVATE_B64_ALPHABET[(block >>> 6) & 0x3f]
      : "=";
    chunk += remaining > 2
      ? PRIVATE_B64_ALPHABET[block & 0x3f]
      : "=";
    if (chunk.length >= 0x8000) {
      result += chunk;
      chunk = "";
    }
  }

  return result + chunk;
}

function parseIpv4Tail(value) {
  let address = value;
  if (address.includes(":") && address.includes(".")) {
    address = address.slice(address.lastIndexOf(":") + 1);
  }

  const parts = address.split(".");
  if (parts.length !== 4) return null;
  const numbers = parts.map((part) => {
    if (!/^\d{1,3}$/.test(part)) return NaN;
    return Number(part);
  });
  if (numbers.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return null;
  }
  return numbers[3];
}

function shiftFromClientIp(clientIp) {
  let address = String(clientIp || "").split(",", 1)[0].trim();
  if (address.startsWith("[") && address.includes("]")) {
    address = address.slice(1, address.indexOf("]"));
  }

  const ipv4Tail = parseIpv4Tail(address);
  if (ipv4Tail !== null) return ipv4Tail % 64;

  if (address.includes(":")) {
    const lastGroup = address.slice(address.lastIndexOf(":") + 1);
    if (lastGroup === "") return 0;
    if (/^[a-f\d]{1,4}$/i.test(lastGroup)) {
      return parseInt(lastGroup, 16) % 64;
    }
  }

  throw new Error("invalid_cf_connecting_ip");
}

function protectSubscription(value, clientIp) {
  const shift = shiftFromClientIp(clientIp);
  const encoded = privateBase64Encode(value);
  if (shift === 0) return encoded;

  let result = "";

  let chunk = "";
  for (const character of encoded) {
    if (character === "=") {
      chunk += character;
      continue;
    }
    const index = PRIVATE_B64_ALPHABET.indexOf(character);
    if (index < 0) throw new Error("private_base64_encoding_failed");
    chunk += PRIVATE_B64_ALPHABET[(index + shift) % 64];
    if (chunk.length >= 0x8000) {
      result += chunk;
      chunk = "";
    }
  }
  return result + chunk;
}

function getClientIp(request) {
  const clientIp = request.headers.get("CF-Connecting-IP");
  if (!clientIp) throw new Error("missing_cf_connecting_ip");
  shiftFromClientIp(clientIp);
  return clientIp.trim();
}

async function readState(env) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const stored = await env.CONFIG.get(STATE_KEY, "json");
  return normalizeState(stored || DEFAULT_STATE);
}

async function writeState(env, state) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  await env.CONFIG.put(STATE_KEY, JSON.stringify(normalizeState(state)));
}

function normalizeState(input) {
  const value = input && typeof input === "object" ? input : {};
  const list = Array.isArray(value.subscriptions)
    ? value.subscriptions
    : Array.isArray(value.sources)
      ? value.sources
      : [];

  const subscriptions = list
    .filter((item) => item && typeof item === "object")
    .map((item) => {
      const content = normalizeManagedContent(
        item.content ?? item.managedContent ?? item.nodesText,
      );
      if (content) assertPlainSubscriptionContent(content);
      const externalUrl = typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
        ? item.url.trim()
        : "";
      return {
        url: content ? "" : externalUrl,
        name: cleanText(item.name || item.title),
        enabled: parseFlag(item.enabled, true),
        refreshOnAppOpen: parseFlag(
          item.refreshOnAppOpen ?? item.openAppImmediateUpdate ?? item.updateOnAppOpen,
          true,
        ),
        minLevel: normalizeLevel(item.minLevel ?? item.requiredLevel),
        minAppVersion: normalizeMinimumAppVersion(
          item.minAppVersion ?? item.minimumAppVersion,
        ),
        cacheMinutes: normalizeSubscriptionCacheMinutes(item.cacheMinutes),
        content,
        remark: cleanText(item.remark || item.note).slice(0, 500),
      };
    })
    .filter((item) => item.url || item.content)
    .map((item, index) => ({
      id: String(index + 1),
      name: item.name || `线路${index + 1}`,
      url: item.url,
      enabled: item.enabled,
      refreshOnAppOpen: item.refreshOnAppOpen,
      minLevel: item.minLevel,
      minAppVersion: item.minAppVersion,
      cacheMinutes: item.cacheMinutes,
      content: item.content,
      remark: item.remark,
    }));

  return {
    version: 1,
    subscriptions,
    notice: normalizeNotice(value.notice),
    appUpdate: plainObject(value.appUpdate) ? value.appUpdate : null,
    settings: normalizeSettings(value.settings),
    ...(value.updatedAt ? { updatedAt: String(value.updatedAt) } : {}),
  };
}

function normalizeNotice(value) {
  if (!plainObject(value)) return null;
  const startAt = normalizeNoticeTimestamp(value.startAt, "start_at");
  const endAt = normalizeNoticeTimestamp(value.endAt, "end_at");
  if (startAt && endAt && Date.parse(startAt) > Date.parse(endAt)) {
    throw new Error("invalid_notice_time_order");
  }
  return {
    hasNotice: parseFlag(value.hasNotice, false),
    title: cleanText(value.title),
    content: cleanText(value.content),
    noticeId: cleanText(value.noticeId),
    showOnce: parseFlag(value.showOnce, true),
    startAt,
    endAt,
  };
}

function normalizeNoticeTimestamp(value, field) {
  const text = cleanText(value);
  if (!text) return "";
  const localMinute = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(text);
  const localSecond = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(text);
  const candidate = localMinute
    ? `${text}:00+08:00`
    : localSecond
      ? `${text}+08:00`
      : text;
  const date = new Date(candidate);
  if (!Number.isFinite(date.getTime())) {
    throw new Error(`invalid_notice_${field}`);
  }
  return beijingTimestamp(date);
}

function visibleNotice(notice, now = Date.now()) {
  if (!notice || !notice.hasNotice) return null;
  const startAt = Date.parse(notice.startAt || "");
  const endAt = Date.parse(notice.endAt || "");
  if (Number.isFinite(startAt) && now < startAt) return null;
  if (Number.isFinite(endAt) && now > endAt) return null;
  return notice;
}

function normalizeSettings(value) {
  const settings = plainObject(value) ? value : {};
  const timeout = Number(settings.nodeRequestTimeoutMs);
  return {
    websiteUrl: cleanText(settings.websiteUrl),
    feedbackEmail: cleanText(settings.feedbackEmail),
    feedbackUrl: cleanText(settings.feedbackUrl),
    githubUrl: cleanText(settings.githubUrl),
    nodeRequestTimeoutMs: Number.isFinite(timeout)
      ? Math.round(Math.min(Math.max(timeout, 5000), 60000))
      : 25000,
    experienceThresholds: normalizeExperienceThresholds(settings.experienceThresholds),
    dailyCheckinExperience: normalizeExperience(
      settings.dailyCheckinExperience,
      DEFAULT_DAILY_CHECKIN_EXPERIENCE,
    ),
    telegramGroupUrl: normalizeTelegramGroupUrl(settings.telegramGroupUrl),
    telegramJoinCode: normalizeTelegramJoinCode(settings.telegramJoinCode),
    telegramTaskExperience: normalizeExperience(
      settings.telegramTaskExperience,
      DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    ),
    speedShareSmartExperience: normalizeExperience(
      settings.speedShareSmartExperience,
      DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
    ),
  };
}

function publicClientSettings(settings) {
  const value = normalizeSettings(settings);
  const { telegramJoinCode, ...publicSettings } = value;
  return publicSettings;
}

function normalizeTelegramGroupUrl(value) {
  const url = cleanText(value).slice(0, 500);
  return /^(?:https?:\/\/|tg:\/\/)/i.test(url) ? url : "";
}

function cleanText(value) {
  return typeof value === "string" ? value.trim() : "";
}

function plainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function parseFlag(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return !["0", "false", "off", "no"].includes(String(value).trim().toLowerCase());
}

function isAdmin(request, env) {
  const token = cleanText(env.ADMIN_TOKEN);
  const authorization = cleanText(request.headers.get("authorization"));
  return Boolean(token) && authorization === `Bearer ${token}`;
}

function corsHeaders() {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
    "access-control-allow-headers": "Content-Type, Authorization",
    "access-control-expose-headers": CLIENT_IP_HEADER,
  };
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...corsHeaders(),
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

const routeDeps = {
  CLIENT_IP_HEADER,
  resolveAccountAccess,
  beijingTimestamp,
  normalizeAccountId,
  isAppVersionAtLeast,
  readExternalSubscription,
  protectSubscription,
  getClientIp,
  readState,
  visibleNotice,
  publicClientSettings,
  corsHeaders,
  json,
  MAX_SPEED_SHARE_RESULTS,
  ACCOUNT_BINDING_EXPERIENCE,
  DEFAULT_DAILY_CHECKIN_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  REWARD_TYPE_ACCOUNT_BINDING,
  REWARD_TYPE_DAILY_CHECKIN,
  REWARD_TYPE_SPEED_SHARE,
  REWARD_TYPE_TELEGRAM,
  REWARD_PERIOD_LIFETIME,
  normalizeSpeedShareResult,
  speedShareRequestLocation,
  publicAccount,
  rewardClaimStatement,
  readDailyCheckin,
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
  usagePeriodKeys,
  normalizeReportTimestamp,
  normalizeAccountEmail,
  normalizeAccountPassword,
  normalizeTelegramJoinCode,
  hashAccountPassword,
  verifyAccountPassword,
  normalizeUid,
  reportBytes,
  cleanText,
  MAX_BATCH_USER_COUNT,
  publicAdminUser,
  resolveUserIdentity,
  rowToUser,
  experienceTotalForLevel,
  normalizeAndroidSdk,
  normalizeGrowthPeriod,
  normalizeGrowthDateKey,
  buildUserGrowthPoints,
  buildUserTrafficPoints,
  normalizeStoredUid,
  normalizeBatchUserReferences,
  validateAndWarmSubscriptionSources,
  writeState,
  normalizeState,
};

const { handleClient, handleManagedSubscription } = createClientRoutes(routeDeps);
const { handleAccount } = createAccountRoutes(routeDeps);
const { handleAdmin } = createAdminRoutes(routeDeps);

const worker = {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";

      if (path === "/" || path === "/health") {
        const usersSchemaVersion = env.USERS_DB
          ? await initializeUserSchema(env)
          : null;
        return json({
          ok: true,
          service: "fireflyvpn-worker",
          bindings: {
            config: Boolean(env.CONFIG),
            usersDb: Boolean(env.USERS_DB),
            adminToken: Boolean(env.ADMIN_TOKEN),
          },
          database: {
            initialized: usersSchemaVersion === USER_SCHEMA_VERSION,
            schemaVersion: usersSchemaVersion,
          },
        });
      }

      if (path === ADMIN_BASE_PATH && request.method === "GET") {
        return new Response(adminHtml(), {
          headers: {
            "content-type": "text/html; charset=utf-8",
            "cache-control": "no-store, no-cache, must-revalidate",
          },
        });
      }

      if (path === "/api/client" && request.method === "GET") {
        return handleClient(request, env);
      }

      if (path.startsWith("/api/subscription/") && request.method === "GET") {
        return handleManagedSubscription(path, request, env);
      }

      if (path.startsWith("/api/account/")) {
        return handleAccount(path, request, env);
      }

      if (path.startsWith(`${ADMIN_BASE_PATH}/api/`)) {
        if (!isAdmin(request, env)) {
          return json({ error: "unauthorized" }, 401);
        }
        return handleAdmin(path.slice(ADMIN_BASE_PATH.length), request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};
export default worker;

