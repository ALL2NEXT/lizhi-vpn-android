import type { Env } from "../types/env.ts";
import type { RouteDependencies } from "../types/routes.ts";
import type { SubscriptionGroup } from "../types/models.ts";
import { subscriptionGroupAccessError } from "../domain/subscription-access.ts";

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
  | "CLIENT_IP_HEADER"
  | "LEGACY_CLIENT_IP_HEADER"
  | "resolveAccountAccess"
  | "beijingTimestamp"
  | "normalizeAccountId"
  | "isAppVersionAtLeast"
  | "readExternalSubscription"
  | "autoRenameSubscriptionContent"
  | "protectSubscription"
  | "getClientIp"
  | "readState"
  | "visibleNotice"
  | "publicClientSettings"
  | "corsHeaders"
  | "json"
>) {
  const {
    CLIENT_IP_HEADER,
    LEGACY_CLIENT_IP_HEADER,
    resolveAccountAccess,
    beijingTimestamp,
    normalizeAccountId,
    isAppVersionAtLeast,
    readExternalSubscription,
    autoRenameSubscriptionContent,
    protectSubscription,
    getClientIp,
    readState,
    visibleNotice,
    publicClientSettings,
    corsHeaders,
    json,
  } = deps;

async function handleClient(request: Request, env: Env) {
  const requestUrl = new URL(request.url);
  const origin = requestUrl.origin;
  const state = await readState(env);
  const groups = state.subscriptionGroups.filter((group) => group.enabled);

  return json({
    subscriptions: groups.flatMap((group) => group.subscriptions
      .filter((source) => source.enabled)
      .map((source) => ({
        id: source.id,
        name: source.name,
        enabled: source.enabled,
        encrypted: true,
        // 保留旧客户端识别的最低等级，并给新客户端完整分组权限用于隐藏无权线路。
        minAccountType: legacyMinimumAccountType(group),
        allowedStatuses: group.allowedStatuses,
        allowedAccountTypes: group.allowedAccountTypes,
        minAppVersion: group.minAppVersion,
        maxAppVersion: group.maxAppVersion,
        url: `${origin}/api/subscription/${encodeURIComponent(source.id)}`,
      }))),
    notice: visibleNotice(state.notice),
    appUpdate: state.appUpdate,
    settings: publicClientSettings(state.settings),
    generatedAt: beijingTimestamp(),
  });
}

async function handleProxyAppList() {
  let upstream;
  try {
    // 固定上游名单地址，只代理这一份资源，避免端点成为任意 URL 代理。
    upstream = await fetch(PROXY_APP_LIST_URL, {
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
  const id = suffix;
  if (!/^\d+$/.test(id)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const state = await readState(env);
  const group = state.subscriptionGroups.find((item) =>
    item.subscriptions.some((source) => source.id === id));
  const source = group?.subscriptions.find((item) => item.id === id);
  if (!group || !group.enabled || !source || !source.enabled || (!source.content && !source.url)) {
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
      accountStatus: access.accountStatus,
      accountType: access.accountType,
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

  const clientIp = getClientIp(request);
  const fetchedContent = source.content || await readExternalSubscription(env, source);
  // 纯文本节点由后台直接维护，自动重命名只对外部接口生效。
  const plaintextContent = source.autoRename && !source.content
    ? autoRenameSubscriptionContent(fetchedContent)
    : fetchedContent;
  const responseContent = protectSubscription(plaintextContent, clientIp);
  const responseHeaders: Record<string, string> = {
    ...corsHeaders(),
    "content-type": "text/plain; charset=utf-8",
    "cache-control": "no-store",
  };
  responseHeaders[CLIENT_IP_HEADER] = clientIp;
  responseHeaders[LEGACY_CLIENT_IP_HEADER] = clientIp;
  return new Response(responseContent, {
    headers: responseHeaders,
  });
}

const DEBUG_INTERFACE_PATH_PREFIX = "/api/subscription/debug/";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function handleDebugSubscription(path: string, _request: Request, env: Env) {
  const token = path.slice(DEBUG_INTERFACE_PATH_PREFIX.length);
  if (!UUID_PATTERN.test(token)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const state = await readState(env);
  const debugInterface = state.settings && state.settings.debugInterface;
  if (!debugInterface || debugInterface.token.toLowerCase() !== token.toLowerCase()) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const selectedIds = new Set(debugInterface.subscriptionIds);
  const sources = state.subscriptionGroups.flatMap((group) => group.subscriptions).filter((source) =>
    selectedIds.has(source.id) && (source.content || source.url));
  if (!sources.length) {
    return json({ error: "subscription_not_found" }, 404);
  }

  let responseContent;
  try {
    const contents = await Promise.all(sources.map(async (source) => {
      const fetchedContent = source.content || await readExternalSubscription(env, source);
      return source.autoRename && !source.content
        ? autoRenameSubscriptionContent(fetchedContent)
        : fetchedContent;
    }));
    responseContent = contents.join("\n");
  } catch (_) {
    return json({ error: "subscription_fetch_failed" }, 502);
  }

  return new Response(responseContent, {
    headers: {
      ...corsHeaders(),
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function legacyMinimumAccountType(group: SubscriptionGroup) {
  if (group.allowedStatuses.includes("banned")) return "banned" as const;
  if (
    group.allowedAccountTypes.includes("formal") &&
    !group.allowedAccountTypes.includes("guest")
  ) {
    return "formal" as const;
  }
  return "guest" as const;
}

  return {
    handleClient,
    handleProxyAppList,
    handleManagedSubscription,
    handleDebugSubscription,
  };
}
