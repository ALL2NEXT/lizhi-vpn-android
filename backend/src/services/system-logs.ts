import type { Env } from "../types/env.ts";
import type {
  SystemLogCategory,
  SystemLogDraft,
  SystemLogHttpCapture,
  SystemLogRecord,
  SystemLogRecordingSchedule,
  SystemLogRecordingSettings,
  SystemLogRecordingMode,
  RequestUserIdentity,
} from "../types/models.ts";

import { SYSTEM_LOG_SETTINGS_KEY } from "../config.ts";
import { beijingTimestamp } from "../domain/time.ts";
import { ensureUserSchema } from "./user-store.ts";

const OUTBOUND_SLOW_MS = 1000;
const LOG_RETENTION_DAYS = 30;
const MAX_LOG_DETAILS_LENGTH = 1600;
const SYSTEM_LOG_CATEGORIES: SystemLogCategory[] = ["request", "outbound"];
const BEIJING_TIME_FORMATTER = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Asia/Shanghai",
  hourCycle: "h23",
  hour: "2-digit",
  minute: "2-digit",
});
const DEFAULT_SYSTEM_LOG_RECORDING_SETTINGS: SystemLogRecordingSettings = {
  mode: "simple",
  categories: { request: true, outbound: true },
  schedule: { enabled: false, startTime: "00:00", endTime: "23:59" },
};

function normalizeSystemLogRecordingSettings(value: unknown): SystemLogRecordingSettings {
  const stored = value && typeof value === "object"
    ? value as Record<string, unknown>
    : {};
  const categories = stored.categories && typeof stored.categories === "object"
    ? stored.categories as Record<string, unknown>
    : stored;
  const schedule = stored.schedule && typeof stored.schedule === "object"
    ? stored.schedule as Record<string, unknown>
    : {};
  const startTime = validScheduleTime(schedule.startTime) ? schedule.startTime : "00:00";
  const endTime = validScheduleTime(schedule.endTime) ? schedule.endTime : "23:59";
  return {
    mode: stored.mode === "full" ? "full" : "simple",
    categories: {
      request: categories.request !== false,
      outbound: categories.outbound !== false,
    },
    schedule: {
      enabled: schedule.enabled === true && startTime !== endTime,
      startTime,
      endTime,
    },
  };
}

function validScheduleTime(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{2}:\d{2}$/.test(value)) return false;
  const [hour, minute] = value.split(":").map(Number);
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
}

export async function readSystemLogRecordingSettings(env: Env): Promise<SystemLogRecordingSettings> {
  if (!env.CONFIG) return { ...DEFAULT_SYSTEM_LOG_RECORDING_SETTINGS };
  const stored = await env.CONFIG.get<unknown>(SYSTEM_LOG_SETTINGS_KEY, "json");
  return normalizeSystemLogRecordingSettings(stored);
}

export async function updateSystemLogRecordingSetting(
  env: Env,
  category: unknown,
  enabled: unknown,
): Promise<SystemLogRecordingSettings> {
  if (!SYSTEM_LOG_CATEGORIES.includes(category as SystemLogCategory)) {
    throw new Error("invalid_system_log_category");
  }
  if (typeof enabled !== "boolean") {
    throw new Error("invalid_system_log_recording_state");
  }
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  const settings = await readSystemLogRecordingSettings(env);
  const next = {
    ...settings,
    categories: { ...settings.categories, [category as SystemLogCategory]: enabled },
  };
  await env.CONFIG.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify(next));
  return next;
}

export async function updateSystemLogRecordingMode(
  env: Env,
  mode: unknown,
): Promise<SystemLogRecordingSettings> {
  if (mode !== "simple" && mode !== "full") {
    throw new Error("invalid_system_log_recording_mode");
  }
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  const settings = await readSystemLogRecordingSettings(env);
  const next = { ...settings, mode: mode as SystemLogRecordingMode };
  await env.CONFIG.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify(next));
  return next;
}

