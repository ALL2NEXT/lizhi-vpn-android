import type { Env } from "./types/env.ts";
import type { RouteDependencies } from "./types/routes.ts";

/*
 * Chimera Cloudflare Worker composition root.
 *
 * Domain logic lives in focused modules. This file only wires route
 * dependencies and dispatches incoming requests.
 */

import {
  MAX_BATCH_USER_COUNT,
  resolveAdminBasePath,
  USER_SCHEMA_VERSION,
} from "./config.ts";
import { adminHtml } from "./admin-ui.ts";
import {
  hashAccountPassword,
  normalizeAccountEmail,
  normalizeAccountId,
  normalizeUserIdentityType,
  normalizeAccountPassword,
  canAccessAccountType,
  normalizeAndroidSdk,
  normalizeBatchUserReferences,
  normalizeRegistrationCode,
  normalizeVipActivationCode,
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
  autoRenameSubscriptionContent,
  cacheLocallyFetchedSubscription,
  deduplicateSubscriptionContent,
  isAppVersionAtLeast,
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
import { readSubscriptionContent } from "./services/subscription-store.ts";
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
import { handleHoneypotRoute, isHoneypotPath } from "./services/honeypot.ts";
import type {
  SystemLogDraft,
  SystemLogHttpBodyCapture,
  SystemLogHttpCapture,
  SystemLogRecordingSettings,
} from "./types/models.ts";
import {
  createObservedEnv,
  isSystemLogScheduleActive,
  pruneSystemLogs,
  readSystemLogPayload,
  readSystemLogs,
  readSystemLogRecordingSettings,
  updateSystemLogRecordingMode,
  updateSystemLogRecordingSchedule,
  updateSystemLogRecordingSetting,
  writeSystemLogs,
} from "./services/system-logs.ts";

const routeDeps = {
  canAccessAccountType,
  resolveAccountAccess,
  beijingTimestamp,
  normalizeAccountId,
  isAppVersionAtLeast,
  cacheLocallyFetchedSubscription,
  readExternalSubscription,
  readExternalSubscriptionTraffic,
  autoRenameSubscriptionContent,
  deduplicateSubscriptionContent,
  readState,
  readSubscriptionContent,
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
  normalizeVipActivationCode,
  usagePeriodKeys,
  normalizeReportTimestamp,
  normalizeAccountEmail,
  normalizeUserIdentityType,
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
  readSystemLogPayload,
  readSystemLogs,
  readSystemLogRecordingSettings,
  updateSystemLogRecordingMode,
  updateSystemLogRecordingSchedule,
  updateSystemLogRecordingSetting,
} satisfies RouteDependencies;

const {
  handleClient,
  handleProxyAppList,
  handleManagedSubscription,
} = createClientRoutes(routeDeps);
const { handleAccount } = createAccountRoutes(routeDeps);
const { handleAdmin } = createAdminRoutes(routeDeps);

function safeRequestPath(path: string) {
  return path
    .replace(/(\/api\/subscription\/debug\/)[^/]+/i, "$1[redacted]")
    .slice(0, 300);
}

function requestIp(request: Request) {
  return String(request.headers.get("CF-Connecting-IP") || "").trim().slice(0, 64);
}

function serializeHeaders(headers: Headers): [string, string][] {
  return Array.from(headers.entries());
}

function base64FromBytes(bytes: Uint8Array) {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  }
  return btoa(binary);
}

async function captureBody(message: Request | Response): Promise<SystemLogHttpBodyCapture> {
  const contentType = message.headers.get("content-type") || "";
  const charset = /charset\s*=\s*["']?([^;"'\s]+)/i.exec(contentType)?.[1].toLowerCase();
  const mediaType = contentType.split(";", 1)[0].trim().toLowerCase();
  const textContent = !mediaType || mediaType.startsWith("text/") ||
    /^application\/(?:[^;]+\+)?(?:json|xml|x-www-form-urlencoded|javascript|graphql)$/.test(mediaType);
  const bytes = new Uint8Array(await message.arrayBuffer());
  if (textContent && (!charset || charset === "utf-8" || charset === "utf8")) {
    try {
      return { contentType, encoding: "utf-8", data: new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes) };
    } catch {
      // Keep non-UTF-8 bytes losslessly as base64.
    }
  }
  return { contentType, encoding: "base64", data: base64FromBytes(bytes) };
}

async function captureRequest(request: Request): Promise<SystemLogHttpCapture["request"]> {
  return {
    method: request.method,
    url: request.url,
    headers: serializeHeaders(request.headers),
    body: await captureBody(request),
  };
}

async function captureResponse(response: Response): Promise<SystemLogHttpCapture["response"]> {
  return {
    status: response.status,
    statusText: response.statusText,
    headers: serializeHeaders(response.headers),
    body: await captureBody(response),
  };
}

function adminOperationLabel(method: string, path: string) {
  if (path.endsWith("/api/system-log-settings") && method === "PUT") return "修改系统日志记录设置";
  if (path.endsWith("/api/state") && method === "PUT") return "保存后台配置";
  if (path.endsWith("/api/registration-codes") && method === "POST") return "生成注册码";
  if (path.endsWith("/api/vip-codes") && method === "POST") return "发放 VIP 卡密";
  if (path.includes("/api/users/batch") && method === "DELETE") return "批量删除用户";
  if (path.includes("/api/users/batch")) return "批量修改用户";
  if (path.includes("/api/users/") && path.endsWith("/ban")) {
    return method === "POST" ? "封禁用户" : "解除用户封禁";
  }
  if (path.includes("/api/users/") && method === "DELETE") return "删除用户";
  if (path.includes("/api/users/") && method === "PATCH") return "编辑用户";
  if (path.includes("/api/security/")) return "修改账户或设备安全设置";
  return `后台操作 ${method} ${path}`;
}

