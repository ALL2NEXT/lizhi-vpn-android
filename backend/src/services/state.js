import { DEFAULT_STATE, STATE_KEY } from "../config.js";
import { normalizeAccountType } from "../domain/account.js";
import { beijingTimestamp } from "../domain/time.js";
import { cleanText, parseFlag, plainObject } from "../lib/values.js";
import {
  normalizeManagedContent,
  normalizeMinimumAppVersion,
  normalizeSubscriptionCacheMinutes,
} from "./subscriptions.js";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function randomDebugInterfaceToken() {
  return globalThis.crypto.randomUUID();
}

function normalizeDebugInterface(value, subscriptions) {
  const input = plainObject(value) ? value : {};
  const availableIds = new Set(subscriptions.map((item) => item.id));
  const rawIds = input.subscriptionIds ?? input.sourceIds ?? input.subscriptions;
  const subscriptionIds = Array.from(new Set(
    (Array.isArray(rawIds) ? rawIds : [])
      .map((id) => String(id).trim())
      .filter((id) => /^\d+$/.test(id) && availableIds.has(id)),
  ));
  const candidate = cleanText(input.token ?? input.uuid).toLowerCase();
  return {
    token: UUID_PATTERN.test(candidate) ? candidate : randomDebugInterfaceToken(),
    subscriptionIds,
  };
}

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
      const externalUrl = typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
        ? item.url.trim()
        : "";
      return {
        url: content ? "" : externalUrl,
        name: cleanText(item.name || item.title),
        enabled: parseFlag(item.enabled, true),
        minAccountType: normalizeAccountType(
          item.minAccountType ?? item.requiredAccountType ?? item.minimumAccountType,
          "guest",
        ),
        minAppVersion: normalizeMinimumAppVersion(
          item.minAppVersion ?? item.minimumAppVersion,
        ),
        cacheMinutes: normalizeSubscriptionCacheMinutes(item.cacheMinutes),
        // 只有外部订阅允许在 Worker 转发前自动重命名节点。
        autoRename: !content && parseFlag(item.autoRename ?? item.autoRenameNodes, false),
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
      minAccountType: item.minAccountType,
      minAppVersion: item.minAppVersion,
      cacheMinutes: item.cacheMinutes,
      autoRename: item.autoRename,
      content: item.content,
      remark: item.remark,
    }));

  return {
    version: 1,
    subscriptions,
    notice: normalizeNotice(value.notice),
    appUpdate: plainObject(value.appUpdate) ? value.appUpdate : null,
    settings: normalizeSettings(value.settings, subscriptions),
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

export function normalizeSettings(value, subscriptions = []) {
  const settings = plainObject(value) ? value : {};
  // 旧版状态没有注册控制字段，升级时采用新的邮箱绑定经验默认值。
  const hasRegistrationControl =
    Object.prototype.hasOwnProperty.call(settings, "registrationEnabled") ||
    Object.prototype.hasOwnProperty.call(settings, "formalRegistrationEnabled");
  const timeout = Number(settings.nodeRequestTimeoutMs);
  const webNavigationGroups = settings.webNavigationGroups == null
    ? DEFAULT_STATE.settings.webNavigationGroups.map((group) => ({
      title: group.title,
      items: group.items.map((item) => ({ ...item })),
    }))
    : normalizeWebNavigationGroups(settings.webNavigationGroups);
  return {
    websiteUrl: cleanText(settings.websiteUrl),
    webNavigationGroups,
    feedbackEmail: cleanText(settings.feedbackEmail),
    feedbackUrl: cleanText(settings.feedbackUrl),
    githubUrl: cleanText(settings.githubUrl),
    telegramUrl: cleanText(settings.telegramUrl),
    nodeRequestTimeoutMs: Number.isFinite(timeout)
      ? Math.round(Math.min(Math.max(timeout, 5000), 60000))
      : 25000,
    registrationEnabled: parseFlag(
      settings.registrationEnabled ?? settings.formalRegistrationEnabled,
      true,
    ),
    requireAdminRegistrationCode: parseFlag(
      settings.requireAdminRegistrationCode,
      false,
    ),
    debugInterface: normalizeDebugInterface(settings.debugInterface, subscriptions),
  };
}

function normalizeWebNavigationGroups(value) {
  if (!Array.isArray(value) || value.length > 50) {
    throw new Error("invalid_web_navigation_groups");
  }

  let itemCount = 0;
  // 图标直接随 KV 配置保存，限制总 Base64 体积以避免配置过大。
  let iconTotalSize = 0;
  return value.map((rawGroup) => {
    if (!rawGroup || typeof rawGroup !== "object" || Array.isArray(rawGroup)) {
      throw new Error("invalid_web_navigation_group");
    }
    const title = cleanText(rawGroup.title);
    if (!title || title.length > 60 || !Array.isArray(rawGroup.items)) {
      throw new Error("invalid_web_navigation_group");
    }

    const items = rawGroup.items.map((rawItem) => {
      if (!rawItem || typeof rawItem !== "object" || Array.isArray(rawItem)) {
        throw new Error("invalid_web_navigation_item");
      }
      const itemTitle = cleanText(rawItem.title);
      const url = cleanText(rawItem.url);
      const iconBase64 = cleanText(rawItem.iconBase64);
      const iconMimeType = cleanText(rawItem.iconMimeType).toLowerCase();
      let parsedUrl;
      try {
        parsedUrl = new URL(url);
      } catch {
        throw new Error("invalid_web_navigation_url");
      }
      if (
        !itemTitle || itemTitle.length > 80 || url.length > 2048 ||
        !["http:", "https:"].includes(parsedUrl.protocol) ||
        parsedUrl.username || parsedUrl.password
      ) {
        throw new Error("invalid_web_navigation_item");
      }
      if (iconBase64 && (
        iconBase64.length > 180000 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(iconBase64) ||
        !["image/png", "image/jpeg", "image/webp", "image/gif", "image/x-icon", "image/vnd.microsoft.icon"].includes(iconMimeType)
      )) {
        throw new Error("invalid_web_navigation_icon");
      }
      if (!iconBase64 && iconMimeType) {
        throw new Error("invalid_web_navigation_icon");
      }
      iconTotalSize += iconBase64.length;
      if (iconTotalSize > 4 * 1024 * 1024) {
        throw new Error("web_navigation_icons_too_large");
      }
      itemCount += 1;
      if (itemCount > 250) throw new Error("too_many_web_navigation_items");
      return { title: itemTitle, url, iconBase64, iconMimeType };
    });

    return { title, items };
  });
}

export function publicClientSettings(settings) {
  const normalized = normalizeSettings(settings);
  const { debugInterface: _debugInterface, ...publicSettings } = normalized;
  return publicSettings;
}