export async function updateSystemLogRecordingSchedule(
  env: Env,
  value: unknown,
): Promise<SystemLogRecordingSettings> {
  if (!value || typeof value !== "object") {
    throw new Error("invalid_system_log_recording_schedule");
  }
  const schedule = value as Record<string, unknown>;
  if (typeof schedule.enabled !== "boolean" ||
    !validScheduleTime(schedule.startTime) || !validScheduleTime(schedule.endTime) ||
    schedule.startTime === schedule.endTime) {
    throw new Error("invalid_system_log_recording_schedule");
  }
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  const settings = await readSystemLogRecordingSettings(env);
  const next = {
    ...settings,
    schedule: {
      enabled: schedule.enabled,
      startTime: schedule.startTime,
      endTime: schedule.endTime,
    },
  };
  await env.CONFIG.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify(next));
  return next;
}

export function isSystemLogScheduleActive(
  schedule: SystemLogRecordingSchedule,
  date = new Date(),
) {
  if (!schedule.enabled) return true;
  if (!Number.isFinite(date.getTime())) return false;
  if (!validScheduleTime(schedule.startTime) || !validScheduleTime(schedule.endTime) ||
    schedule.startTime === schedule.endTime) return false;

  const timeParts = BEIJING_TIME_FORMATTER.formatToParts(date);
  const hour = Number(timeParts.find((part) => part.type === "hour")?.value);
  const minute = Number(timeParts.find((part) => part.type === "minute")?.value);
  const currentMinute = hour * 60 + minute;
  const [startHour, startMinute] = schedule.startTime.split(":").map(Number);
  const [endHour, endMinute] = schedule.endTime.split(":").map(Number);
  const start = startHour * 60 + startMinute;
  const end = endHour * 60 + endMinute;

  return start < end
    ? currentMinute >= start && currentMinute < end
    : currentMinute >= start || currentMinute < end;
}

function truncate(value: unknown, maximum: number) {
  return String(value == null ? "" : value).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, " ").trim().slice(0, maximum);
}

