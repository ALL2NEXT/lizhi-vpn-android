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
  const id = path.slice("/api/subscription/".length);
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
  const protectedContent = protectSubscription(plaintextContent, clientIp);
  return new Response(protectedContent, {
    headers: {
      ...corsHeaders(),
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
      [CLIENT_IP_HEADER]: clientIp,
    },
  });
}

  return { handleClient, handleManagedSubscription };
}