function isAdminWriteOperation(method: string, path: string) {
  if (method === "PUT" && path.endsWith("/api/system-log-settings")) return true;
  if (method === "PUT" && path.endsWith("/api/state")) return true;
  if (method === "POST" && path.endsWith("/api/registration-codes")) return true;
  if (method === "POST" && path.endsWith("/api/vip-codes")) return true;
  if (path.includes("/api/users/") || path.includes("/api/users/batch")) {
    return ["PATCH", "POST", "DELETE"].includes(method);
  }
  if (path.includes("/api/security/")) return ["POST", "PATCH", "DELETE"].includes(method);
  return false;
}

async function dispatchWorkerRequest(request: Request, env: Env) {
  try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";
      const adminBasePath = resolveAdminBasePath(env.ADMIN_PATH);

      if (isHoneypotPath(path)) {
        return handleHoneypotRoute(request, json);
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
        return handleProxyAppList(env);
      }

      if (path.startsWith("/api/subscription/") && request.method === "GET") {
        return handleManagedSubscription(path, request, env);
      }

      if (path.startsWith("/api/account/")) {
        return handleAccount(path, request, env);
      }

      if (adminBasePath && path.startsWith(`${adminBasePath}/api/`)) {
        const authorized = await isAdmin(request, env);
        if (!authorized) {
          return json({ error: "unauthorized" }, 401);
        }
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
}

const worker = {
  async fetch(request: Request, env: Env, ctx?: ExecutionContext) {
    const startedAt = performance.now();
    const occurredAt = beijingTimestamp();
    const url = new URL(request.url);
    const path = safeRequestPath(url.pathname.replace(/\/+$/, "") || "/");
    const adminBasePath = resolveAdminBasePath(env.ADMIN_PATH);
    const isAdminApi = Boolean(adminBasePath && path.startsWith(`${adminBasePath}/api/`));
    const requestId = request.headers.get("cf-ray") || crypto.randomUUID();
    let recordingSettings: SystemLogRecordingSettings | undefined;
    try {
      recordingSettings = await readSystemLogRecordingSettings(env);
    } catch {
      recordingSettings = undefined;
    }
    // 日志载荷读取和 VIP 卡密兑换/发放接口不保存正文，避免递归记录或泄露卡密。
    const skipFullCapture =
      (isAdminApi && path.endsWith("/api/system-logs/payload")) ||
      path.endsWith("/api/account/activate-vip") ||
      (isAdminApi && path.endsWith("/api/vip-codes"));
    const captureFullRequest = recordingSettings?.mode === "full" &&
      recordingSettings.categories.request &&
      isSystemLogScheduleActive(recordingSettings.schedule, new Date(occurredAt)) &&
      !skipFullCapture;
    const requestCapturePromise = captureFullRequest
      ? captureRequest(request.clone()).catch(() => null)
      : null;
    const diagnostics: SystemLogDraft[] = [];
    const sink = (entry: SystemLogDraft) => {
      diagnostics.push({
        ...entry,
        requestId,
        actor: entry.actor || "",
        path: entry.path || path,
        method: entry.method || request.method,
        ip: entry.ip || requestIp(request),
        userAgent: entry.userAgent || request.headers.get("user-agent") || "",
        occurredAt: entry.occurredAt || beijingTimestamp(),
      });
    };
    const observedEnv = createObservedEnv(env, sink);
    const response = await dispatchWorkerRequest(request, observedEnv);
    const durationMs = Math.round(performance.now() - startedAt);
    const authorizedAdmin = isAdminApi && request.method !== "OPTIONS" && response.status !== 401;
    const level = response.status >= 500 ? "error" : response.status >= 400 ? "warning" : "info";
    const actor = authorizedAdmin
      ? "管理员"
      : isAdminApi && response.status === 401
        ? "未认证"
        : "";
    const entries: SystemLogDraft[] = [
      ...diagnostics.map((entry) => ({ ...entry, actor: entry.actor || actor })),
      {
        category: "request",
        level,
        requestId,
        actor,
        method: request.method,
        path,
        status: response.status,
        durationMs,
        ip: requestIp(request),
        userAgent: request.headers.get("user-agent") || "",
        message: `${request.method} ${path} 返回 HTTP ${response.status}`,
        occurredAt,
      },
    ];
    if (isAdminApi && authorizedAdmin && isAdminWriteOperation(request.method, path)) {
      entries.push({
        category: "operation",
        level,
        requestId,
        actor,
        method: request.method,
        path,
        status: response.status,
        durationMs,
        ip: requestIp(request),
        userAgent: request.headers.get("user-agent") || "",
        message: adminOperationLabel(request.method, path),
        occurredAt,
      });
    }
    const persist = (async () => {
      if (requestCapturePromise) {
        try {
          const responseCapturePromise = captureResponse(response.clone());
          const [capturedRequest, capturedResponse] = await Promise.all([
            requestCapturePromise,
            responseCapturePromise,
          ]);
          const requestEntry = entries.find((entry) => entry.category === "request");
          if (capturedRequest && capturedResponse && requestEntry) {
            requestEntry.fullCapture = { request: capturedRequest, response: capturedResponse };
          }
        } catch {
          // Payload capture failures do not interfere with the original Worker response.
        }
      }
      await writeSystemLogs(env, entries, recordingSettings);
    })();
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(persist);
    else await persist;
    return response;
  },
  scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(pruneSystemLogs(env));
  },
} satisfies ExportedHandler<Env>;

export default worker;