function safeError(error: unknown) {
  const name = error instanceof Error ? error.name : "Error";
  const message = error instanceof Error ? error.message : String(error);
  return truncate(`${name}: ${message}`
    .replace(/https?:\/\/[^\s'"<>]+/gi, "[url]")
    .replace(/\b(token|key|password|secret)=([^&\s]+)/gi, "$1=[redacted]")
    .replace(/'(?:''|[^'])*'/g, "'?'"), 300);
}

export function createObservedEnv(
  env: Env,
  sink: (entry: SystemLogDraft) => void,
  userSink?: (identity: RequestUserIdentity) => void,
): Env {
  return new Proxy(env, {
    get(target, property, receiver) {
      if (property === "__systemLogSink") return sink;
      if (property === "__requestUserSink" && userSink) return userSink;
      return Reflect.get(target, property, receiver);
    },
  });
}

function outboundHost(input: RequestInfo | URL) {
  try {
    const rawUrl = input instanceof Request ? input.url : String(input);
    return new URL(rawUrl).hostname.toLowerCase().slice(0, 160);
  } catch {
    return "unknown-host";
  }
}

export async function observedFetch(
  env: Env | undefined,
  input: RequestInfo | URL,
  init?: RequestInit,
) {
  const startedAt = performance.now();
  const method = truncate(init?.method || (input instanceof Request ? input.method : "GET"), 16).toUpperCase();
  const host = outboundHost(input);
  try {
    const response = await fetch(input, init);
    const durationMs = Math.round(performance.now() - startedAt);
    if (!response.ok || durationMs >= OUTBOUND_SLOW_MS) {
      env?.__systemLogSink?.({
        category: "outbound",
        level: response.ok ? "warning" : "error",
        method,
        path: host,
        status: response.status,
        durationMs,
        message: `${method} ${host} 返回 HTTP ${response.status}`,
      });
    }
    return response;
  } catch (error) {
    const durationMs = Math.round(performance.now() - startedAt);
    env?.__systemLogSink?.({
      category: "outbound",
      level: "error",
      method,
      path: host,
      durationMs,
      message: `${method} ${host} 请求失败`,
      details: safeError(error),
    });
    throw error;
  }
}

function normalizeDraft(entry: SystemLogDraft) {
  const level = entry.level === "error" || entry.level === "warning" ? entry.level : "info";
  return {
    occurredAt: truncate(entry.occurredAt || beijingTimestamp(), 40),
    category: entry.category,
    level,
    requestId: truncate(entry.requestId, 96),
    actor: truncate(entry.actor, 64),
    method: truncate(entry.method, 16).toUpperCase(),
    path: truncate(entry.path, 300),
    status: Number.isInteger(entry.status) ? Number(entry.status) : null,
    durationMs: Number.isFinite(entry.durationMs) ? Math.max(0, Math.round(Number(entry.durationMs))) : null,
    ip: truncate(entry.ip, 64),
    userUid: truncate(entry.userUid, 64),
    deviceId: truncate(entry.deviceId, 64),
    identitySource: entry.identitySource === "login" || entry.identitySource === "device"
      ? entry.identitySource : "",
    userAgent: truncate(entry.userAgent, 300),
    message: truncate(entry.message, 300),
    details: truncate(entry.details, MAX_LOG_DETAILS_LENGTH),
  };
}

function splitPayload(serialized: string) {
  const maximumCodeUnits = 64 * 1024;
  const parts: string[] = [];
  for (let start = 0; start < serialized.length;) {
    let end = Math.min(start + maximumCodeUnits, serialized.length);
    if (end < serialized.length) {
      const lastCodeUnit = serialized.charCodeAt(end - 1);
      if (lastCodeUnit >= 0xd800 && lastCodeUnit <= 0xdbff) end -= 1;
    }
    parts.push(serialized.slice(start, end));
    start = end;
  }
  return parts.length ? parts : [""];
}

async function writeSystemLogPayload(
  db: D1Database,
  requestId: string,
  occurredAt: string,
  capture: SystemLogHttpCapture,
) {
  const id = truncate(requestId, 96);
  if (!id) return;
  const parts = splitPayload(JSON.stringify(capture));
  await db.prepare("DELETE FROM system_log_payload_parts_mb WHERE request_id = ?").bind(id).run();
  for (let start = 0; start < parts.length; start += 2) {
    const statements = parts.slice(start, start + 2).map((content, index) =>
      db.prepare(
        `INSERT INTO system_log_payload_parts_mb (
          request_id, occurred_at, part_index, part_count, content
        ) VALUES (?, ?, ?, ?, ?)`,
      ).bind(id, occurredAt, start + index, parts.length, content));
    await db.batch(statements);
  }
}

export async function writeSystemLogs(
  env: Env,
  entries: SystemLogDraft[],
  recordingSettings?: SystemLogRecordingSettings,
) {
  if (!entries.length || !env.USERS_DB) return;
  try {
    const settings = recordingSettings || await readSystemLogRecordingSettings(env);
    const enabledEntries = entries.filter((entry) => {
      if (!SYSTEM_LOG_CATEGORIES.includes(entry.category)) return false;
      if (!settings.categories[entry.category]) return false;
      const occurredAt = entry.occurredAt ? new Date(entry.occurredAt) : new Date();
      return isSystemLogScheduleActive(settings.schedule, occurredAt);
    });
    if (!enabledEntries.length) return;
    const db = await ensureUserSchema(env);
    const statements = enabledEntries.map((entry) => {
      const value = normalizeDraft(entry);
      return db.prepare(
        `INSERT INTO system_logs_mb (
          occurred_at, category, level, request_id, actor, method, path,
          status, duration_ms, ip, user_agent, message, details,
          user_uid, device_id, identity_source
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        value.occurredAt, value.category, value.level, value.requestId,
        value.actor, value.method, value.path, value.status, value.durationMs,
        value.ip, value.userAgent, value.message, value.details,
        value.userUid, value.deviceId, value.identitySource,
      );
    });
    await db.batch(statements);
    for (const entry of enabledEntries) {
      if (entry.category === "request" && entry.fullCapture) {
        await writeSystemLogPayload(db, entry.requestId || "", entry.occurredAt || beijingTimestamp(), entry.fullCapture);
      }
    }
  } catch (error) {
    console.error(JSON.stringify({
      message: "system_log_write_failed",
      error: safeError(error),
    }));
  }
}

export async function readSystemLogPayload(env: Env, requestId: unknown) {
  const id = truncate(requestId, 96);
  if (!id) return { ok: true, payload: null as SystemLogHttpCapture | null };
  const db = await ensureUserSchema(env);
  const result = await db.prepare(
    `SELECT part_index, part_count, content
     FROM system_log_payload_parts_mb
     WHERE request_id = ?
     ORDER BY part_index ASC`,
  ).bind(id).all<{ part_index: number; part_count: number; content: string }>();
  const rows = result.results || [];
  if (!rows.length) return { ok: true, payload: null as SystemLogHttpCapture | null };
  const expectedParts = Number(rows[0].part_count);
  if (!expectedParts || rows.length !== expectedParts) {
    return { ok: false, error: "system_log_payload_incomplete", payload: null as SystemLogHttpCapture | null };
  }
  try {
    const payload = JSON.parse(rows.map((row) => row.content).join("")) as SystemLogHttpCapture;
    return { ok: true, payload };
  } catch {
    return { ok: false, error: "system_log_payload_invalid", payload: null as SystemLogHttpCapture | null };
  }
}

export async function readSystemLogs(
  env: Env,
  filters: {
    category?: string;
    level?: string;
    status?: string;
    startDate?: string;
    endDate?: string;
    query?: string;
    endpointPath?: string;
    endpointMethod?: string;
    includeEndpoints?: boolean;
    limit?: number;
    offset?: number;
  } = {},
) {
  const db = await ensureUserSchema(env);
  const conditions: string[] = [];
  const bindings: (string | number)[] = [];
  const dateFilter = (value: string | undefined) => {
    if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const parsed = new Date(`${value}T00:00:00.000Z`);
    return Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value ? null : value;
  };
  const nextDate = (value: string) => {
    const date = new Date(`${value}T00:00:00.000Z`);
    date.setUTCDate(date.getUTCDate() + 1);
    return `${date.toISOString().slice(0, 10)}T00:00:00+08:00`;
  };
  const categories: SystemLogCategory[] = ["request", "outbound"];
  const endpointPath = truncate(filters.endpointPath, 300);
  const endpointMethod = truncate(filters.endpointMethod, 16).toUpperCase();
  if (endpointPath) {
    conditions.push("category = ?");
    bindings.push("request");
  } else if (categories.includes(filters.category as SystemLogCategory)) {
    conditions.push("category = ?");
    bindings.push(filters.category as string);
  } else {
    conditions.push("category IN ('request', 'outbound')");
  }
  if (endpointPath) {
    conditions.push("path = ?");
    bindings.push(endpointPath);
    if (endpointMethod) {
      conditions.push("method = ?");
      bindings.push(endpointMethod);
    }
  }
  if (["info", "warning", "error"].includes(String(filters.level))) {
    conditions.push("level = ?");
    bindings.push(String(filters.level));
  }
  const statusRanges: Record<string, [number, number]> = {
    success: [200, 299],
    redirect: [300, 399],
    "client-error": [400, 499],
    "server-error": [500, 599],
  };
  if (filters.status === "none") {
    conditions.push("status IS NULL");
  } else if (filters.status && statusRanges[filters.status]) {
    const [minimum, maximum] = statusRanges[filters.status];
    conditions.push("status BETWEEN ? AND ?");
    bindings.push(minimum, maximum);
  }
  const startDate = dateFilter(filters.startDate);
  const endDate = dateFilter(filters.endDate);
  if (startDate) {
    conditions.push("occurred_at >= ?");
    bindings.push(`${startDate}T00:00:00+08:00`);
  }
  if (endDate) {
    conditions.push("occurred_at < ?");
    bindings.push(nextDate(endDate));
  }
  const query = truncate(filters.query, 100);
  if (query) {
    conditions.push("(message LIKE ? OR path LIKE ? OR details LIKE ? OR ip LIKE ? OR request_id LIKE ? OR user_agent LIKE ? OR user_uid LIKE ? OR device_id LIKE ?)");
    const pattern = `%${query}%`;
    bindings.push(pattern, pattern, pattern, pattern, pattern, pattern, pattern, pattern);
  }
  const where = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
  const limit = Math.min(200, Math.max(1, Math.floor(Number(filters.limit) || 100)));
  const offset = Math.min(20000, Math.max(0, Math.floor(Number(filters.offset) || 0)));
  const endpointConditions = ["category = 'request'"];
  const endpointBindings: string[] = [];
  if (startDate) {
    endpointConditions.push("occurred_at >= ?");
    endpointBindings.push(`${startDate}T00:00:00+08:00`);
  }
  if (endDate) {
    endpointConditions.push("occurred_at < ?");
    endpointBindings.push(nextDate(endDate));
  }
  if (!startDate && !endDate) {
    endpointConditions.push("occurred_at >= ?");
    endpointBindings.push(beijingTimestamp(new Date(Date.now() - LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000)));
  }
  const endpointRowsPromise = filters.includeEndpoints
    ? db.prepare(
      `SELECT method, path, COUNT(*) AS request_count
       FROM system_logs_mb WHERE ${endpointConditions.join(" AND ")}
       GROUP BY method, path
       ORDER BY request_count DESC, method ASC, path ASC`,
    ).bind(...endpointBindings).all<Record<string, unknown>>()
    : Promise.resolve(null);
  const [count, rows, stats, endpointRows] = await Promise.all([
    db.prepare(`SELECT COUNT(*) AS total FROM system_logs_mb ${where}`)
      .bind(...bindings).first<{ total: number }>(),
    db.prepare(
      `SELECT id, occurred_at, category, level, request_id, actor, method, path,
        status, duration_ms, ip, user_agent, message, details, user_uid, device_id, identity_source
       FROM system_logs_mb ${where}
       ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?`,
    ).bind(...bindings, limit, offset).all<Record<string, unknown>>(),
    db.prepare(
      `SELECT COUNT(*) AS total,
        SUM(CASE WHEN level = 'error' THEN 1 ELSE 0 END) AS error_count,
        SUM(CASE WHEN level = 'warning' THEN 1 ELSE 0 END) AS warning_count,
        ROUND(AVG(duration_ms)) AS average_duration_ms
       FROM system_logs_mb ${where}`,
    ).bind(...bindings).first<Record<string, number | null>>(),
    endpointRowsPromise,
  ]);
  const records: SystemLogRecord[] = (rows.results || []).map((row) => ({
    id: Number(row.id),
    occurredAt: String(row.occurred_at || ""),
    category: row.category as SystemLogCategory,
    level: row.level as SystemLogRecord["level"],
    requestId: String(row.request_id || ""),
    actor: String(row.actor || ""),
    method: String(row.method || ""),
    path: String(row.path || ""),
    status: row.status == null ? null : Number(row.status),
    durationMs: row.duration_ms == null ? null : Number(row.duration_ms),
    ip: String(row.ip || ""),
    userUid: String(row.user_uid || ""),
    deviceId: String(row.device_id || ""),
    identitySource: String(row.identity_source || ""),
    userAgent: String(row.user_agent || ""),
    message: String(row.message || ""),
    details: String(row.details || ""),
  }));
  return {
    ok: true,
    total: Number(count?.total) || 0,
    limit,
    offset,
    records,
    requestEndpoints: (endpointRows?.results || []).map((row) => ({
      method: String(row.method || "").toUpperCase(),
      path: String(row.path || ""),
      requestCount: Number(row.request_count) || 0,
    })),
    stats: {
      total: Number(stats?.total) || 0,
      errorCount: Number(stats?.error_count) || 0,
      warningCount: Number(stats?.warning_count) || 0,
      averageDurationMs: Number(stats?.average_duration_ms) || 0,
    },
  };
}

export async function pruneSystemLogs(env: Env) {
  if (!env.USERS_DB) return 0;
  const db = await ensureUserSchema(env);
  const cutoff = beijingTimestamp(new Date(Date.now() - LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000));
  const [, result] = await db.batch([
    db.prepare("DELETE FROM system_log_payload_parts_mb WHERE occurred_at < ?").bind(cutoff),
    db.prepare("DELETE FROM system_logs_mb WHERE occurred_at < ?").bind(cutoff),
  ]);
  return Number(result.meta?.changes) || 0;
}
