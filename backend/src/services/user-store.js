import { USER_SCHEMA_STATEMENTS, USER_SCHEMA_VERSION } from "../config.js";
import {
  accountProgress,
  defaultNickname,
  normalizeAccountId,
  normalizeAndroidSdk,
  normalizeNickname,
  normalizeStoredUid,
  normalizeUid,
  normalizeUserReference,
  randomPublicUid,
} from "../domain/account.js";
import { beijingTimestamp } from "../domain/time.js";
import { cleanText } from "../lib/values.js";

let userSchemaReady = false;
let userSchemaInitialization = null;

export function usersDb(env) {
  if (!env.USERS_DB) throw new Error("Missing D1 binding USERS_DB");
  return env.USERS_DB;
}

export async function ensureUserSchema(env) {
  const db = usersDb(env);
  if (userSchemaReady) return db;
  await initializeUserSchema(env);
  return db;
}

export async function initializeUserSchema(env) {
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

export async function initializeUserSchemaDatabase(db) {
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

export async function resolveUserIdentity(env, reference) {
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

export async function resolveDeviceIdentity(env, value) {
  const context = await readDeviceContext(env, value);
  return context && !context.deviceBanned && context.userStatus === "active" &&
      context.userId > 0
    ? context
    : null;
}

export async function readDeviceContext(env, value) {
  const deviceId = normalizeAccountId(value);
  if (!deviceId) return null;
  return readDeviceContextFromDb(await ensureUserSchema(env), deviceId);
}

export async function readDeviceContextFromDb(db, deviceId) {
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

export async function resolveAccountAccess(env, deviceId, settings) {
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

export async function readD1User(env, userId, settings) {
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

export function rowToUser(row, settings) {
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

export async function attachDeviceToUser(db, userId, deviceId, profile, allowTransfer = false) {
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

export async function registerOrTouchUser(env, deviceId, profile, now) {
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

