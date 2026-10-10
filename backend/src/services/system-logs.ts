import type { Env } from "../types/env.ts";
import type {
  SystemLogCategory,
  SystemLogDraft,
  SystemLogRecord,
  SystemLogRecordingSettings,
} from "../types/models.ts";

import { SYSTEM_LOG_SETTINGS_KEY } from "../config.ts";
import { beijingTimestamp } from "../domain/time.ts";
import { ensureUserSchema } from "./user-store.ts";

const DATABASE_SLOW_MS = 250;
const OUTBOUND_SLOW_MS = 1000;
const LOG_RETENTION_DAYS = 30;
const MAX_LOG_DETAILS_LENGTH = 1600;
const SYSTEM_LOG_CATEGORIES: SystemLogCategory[] = ["request", "operation", "database", "outbound"];
const DEFAULT_SYSTEM_LOG_RECORDING_SETTINGS: SystemLogRecordingSettings = {
  request: true,
  operation: true,
  database: true,
  outbound: true,
};
const preparedMetadata = new WeakMap<object, { raw: D1PreparedStatement; sql: string }>();

function normalizeSystemLogRecordingSettings(value: unknown): SystemLogRecordingSettings {
  const settings = value && typeof value === "object"
    ? value as Partial<SystemLogRecordingSettings>
    : {};
  return {
    request: settings.request !== false,
    operation: settings.operation !== false,
    database: settings.database !== false,
    outbound: settings.outbound !== false,
  };
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
  const next = { ...settings, [category as SystemLogCategory]: enabled };
  await env.CONFIG.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify(next));
  return next;
}

function truncate(value: unknown, maximum: number) {
  return String(value == null ? "" : value).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, " ").trim().slice(0, maximum);
}

function safeSql(sql: string) {
  return truncate(sql
    .replace(/--[^\r\n]*/g, " ")
    .replace(/'(?:''|[^'])*'/g, "'?'" )
    .replace(/"(?:""|[^"])*"/g, '"?"')
    .replace(/\s+/g, " "), 900);
}

