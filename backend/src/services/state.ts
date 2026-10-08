import type { Env } from "../types/env.ts";
import type {
  AppState,
  DebugInterface,
  JsonObject,
  NavigationGroup,
  Notice,
  Settings,
  SubscriptionAccountStatus,
  SubscriptionGroup,
  SubscriptionSource,
} from "../types/models.ts";

import { DEFAULT_STATE, LEGACY_STATE_KEY, STATE_KEY } from "../config.ts";
import { normalizeAccountType } from "../domain/account.ts";
import { beijingTimestamp } from "../domain/time.ts";
import { cleanText, parseFlag, plainObject } from "../lib/values.ts";
import {
  normalizeManagedContent,
  normalizeMinimumAppVersion,
  normalizeSubscriptionCacheMinutes,
} from "./subscriptions.ts";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SUBSCRIPTION_STATUSES: SubscriptionAccountStatus[] = ["normal", "banned"];
const SUBSCRIPTION_ACCOUNT_TYPES = ["guest", "formal"] as const;

function normalizeSelection<T extends string>(value: unknown, allowed: readonly T[], fallback: T[]): T[] {
  const input = Array.isArray(value) ? value : fallback;
  return Array.from(new Set(input.filter((item): item is T =>
    typeof item === "string" && allowed.includes(item as T))));
}

function normalizeSubscriptionSource(item: JsonObject, id: number): SubscriptionSource | null {
  const content = normalizeManagedContent(
    item.content ?? item.managedContent ?? item.nodesText,
  );
  const externalUrl = typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
    ? item.url.trim()
    : "";
  if (!content && !externalUrl) return null;
  return {
    id: String(id),
    name: cleanText(item.name || item.title) || `线路${id}`,
    url: content ? "" : externalUrl,
    enabled: parseFlag(item.enabled, true),
    cacheMinutes: normalizeSubscriptionCacheMinutes(item.cacheMinutes),
    // 自动重命名只对外部订阅生效。
    autoRename: !content && parseFlag(item.autoRename ?? item.autoRenameNodes, false),
    content,
    remark: cleanText(item.remark || item.note).slice(0, 500),
  };
}

function normalizeSubscriptionGroup(
  input: JsonObject,
  index: number,
  nextSourceId: () => number,
): SubscriptionGroup {
  const rawSources = input.subscriptions ?? input.sources;
  const sources = (Array.isArray(rawSources) ? rawSources : [])
    .filter(plainObject)
    .map((item) => normalizeSubscriptionSource(item, nextSourceId()))
    .filter((item): item is SubscriptionSource => item !== null);
  return {
    id: cleanText(input.id).slice(0, 80) || String(index + 1),
    name: cleanText(input.name || input.title).slice(0, 80) || `分组${index + 1}`,
    enabled: parseFlag(input.enabled, true),
    allowedStatuses: normalizeSelection(input.allowedStatuses, SUBSCRIPTION_STATUSES, ["normal"]),
    allowedAccountTypes: normalizeSelection(input.allowedAccountTypes, SUBSCRIPTION_ACCOUNT_TYPES, ["guest", "formal"]),
    minAppVersion: normalizeMinimumAppVersion(input.minAppVersion ?? input.minimumAppVersion),
    subscriptions: sources,
  };
}

function randomDebugInterfaceToken() {
  return crypto.randomUUID();
}

function normalizeDebugInterface(value: unknown, subscriptions: SubscriptionSource[]): DebugInterface {
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

export async function readState(env: Env): Promise<AppState> {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const [chimeraState, legacyState] = await Promise.all([
    env.CONFIG.get<JsonObject>(STATE_KEY, "json"),
    env.CONFIG.get<JsonObject>(LEGACY_STATE_KEY, "json"),
  ]);
  const chimeraUpdatedAt = Date.parse(String(chimeraState?.updatedAt || "")) || 0;
  const legacyUpdatedAt = Date.parse(String(legacyState?.updatedAt || "")) || 0;
  const stored = legacyUpdatedAt > chimeraUpdatedAt ? legacyState : chimeraState || legacyState;
  const normalized = normalizeState(stored || DEFAULT_STATE);
  if (stored && !chimeraState) {
    await env.CONFIG.put(STATE_KEY, JSON.stringify(normalized));
  }
  return normalized;
}

export async function writeState(env: Env, state: AppState) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const serialized = JSON.stringify(normalizeState(state));
  // 双写旧键，迁移期间让仍在使用旧 Worker 的管理端保持数据一致。
  await Promise.all([
    env.CONFIG.put(STATE_KEY, serialized),
    env.CONFIG.put(LEGACY_STATE_KEY, serialized),
  ]);
}

