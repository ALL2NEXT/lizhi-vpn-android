/*
 * KV-backed application settings and managed subscription configuration.
 */

export function createAdminStateRoutes(deps) {
  const {
    beijingTimestamp,
    json,
    normalizeState,
    readState,
    validateAndWarmSubscriptionSources,
    writeState,
  } = deps;

  return async function handleAdminState(path, request, env) {
  if (path === "/api/state" && request.method === "GET") {
      return json(await readState(env));
    }

    if (path === "/api/state" && request.method === "PUT") {
      const incoming = await request.json();
      try {
        const next = normalizeState(incoming);
        await validateAndWarmSubscriptionSources(env, next.subscriptions);
        next.updatedAt = beijingTimestamp();
        await writeState(env, next);
        return json({ ok: true, state: next });
      } catch (error) {
        return json({
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        }, 400);
      }
    }

    return null;
  };
}

