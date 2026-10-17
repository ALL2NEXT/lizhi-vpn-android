import type { Env } from "../types/env.ts";
import type { Database } from "../storage/contracts.ts";
import { ensureUserSchema } from "./user-store.ts";

export type NotificationLevel = "info" | "important";
export type NotificationStatus = "draft" | "published" | "archived";

export interface NotificationInput {
  title: string;
  body: string;
  level: NotificationLevel;
  status: NotificationStatus;
  startsAt: string | null;
  expiresAt: string | null;
}

export class NotificationError extends Error {
  constructor(readonly code: string) {
    super(code);
  }
}

function normalizeDate(value: unknown, errorCode: string): string | null {
  if (value == null || String(value).trim() === "") return null;
  const timestamp = Date.parse(String(value));
  if (!Number.isFinite(timestamp)) throw new NotificationError(errorCode);
  return new Date(timestamp).toISOString();
}

export function normalizeNotificationInput(value: Record<string, unknown>): NotificationInput {
  const title = typeof value.title === "string" ? value.title.trim() : "";
  const body = typeof value.body === "string"
    ? value.body.replace(/\r\n?/g, "\n").trim()
    : "";
  const level = value.level === "important" ? "important" : value.level === "info" || value.level == null
    ? "info"
    : null;
  const status = value.status === "draft" || value.status === "published" || value.status === "archived"
    ? value.status
    : value.status == null
      ? "draft"
      : null;
  const startsAt = normalizeDate(value.startsAt, "invalid_notification_start_time");
  const expiresAt = normalizeDate(value.expiresAt, "invalid_notification_expiry_time");

  if (!title || title.length > 100) throw new NotificationError("invalid_notification_title");
  if (!body || body.length > 10000) throw new NotificationError("invalid_notification_body");
  if (!level) throw new NotificationError("invalid_notification_level");
  if (!status) throw new NotificationError("invalid_notification_status");
  if (startsAt && expiresAt && expiresAt <= startsAt) {
    throw new NotificationError("invalid_notification_time_range");
  }
  return { title, body, level, status, startsAt, expiresAt };
}

function database(env: Env): Promise<Database> {
  return ensureUserSchema(env);
}

function visibleNotificationSql() {
  return `status = 'published'
    AND (starts_at IS NULL OR starts_at <= ?)
    AND (expires_at IS NULL OR expires_at > ?)`;
}

export async function readClientNotifications(env: Env, userId: number, now = new Date().toISOString()) {
  const db = await database(env);
  const visibility = visibleNotificationSql();
  const [items, unread] = await Promise.all([
    db.prepare(
      `SELECT n.id, n.title, n.body, n.level, n.published_at, n.starts_at, n.expires_at,
              CASE WHEN r.notification_id IS NULL THEN 0 ELSE 1 END AS is_read
       FROM notifications_mb n
       LEFT JOIN notification_reads_mb r
         ON r.notification_id = n.id AND r.user_id = ?
       WHERE ${visibility}
       ORDER BY COALESCE(n.published_at, n.created_at) DESC, n.id DESC
       LIMIT 100`,
    ).bind(userId, now, now).all<{
      id: number;
      title: string;
      body: string;
      level: NotificationLevel;
      published_at: string | null;
      starts_at: string | null;
      expires_at: string | null;
      is_read: number;
    }>(),
    db.prepare(
      `SELECT COUNT(*) AS count
       FROM notifications_mb n
       LEFT JOIN notification_reads_mb r
         ON r.notification_id = n.id AND r.user_id = ?
       WHERE ${visibility} AND r.notification_id IS NULL`,
    ).bind(userId, now, now).first<{ count: number }>(),
  ]);
  return {
    ok: true,
    unreadCount: Math.max(0, Number(unread?.count) || 0),
    items: (items.results || []).map((row) => ({
      id: Number(row.id),
      title: row.title,
      body: row.body,
      level: row.level,
      publishedAt: row.published_at,
      startsAt: row.starts_at,
      expiresAt: row.expires_at,
      isRead: Number(row.is_read) > 0,
    })),
  };
}

export async function markClientNotificationRead(
  env: Env,
  userId: number,
  notificationId: number,
  now = new Date().toISOString(),
) {
  const db = await database(env);
  // 只记录当前仍对用户可见的通知，避免客户端对已撤回或过期内容写入已读状态。
  const result = await db.prepare(
    `INSERT OR IGNORE INTO notification_reads_mb (notification_id, user_id, read_at)
     SELECT id, ?, ? FROM notifications_mb
     WHERE id = ? AND ${visibleNotificationSql()}`,
  ).bind(userId, now, notificationId, now, now).run();
  return { ok: true, changed: Number(result.meta?.changes) || 0 };
}

export async function markAllClientNotificationsRead(
  env: Env,
  userId: number,
  now = new Date().toISOString(),
) {
  const db = await database(env);
  const result = await db.prepare(
    `INSERT OR IGNORE INTO notification_reads_mb (notification_id, user_id, read_at)
     SELECT id, ?, ? FROM notifications_mb WHERE ${visibleNotificationSql()}`,
  ).bind(userId, now, now, now).run();
  return { ok: true, changed: Number(result.meta?.changes) || 0 };
}

export async function listAdminNotifications(env: Env) {
  const db = await database(env);
  const result = await db.prepare(
    `SELECT id, title, body, level, status, starts_at, expires_at, published_at, created_at, updated_at
     FROM notifications_mb
     ORDER BY COALESCE(published_at, created_at) DESC, id DESC
     LIMIT 500`,
  ).all<{
    id: number;
    title: string;
    body: string;
    level: NotificationLevel;
    status: NotificationStatus;
    starts_at: string | null;
    expires_at: string | null;
    published_at: string | null;
    created_at: string;
    updated_at: string;
  }>();
  return {
    ok: true,
    items: (result.results || []).map((row) => ({
      id: Number(row.id),
      title: row.title,
      body: row.body,
      level: row.level,
      status: row.status,
      startsAt: row.starts_at,
      expiresAt: row.expires_at,
      publishedAt: row.published_at,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    })),
  };
}

export async function createAdminNotification(env: Env, input: NotificationInput, now: string) {
  const db = await database(env);
  await db.prepare(
    `INSERT INTO notifications_mb
       (title, body, level, status, starts_at, expires_at, published_at, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    input.title,
    input.body,
    input.level,
    input.status,
    input.startsAt,
    input.expiresAt,
    input.status === "published" ? now : null,
    now,
    now,
  ).run();
  return { ok: true };
}

export async function updateAdminNotification(
  env: Env,
  notificationId: number,
  input: NotificationInput,
  now: string,
) {
  const db = await database(env);
  const result = await db.prepare(
    `UPDATE notifications_mb
     SET title = ?, body = ?, level = ?,
         published_at = CASE
           WHEN ? = 'published' AND status <> 'published' THEN ?
           ELSE published_at
         END,
         status = ?, starts_at = ?, expires_at = ?, updated_at = ?
     WHERE id = ?`,
  ).bind(
    input.title,
    input.body,
    input.level,
    input.status,
    now,
    input.status,
    input.startsAt,
    input.expiresAt,
    now,
    notificationId,
  ).run();
  return { ok: true, changed: Number(result.meta?.changes) || 0 };
}
