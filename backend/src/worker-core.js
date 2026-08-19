/*
 * FireflyVPN Cloudflare Worker composition root.
 *
 * Domain logic lives in focused modules. This file only wires route
 * dependencies and dispatches incoming requests.
 */

import {
  ACCOUNT_BINDING_EXPERIENCE,
  CLIENT_IP_HEADER,
  DEFAULT_INVITATION_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  MAX_ACCOUNT_EXPERIENCE,
  MAX_BATCH_USER_COUNT,
  REWARD_PERIOD_LIFETIME,
  REWARD_TYPE_ACCOUNT_BINDING,
  REWARD_TYPE_INVITATION,
  REWARD_TYPE_TELEGRAM,
  resolveAdminBasePath,
  USER_SCHEMA_VERSION,
} from "./config.js";
import { adminHtml } from "./admin-ui.js";
import {
  experienceTotalForLevel,
  hashAccountPassword,
  isEmailBoundAccount,
  normalizeAccountEmail,
  normalizeAccountId,
  normalizeAccountPassword,
  normalizeAndroidSdk,
  normalizeBatchUserReferences,
  normalizeExperience,
  normalizeInviteCode,
  normalizeRegistrationCode,
  normalizeNickname,
  normalizeStoredUid,
  randomRegistrationCode,
  normalizeTelegramJoinCode,
  normalizeUid,
  reportBytes,
  verifyAccountPassword,
} from "./domain/account.js";
import {
  buildUserGrowthPoints,
  buildUserActivityPoints,
  buildUserTrafficPoints,
  normalizeGrowthDateKey,
  normalizeGrowthPeriod,
} from "./domain/analytics.js";
import { beijingTimestamp, normalizeReportTimestamp, usagePeriodKeys } from "./domain/time.js";
import { corsHeaders, isAdmin, json } from "./http/responses.js";
import { cleanText } from "./lib/values.js";
import { createAccountRoutes } from "./routes/account.js";
import { createAdminRoutes } from "./routes/admin.js";
import { createClientRoutes } from "./routes/client.js";
import {
  invitationRewardClaimStatement,
  publicAccount,
  publicAdminUser,
  readTelegramTask,
  rewardClaimStatement,
} from "./services/rewards.js";
import {
  getClientIp,
  isAppVersionAtLeast,
  protectSubscription,
  readExternalSubscription,
  validateAndWarmSubscriptionSources,
} from "./services/subscriptions.js";
import {
  normalizeState,
  publicClientSettings,
  readState,
  visibleNotice,
  writeState,
} from "./services/state.js";
import {
  attachDeviceToUser,
  ensureUserSchema,
  initializeUserSchema,
  readD1User,
  readDeviceContextFromDb,
  registerOrTouchUser,
  resolveAccountAccess,
  resolveDeviceIdentity,
  resolveUserIdentity,
  rowToUser,
  usersDb,
} from "./services/user-store.js";
import { readAdminLoginRecords, recordAdminLogin } from "./services/admin-access.js";
import { handleHoneypotRoute, isHoneypotPath } from "./services/honeypot.js";

const routeDeps = {
  CLIENT_IP_HEADER,
  resolveAccountAccess,
  beijingTimestamp,
  normalizeAccountId,
  isAppVersionAtLeast,
  readExternalSubscription,
  protectSubscription,
  getClientIp,
  readState,
  visibleNotice,
  publicClientSettings,
  corsHeaders,
  json,
  ACCOUNT_BINDING_EXPERIENCE,
  DEFAULT_INVITATION_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  MAX_ACCOUNT_EXPERIENCE,
  REWARD_TYPE_ACCOUNT_BINDING,
  REWARD_TYPE_INVITATION,
  REWARD_TYPE_TELEGRAM,
  REWARD_PERIOD_LIFETIME,
  publicAccount,
  invitationRewardClaimStatement,
  rewardClaimStatement,
  readTelegramTask,
  usersDb,
  ensureUserSchema,
  resolveDeviceIdentity,
  readDeviceContextFromDb,
  readD1User,
  attachDeviceToUser,
  registerOrTouchUser,
  normalizeNickname,
  normalizeInviteCode,
  normalizeRegistrationCode,
  normalizeExperience,
  usagePeriodKeys,
  normalizeReportTimestamp,
  normalizeAccountEmail,
  normalizeAccountPassword,
  normalizeTelegramJoinCode,
  hashAccountPassword,
  isEmailBoundAccount,
  verifyAccountPassword,
  normalizeUid,
  reportBytes,
  cleanText,
  MAX_BATCH_USER_COUNT,
  publicAdminUser,
  resolveUserIdentity,
  rowToUser,
  experienceTotalForLevel,
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
  readAdminLoginRecords,
};

const { handleClient, handleManagedSubscription } = createClientRoutes(routeDeps);
const { handleAccount } = createAccountRoutes(routeDeps);
const { handleAdmin } = createAdminRoutes(routeDeps);

const worker = {
  async fetch(request, env) {
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
          service: "fireflyvpn-worker",
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
};

export default worker;
