import {
  ACCOUNT_BINDING_EXPERIENCE,
  DEFAULT_INVITATION_EXPERIENCE,
  DEFAULT_STATE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  STATE_KEY,
} from "../config.js";
import {
  normalizeExperience,
  normalizeExperienceThresholds,
  normalizeLevel,
  normalizeTelegramJoinCode,
} from "../domain/account.js";
import { beijingTimestamp } from "../domain/time.js";
import { cleanText, parseFlag, plainObject } from "../lib/values.js";
import {
  assertPlainSubscriptionContent,
  normalizeManagedContent,
  normalizeMinimumAppVersion,
  normalizeSubscriptionCacheMinutes,
} from "./subscriptions.js";

export async function readState(env) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const stored = await env.CONFIG.get(STATE_KEY, "json");
  return normalizeState(stored || DEFAULT_STATE);
}

export async function writeState(env, state) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  await env.CONFIG.put(STATE_KEY, JSON.stringify(normalizeState(state)));
}

export function normalizeState(input) {
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

export function normalizeNotice(value) {
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

export function normalizeNoticeTimestamp(value, field) {
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

export function visibleNotice(notice, now = Date.now()) {
  if (!notice || !notice.hasNotice) return null;
  const startAt = Date.parse(notice.startAt || "");
  const endAt = Date.parse(notice.endAt || "");
  if (Number.isFinite(startAt) && now < startAt) return null;
  if (Number.isFinite(endAt) && now > endAt) return null;
  return notice;
}

export function normalizeSettings(value) {
  const settings = plainObject(value) ? value : {};
  // 旧版状态没有注册控制字段，升级时采用新的邮箱绑定经验默认值。
  const hasRegistrationControl =
    Object.prototype.hasOwnProperty.call(settings, "registrationEnabled") ||
    Object.prototype.hasOwnProperty.call(settings, "formalRegistrationEnabled");
  const isLegacySettings = !hasRegistrationControl;
  const configuredAccountBindingExperience = isLegacySettings
    ? ACCOUNT_BINDING_EXPERIENCE
    : settings.accountBindingExperience;
  const timeout = Number(settings.nodeRequestTimeoutMs);
  const aiAnalysis = plainObject(settings.aiAnalysis) ? settings.aiAnalysis : {};
  const aiMaxOutputTokens = Number(aiAnalysis.maxOutputTokens);
  const aiModel = ["gemini-flash-latest", "gemini-flash-lite-latest"].includes(
    cleanText(aiAnalysis.model),
  )
    ? cleanText(aiAnalysis.model)
    : "gemini-flash-latest";
  const aiThinkingLevel = ["minimal", "low", "medium", "high"].includes(
    cleanText(aiAnalysis.thinkingLevel),
  )
    ? cleanText(aiAnalysis.thinkingLevel)
    : "low";
  const aiAnalysisStyle = ["concise", "standard", "detailed"].includes(
    cleanText(aiAnalysis.analysisStyle),
  )
    ? cleanText(aiAnalysis.analysisStyle)
    : "standard";
  return {
    websiteUrl: cleanText(settings.websiteUrl),
    feedbackEmail: cleanText(settings.feedbackEmail),
    feedbackUrl: cleanText(settings.feedbackUrl),
    githubUrl: cleanText(settings.githubUrl),
    nodeRequestTimeoutMs: Number.isFinite(timeout)
      ? Math.round(Math.min(Math.max(timeout, 5000), 60000))
      : 25000,
    registrationEnabled: parseFlag(
      settings.registrationEnabled ?? settings.formalRegistrationEnabled,
      true,
    ),
    requireInviteForRegistration: parseFlag(
      settings.requireInviteForRegistration,
      false,
    ),
    requireAdminRegistrationCode: parseFlag(
      settings.requireAdminRegistrationCode,
      false,
    ),
    experienceThresholds: normalizeExperienceThresholds(settings.experienceThresholds),
    accountBindingExperience: normalizeExperience(
      configuredAccountBindingExperience,
      ACCOUNT_BINDING_EXPERIENCE,
    ),
    invitationExperience: normalizeExperience(
      settings.invitationExperience,
      DEFAULT_INVITATION_EXPERIENCE,
    ),
    telegramGroupUrl: normalizeTelegramGroupUrl(settings.telegramGroupUrl),
    telegramJoinCode: normalizeTelegramJoinCode(settings.telegramJoinCode),
    telegramTaskExperience: normalizeExperience(
      settings.telegramTaskExperience,
      DEFAULT_TELEGRAM_TASK_EXPERIENCE,
    ),
    aiAnalysis: {
      model: aiModel,
      thinkingLevel: aiThinkingLevel,
      maxOutputTokens: Number.isFinite(aiMaxOutputTokens)
        ? Math.min(Math.max(Math.round(aiMaxOutputTokens), 256), 65536)
        : 4096,
      analysisStyle: aiAnalysisStyle,
      customInstruction: cleanText(aiAnalysis.customInstruction).slice(0, 1000),
    },
  };
}

export function publicClientSettings(settings) {
  const value = normalizeSettings(settings);
  const { telegramJoinCode, aiAnalysis, ...publicSettings } = value;
  return publicSettings;
}

export function normalizeTelegramGroupUrl(value) {
  const url = cleanText(value).slice(0, 500);
  return /^(?:https?:\/\/|tg:\/\/)/i.test(url) ? url : "";
}
