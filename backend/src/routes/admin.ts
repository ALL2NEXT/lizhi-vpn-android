import type { Env } from "../types/env.ts";
import type { RouteDependencies } from "../types/routes.ts";

/*
 * Admin route composition.
 */

import { createAdminAccessRoutes } from "./admin/access.ts";
import { createAdminAnalyticsRoutes } from "./admin/analytics.ts";
import { createAdminRegistrationCodeRoutes } from "./admin/registration-codes.ts";
import { createAdminSecurityRoutes } from "./admin/security.ts";
import { createAdminStateRoutes } from "./admin/state.ts";
import { createAdminUserRoutes } from "./admin/users.ts";

export function createAdminRoutes(deps: RouteDependencies) {
  const handlers = [
    createAdminAccessRoutes(deps),
    createAdminSecurityRoutes(deps),
    createAdminAnalyticsRoutes(deps),
    createAdminRegistrationCodeRoutes(deps),
    createAdminUserRoutes(deps),
    createAdminStateRoutes(deps),
  ];

  async function handleAdmin(path: string, request: Request, env: Env) {
    for (const handler of handlers) {
      const response = await handler(path, request, env);
      if (response) return response;
    }
    return deps.json({ error: "admin_route_not_found" }, 404);
  }

  return { handleAdmin };
}
