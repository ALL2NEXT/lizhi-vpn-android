import type { Env } from "../../types/env.ts";
import type { RouteDependencies } from "../../types/routes.ts";
import { readJsonObject } from "../../http/request.ts";
import {
  AiRiskError,
  analyzeAiRisk,
  listGeminiModels,
  readAiRiskDashboardData,
  readAiRiskDataset,
  readAiRiskSettings,
  testGeminiConnection,
  updateAiRiskSettings,
} from "../../services/ai-risk.ts";
import { readSecurityRuleHits } from "../../services/security-risk.ts";

/* System log query route. */

function requestedRiskRange(url: URL) {
  const startDate = url.searchParams.get("startDate");
  const endDate = url.searchParams.get("endDate");
  if (startDate !== null || endDate !== null) return { startDate, endDate };
  const hours = url.searchParams.get("windowHours");
  return hours === null ? 24 : Number(hours);
}

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
    updateSystemLogRecordingSchedule,
  } = deps;

  return async function handleAdminAccess(path: string, request: Request, env: Env) {
    if (path === "/api/security-risk/rule-hits" && request.method === "GET") {
      try {
        return json(await readSecurityRuleHits(env, requestedRiskRange(new URL(request.url))));
      } catch (error) {
        const errorCode = error instanceof Error ? error.message : "";
        const invalidWindow = errorCode === "invalid_security_risk_window" || errorCode === "invalid_security_risk_date_range";
        return json({
          ok: false,
          error: invalidWindow ? errorCode : "security_rule_hits_read_failed",
        }, invalidWindow ? 400 : 500);
      }
    }

    if (path === "/api/ai-risk/settings" && request.method === "GET") {
      return json({ ok: true, settings: await readAiRiskSettings(env) });
    }

    if (path === "/api/ai-risk/settings" && request.method === "PUT") {
      try {
        const incoming = await readJsonObject(request);
        return json({ ok: true, settings: await updateAiRiskSettings(env, incoming) });
      } catch (error) {
        const status = error instanceof AiRiskError ? error.status : 500;
        const code = error instanceof AiRiskError ? error.code : "ai_risk_settings_save_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    if (path === "/api/ai-risk/test" && request.method === "POST") {
      try {
        return json(await testGeminiConnection(env));
      } catch (error) {
        const status = error instanceof AiRiskError ? error.status : 500;
        const code = error instanceof AiRiskError ? error.code : "gemini_test_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    if (path === "/api/ai-risk/models" && request.method === "GET") {
      try {
        return json({ ok: true, ...await listGeminiModels(env) });
      } catch (error) {
        const status = error instanceof AiRiskError ? error.status : 500;
        const code = error instanceof AiRiskError ? error.code : "gemini_models_list_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    if (path === "/api/ai-risk/data" && request.method === "GET") {
      try {
        return json({ ok: true, dataset: await readAiRiskDataset(env, requestedRiskRange(new URL(request.url))) });
      } catch (error) {
        const status = error instanceof AiRiskError ? error.status : 500;
        const code = error instanceof AiRiskError ? error.code : "ai_risk_data_read_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    if (path === "/api/ai-risk/dashboard-data" && request.method === "GET") {
      try {
        return json({ ok: true, dataset: await readAiRiskDashboardData(env, requestedRiskRange(new URL(request.url))) });
      } catch (error) {
        const status = error instanceof AiRiskError ? error.status : 500;
        const code = error instanceof AiRiskError ? error.code : "ai_risk_dashboard_data_read_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    if (path === "/api/ai-risk/analyze" && request.method === "POST") {
      try {
        const incoming = await readJsonObject(request);
        const requestedRange = incoming.startDate !== undefined || incoming.endDate !== undefined
          ? { startDate: incoming.startDate, endDate: incoming.endDate }
          : incoming.windowHours ?? 24;
        return json(await analyzeAiRisk(env, requestedRange));
      } catch (error) {
        const status = error instanceof AiRiskError ? error.status : 500;
        const code = error instanceof AiRiskError ? error.code : "ai_risk_analysis_failed";
        return json({ ok: false, error: code }, status);
      }
    }

    if (path === "/api/system-log-settings" && request.method === "GET") {
      return json({ ok: true, settings: await readSystemLogRecordingSettings(env) });
    }

    if (path === "/api/system-log-settings" && request.method === "PUT") {
      try {
        const incoming = await readJsonObject(request);
        const settings = incoming.schedule !== undefined
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