function safeError(error: unknown) {
  const name = error instanceof Error ? error.name : "Error";
  const message = error instanceof Error ? error.message : String(error);
  return truncate(`${name}: ${message}`
    .replace(/https?:\/\/[^\s'"<>]+/gi, "[url]")
    .replace(/\b(token|key|password|secret)=([^&\s]+)/gi, "$1=[redacted]")
    .replace(/'(?:''|[^'])*'/g, "'?'"), 300);
}

function notify(sink: (entry: SystemLogDraft) => void, entry: SystemLogDraft) {
  try {
    sink(entry);
  } catch {
    // 诊断日志失败不能影响原请求。
  }
}

function measured<T>(
  sink: (entry: SystemLogDraft) => void,
  database: string,
  operation: string,
  sql: string,
  run: () => Promise<T>,
) {
  const startedAt = performance.now();
  return run().then((result) => {
    const durationMs = Math.round(performance.now() - startedAt);
    if (durationMs >= DATABASE_SLOW_MS) {
      notify(sink, {
        category: "database",
        level: "warning",
        durationMs,
        message: `${database} ${operation} 用时 ${durationMs} ms`,
        details: safeSql(sql),
      });
    }
    return result;
  }, (error: unknown) => {
    const durationMs = Math.round(performance.now() - startedAt);
    notify(sink, {
      category: "database",
      level: "error",
      durationMs,
      message: `${database} ${operation} 失败`,
      details: `${safeError(error)}\n${safeSql(sql)}`,
    });
    throw error;
  });
}

function observedStatement(
  statement: D1PreparedStatement,
  sql: string,
  database: string,
  sink: (entry: SystemLogDraft) => void,
) {
  const wrapped = new Proxy(statement, {
    get(target, property) {
      if (property === "bind") {
        return (...values: unknown[]) => observedStatement(
          target.bind(...values), sql, database, sink,
        );
      }
      if (property === "run" || property === "first" || property === "all" || property === "raw") {
        return (...args: unknown[]) => measured(
          sink,
          database,
          String(property).toUpperCase(),
          sql,
          () => Reflect.apply(target[property] as (...input: unknown[]) => Promise<unknown>, target, args),
        );
      }
      const value = Reflect.get(target, property, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
  preparedMetadata.set(statement, { raw: statement, sql });
  preparedMetadata.set(wrapped, { raw: statement, sql });
  return wrapped;
}

function observedSession(
  session: D1DatabaseSession,
  database: string,
  sink: (entry: SystemLogDraft) => void,
) {
  return new Proxy(session, {
    get(target, property) {
      if (property === "prepare") {
        return (sql: string) => observedStatement(target.prepare(sql), sql, database, sink);
      }
      if (property === "batch") {
        return (statements: D1PreparedStatement[]) => {
          const metadata = statements.map((statement) => preparedMetadata.get(statement));
          const sql = metadata.map((item) => item && safeSql(item.sql)).filter(Boolean).join("; ");
          const rawStatements = statements.map((statement, index) => metadata[index]?.raw || statement);
          return measured(sink, database, `BATCH ${statements.length}`, sql, () =>
            Reflect.apply(target.batch, target, [rawStatements]));
        };
      }
      const value = Reflect.get(target, property, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

function observedDatabase(
  databaseBinding: D1Database,
  database: string,
  sink: (entry: SystemLogDraft) => void,
) {
  return new Proxy(databaseBinding, {
    get(target, property) {
      if (property === "prepare") {
        return (sql: string) => observedStatement(target.prepare(sql), sql, database, sink);
      }
      if (property === "batch") {
        return (statements: D1PreparedStatement[]) => {
          const metadata = statements.map((statement) => preparedMetadata.get(statement));
          const sql = metadata.map((item) => item && safeSql(item.sql)).filter(Boolean).join("; ");
          const rawStatements = statements.map((statement, index) => metadata[index]?.raw || statement);
          return measured(sink, database, `BATCH ${statements.length}`, sql, () =>
            Reflect.apply(target.batch, target, [rawStatements]));
        };
      }
      if (property === "exec") {
        return (sql: string) => measured(sink, database, "EXEC", sql, () =>
          Reflect.apply(target.exec, target, [sql]));
      }
      if (property === "withSession") {
        return (constraint?: D1SessionBookmark | D1SessionConstraint) => observedSession(
          target.withSession(constraint), database, sink,
        );
      }
      const value = Reflect.get(target, property, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

export function createObservedEnv(env: Env, sink: (entry: SystemLogDraft) => void): Env {
  const databases = new Map<PropertyKey, D1Database>();
  return new Proxy(env, {
    get(target, property, receiver) {
      if (property === "__systemLogSink") return sink;
      if (property === "USERS_DB" || property === "SUBSCRIPTIONS_DB") {
        const binding = Reflect.get(target, property, receiver) as D1Database | undefined;
        if (!binding) return binding;
        let wrapped = databases.get(property);
        if (!wrapped) {
          wrapped = observedDatabase(binding, String(property), sink);
          databases.set(property, wrapped);
        }
        return wrapped;
      }
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
  const allowedCategories: SystemLogCategory[] = ["request", "operation", "database", "outbound"];
  const category = allowedCategories.includes(entry.category) ? entry.category : "request";
  const level = entry.level === "error" || entry.level === "warning" ? entry.level : "info";
  return {
    occurredAt: truncate(entry.occurredAt || beijingTimestamp(), 40),
    category,
    level,
    requestId: truncate(entry.requestId, 96),
    actor: truncate(entry.actor, 64),
    method: truncate(entry.method, 16).toUpperCase(),
    path: truncate(entry.path, 300),
    status: Number.isInteger(entry.status) ? Number(entry.status) : null,
    durationMs: Number.isFinite(entry.durationMs) ? Math.max(0, Math.round(Number(entry.durationMs))) : null,
    ip: truncate(entry.ip, 64),
    userAgent: truncate(entry.userAgent, 300),
    message: truncate(entry.message, 300),
    details: truncate(entry.details, MAX_LOG_DETAILS_LENGTH),
  };
}

export async function writeSystemLogs(env: Env, entries: SystemLogDraft[]) {
  if (!entries.length || !env.USERS_DB) return;
  try {
    const settings = await readSystemLogRecordingSettings(env);
    const enabledEntries = entries.filter((entry) => settings[entry.category]);
    if (!enabledEntries.length) return;
    const db = await ensureUserSchema(env);
    const statements = enabledEntries.map((entry) => {
      const value = normalizeDraft(entry);
      return db.prepare(
        `INSERT INTO system_logs_mb (
          occurred_at, category, level, request_id, actor, method, path,
          status, duration_ms, ip, user_agent, message, details
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        value.occurredAt, value.category, value.level, value.requestId,
        value.actor, value.method, value.path, value.status, value.durationMs,
        value.ip, value.userAgent, value.message, value.details,
      );
    });
    await db.batch(statements);
  } catch (error) {
    console.error(JSON.stringify({
      message: "system_log_write_failed",
      error: safeError(error),
    }));
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
  const categories: SystemLogCategory[] = ["request", "operation", "database", "outbound"];
  if (categories.includes(filters.category as SystemLogCategory)) {
    conditions.push("category = ?");
    bindings.push(filters.category as string);
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
    conditions.push("(message LIKE ? OR path LIKE ? OR details LIKE ? OR ip LIKE ? OR request_id LIKE ? OR user_agent LIKE ?)");
    const pattern = `%${query}%`;
    bindings.push(pattern, pattern, pattern, pattern, pattern, pattern);
  }
  const where = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
  const limit = Math.min(200, Math.max(1, Math.floor(Number(filters.limit) || 100)));
  const offset = Math.min(20000, Math.max(0, Math.floor(Number(filters.offset) || 0)));
  const statsConditions: string[] = [];
  const statsBindings: string[] = [];
  if (categories.includes(filters.category as SystemLogCategory)) {
    statsConditions.push("category = ?");
    statsBindings.push(filters.category as string);
  }
  if (startDate) {
    statsConditions.push("occurred_at >= ?");
    statsBindings.push(`${startDate}T00:00:00+08:00`);
  }
  if (endDate) {
    statsConditions.push("occurred_at < ?");
    statsBindings.push(nextDate(endDate));
  }
  if (!startDate && !endDate) {
    statsConditions.push("occurred_at >= ?");
    statsBindings.push(beijingTimestamp(new Date(Date.now() - LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000)));
  }
  const statsWhere = statsConditions.length ? `WHERE ${statsConditions.join(" AND ")}` : "";
  const [count, rows, stats] = await Promise.all([
    db.prepare(`SELECT COUNT(*) AS total FROM system_logs_mb ${where}`)
      .bind(...bindings).first<{ total: number }>(),
    db.prepare(
      `SELECT id, occurred_at, category, level, request_id, actor, method, path,
        status, duration_ms, ip, user_agent, message, details
       FROM system_logs_mb ${where}
       ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?`,
    ).bind(...bindings, limit, offset).all<Record<string, unknown>>(),
    db.prepare(
      `SELECT COUNT(*) AS total,
        SUM(CASE WHEN level = 'error' THEN 1 ELSE 0 END) AS error_count,
        SUM(CASE WHEN level = 'warning' THEN 1 ELSE 0 END) AS warning_count,
        ROUND(AVG(duration_ms)) AS average_duration_ms
       FROM system_logs_mb ${statsWhere}`,
    ).bind(...statsBindings).first<Record<string, number | null>>(),
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
  const result = await db.prepare("DELETE FROM system_logs_mb WHERE occurred_at < ?")
    .bind(cutoff).run();
  return Number(result.meta?.changes) || 0;
}
