/*
 * Admin route composition.
 */

import { createAdminAiRoutes } from "./admin/ai-analysis.js";
import { createAdminAccessRoutes } from "./admin/access.js";
import { createAdminAnalyticsRoutes } from "./admin/analytics.js";
import { createAdminExperienceRoutes } from "./admin/experience.js";
import { createAdminSecurityRoutes } from "./admin/security.js";
import { createAdminStateRoutes } from "./admin/state.js";
import { createAdminUserRoutes } from "./admin/users.js";

export function createAdminRoutes(deps) {
  const handlers = [
    createAdminAccessRoutes(deps),
    createAdminSecurityRoutes(deps),
    createAdminAnalyticsRoutes(deps),
    createAdminAiRoutes(deps),
    createAdminExperienceRoutes(deps),
    createAdminUserRoutes(deps),
    createAdminStateRoutes(deps),
  ];

  async function handleAdmin(path, request, env) {
    for (const handler of handlers) {
      const response = await handler(path, request, env);
      if (response) return response;
    }
    return deps.json({ error: "admin_route_not_found" }, 404);
  }

  return { handleAdmin };
}
