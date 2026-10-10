import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";

/* System log query route. */

export function createAdminAccessRoutes(deps: Pick<
  RouteDependencies,
  | "json"
  | "readSystemLogs"
>) {
  const { json, readSystemLogs } = deps;

  return async function handleAdminAccess(path: string, request: Request, env: Env) {
    if (path === "/api/system-logs" && request.method === "GET") {
      const url = new URL(request.url);
      return json(await readSystemLogs(env, {
        category: url.searchParams.get("category") || undefined,
        level: url.searchParams.get("level") || undefined,
        status: url.searchParams.get("status") || undefined,
        startDate: url.searchParams.get("startDate") || undefined,
        endDate: url.searchParams.get("endDate") || undefined,
        query: url.searchParams.get("q") || undefined,
        limit: Number(url.searchParams.get("limit")) || 100,
        offset: Number(url.searchParams.get("offset")) || 0,
      }));
    }

    return null;
  };
}
