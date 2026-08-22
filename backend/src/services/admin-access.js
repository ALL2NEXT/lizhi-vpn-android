import { beijingTimestamp } from "../domain/time.js";

const ADMIN_LOGIN_RECORDS_KEY = "fireflyvpn:admin-login-records:v1";
const MAX_ADMIN_LOGIN_RECORDS = 200;

function requestIp(request) {
  return String(
    request.headers.get("CF-Connecting-IP") ||
      request.headers.get("X-Forwarded-For") ||
      "",
  )
    .split(",")[0]
    .trim()
    .slice(0, 128);
}

function requestUserAgent(request) {
  return String(request.headers.get("User-Agent") || "").trim().slice(0, 500);
}

function requestPath(request, path) {
  return String(path || new URL(request.url).pathname).slice(0, 256);
}

export async function recordAdminLogin(env, request, success, details = {}) {
  if (!env.CONFIG) return;
  try {
    const stored = await env.CONFIG.get(ADMIN_LOGIN_RECORDS_KEY, "json");
    const records = Array.isArray(stored) ? stored : [];
    records.unshift({
      at: beijingTimestamp(),
      ip: requestIp(request),
      userAgent: requestUserAgent(request),
      success: Boolean(success),
      path: requestPath(request, details.path),
      method: String(details.method || request.method).slice(0, 16),
      honeypot: Boolean(details.honeypot),
    });
    await env.CONFIG.put(
      ADMIN_LOGIN_RECORDS_KEY,
      JSON.stringify(records.slice(0, MAX_ADMIN_LOGIN_RECORDS)),
    );
  } catch (error) {
    console.error(JSON.stringify({
      message: "admin_login_record_failed",
      error: error instanceof Error ? error.message : String(error),
    }));
  }
}

export async function readAdminLoginRecords(env) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  const stored = await env.CONFIG.get(ADMIN_LOGIN_RECORDS_KEY, "json");
  return Array.isArray(stored) ? stored.slice(0, MAX_ADMIN_LOGIN_RECORDS) : [];
}

// 仅清理后台登录与蜜罐记录，不触碰用户、设备或流量数据。
export async function clearAdminLoginRecords(env) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  await env.CONFIG.delete(ADMIN_LOGIN_RECORDS_KEY);
}
