import type { Env } from "../types/env.ts";
import type {
  AppState,
  JsonObject,
  Settings,
  SubscriptionAccountStatus,
  SubscriptionGroup,
  UserIdentityType,
  SubscriptionMembershipStatus,
  SubscriptionSource,
} from "../types/models.ts";

import { DEFAULT_STATE, LEGACY_STATE_KEY, STATE_KEY } from "../config.ts";
import { normalizeAccountType } from "../domain/account.ts";
import { cleanText, parseFlag, plainObject } from "../lib/values.ts";
import {
  loadSubscriptionGroups,
  saveSubscriptionGroups,
} from "./subscription-store.ts";
import {
  isAppVersionAtLeast,
  normalizeManagedContent,
  normalizeMinimumAppVersion,
  normalizeSubscriptionCacheMinutes,
} from "./subscriptions.ts";

const SUBSCRIPTION_STATUSES: SubscriptionAccountStatus[] = ["normal", "banned"];
const SUBSCRIPTION_IDENTITY_TYPES: UserIdentityType[] = ["human", "bot"];
const SUBSCRIPTION_MEMBERSHIP_TYPES: SubscriptionMembershipStatus[] = ["member", "non_member"];

/** 按订阅配置签名维护版本；普通订阅彼此独立，负载均衡订阅作为一项整体管理。 */
export function reconcileSubscriptionVersions(
  currentGroups: SubscriptionGroup[],
  nextGroups: SubscriptionGroup[],
  currentVersions: Record<string, number>,
) {
  const currentSignatures = subscriptionSignatures(currentGroups);
  const nextVersions = { ...currentVersions };
  for (const group of nextGroups) {
    for (const [subscriptionId, signature] of groupSubscriptionSignatures(group)) {
      const version = nextVersions[subscriptionId] ?? 0;
      nextVersions[subscriptionId] = currentSignatures.get(subscriptionId) === signature
        ? version
        : Math.min(Number.MAX_SAFE_INTEGER, version + 1);
    }
  }
  return nextVersions;
}

function subscriptionSignatures(groups: SubscriptionGroup[]) {
  return new Map(groups.flatMap((group) => [...groupSubscriptionSignatures(group)]));
}

function groupSubscriptionSignatures(group: SubscriptionGroup) {
  if (group.groupType === "load_balanced") {
    return [[`load-balanced:${group.id}`, JSON.stringify({
      groupId: group.id,
      maxUsersPerSubscription: group.maxUsersPerSubscription,
      resetPeriodDays: group.resetPeriodDays,
      subscriptions: group.subscriptions.map(subscriptionSignatureSource),
    })] as const];
  }
  return group.subscriptions.map((source) => [
    source.id,
    JSON.stringify(subscriptionSignatureSource(source)),
  ] as const);
}

function subscriptionSignatureSource(source: SubscriptionSource) {
  const signatureSource = { ...source };
  delete signatureSource.contentKey;
  delete signatureSource.assignmentKey;
  return signatureSource;
}

export function incrementSubscriptionVersions(
  versions: Record<string, number>,
  subscriptionIds: Iterable<string>,
) {
  const next = { ...versions };
  for (const subscriptionId of subscriptionIds) {
    next[subscriptionId] = Math.min(Number.MAX_SAFE_INTEGER, (next[subscriptionId] ?? 0) + 1);
  }
  return next;
}

function normalizeSelection<T extends string>(value: unknown, allowed: readonly T[], fallback: T[]): T[] {
  const input = Array.isArray(value) ? value : fallback;
  return Array.from(new Set(input.filter((item): item is T =>
    typeof item === "string" && allowed.includes(item as T))));
}

