import type { Env } from "../types/env.ts";
import type { AccountType, DeviceProfile, PublicAccount, Settings, User } from "../types/models.ts";
import type { AccountRow, DeviceContextRow, TrafficFields, UserRow } from "../types/database.ts";

import {
  USER_SCHEMA_MIGRATIONS,
  USER_SCHEMA_STATEMENTS,
  USER_SCHEMA_VERSION,
} from "../config.ts";
import {
  accountTypeForUser,
  bytesToMegabytes,
  defaultNickname,
  normalizeAccountId,
  normalizeAndroidSdk,
  normalizeNickname,
  normalizeStoredUid,
  normalizeUid,
  normalizeUserReference,
  randomPublicUid,
} from "../domain/account.ts";
import { beijingTimestamp, usagePeriodKeys } from "../domain/time.ts";
import { cleanText } from "../lib/values.ts";

let userSchemaReady = false;
let userSchemaInitialization: Promise<number> | null = null;

export function usersDb(env: Env) {
  if (!env.USERS_DB) throw new Error("Missing D1 binding USERS_DB");
  return env.USERS_DB;
}

export async function ensureUserSchema(env: Env) {
  const db = usersDb(env);
  if (userSchemaReady) return db;
  await initializeUserSchema(env);
  return db;
}

export async function initializeUserSchema(env: Env) {
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

export async function initializeUserSchemaDatabase(db: D1Database) {
  await restoreUsageCompatibilityColumns(db);

  let schema;
  try {
    schema = await db.prepare(
      "SELECT version FROM app_schema_mb WHERE component = 'users'",
    ).first<{ version: number }>();
  } catch (error) {
    const message = String(error instanceof Error ? error.message : error);
    if (!/no such table[^]*app_schema_mb/i.test(message)) throw error;
    schema = null;
  }

  if (schema) {
    let version = Number(schema.version);
    if (!Number.isInteger(version) || version < 1 || version > USER_SCHEMA_VERSION) {
      throw new Error("users_db_schema_version_mismatch");
    }
    for (let nextVersion = version + 1; nextVersion <= USER_SCHEMA_VERSION; nextVersion += 1) {
      const statements = USER_SCHEMA_MIGRATIONS[nextVersion] || [];
      if (statements.length > 0) {
        await db.batch(statements.map((statement) => db.prepare(statement)));
      }
      await db.prepare(
        "UPDATE app_schema_mb SET version = ?, applied_at = ? WHERE component = 'users'",
      ).bind(nextVersion, beijingTimestamp()).run();
      version = nextVersion;
    }
    return version;
  }

  const existing = await db.prepare(
    `SELECT name FROM sqlite_schema
     WHERE type = 'table' AND name IN (
       'users_mb',
       'user_devices_mb',
       'device_account_links_mb',
       'device_bans_mb',
       'user_usage_mb'
     )
     LIMIT 1`,
  ).first<{ name: string }>();
  if (existing) {
    throw new Error("users_db_unversioned_schema_detected");
  }

  await db.batch(
    USER_SCHEMA_STATEMENTS.map((statement) => db.prepare(statement)),
  );
  const initialized = await db.prepare(
    "SELECT version FROM app_schema_mb WHERE component = 'users'",
  ).first<{ version: number }>();
  if (!initialized || Number(initialized.version) !== USER_SCHEMA_VERSION) {
    throw new Error("users_db_initialization_failed");
  }
  return Number(initialized.version);
}

async function restoreUsageCompatibilityColumns(db: D1Database) {
  const tableInfo = await db.prepare("PRAGMA table_info(user_usage_mb)").all<{ name: string }>();
  const columns = new Set((tableInfo.results || []).map((row) => String(row.name || "")));
  if (columns.size === 0) return;

  const statements = [];
  if (!columns.has("subscription_group_name")) {
    statements.push(
      "ALTER TABLE user_usage_mb ADD COLUMN subscription_group_name TEXT NOT NULL DEFAULT ''",
    );
  }
  if (!columns.has("reported_at")) {
    statements.push(
      "ALTER TABLE user_usage_mb ADD COLUMN reported_at TEXT NOT NULL DEFAULT ''",
    );
  }
  if (statements.length > 0) {
    await db.batch(statements.map((statement) => db.prepare(statement)));
  }
}

export async function resolveUserIdentity(env: Env, reference: unknown) {
  const normalized = normalizeUserReference(reference);
  if (!normalized) return null;
  const db = await ensureUserSchema(env);
  let row = null;
  if (normalizeUid(normalized)) {
    row = await db.prepare("SELECT id AS user_id, uid FROM users_mb WHERE uid = ?")
      .bind(normalized).first<{ user_id: number; uid: string }>();
  } else {
    row = await db.prepare(
      "SELECT u.id AS user_id, u.uid FROM users_mb u " +
      "JOIN user_devices_mb d ON d.user_id = u.id WHERE d.device_id = ?",
    ).bind(normalized).first<{ user_id: number; uid: string }>();
  }
  return row
    ? { userId: Number(row.user_id), uid: normalizeStoredUid(row.uid) }
    : null;
}

export async function resolveDeviceIdentity(env: Env, value: unknown) {
  const context = await readDeviceContext(env, value);
  return context && !context.deviceBanned && context.userStatus === "active" &&
      context.userId > 0
    ? context
    : null;
}

export async function readDeviceContext(env: Env, value: unknown) {
  const deviceId = normalizeAccountId(value);
  if (!deviceId) return null;
  return readDeviceContextFromDb(await ensureUserSchema(env), deviceId);
}

export async function readDeviceContextFromDb(db: D1Database, deviceId: string) {
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
  ).bind(deviceId).first<DeviceContextRow>();
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

export async function resolveAccountAccess(env: Env, deviceId: string): Promise<{ accountType: AccountType; appVersion: string }> {
  const identity = await resolveDeviceIdentity(env, deviceId);
  if (!identity) return { accountType: "banned", appVersion: "" };
  const db = await ensureUserSchema(env);
  const row = await db.prepare(
    "SELECT u.status, u.email, COALESCE(d.app_version, '') AS app_version " +
      "FROM users_mb u JOIN user_devices_mb d ON d.user_id = u.id " +
      "WHERE u.id = ? AND d.device_id = ?",
  ).bind(identity.userId, identity.deviceId).first<Pick<UserRow, "status" | "email"> & { app_version: string }>();
  return row
    ? {
      accountType: accountTypeForUser(row),
      appVersion: cleanText(row.app_version),
    }
    : { accountType: "banned", appVersion: "" };
}

export async function readD1User(env: Env, userId: number, settings?: Settings) {
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
  ).bind(normalizedUserId).first<UserRow>();
  return row ? rowToUser(row, settings) : null;
}

