import type { Env } from "./types/env.ts";
import type { RouteDependencies } from "./types/routes.ts";

/*
 * Chimera Cloudflare Worker composition root.
 *
 * Domain logic lives in focused modules. This file only wires route
 * dependencies and dispatches incoming requests.
 */

import {
  CLIENT_IP_HEADER,
  LEGACY_CLIENT_IP_HEADER,
  MAX_BATCH_USER_COUNT,
  resolveAdminBasePath,
  USER_SCHEMA_VERSION,
} from "./config.ts";
import { adminHtml } from "./admin-ui.ts";
import {
  hashAccountPassword,
  normalizeAccountEmail,
  normalizeAccountId,
  normalizeAccountPassword,
  canAccessAccountType,
  normalizeAndroidSdk,
  normalizeBatchUserReferences,
  normalizeRegistrationCode,
  normalizeNickname,
  normalizeStoredUid,
  randomRegistrationCode,
  normalizeUid,
  reportBytes,
  verifyAccountPassword,
} from "./domain/account.ts";
import {
  buildUserGrowthPoints,
  buildUserActivityPoints,
  buildUserTrafficPoints,
  normalizeGrowthDateKey,
  normalizeGrowthPeriod,
} from "./domain/analytics.ts";
import { beijingTimestamp, normalizeReportTimestamp, usagePeriodKeys } from "./domain/time.ts";
import { corsHeaders, isAdmin, json } from "./http/responses.ts";
import { cleanText } from "./lib/values.ts";
import { createAccountRoutes } from "./routes/account.ts";
import { createAdminRoutes } from "./routes/admin.ts";
import { createClientRoutes } from "./routes/client.ts";
import {
  getClientIp,
  autoRenameSubscriptionContent,
  isAppVersionAtLeast,
  protectSubscription,
  readExternalSubscription,
  readExternalSubscriptionTraffic,
  validateAndWarmSubscriptionSources,
} from "./services/subscriptions.ts";
import {
  normalizeState,
  publicClientSettings,
  readState,
  visibleNotice,
  writeState,
} from "./services/state.ts";
import {
  readSubscriptionContent,
  readSubscriptionContents,
} from "./services/subscription-store.ts";
import {
  attachDeviceToUser,
  ensureUserSchema,
  initializeUserSchema,
  readD1User,
  readD1UserWithTraffic,
  readDeviceContextFromDb,
  registerOrTouchUser,
  publicAccount,
  publicAdminUser,
  resolveAccountAccess,
  resolveDeviceIdentity,
  resolveUserIdentity,
  rowToUser,
  usersDb,
} from "./services/user-store.ts";
import {
  clearAdminLoginRecords,
  readAdminLoginRecords,
  recordAdminLogin,
} from "./services/admin-access.ts";
import { handleHoneypotRoute, isHoneypotPath } from "./services/honeypot.ts";

const routeDeps = {
  CLIENT_IP_HEADER,
  LEGACY_CLIENT_IP_HEADER,
  canAccessAccountType,
  resolveAccountAccess,
  beijingTimestamp,
  normalizeAccountId,
  isAppVersionAtLeast,
  readExternalSubscription,
  readExternalSubscriptionTraffic,
  autoRenameSubscriptionContent,
  protectSubscription,
  getClientIp,
  readState,
  readSubscriptionContent,
  readSubscriptionContents,
  visibleNotice,
  publicClientSettings,
  corsHeaders,
  json,
  usersDb,
  ensureUserSchema,
  resolveDeviceIdentity,
  readDeviceContextFromDb,
  readD1User,
  readD1UserWithTraffic,
  attachDeviceToUser,
  registerOrTouchUser,
  // 账户注册成功后需要用统一格式返回账户信息。
  publicAccount,
  normalizeNickname,
  normalizeRegistrationCode,
  usagePeriodKeys,
  normalizeReportTimestamp,
  normalizeAccountEmail,
  normalizeAccountPassword,
  hashAccountPassword,
  verifyAccountPassword,
  normalizeUid,
  reportBytes,
  cleanText,
  MAX_BATCH_USER_COUNT,
  publicAdminUser,
  resolveUserIdentity,
  rowToUser,
  normalizeAndroidSdk,
  normalizeGrowthPeriod,
  normalizeGrowthDateKey,
  buildUserGrowthPoints,
  buildUserActivityPoints,
  buildUserTrafficPoints,
  normalizeStoredUid,
  randomRegistrationCode,
  normalizeBatchUserReferences,
  validateAndWarmSubscriptionSources,
  writeState,
  normalizeState,
  clearAdminLoginRecords,
  readAdminLoginRecords,
} satisfies RouteDependencies;

const {
  handleClient,
  handleProxyAppList,
  handleManagedSubscription,
  handleDebugSubscription,
} = createClientRoutes(routeDeps);
const { handleAccount } = createAccountRoutes(routeDeps);
const { handleAdmin } = createAdminRoutes(routeDeps);

const worker = {
  async fetch(request: Request, env: Env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";
      const adminBasePath = resolveAdminBasePath(env.ADMIN_PATH);

      if (isHoneypotPath(path)) {
        return handleHoneypotRoute(path, request, env, recordAdminLogin, json);
      }

      if (path === "/" || path === "/health") {
        const usersSchemaVersion = env.USERS_DB
          ? await initializeUserSchema(env)
          : null;
        return json({
          ok: true,
          service: "chimera-worker",
          bindings: {
            config: Boolean(env.CONFIG),
            usersDb: Boolean(env.USERS_DB),
            adminToken: Boolean(env.ADMIN_TOKEN),
          },
          database: {
            initialized: usersSchemaVersion === USER_SCHEMA_VERSION,
            schemaVersion: usersSchemaVersion,
          },
        });
      }

      if (adminBasePath && path === adminBasePath && request.method === "GET") {
        return new Response(adminHtml(adminBasePath), {
          headers: {
            "content-type": "text/html; charset=utf-8",
            "cache-control": "no-store, no-cache, must-revalidate",
          },
        });
      }

      if (path === "/api/client" && request.method === "GET") {
        return handleClient(request, env);
      }

      if (path === "/api/app/proxy-packages" && request.method === "GET") {
        return handleProxyAppList();
      }

      if (path.startsWith("/api/subscription/debug/") && request.method === "GET") {
        return handleDebugSubscription(path, request, env);
      }

      if (path.startsWith("/api/subscription/") && request.method === "GET") {
        return handleManagedSubscription(path, request, env);
      }

      if (path.startsWith("/api/account/")) {
        return handleAccount(path, request, env);
      }

      if (adminBasePath && path.startsWith(`${adminBasePath}/api/`)) {
        const isLoginRequest = path === `${adminBasePath}/api/state` && request.method === "GET";
        const authorized = await isAdmin(request, env);
        if (!authorized) {
          if (isLoginRequest) await recordAdminLogin(env, request, false);
          return json({ error: "unauthorized" }, 401);
        }
        if (isLoginRequest) await recordAdminLogin(env, request, true);
        return handleAdmin(path.slice(adminBasePath.length), request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      console.error(JSON.stringify({
        message: "request_failed",
        method: request.method,
        path: new URL(request.url).pathname,
        error: error instanceof Error ? error.message : String(error),
      }));
      return json({ error: "internal_error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

export default worker;