function normalizeSubscriptionSource(item: JsonObject, id: number): SubscriptionSource | null {
  const content = normalizeManagedContent(
    item.content ?? item.managedContent ?? item.nodesText,
  );
  const sourceType = item.sourceType === "content" || content ? "content" : "url";
  const externalUrl = typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
    ? item.url.trim()
    : "";
  if (sourceType !== "content" && !externalUrl) return null;
  return {
    id: String(id),
    name: cleanText(item.name || item.title) || `线路${id}`,
    url: sourceType === "content" ? "" : externalUrl,
    sourceType,
    ...(typeof item.contentKey === "string" && item.contentKey ? { contentKey: item.contentKey } : {}),
    ...(typeof item.assignmentKey === "string" && item.assignmentKey ? { assignmentKey: item.assignmentKey } : {}),
    enabled: parseFlag(item.enabled, true),
    // 自动重命名只对外部订阅生效。
    autoRename: sourceType === "url" && parseFlag(item.autoRename ?? item.autoRenameNodes, false),
    deduplicate: parseFlag(item.deduplicate, true),
    content,
    remark: cleanText(item.remark || item.note).slice(0, 500),
  };
}

function normalizeIdentitySelection(value: unknown): UserIdentityType[] {
  if (!Array.isArray(value)) return ["human", "bot"];
  return normalizeSelection(
    value.map((item) => item === "unknown" ? "human" : item),
    SUBSCRIPTION_IDENTITY_TYPES,
    ["human", "bot"],
  );
}

function normalizeSubscriptionGroup(
  input: JsonObject,
  index: number,
  nextSourceId: (requestedId?: unknown) => number,
): SubscriptionGroup {
  const rawSources = input.subscriptions ?? input.sources;
  const sourceInputs = (Array.isArray(rawSources) ? rawSources : []).filter(plainObject);
  const sources = sourceInputs
    .map((item) => normalizeSubscriptionSource(item, nextSourceId(item.id)))
    .filter((item): item is SubscriptionSource => item !== null);
  // 旧配置把缓存时间存在订阅行；迁移时采用组内第一条外部订阅的旧值。
  const legacyCacheMinutes = sourceInputs.find((item) =>
    typeof item.url === "string" && item.url.trim() && !item.content &&
    item.cacheMinutes !== undefined && item.cacheMinutes !== null && item.cacheMinutes !== "")?.cacheMinutes;
  const cacheMinutes = normalizeSubscriptionCacheMinutes(input.cacheMinutes ?? legacyCacheMinutes);
  const minAppVersion = normalizeMinimumAppVersion(input.minAppVersion ?? input.minimumAppVersion);
  const maxAppVersion = normalizeMinimumAppVersion(input.maxAppVersion ?? input.maximumAppVersion);
  if (minAppVersion && maxAppVersion && !isAppVersionAtLeast(maxAppVersion, minAppVersion)) {
    throw new Error("invalid_app_version_range");
  }
  return {
    id: cleanText(input.id).slice(0, 80) || String(index + 1),
    name: cleanText(input.name || input.title).slice(0, 80) || `分组${index + 1}`,
    groupType: input.groupType === "load_balanced" ? "load_balanced" : "standard",
    // A legacy group that excluded registered users must not become accessible.
    enabled: parseFlag(input.enabled, true) &&
      (!Array.isArray(input.allowedAccountTypes) || input.allowedAccountTypes.includes("formal")),
    allowedStatuses: normalizeSelection(input.allowedStatuses, SUBSCRIPTION_STATUSES, ["normal"]),
    allowedIdentityTypes: normalizeIdentitySelection(input.allowedIdentityTypes),
    allowedMembershipTypes: normalizeSelection(
      input.allowedMembershipTypes,
      SUBSCRIPTION_MEMBERSHIP_TYPES,
      ["member", "non_member"],
    ),
    updateEnabled: parseFlag(input.updateEnabled, true),
    cacheMinutes,
    minAppVersion,
    maxAppVersion,
    maxUsersPerSubscription: normalizeBoundedInteger(input.maxUsersPerSubscription, 10, 1, 1_000_000),
    resetPeriodDays: normalizeBoundedInteger(input.resetPeriodDays, 30, 1, 3650),
    subscriptions: sources,
  };
}

function normalizeBoundedInteger(value: unknown, fallback: number, minimum: number, maximum: number) {
  const number = Number(value);
  return Number.isInteger(number) && number >= minimum && number <= maximum
    ? number
    : fallback;
}

