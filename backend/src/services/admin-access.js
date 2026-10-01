import { beijingTimestamp } from "../domain/time.js";

const ADMIN_LOGIN_RECORDS_KEY = "chimera:admin-login-records:v1";
const LEGACY_ADMIN_LOGIN_RECORDS_KEY = "fireflyvpn:admin-login-records:v1";
const MAX_ADMIN_LOGIN_RECORDS = 200;

function mergeAdminLoginRecords(...recordLists) {
  const unique = new Map();
  for (const records of recordLists) {
    for (const record of Array.isArray(records) ? records : []) {
      unique.set(JSON.stringify(record), record);
    }
  }
  return Array.from(unique.values())
    .sort((left, right) => String(right.at || "").localeCompare(String(left.at || "")))
    .slice(0, MAX_ADMIN_LOGIN_RECORDS);
}

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
    const [chimeraRecords, legacyRecords] = await Promise.all([
      env.CONFIG.get(ADMIN_LOGIN_RECORDS_KEY, "json"),
      env.CONFIG.get(LEGACY_ADMIN_LOGIN_RECORDS_KEY, "json"),
    ]);
    const records = mergeAdminLoginRecords(chimeraRecords, legacyRecords);
    records.unshift({
      at: beijingTimestamp(),
      ip: requestIp(request),
      userAgent: requestUserAgent(request),
      success: Boolean(success),
      path: requestPath(request, details.path),
      method: String(details.method || request.method).slice(0, 16),
      honeypot: Boolean(details.honeypot),
    });
    const serialized = JSON.stringify(records.slice(0, MAX_ADMIN_LOGIN_RECORDS));
    await Promise.all([
      env.CONFIG.put(ADMIN_LOGIN_RECORDS_KEY, serialized),
      env.CONFIG.put(LEGACY_ADMIN_LOGIN_RECORDS_KEY, serialized),
    ]);
  } catch (error) {
    console.error(JSON.stringify({
      message: "admin_login_record_failed",
      error: error instanceof Error ? error.message : String(error),
    }));
  }
}

export async function readAdminLoginRecords(env) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  const [chimeraRecords, legacyRecords] = await Promise.all([
    env.CONFIG.get(ADMIN_LOGIN_RECORDS_KEY, "json"),
    env.CONFIG.get(LEGACY_ADMIN_LOGIN_RECORDS_KEY, "json"),
  ]);
  return mergeAdminLoginRecords(chimeraRecords, legacyRecords);
}

// 仅清理后台登录与蜜罐记录，不触碰用户、设备或流量数据。
export async function clearAdminLoginRecords(env) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  await Promise.all([
    env.CONFIG.delete(ADMIN_LOGIN_RECORDS_KEY),
    env.CONFIG.delete(LEGACY_ADMIN_LOGIN_RECORDS_KEY),
  ]);
}