export async function readD1UserWithTraffic(env: Env, userId: number, settings?: Settings, now = new Date()) {
  const db = await ensureUserSchema(env);
  const normalizedUserId = Number(userId);
  if (!Number.isInteger(normalizedUserId) || normalizedUserId <= 0) return null;
  const { day, month } = usagePeriodKeys(now);
  const row = await db.prepare(
    `SELECT
       u.*,
       d.brand AS device_brand,
       d.model AS device_model,
       d.android_version AS device_android_version,
       d.android_sdk AS device_android_sdk,
       d.app_version AS device_app_version,
       COALESCE(SUM(CASE WHEN r.usage_day = ? THEN r.upload_bytes ELSE 0 END), 0)
         AS today_upload_bytes,
       COALESCE(SUM(CASE WHEN r.usage_day = ? THEN r.download_bytes ELSE 0 END), 0)
         AS today_download_bytes,
       COALESCE(SUM(CASE WHEN substr(r.usage_day, 1, 7) = ? THEN r.upload_bytes ELSE 0 END), 0)
         AS month_upload_bytes,
       COALESCE(SUM(CASE WHEN substr(r.usage_day, 1, 7) = ? THEN r.download_bytes ELSE 0 END), 0)
         AS month_download_bytes,
       COALESCE(SUM(r.upload_bytes), 0) AS total_upload_bytes,
       COALESCE(SUM(r.download_bytes), 0) AS total_download_bytes,
       MAX(r.reported_at) AS last_report_at
     FROM users_mb u
     LEFT JOIN user_devices_mb d ON d.id = (
       SELECT id FROM user_devices_mb
       WHERE user_id = u.id
       ORDER BY last_seen_at DESC, id DESC LIMIT 1
     )
     LEFT JOIN user_usage_mb r ON r.user_id = u.id
     WHERE u.id = ?
     GROUP BY u.id`,
  ).bind(day, day, month, month, normalizedUserId).first<UserRow>();
  if (!row) return null;
  return {
    ...rowToUser(row, settings),
    traffic: accountTrafficFromRow(row),
  };
}

