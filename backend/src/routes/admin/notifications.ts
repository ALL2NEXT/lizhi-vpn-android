import type { Env } from "../../types/env.ts";
import { readJsonObject } from "../../http/request.ts";
import type { RouteDependencies } from "../../types/routes.ts";
import {
  createAdminNotification,
  listAdminNotifications,
  normalizeNotificationInput,
  NotificationError,
  updateAdminNotification,
} from "../../services/notifications.ts";

export function createAdminNotificationRoutes(deps: Pick<RouteDependencies, "json">) {
  const { json } = deps;

  return async function handleAdminNotifications(path: string, request: Request, env: Env) {
    if (path === "/api/notifications" && request.method === "GET") {
      try {
        return json(await listAdminNotifications(env));
      } catch {
        return json({ ok: false, error: "notifications_read_failed" }, 500);
      }
    }

    if (path === "/api/notifications" && request.method === "POST") {
      try {
        const input = normalizeNotificationInput(await readJsonObject(request));
        return json(await createAdminNotification(env, input, new Date().toISOString()), 201);
      } catch (error) {
        const status = error instanceof NotificationError ? 400 : 500;
        const code = error instanceof NotificationError
          ? error.code
          : "notification_create_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    const match = /^\/api\/notifications\/(\d+)$/.exec(path);
    if (match && request.method === "PUT") {
      const notificationId = Number(match[1]);
      if (!Number.isSafeInteger(notificationId) || notificationId <= 0) {
        return json({ ok: false, error: "invalid_notification_id" }, 400);
      }
      try {
        const input = normalizeNotificationInput(await readJsonObject(request));
        const result = await updateAdminNotification(
          env,
          notificationId,
          input,
          new Date().toISOString(),
        );
        if (!result.changed) return json({ ok: false, error: "notification_not_found" }, 404);
        return json({ ok: true });
      } catch (error) {
        const status = error instanceof NotificationError ? 400 : 500;
        const code = error instanceof NotificationError
          ? error.code
          : "notification_update_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    return undefined;
  };
}
