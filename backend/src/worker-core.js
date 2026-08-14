/*
 * FireflyVPN Cloudflare Worker composition root.
 *
 * Domain logic lives in focused modules. This file only wires route
 * dependencies and dispatches incoming requests.
 */

import {
  ACCOUNT_BINDING_EXPERIENCE,
  ADMIN_BASE_PATH,
  CLIENT_IP_HEADER,
  DEFAULT_DAILY_CHECKIN_EXPERIENCE,
  DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  MAX_BATCH_USER_COUNT,
  MAX_SPEED_SHARE_RESULTS,
  REWARD_PERIOD_LIFETIME,
  REWARD_TYPE_ACCOUNT_BINDING,
  REWARD_TYPE_DAILY_CHECKIN,
  REWARD_TYPE_SPEED_SHARE,
  REWARD_TYPE_TELEGRAM,
  USER_SCHEMA_VERSION,
} from "./config.js";
import { adminHtml } from "./admin-ui.js";
import {
  experienceTotalForLevel,
  hashAccountPassword,
  normalizeAccountEmail,
  normalizeAccountId,
  normalizeAccountPassword,
  normalizeAndroidSdk,
  normalizeBatchUserReferences,
  normalizeExperience,
  normalizeNickname,
  normalizeStoredUid,
  normalizeTelegramJoinCode,
  normalizeUid,
  reportBytes,
  verifyAccountPassword,
} from "./domain/account.js";
import {
  buildUserGrowthPoints,
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
  publicAccount,
  publicAdminUser,
  readDailyCheckin,
  readSpeedShareStatus,
  readTelegramTask,
  rewardClaimStatement,
} from "./services/rewards.js";
import {
  normalizeSpeedShareResult,
  publicAdminSpeedShareResult,
  speedShareRequestLocation,
} from "./services/speed-share.js";
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
  MAX_SPEED_SHARE_RESULTS,
  ACCOUNT_BINDING_EXPERIENCE,
  DEFAULT_DAILY_CHECKIN_EXPERIENCE,
  DEFAULT_TELEGRAM_TASK_EXPERIENCE,
  DEFAULT_SPEED_SHARE_SMART_EXPERIENCE,
  REWARD_TYPE_ACCOUNT_BINDING,
  REWARD_TYPE_DAILY_CHECKIN,
  REWARD_TYPE_SPEED_SHARE,
  REWARD_TYPE_TELEGRAM,
  REWARD_PERIOD_LIFETIME,
  normalizeSpeedShareResult,
  speedShareRequestLocation,
  publicAccount,
  rewardClaimStatement,
  readDailyCheckin,
  readSpeedShareStatus,
  readTelegramTask,
  publicAdminSpeedShareResult,
  usersDb,
  ensureUserSchema,
  resolveDeviceIdentity,
  readDeviceContextFromDb,
  readD1User,
  attachDeviceToUser,
  registerOrTouchUser,
  normalizeNickname,
  normalizeExperience,
  usagePeriodKeys,
  normalizeReportTimestamp,
  normalizeAccountEmail,
  normalizeAccountPassword,
  normalizeTelegramJoinCode,
  hashAccountPassword,
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
  buildUserTrafficPoints,
  normalizeStoredUid,
  normalizeBatchUserReferences,
  validateAndWarmSubscriptionSources,
  writeState,
  normalizeState,
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

      if (path === ADMIN_BASE_PATH && request.method === "GET") {
        return new Response(adminHtml(), {
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

      if (path.startsWith(`${ADMIN_BASE_PATH}/api/`)) {
        if (!(await isAdmin(request, env))) {
          return json({ error: "unauthorized" }, 401);
        }
        return handleAdmin(path.slice(ADMIN_BASE_PATH.length), request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      console.error(JSON.stringify({
        message: "request_failed",
        method: request.method,
        path: new URL(request.url).pathname,
        error: error instanceof Error ? error.message : String(error),
      }));
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};

export default worker;