function accountTrafficFromRow(row: Partial<TrafficFields>) {
  const period = (uploadValue: unknown, downloadValue: unknown) => {
    const uploadBytes = safeTrafficBytes(uploadValue);
    const downloadBytes = safeTrafficBytes(downloadValue);
    return {
      uploadBytes,
      downloadBytes,
      totalBytes: safeTrafficBytes(uploadBytes + downloadBytes),
    };
  };
  return {
    today: period(row.today_upload_bytes, row.today_download_bytes),
    month: period(row.month_upload_bytes, row.month_download_bytes),
    total: period(row.total_upload_bytes, row.total_download_bytes),
    lastReportAt: cleanText(row.last_report_at) || null,
  };
}

function safeTrafficBytes(value: unknown) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes <= 0) return 0;
  return Math.min(Math.round(bytes), Number.MAX_SAFE_INTEGER);
}

export function rowToUser(row: AccountRow, settings?: Settings): User {
  const uid = normalizeStoredUid(row.uid);
  return {
    uid,
    nickname: normalizeNickname(row.nickname, defaultNickname(uid)),
    accountType: accountTypeForUser(row),
    email: cleanText(row.email),
    brand: cleanText(row.device_brand ?? row.brand),
    model: cleanText(row.device_model ?? row.model),
    androidVersion: cleanText(row.device_android_version ?? row.android_version),
    androidSdk: normalizeAndroidSdk(row.device_android_sdk ?? row.android_sdk),
    appVersion: cleanText(row.device_app_version ?? row.app_version),
    createdAt: cleanText(row.created_at),
  };
}

export function publicAccount(user: User | null): PublicAccount {
  if (!user) throw new Error("account_not_found");
  const accountType: AccountType = user.deviceBlocked
    ? "banned"
    : ["banned", "guest", "formal"].includes(cleanText(user.accountType))
    ? user.accountType
    : accountTypeForUser(user);
  const account: PublicAccount = {
    uid: user.uid,
    nickname: user.nickname,
    accountType,
    email: cleanText(user.email),
    brand: user.brand,
    model: user.model,
    androidVersion: user.androidVersion,
    androidSdk: normalizeAndroidSdk(user.androidSdk),
    appVersion: user.appVersion,
    deviceBlocked: Boolean(user.deviceBlocked),
  };
  if (user.traffic) account.traffic = user.traffic;
  return account;
}

export function publicAdminUser(row: UserRow, settings?: Settings) {
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

export async function attachDeviceToUser(db: D1Database, userId: number, deviceId: string, profile: DeviceProfile, allowTransfer = false) {
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
  ).bind(userId).first<Pick<UserRow, "uid" | "status">>();
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

export async function registerOrTouchUser(env: Env, deviceId: string, profile: DeviceProfile, now: Date) {
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

  let uid = "";
  let created = null;
  for (let attempt = 0; attempt < 8 && !created; attempt += 1) {
    uid = randomPublicUid();
    try {
      await db.prepare(
        "INSERT INTO users_mb (uid, nickname, created_at) VALUES (?, ?, ?)",
      ).bind(uid, defaultNickname(uid), seenAt).run();
      created = await db.prepare(
        "SELECT id FROM users_mb WHERE uid = ?",
      ).bind(uid).first<Pick<UserRow, "id">>();
    } catch (error) {
      if (!/unique|constraint/i.test(String(error instanceof Error ? error.message : error))) throw error;
    }
  }
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
