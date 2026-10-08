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
  | "readExternalSubscriptionTraffic"
  | "validateAndWarmSubscriptionSources"
  | "writeState"
>) {
  const {
    beijingTimestamp,
    json,
    normalizeState,
    readState,
    readExternalSubscriptionTraffic,
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

    if (path === "/api/subscriptions/traffic" && request.method === "GET") {
      const state = await readState(env);
      const sources = state.subscriptionGroups.flatMap((group) => group.subscriptions.map((source) => ({
        source,
        cacheMinutes: group.cacheMinutes,
      })));
      const items = sources.map((source) => ({
        id: source.source.id,
        traffic: null as Awaited<ReturnType<typeof readExternalSubscriptionTraffic>>,
        unavailable: false,
      }));
      const activeSources = sources
        .map((source, index) => ({ source, item: items[index] }))
        .filter(({ source }) => source.source.url && source.source.enabled !== false);

      for (let index = 0; index < activeSources.length; index += 4) {
        await Promise.all(activeSources.slice(index, index + 4).map(async ({ source, item }) => {
          try {
            item.traffic = await readExternalSubscriptionTraffic(env, source.source, source.cacheMinutes);
          } catch {
            item.unavailable = true;
          }
        }));
      }

      return json({ ok: true, items });
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
          next.subscriptionGroups,
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
