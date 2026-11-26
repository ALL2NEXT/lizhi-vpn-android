import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";
import { readJsonObject } from "../../http/request.ts";

/* System log query route. */

export function createAdminAccessRoutes(deps: Pick<
  RouteDependencies,
  | "json"
  | "readSystemLogs"
  | "deleteSystemLogs"
  | "deleteAllSystemLogs"
  | "readSystemLogPayload"
  | "readSystemLogRecordingSettings"
  | "updateSystemLogRecordingSetting"
  | "updateSystemLogDetailSetting"
  | "updateSystemLogEndpointRecordingSettings"
  | "updateSystemLogRecordingSchedule"
>) {
  const {
    json,
    readSystemLogs,
    deleteSystemLogs,
    deleteAllSystemLogs,
    readSystemLogPayload,
    readSystemLogRecordingSettings,
    updateSystemLogRecordingSetting,
    updateSystemLogDetailSetting,
    updateSystemLogEndpointRecordingSettings,
    updateSystemLogRecordingSchedule,
  } = deps;

  return async function handleAdminAccess(path: string, request: Request, env: Env) {
    if (path === "/api/system-log-settings" && request.method === "GET") {
      return json({ ok: true, settings: await readSystemLogRecordingSettings(env) });
    }

    if (path === "/api/system-log-settings" && request.method === "PUT") {
      try {
        const incoming = await readJsonObject(request);
        const settings = incoming.endpointRecording !== undefined
          ? await updateSystemLogEndpointRecordingSettings(env, incoming.endpointRecording)
          : incoming.schedule !== undefined
          ? await updateSystemLogRecordingSchedule(env, incoming.schedule)
          : incoming.detail !== undefined
            ? await updateSystemLogDetailSetting(env, incoming.detail, incoming.enabled)
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
        status: url.searchParams.get("status") || undefined,
        startDate: url.searchParams.get("startDate") || undefined,
        endDate: url.searchParams.get("endDate") || undefined,
        query: url.searchParams.get("q") || undefined,
        endpointPath: url.searchParams.get("endpointPath") || undefined,
        endpointMethod: url.searchParams.get("endpointMethod") || undefined,
        includeEndpoints: url.searchParams.get("includeEndpoints") === "true",
        limit: Number(url.searchParams.get("limit")) || 100,
        offset: Number(url.searchParams.get("offset")) || 0,
      }));
    }

    if (path === "/api/system-logs" && request.method === "DELETE") {
      try {
        const incoming = await readJsonObject(request);
        const deleted = incoming.all === true
          ? await deleteAllSystemLogs(env)
          : await deleteSystemLogs(env, incoming.ids);
        return json({ ok: true, deleted });
      } catch (error) {
        const invalidIds = error instanceof Error && error.message === "invalid_system_log_ids";
        return json({
          ok: false,
          error: error instanceof Error ? error.message : String(error),
        }, invalidIds ? 400 : 500);
      }
    }

    return null;
  };
}