export function normalizeState(input: unknown): AppState {
  const value = plainObject(input) ? input : {};
  let sourceId = 0;
  const nextSourceId = () => ++sourceId;
  const storedGroups = Array.isArray(value.subscriptionGroups)
    ? value.subscriptionGroups
    : [];
  const subscriptionGroups: SubscriptionGroup[] = storedGroups.length
    ? storedGroups
      .filter(plainObject)
      .map((group, index) => normalizeSubscriptionGroup(group, index, nextSourceId))
    : (Array.isArray(value.subscriptions)
      ? value.subscriptions
      : Array.isArray(value.sources)
        ? value.sources
        : [])
      .filter(plainObject)
      .flatMap((item, index) => {
        const source = normalizeSubscriptionSource(item, nextSourceId());
        if (!source) return [];
        // 旧版每条线路独立配置最低等级和版本，迁移时各自成为一个可编辑分组。
        const minimumType = normalizeAccountType(
          item.minAccountType ?? item.requiredAccountType ?? item.minimumAccountType,
          "guest",
        );
        return [{
          id: String(index + 1),
          name: cleanText(item.groupName || item.name || item.title) || `分组${index + 1}`,
          enabled: true,
          allowedStatuses: minimumType === "banned" ? ["normal", "banned"] : ["normal"],
          allowedAccountTypes: minimumType === "formal" ? ["formal"] : ["guest", "formal"],
          minAppVersion: normalizeMinimumAppVersion(item.minAppVersion ?? item.minimumAppVersion),
          subscriptions: [source],
        }];
      });
  const subscriptions = subscriptionGroups.flatMap((group) => group.subscriptions);

  return {
    version: 2,
    subscriptionGroups,
    notice: normalizeNotice(value.notice),
    appUpdate: plainObject(value.appUpdate) ? value.appUpdate : null,
    settings: normalizeSettings(value.settings, subscriptions),
    ...(value.updatedAt ? { updatedAt: String(value.updatedAt) } : {}),
  };
}

export function normalizeNotice(value: unknown): Notice | null {
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

export function normalizeNoticeTimestamp(value: unknown, field: string) {
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

export function visibleNotice(notice: Notice | null, now = Date.now()) {
  if (!notice || !notice.hasNotice) return null;
  const startAt = Date.parse(notice.startAt || "");
  const endAt = Date.parse(notice.endAt || "");
  if (Number.isFinite(startAt) && now < startAt) return null;
  if (Number.isFinite(endAt) && now > endAt) return null;
  return notice;
}

export function normalizeSettings(value: unknown, subscriptions: SubscriptionSource[] = []): Settings {
  const settings = plainObject(value) ? value : {};
  // 旧版状态没有注册控制字段，升级时采用新的邮箱绑定经验默认值。
  const hasRegistrationControl =
    Object.prototype.hasOwnProperty.call(settings, "registrationEnabled") ||
    Object.prototype.hasOwnProperty.call(settings, "formalRegistrationEnabled");
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

function normalizeWebNavigationGroups(value: unknown): NavigationGroup[] {
  if (!Array.isArray(value) || value.length > 50) {
    throw new Error("invalid_web_navigation_groups");
  }

  let itemCount = 0;
  // 图标直接随 KV 配置保存，限制总 Base64 体积以避免配置过大。
  let iconTotalSize = 0;
  const groups: unknown[] = value;
  return groups.map((rawGroup) => {
    if (!plainObject(rawGroup)) {
      throw new Error("invalid_web_navigation_group");
    }
    const title = cleanText(rawGroup.title);
    if (!title || title.length > 60 || !Array.isArray(rawGroup.items)) {
      throw new Error("invalid_web_navigation_group");
    }

    const rawItems: unknown[] = rawGroup.items;
    const items = rawItems.map((rawItem) => {
      if (!plainObject(rawItem)) {
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

export function publicClientSettings(settings: Settings) {
  const normalized = normalizeSettings(settings);
  const { debugInterface: _debugInterface, ...publicSettings } = normalized;
  return publicSettings;
}
