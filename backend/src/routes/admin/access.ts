import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";
import { readJsonObject } from "../../http/request.ts";

/* System log query route. */

export function createAdminAccessRoutes(deps: Pick<
  RouteDependencies,
  | "json"
  | "readSystemLogs"
  | "readSystemLogPayload"
  | "readSystemLogRecordingSettings"
  | "updateSystemLogRecordingSetting"
  | "updateSystemLogRecordingMode"
  | "updateSystemLogRecordingSchedule"
>) {
  const {
    json,
    readSystemLogs,
    readSystemLogPayload,
    readSystemLogRecordingSettings,
    updateSystemLogRecordingSetting,
    updateSystemLogRecordingMode,
    updateSystemLogRecordingSchedule,
  } = deps;

  return async function handleAdminAccess(path: string, request: Request, env: Env) {
    if (path === "/api/system-log-settings" && request.method === "GET") {
      return json({ ok: true, settings: await readSystemLogRecordingSettings(env) });
    }

    if (path === "/api/system-log-settings" && request.method === "PUT") {
      try {
        const incoming = await readJsonObject(request);
        const settings = incoming.schedule !== undefined
          ? await updateSystemLogRecordingSchedule(env, incoming.schedule)
          : incoming.mode !== undefined
            ? await updateSystemLogRecordingMode(env, incoming.mode)
            : await updateSystemLogRecordingSetting(env, incoming.category, incoming.enabled);
        return json({ ok: true, settings });
      } catch (error) {
        return json({
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        }, 400);
      }
    }

    if (path === "/api/system-logs/payload" && request.method === "GET") {
      const url = new URL(request.url);
      return json(await readSystemLogPayload(env, url.searchParams.get("requestId")));
    }

    if (path === "/api/system-logs" && request.method === "GET") {
      const url = new URL(request.url);
      return json(await readSystemLogs(env, {
        category: url.searchParams.get("category") || undefined,
        level: url.searchParams.get("level") || undefined,
        status: url.searchParams.get("status") || undefined,
        startDate: url.searchParams.get("startDate") || undefined,
        endDate: url.searchParams.get("endDate") || undefined,
        query: url.searchParams.get("q") || undefined,
        limit: Number(url.searchParams.get("limit")) || 100,
        offset: Number(url.searchParams.get("offset")) || 0,
      }));
    }

    return null;
  };
}
