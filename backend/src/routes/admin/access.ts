import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";

/*
 * Admin access and login history routes.
 */

export function createAdminAccessRoutes(deps: Pick<
  RouteDependencies,
  | "clearAdminLoginRecords"
  | "json"
  | "readAdminLoginRecords"
>) {
  const { clearAdminLoginRecords, json, readAdminLoginRecords } = deps;

  return async function handleAdminAccess(path: string, request: Request, env: Env) {
    if (path === "/api/admin-login-records" && request.method === "GET") {
      return json({
        ok: true,
        records: await readAdminLoginRecords(env),
      });
    }

    if (path === "/api/admin-login-records" && request.method === "DELETE") {
      await clearAdminLoginRecords(env);
      return json({ ok: true, cleared: true });
    }

    return null;
  };
}
