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

export async function recordAdminLogin(env, request, success) {
  if (!env.CONFIG) return;
  try {
    const stored = await env.CONFIG.get(ADMIN_LOGIN_RECORDS_KEY, "json");
    const records = Array.isArray(stored) ? stored : [];
    records.unshift({
      at: beijingTimestamp(),
      ip: requestIp(request),
      userAgent: requestUserAgent(request),
      success: Boolean(success),
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