export interface ReadStateOptions {
  includeSubscriptionContent?: boolean;
}

export async function readState(env: Env, options: ReadStateOptions = {}): Promise<AppState> {
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
  if (!env.SUBSCRIPTIONS_DB) {
    if (stored && !chimeraState) {
      await env.CONFIG.put(STATE_KEY, JSON.stringify(normalized));
    }
    return normalized;
  }

  let groups = await loadSubscriptionGroups(env, options.includeSubscriptionContent === true);
  if (!groups) {
    await saveSubscriptionGroups(env, normalized.subscriptionGroups, { importLegacyOnly: true });
    groups = await loadSubscriptionGroups(env, options.includeSubscriptionContent === true);
    const [latestChimeraState, latestLegacyState] = await Promise.all([
      env.CONFIG.get<JsonObject>(STATE_KEY, "json"),
      env.CONFIG.get<JsonObject>(LEGACY_STATE_KEY, "json"),
    ]);
    const latestChimeraUpdatedAt = Date.parse(String(latestChimeraState?.updatedAt || "")) || 0;
    const latestLegacyUpdatedAt = Date.parse(String(latestLegacyState?.updatedAt || "")) || 0;
    const latestStored = latestLegacyUpdatedAt > latestChimeraUpdatedAt
      ? latestLegacyState
      : latestChimeraState || latestLegacyState;
    const latestState = normalizeState(latestStored || stored || DEFAULT_STATE);
    const compactState = JSON.stringify({ ...latestState, subscriptionGroups: [] });
    // 把已有订阅正文从 KV 主状态剥离；如果并发管理请求已保存更新，保留较新的应用设置。
    await Promise.all([
      env.CONFIG.put(STATE_KEY, compactState),
      env.CONFIG.put(LEGACY_STATE_KEY, compactState),
    ]);
  }
  return normalizeState({ ...(stored || {}), subscriptionGroups: groups || [] });
}

export async function writeState(env: Env, state: AppState) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const normalized = normalizeState(state);
  if (env.SUBSCRIPTIONS_DB) {
    await saveSubscriptionGroups(env, normalized.subscriptionGroups);
  }
  const storedState = env.SUBSCRIPTIONS_DB
    ? { ...normalized, subscriptionGroups: [] }
    : normalized;
  const serialized = JSON.stringify(storedState);
  await Promise.all([
    env.CONFIG.put(STATE_KEY, serialized),
    env.CONFIG.put(LEGACY_STATE_KEY, serialized),
  ]);
}

