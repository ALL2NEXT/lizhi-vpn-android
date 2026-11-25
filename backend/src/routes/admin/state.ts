import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";

/*
 * Application settings in KV and subscription configuration in D1.
 */

import { readJsonObject } from "../../http/request.ts";
import { importSubscriptionSources } from "../../services/subscription-import.ts";
import {
  incrementSubscriptionVersions,
  reconcileSubscriptionVersions,
} from "../../services/state.ts";

export function createAdminStateRoutes(deps: Pick<
  RouteDependencies,
  | "beijingTimestamp"
  | "json"
  | "normalizeState"
  | "readExternalSubscription"
  | "readState"
  | "readExternalSubscriptionTraffic"
  | "validateAndWarmSubscriptionSources"
  | "writeState"
>) {
  const {
    beijingTimestamp,
    json,
    normalizeState,
    readExternalSubscription,
    readState,
    readExternalSubscriptionTraffic,
    validateAndWarmSubscriptionSources,
    writeState,
  } = deps;

  return async function handleAdminState(path: string, request: Request, env: Env) {
    if (path === "/api/subscriptions/import" && request.method === "POST") {
      try {
        const incoming = await readJsonObject(request);
        const result = await importSubscriptionSources(incoming.input, env);
        return json({ ok: result.sources.length > 0, ...result }, result.sources.length ? 200 : 422);
      } catch (error) {
        return json({ ok: false, error: error instanceof Error ? error.message : String(error) }, 400);
      }
    }

    if (path === "/api/subscriptions/traffic" && request.method === "GET") {
      const state = await readState(env);
      const requestedIds = new URL(request.url).searchParams.getAll("id");
      const requestedIdSet = new Set(requestedIds);
      const sources = state.subscriptionGroups.flatMap((group) => group.subscriptions.map((source) => ({
        source,
        cacheMinutes: group.cacheMinutes,
        updateEnabled: group.updateEnabled,
      }))).filter(({ source }) =>
        requestedIds.length === 0 || requestedIdSet.has(source.id)
      );
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
            item.traffic = await readExternalSubscriptionTraffic(
              env,
              source.source,
              source.cacheMinutes,
              source.updateEnabled,
            );
          } catch {
            item.unavailable = true;
          }
        }));
      }

      return json({ ok: true, items });
    }

    if (path === "/api/subscriptions/refresh" && request.method === "POST") {
      const incoming = await readJsonObject(request);
      const requestedIds = new Set(Array.isArray(incoming.ids)
        ? incoming.ids.filter((id): id is string => typeof id === "string" && id.trim().length > 0)
          .map((id) => id.trim())
        : []);
      if (requestedIds.size === 0) return json({ ok: false, error: "subscription_ids_required" }, 400);
      const state = await readState(env, { includeSubscriptionContent: true });
      const sources = state.subscriptionGroups.flatMap((group) =>
        group.subscriptions.filter((source) =>
          requestedIds.has(source.id) && source.url && !source.content && source.enabled !== false
        )
      );
      const results: Array<{ id: string; ok: boolean }> = [];
      for (let index = 0; index < sources.length; index += 4) {
        const batch = await Promise.all(sources.slice(index, index + 4).map(async (source) => {
          try {
            // Explicit admin refresh bypasses the configured cache and update schedule.
            await readExternalSubscription(env, source, 0, true);
            return { id: source.id, ok: true };
          } catch {
            return { id: source.id, ok: false };
          }
        }));
        results.push(...batch);
      }
      const updated = results.filter((result) => result.ok).length;
      const failed = results.length - updated;
      if (updated > 0) {
        const updatedIds = new Set(results.filter((result) => result.ok).map((result) => result.id));
        const updatedSubscriptionIds = [...updatedIds];
        await writeState(env, {
          ...state,
          subscriptionVersions: incrementSubscriptionVersions(
            state.subscriptionVersions,
            updatedSubscriptionIds,
          ),
          updatedAt: beijingTimestamp(),
        });
      }
      return json({
        ok: failed === 0,
        attempted: results.length,
        updated,
        failed,
        skipped: requestedIds.size - sources.length,
        updatedIds: results.filter((result) => result.ok).map((result) => result.id),
      });
    }

    if (path === "/api/state" && request.method === "GET") {
      return json(await readState(env, { includeSubscriptionContent: true }));
    }

    if (path === "/api/state" && request.method === "PUT") {
      const incoming = await request.json();
      try {
        const next = normalizeState(incoming);
        const current = await readState(env, { includeSubscriptionContent: true });
        // 仅为配置发生变化的客户端订阅递增版本。
        next.subscriptionVersions = reconcileSubscriptionVersions(
          current.subscriptionGroups,
          next.subscriptionGroups,
          current.subscriptionVersions,
        );
        const currentSources = new Map(current.subscriptionGroups.flatMap((group) =>
          group.subscriptions.map((source) => [source.id, {
            url: source.url,
            enabled: source.enabled,
            updateEnabled: group.updateEnabled,
          }] as const)));
        const sourcesToWarm = next.subscriptionGroups.map((group) => ({
          ...group,
          subscriptions: group.subscriptions.filter((source) => {
            if (!source.url || source.enabled === false) return false;
            const previous = currentSources.get(source.id);
            return !previous || previous.url !== source.url || !previous.enabled ||
              previous.updateEnabled !== group.updateEnabled;
          }),
        })).filter((group) => group.subscriptions.length > 0);
        if (sourcesToWarm.length) {
          await validateAndWarmSubscriptionSources(env, sourcesToWarm);
        }
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
