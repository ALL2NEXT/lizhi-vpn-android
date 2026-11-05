import type { Env } from "../types/env.ts";
import type { RouteDependencies } from "../types/routes.ts";
import type { SubscriptionGroup, SubscriptionSource } from "../types/models.ts";
import { subscriptionGroupAccessError } from "../domain/subscription-access.ts";
import { getOrAssignBalancedSubscription } from "../services/subscription-balancing.ts";
import {
  markAllClientNotificationsRead,
  markClientNotificationRead,
  readClientNotifications,
} from "../services/notifications.ts";
import { observedFetch } from "../services/system-logs.ts";

/*
 * Client routes.
 *
 * Handlers receive only the shared runtime dependencies they use.
 */

const PROXY_APP_LIST_URL =
  "https://raw.githubusercontent.com/2dust/androidpackagenamelist/master/proxy.txt";
const MAX_PROXY_APP_LIST_BYTES = 1024 * 1024;

export function createClientRoutes(deps: Pick<
  RouteDependencies,
  | "resolveAccountAccess"
  | "resolveDeviceIdentity"
  | "beijingTimestamp"
  | "normalizeAccountId"
  | "isAppVersionAtLeast"
  | "isExternalSubscriptionRefreshDue"
  | "readExternalSubscription"
  | "readSubscriptionContent"
  | "autoRenameSubscriptionContent"
  | "deduplicateSubscriptionContent"
  | "readState"
  | "writeState"
  | "resolveUserIdentity"
  | "publicClientSettings"
  | "corsHeaders"
  | "json"
>) {
  const {
    resolveAccountAccess,
    resolveDeviceIdentity,
    beijingTimestamp,
    normalizeAccountId,
    isAppVersionAtLeast,
    isExternalSubscriptionRefreshDue,
    readExternalSubscription,
    readSubscriptionContent,
    autoRenameSubscriptionContent,
    deduplicateSubscriptionContent,
    readState,
    writeState,
    resolveUserIdentity,
    publicClientSettings,
    corsHeaders,
    json,
  } = deps;

  const hasSubscriptionData = (source: SubscriptionSource) =>
    source.sourceType === "content" || Boolean(source.content || source.url);
  const isLocalSubscription = (source: SubscriptionSource) =>
    source.sourceType === "content" || Boolean(source.content);

async function handleClient(request: Request, env: Env) {
  const requestUrl = new URL(request.url);
  const deviceId = normalizeAccountId(requestUrl.searchParams.get("deviceId"));
  if (!deviceId) {
    return json({ ok: false, error: "device_id_required" }, 401);
  }
  const access = await resolveAccountAccess(env, deviceId);
  if (!access.registered) {
    return json({ ok: false, error: "account_access_denied" }, 403);
  }
  const origin = requestUrl.origin;
  let state = await readState(env);
  let groups = state.subscriptionGroups.filter((group) =>
    subscriptionGroupAccessError(group, access, isAppVersionAtLeast) === null
  );

  // 缓存到期由后台策略决定是否递增版本，客户端据此判断是否需要拉取节点。
  const refreshDue = await Promise.all(groups.flatMap((group) =>
    group.subscriptions
      .filter((source) => source.enabled && source.url && !isLocalSubscription(source))
      .map(async (source) => {
        try {
          return await isExternalSubscriptionRefreshDue(
            env,
            source,
            group.cacheMinutes,
            group.updateEnabled,
          );
        } catch {
          return false;
        }
      })
  ));
  if (refreshDue.some(Boolean)) {
    const latestState = await readState(env, { includeSubscriptionContent: true });
    state = {
      ...latestState,
      subscriptionVersion: Math.min(Number.MAX_SAFE_INTEGER, latestState.subscriptionVersion + 1),
      updatedAt: beijingTimestamp(),
    };
    await writeState(env, state);
    groups = state.subscriptionGroups.filter((group) =>
      subscriptionGroupAccessError(group, access, isAppVersionAtLeast) === null
    );
  }

  return json({
    subscriptionVersion: state.subscriptionVersion,
    subscriptions: groups.flatMap((group) => {
      const sources = group.subscriptions.filter((source) =>
        source.enabled && hasSubscriptionData(source)
      );
      if (!sources.length) return [];
      if (group.groupType === "load_balanced") {
        return [{
          enabled: true,
          id: `load-balanced:${group.id}`,
          name: group.name,
          url: `${origin}/api/subscription/lb/${encodeURIComponent(group.id)}`,
        }];
      }
      return sources.map((source) => ({
        enabled: true,
        id: source.id,
        name: source.name,
        url: `${origin}/api/subscription/${encodeURIComponent(source.id)}`,
      }));
    }),
    settings: publicClientSettings(state.settings),
    generatedAt: beijingTimestamp(),
  });
}

async function handleClientNotifications(path: string, request: Request, env: Env) {
  const requestUrl = new URL(request.url);
  const deviceId = normalizeAccountId(requestUrl.searchParams.get("deviceId"));
  if (!deviceId) return json({ ok: false, error: "device_id_required" }, 401);

  const access = await resolveAccountAccess(env, deviceId);
  if (!access.registered) return json({ ok: false, error: "account_access_denied" }, 403);
  const identity = await resolveDeviceIdentity(env, deviceId);
  if (!identity || identity.userId <= 0) {
    return json({ ok: false, error: "account_access_denied" }, 403);
  }

  if (path === "/api/client/notifications" && request.method === "GET") {
    return json(await readClientNotifications(env, identity.userId));
  }
  if (path === "/api/client/notifications/read-all" && request.method === "POST") {
    return json(await markAllClientNotificationsRead(env, identity.userId));
  }

  const match = /^\/api\/client\/notifications\/(\d+)\/read$/.exec(path);
  if (match && request.method === "POST") {
    const notificationId = Number(match[1]);
    if (!Number.isSafeInteger(notificationId) || notificationId <= 0) {
      return json({ ok: false, error: "invalid_notification_id" }, 400);
    }
    return json(await markClientNotificationRead(env, identity.userId, notificationId));
  }

  return json({ ok: false, error: "notification_route_not_found" }, 404);
}

async function handleProxyAppList(env: Env) {
  let upstream;
  try {
    // 固定上游名单地址，只代理这一份资源，避免端点成为任意 URL 代理。
    upstream = await observedFetch(env, PROXY_APP_LIST_URL, {
      headers: {
        accept: "text/plain",
        "user-agent": "Chimera-Worker",
      },
      cf: { cacheTtl: 900, cacheEverything: true },
    });
  } catch (error) {
    console.error(JSON.stringify({
      message: "proxy_app_list_upstream_fetch_failed",
      error: error instanceof Error ? error.message : String(error),
    }));
    return json({ error: "proxy_app_list_unavailable" }, 502);
  }

  if (!upstream.ok) {
    return json({ error: "proxy_app_list_upstream_failed" }, 502);
  }

  const contentLength = Number.parseInt(upstream.headers.get("content-length") || "", 10);
  if (Number.isFinite(contentLength) && contentLength > MAX_PROXY_APP_LIST_BYTES) {
    return json({ error: "proxy_app_list_too_large" }, 502);
  }

  let content;
  try {
    content = await upstream.text();
  } catch (error) {
    console.error(JSON.stringify({
      message: "proxy_app_list_upstream_read_failed",
      error: error instanceof Error ? error.message : String(error),
    }));
    return json({ error: "proxy_app_list_unavailable" }, 502);
  }

  const byteLength = new TextEncoder().encode(content).byteLength;
  const hasPackageName = content.split(/\r?\n/).some((line) =>
    /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$/.test(line.trim()));
  if (byteLength > MAX_PROXY_APP_LIST_BYTES || !content.trim() || !hasPackageName) {
    return json({ error: "proxy_app_list_invalid" }, 502);
  }

  return new Response(content, {
    headers: {
      ...corsHeaders(),
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "public, max-age=300, s-maxage=900",
    },
  });
}

async function handleManagedSubscription(path: string, request: Request, env: Env) {
  const suffix = path.slice("/api/subscription/".length);
  const isLoadBalanced = suffix.startsWith("lb/");
  let balancedGroupId = "";
  if (isLoadBalanced) {
    try {
      balancedGroupId = decodeURIComponent(suffix.slice("lb/".length));
    } catch {
      return json({ error: "subscription_not_found" }, 404);
    }
  } else if (!/^\d+$/.test(suffix)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const state = await readState(env);
  let group: SubscriptionGroup | undefined;
  let source: SubscriptionSource | undefined;
  if (isLoadBalanced) {
    group = state.subscriptionGroups.find((item) =>
      item.id === balancedGroupId && item.groupType === "load_balanced");
    if (!group || !group.enabled || !group.subscriptions.some((item) =>
      item.enabled && hasSubscriptionData(item)
    )) {
      return json({ error: "subscription_not_found" }, 404);
    }
  } else {
    group = state.subscriptionGroups.find((item) =>
      item.subscriptions.some((itemSource) => itemSource.id === suffix));
    source = group?.subscriptions.find((item) => item.id === suffix);
    if (!group || !group.enabled || !source || !source.enabled || !hasSubscriptionData(source)) {
      return json({ error: "subscription_not_found" }, 404);
    }
  }
  if (!group) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const requestUrl = new URL(request.url);
  const deviceId = normalizeAccountId(requestUrl.searchParams.get("deviceId"));
  if (!deviceId) {
    return json({ ok: false, error: "device_id_required" }, 401);
  }
  const access = await resolveAccountAccess(env, deviceId);
  const accessError = subscriptionGroupAccessError(group, access, isAppVersionAtLeast);
  if (accessError === "account_access_denied") {
    return json({
      ok: false,
      error: "account_access_denied",
      requiredStatuses: group.allowedStatuses,
      requiredAccountTypes: group.allowedAccountTypes,
      requiredMembershipTypes: group.allowedMembershipTypes,
      accountStatus: access.accountStatus,
      accountType: access.accountType,
      vipActive: access.vipActive,
    }, 403);
  }
  if (accessError === "app_version_too_low") {
    return json({
      ok: false,
      error: "app_version_too_low",
      minimumVersion: group.minAppVersion,
      maximumVersion: group.maxAppVersion || null,
      currentVersion: access.appVersion || null,
    }, 403);
  }
  if (accessError === "app_version_too_high") {
    return json({
      ok: false,
      error: "app_version_too_high",
      minimumVersion: group.minAppVersion || null,
      maximumVersion: group.maxAppVersion,
      currentVersion: access.appVersion || null,
    }, 403);
  }

  if (isLoadBalanced) {
    const identity = await resolveUserIdentity(env, deviceId);
    if (!identity || identity.userId <= 0) {
      return json({ ok: false, error: "account_access_denied" }, 403);
    }
    source = (await getOrAssignBalancedSubscription(env, group, identity.userId)) || undefined;
    if (!source) {
      return json({ ok: false, error: "subscription_capacity_exhausted" }, 503);
    }
  }
  if (!source) return json({ error: "subscription_not_found" }, 404);

  let fetchedContent = "";
  try {
    if (isLocalSubscription(source)) {
      fetchedContent = await readSubscriptionContent(env, source);
    } else {
      fetchedContent = await readExternalSubscription(
        env,
        source,
        group.cacheMinutes,
        group.updateEnabled,
      );
    }
  } catch (error) {
    if (error instanceof Error && error.message === "subscription_update_disabled_cache_missing") {
      return json({ error: "subscription_update_disabled_cache_missing" }, 503);
    }
    return json({ error: "subscription_fetch_failed" }, 502);
  }
  // 纯文本节点由后台直接维护，自动重命名只对外部接口生效。
  const uniqueContent = source.deduplicate
    ? deduplicateSubscriptionContent(fetchedContent)
    : fetchedContent;
  const plaintextContent = source.autoRename && !isLocalSubscription(source)
    ? autoRenameSubscriptionContent(uniqueContent)
    : uniqueContent;
  return new Response(plaintextContent, {
    headers: {
      ...corsHeaders(),
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

  return {
    handleClient,
    handleClientNotifications,
    handleProxyAppList,
    handleManagedSubscription,
  };
}
