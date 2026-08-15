import {
  DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  REWARD_PERIOD_LIFETIME,
  REWARD_TYPE_SPEED_SHARE,
  REWARD_TYPE_TELEGRAM,
} from "../config.js";
import {
  bytesToMegabytes,
  normalizeAndroidSdk,
  normalizeInviteCode,
  normalizeExperience,
  normalizeLevel,
  normalizeStoredUid,
} from "../domain/account.js";
import { usagePeriodKeys } from "../domain/time.js";
import { cleanText } from "../lib/values.js";
import { ensureUserSchema, rowToUser } from "./user-store.js";

export function publicAccount(user) {
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
    inviteCode: normalizeInviteCode(user.inviteCode),
    invitedByCode: normalizeInviteCode(user.invitedByCode),
    inviteeCount: Math.max(0, Number(user.inviteeCount) || 0),
    deviceBlocked: Boolean(user.deviceBlocked),
  };
}

export function rewardClaimStatement(db, claim) {
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

export function invitationRewardClaimStatement(db, claim) {
  const claimToken = cleanText(claim && claim.claimToken) || crypto.randomUUID();
  return db.prepare(
    `INSERT OR IGNORE INTO user_reward_claims_mb
     (user_id, user_uid, device_row_id, reward_type, period_key, context_key,
      claim_token, claimed_at, experience_awarded)
     SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
     WHERE EXISTS (
       SELECT 1 FROM users_mb
       WHERE id = ? AND invited_by_user_id = ?
     )`,
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
    Number(claim.inviteeUserId),
    Number(claim.userId),
  );
}

export async function readSpeedShareStatus(env, userId, deviceRowId, settings = {}) {
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

export async function readTelegramTask(env, userId, deviceRowId, settings) {
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

export function publicAdminUser(row, settings) {
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
