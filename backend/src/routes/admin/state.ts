import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";

/*
 * KV-backed application settings and managed subscription configuration.
 */

import { fetchWebsiteIcon } from "../../services/navigation-icons.ts";
import { readJsonObject } from "../../http/request.ts";
import { importSubscriptionSources } from "../../services/subscription-import.ts";

export function createAdminStateRoutes(deps: Pick<
  RouteDependencies,
  | "beijingTimestamp"
  | "json"
  | "normalizeState"
  | "readState"
  | "validateAndWarmSubscriptionSources"
  | "writeState"
>) {
  const {
    beijingTimestamp,
    json,
    normalizeState,
    readState,
    validateAndWarmSubscriptionSources,
    writeState,
  } = deps;

  return async function handleAdminState(path: string, request: Request, env: Env) {
    if (path === "/api/subscriptions/import" && request.method === "POST") {
      try {
        const incoming = await readJsonObject(request);
        const result = await importSubscriptionSources(incoming.input, incoming.minAccountType);
        return json({ ok: result.sources.length > 0, ...result }, result.sources.length ? 200 : 422);
      } catch (error) {
        return json({ ok: false, error: error instanceof Error ? error.message : String(error) }, 400);
      }
    }

    if (path === "/api/navigation-icon" && request.method === "POST") {
      try {
        const incoming = await readJsonObject(request);
        return json(await fetchWebsiteIcon(typeof incoming.url === "string" ? incoming.url.trim() : ""));
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        const status = message === "navigation_icon_not_found"
          ? 404
          : message.startsWith("invalid_navigation_icon") || message === "navigation_icon_redirect_blocked"
            ? 400
            : message === "navigation_icon_site_challenge" || message === "navigation_icon_unsupported_format"
              ? 422
            : 502;
        return json({ error: message }, status);
      }
    }

    if (path === "/api/state" && request.method === "GET") {
      return json(await readState(env));
    }

    if (path === "/api/state" && request.method === "PUT") {
      const incoming = await request.json();
      try {
        const next = normalizeState(incoming);
        await validateAndWarmSubscriptionSources(
          env,
          next.subscriptionGroups.flatMap((group) => group.subscriptions),
        );
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