export function normalizeState(input: unknown): AppState {
  const value = plainObject(input) ? input : {};
  const subscriptionVersions = plainObject(value.subscriptionVersions)
    ? Object.fromEntries(Object.entries(value.subscriptionVersions).filter(([, rawVersion]) => {
      const version = Number(rawVersion);
      return Number.isSafeInteger(version) && version >= 0;
    }).map(([subscriptionId, rawVersion]) => [subscriptionId, Number(rawVersion)]))
    : {};
  const storedGroups = Array.isArray(value.subscriptionGroups)
    ? value.subscriptionGroups
    : [];
  const sourceInputs = storedGroups.length
    ? storedGroups.filter(plainObject).flatMap((group) => {
      const sources = group.subscriptions ?? group.sources;
      return (Array.isArray(sources) ? sources : []).filter(plainObject);
    })
    : (Array.isArray(value.subscriptions)
      ? value.subscriptions
      : Array.isArray(value.sources)
        ? value.sources
        : []).filter(plainObject);
  const reservedSourceIds = new Set<number>();
  let sourceId = 0;
  for (const item of sourceInputs) {
    const requested = Number(item.id);
    if (/^\d+$/.test(String(item.id ?? "")) && Number.isSafeInteger(requested) && requested > 0) {
      reservedSourceIds.add(requested);
      sourceId = Math.max(sourceId, requested);
    }
  }
  const claimedSourceIds = new Set<number>();
  const nextSourceId = (requestedId?: unknown) => {
    const requested = Number(requestedId);
    if (
      /^\d+$/.test(String(requestedId ?? "")) && Number.isSafeInteger(requested) && requested > 0 &&
      reservedSourceIds.has(requested) && !claimedSourceIds.has(requested)
    ) {
      claimedSourceIds.add(requested);
      return requested;
    }
    do {
      sourceId += 1;
    } while (reservedSourceIds.has(sourceId) || claimedSourceIds.has(sourceId));
    claimedSourceIds.add(sourceId);
    return sourceId;
  };
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
        const source = normalizeSubscriptionSource(item, nextSourceId(item.id));
        if (!source) return [];
        // 旧版每条线路独立配置最低等级和版本，迁移时各自成为一个可编辑分组。
        const minimumType = normalizeAccountType(
          item.minAccountType ?? item.requiredAccountType ?? item.minimumAccountType,
          "formal",
        );
        return [{
          id: String(index + 1),
          name: cleanText(item.groupName || item.name || item.title) || `分组${index + 1}`,
          groupType: "standard",
          enabled: true,
          allowedStatuses: minimumType === "banned" ? ["normal", "banned"] : ["normal"],
          allowedIdentityTypes: ["human", "bot"],
          allowedMembershipTypes: ["member", "non_member"],
          updateEnabled: true,
          cacheMinutes: normalizeSubscriptionCacheMinutes(source.url ? item.cacheMinutes : undefined),
          minAppVersion: normalizeMinimumAppVersion(item.minAppVersion ?? item.minimumAppVersion),
          maxAppVersion: "",
          maxUsersPerSubscription: 10,
          resetPeriodDays: 30,
          subscriptions: [source],
        }];
      });
  return {
    version: 2,
    subscriptionVersions,
    subscriptionGroups,
    settings: normalizeSettings(value.settings),
    ...(value.updatedAt ? { updatedAt: String(value.updatedAt) } : {}),
  };
}

export function normalizeSettings(value: unknown): Settings {
  const settings = plainObject(value) ? value : {};
  // 旧版状态没有注册控制字段，升级时采用新的邮箱绑定经验默认值。
  const hasRegistrationControl =
    Object.prototype.hasOwnProperty.call(settings, "registrationEnabled") ||
    Object.prototype.hasOwnProperty.call(settings, "formalRegistrationEnabled");
  const latestAppVersion = normalizeLatestAppVersion(settings.latestAppVersion);
  const appDownloadUrl = normalizeAppDownloadUrl(settings.appDownloadUrl);
  if (Boolean(latestAppVersion) !== Boolean(appDownloadUrl)) {
    throw new Error("incomplete_app_update_config");
  }
  return {
    websiteUrl: cleanText(settings.websiteUrl),
    feedbackEmail: cleanText(settings.feedbackEmail),
    feedbackUrl: cleanText(settings.feedbackUrl),
    githubUrl: cleanText(settings.githubUrl),
    telegramUrl: cleanText(settings.telegramUrl),
    latestAppVersion,
    appDownloadUrl,
    registrationEnabled: parseFlag(
      settings.registrationEnabled ?? settings.formalRegistrationEnabled,
      true,
    ),
    requireAdminRegistrationCode: parseFlag(
      settings.requireAdminRegistrationCode,
      false,
    ),
  };
}

function normalizeAppDownloadUrl(value: unknown) {
  const raw = cleanText(value);
  if (!raw) return "";
  if (raw.length > 2048) throw new Error("invalid_app_download_url");
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error("invalid_app_download_url");
  }
  if (!new Set(["http:", "https:"]).has(url.protocol) || !url.hostname || url.username || url.password) {
    throw new Error("invalid_app_download_url");
  }
  return url.toString();
}

function normalizeLatestAppVersion(value: unknown) {
  try {
    return normalizeMinimumAppVersion(value);
  } catch {
    throw new Error("invalid_latest_app_version");
  }
}

export function publicClientSettings(settings: Settings) {
  return normalizeSettings(settings);
}
