/*
 * Client routes.
 *
 * Handlers receive only the shared runtime dependencies they use.
 */

export function createClientRoutes(deps) {
  const {
    CLIENT_IP_HEADER,
    canAccessAccountType,
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

async function handleClient(request, env) {
  const requestUrl = new URL(request.url);
  const origin = requestUrl.origin;
  const state = await readState(env);

  return json({
    subscriptions: state.subscriptions
      .filter((source) => source.enabled)
      .map((source) => ({
        id: source.id,
        name: source.name,
        enabled: source.enabled,
        encrypted: true,
        minAccountType: source.minAccountType,
        url: `${origin}/api/subscription/${encodeURIComponent(source.id)}`,
      })),
    notice: visibleNotice(state.notice),
    appUpdate: state.appUpdate,
    settings: publicClientSettings(state.settings),
    generatedAt: beijingTimestamp(),
  });
}

async function handleManagedSubscription(path, request, env) {
  const suffix = path.slice("/api/subscription/".length);
  const id = suffix;
  if (!/^\d+$/.test(id)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const state = await readState(env);
  const source = state.subscriptions.find((item) => item.id === id);
  if (!source || !source.enabled || (!source.content && !source.url)) {
    return json({ error: "subscription_not_found" }, 404);
  }

  const requestUrl = new URL(request.url);
  const deviceId = normalizeAccountId(requestUrl.searchParams.get("deviceId"));
  if (!deviceId) {
    return json({ ok: false, error: "device_id_required" }, 401);
  }
  const access = await resolveAccountAccess(env, deviceId);
  if (!canAccessAccountType(access.accountType, source.minAccountType)) {
    return json({
      ok: false,
      error: "account_access_denied",
      requiredAccountType: source.minAccountType,
      accountType: access.accountType,
    }, 403);
  }
  if (source.minAppVersion && !isAppVersionAtLeast(access.appVersion, source.minAppVersion)) {
    return json({
      ok: false,
      error: "app_version_too_low",
      requiredVersion: source.minAppVersion,
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
  const responseHeaders = {
    ...corsHeaders(),
    "content-type": "text/plain; charset=utf-8",
    "cache-control": "no-store",
  };
  responseHeaders[CLIENT_IP_HEADER] = clientIp;
  return new Response(responseContent, {
    headers: responseHeaders,
  });
}

const DEBUG_INTERFACE_PATH_PREFIX = "/api/subscription/debug/";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function handleDebugSubscription(path, _request, env) {
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
  const sources = state.subscriptions.filter((source) =>
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

  return { handleClient, handleManagedSubscription, handleDebugSubscription };
}
