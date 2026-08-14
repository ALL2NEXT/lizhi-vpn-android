const GEMINI_MODELS = new Map([
  ["gemini-flash-latest", "gemini-3.6-flash"],
  ["gemini-flash-lite-latest", "gemini-3.5-flash-lite"],
]);
const DEFAULT_GEMINI_MODEL = "gemini-flash-latest";
const DASHBOARD_AI_PANEL_LABELS = new Map([
  ["user-trends", "用户趋势"],
  ["traffic-trends", "流量趋势"],
  ["profile-ratio", "占比画像"],
]);
const DASHBOARD_AI_TASKS = new Map([
  [
    "user-trends",
    "根据近 30 天新增用户趋势，预测明天的新增用户量和累计用户量。",
  ],
  [
    "traffic-trends",
    "根据近 30 天上传、下载和总流量趋势，预测明天的上传流量、下载流量和总流量。",
  ],
  [
    "profile-ratio",
    "根据设备品牌和 App 版本占比，判断是否需要更新客户端版本，并给出更新优先级和依据。",
  ],
]);
const GEMINI_THINKING_LEVELS = new Set(["minimal", "low", "medium", "high"]);
const AI_ANALYSIS_STYLES = new Map([
  ["concise", "简洁摘要：优先给出关键结论和少量重点数据，控制篇幅。"],
  ["standard", "标准报告：平衡结论、风险、建议和数据口径。"],
  ["detailed", "详细报告：完整展开趋势、异常、可能原因边界和分步骤建议。"],
]);
const DEFAULT_AI_MAX_OUTPUT_TOKENS = 4096;

function positiveNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, number) : 0;
}

function roundedNumber(value, digits = 2) {
  const factor = 10 ** digits;
  return Math.round(positiveNumber(value) * factor) / factor;
}

function bytesToGiB(value) {
  return roundedNumber(positiveNumber(value) / (1024 ** 3), 2);
}

function aggregateLabels(rows, total, labelColumn = "label") {
  return (rows || []).map((row) => ({
    label: String(row[labelColumn] == null ? "" : row[labelColumn]).trim() || "未知",
    count: positiveNumber(row.count),
    ratioPercent: total > 0
      ? roundedNumber((positiveNumber(row.count) / total) * 100, 1)
      : 0,
  }));
}

/*
 * Gemini-backed analysis for aggregate dashboard data only.
 */

export function createAdminAiRoutes(deps) {
  const {
    beijingTimestamp,
    cleanText,
    ensureUserSchema,
    json,
    usagePeriodKeys,
  } = deps;

  return async function handleAdminAi(path, request, env) {
  if (path === "/api/ai/analyze-dashboard" && request.method === "POST") {
      const apiKey = cleanText(env.GEMINI_API_KEY);
      if (!apiKey) {
        return json({ ok: false, error: "gemini_not_configured" }, 503);
      }

      const body = await request.json();
      const stream = Boolean(body && body.stream);
      const requestedModel = cleanText(body && body.model);
      const modelAlias = GEMINI_MODELS.has(requestedModel)
        ? requestedModel
        : DEFAULT_GEMINI_MODEL;
      const model = GEMINI_MODELS.get(modelAlias);
      const requestedPanel = cleanText(body && body.panel);
      const panel = DASHBOARD_AI_PANEL_LABELS.has(requestedPanel)
        ? requestedPanel
        : "user-trends";
      const panelLabel = DASHBOARD_AI_PANEL_LABELS.get(panel);
      const requestedThinkingLevel = cleanText(body && body.thinkingLevel);
      const thinkingLevel = GEMINI_THINKING_LEVELS.has(requestedThinkingLevel)
        ? requestedThinkingLevel
        : "low";
      const requestedMaxOutputTokens = Number(body && body.maxOutputTokens);
      const maxOutputTokens = Number.isFinite(requestedMaxOutputTokens)
        ? Math.min(Math.max(Math.round(requestedMaxOutputTokens), 256), 65536)
        : DEFAULT_AI_MAX_OUTPUT_TOKENS;
      const requestedStyle = cleanText(body && body.analysisStyle);
      const analysisStyle = AI_ANALYSIS_STYLES.has(requestedStyle)
        ? requestedStyle
        : "standard";
      const customInstruction = cleanText(body && body.customInstruction).slice(0, 1000);
      const db = await ensureUserSchema(env);
      const today = usagePeriodKeys(new Date()).day;
      const tomorrowDate = new Date(`${today}T00:00:00+08:00`);
      tomorrowDate.setUTCDate(tomorrowDate.getUTCDate() + 1);
      const tomorrow = tomorrowDate.toISOString().slice(0, 10);
      const startDateObject = new Date(`${today}T00:00:00+08:00`);
      startDateObject.setUTCDate(startDateObject.getUTCDate() - 29);
      const startDate = startDateObject.toISOString().slice(0, 10);
      const aggregate = await db.batch([
        db.prepare(
          `SELECT
             COUNT(*) AS total_users,
             SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) AS active_users,
             SUM(CASE WHEN status = 'banned' THEN 1 ELSE 0 END) AS banned_users,
             SUM(CASE WHEN created_day >= ? THEN 1 ELSE 0 END) AS new_users_last_30_days,
             COALESCE(AVG(experience_total), 0) AS average_experience
           FROM users_mb`,
        ).bind(startDate),
        db.prepare(
          `SELECT
             COUNT(DISTINCT usage_day) AS active_days,
             COUNT(DISTINCT user_id) AS active_users,
             COALESCE(SUM(upload_bytes), 0) AS upload_bytes,
             COALESCE(SUM(download_bytes), 0) AS download_bytes
           FROM user_usage_daily_mb
           WHERE usage_day >= ?`,
        ).bind(startDate),
        db.prepare(
          `SELECT usage_day AS date_key,
                  COUNT(DISTINCT user_id) AS active_users,
                  COALESCE(SUM(upload_bytes), 0) AS upload_bytes,
                  COALESCE(SUM(download_bytes), 0) AS download_bytes
           FROM user_usage_daily_mb
           WHERE usage_day >= ?
           GROUP BY usage_day
           ORDER BY usage_day ASC`,
        ).bind(startDate),
        db.prepare(
          `SELECT created_day AS date_key, COUNT(*) AS new_users
           FROM users_mb
           WHERE created_day >= ?
           GROUP BY created_day
           ORDER BY created_day ASC`,
        ).bind(startDate),
        db.prepare(
          `SELECT
             COUNT(*) AS total_devices,
             COUNT(DISTINCT CASE WHEN last_seen_at >= ? THEN id END) AS active_devices_30d,
             COUNT(DISTINCT CASE WHEN brand <> '' THEN brand END) AS brand_count,
             COUNT(DISTINCT CASE WHEN app_version <> '' THEN app_version END) AS app_version_count
           FROM user_devices_mb`,
        ).bind(startDate),
        db.prepare(
          `SELECT COALESCE(NULLIF(TRIM(brand), ''), '未知') AS label, COUNT(*) AS count
           FROM user_devices_mb
           GROUP BY COALESCE(NULLIF(TRIM(brand), ''), '未知')
           ORDER BY count DESC, label ASC
           LIMIT 8`,
        ),
        db.prepare(
          `SELECT COALESCE(NULLIF(TRIM(app_version), ''), '未知') AS label, COUNT(*) AS count
           FROM user_devices_mb
           GROUP BY COALESCE(NULLIF(TRIM(app_version), ''), '未知')
           ORDER BY count DESC, label ASC
           LIMIT 8`,
        ),
        db.prepare(
          `SELECT
             COUNT(*) AS result_count,
             COALESCE(SUM(report_count), 0) AS report_count,
             COALESCE(AVG(tcping_latency_ms), 0) AS average_tcping_latency_ms,
             COALESCE(AVG(url_test_latency_ms), 0) AS average_url_test_latency_ms,
             COALESCE(AVG(download_mbps), 0) AS average_download_mbps,
             COALESCE(AVG(upload_mbps), 0) AS average_upload_mbps
           FROM speed_share_results_mb`,
        ),
      ]);

      const users = aggregate[0] && aggregate[0].results && aggregate[0].results[0] || {};
      const traffic = aggregate[1] && aggregate[1].results && aggregate[1].results[0] || {};
      const devices = aggregate[4] && aggregate[4].results && aggregate[4].results[0] || {};
      const speed = aggregate[7] && aggregate[7].results && aggregate[7].results[0] || {};
      const trafficDaily = (aggregate[2] && aggregate[2].results || []).map((row) => ({
        date: cleanText(row.date_key),
        activeUsers: positiveNumber(row.active_users),
        uploadGiB: bytesToGiB(row.upload_bytes),
        downloadGiB: bytesToGiB(row.download_bytes),
        totalGiB: bytesToGiB(positiveNumber(row.upload_bytes) + positiveNumber(row.download_bytes)),
      }));
      const userGrowthDaily = (aggregate[3] && aggregate[3].results || []).map((row) => ({
        date: cleanText(row.date_key),
        newUsers: positiveNumber(row.new_users),
      }));
      const dataWindow = {
        startDate,
        endDate: today,
        forecastDate: tomorrow,
        timezone: "Asia/Shanghai",
      };
      let facts;
      if (panel === "user-trends") {
        facts = {
          dataWindow,
          accounts: {
            total: positiveNumber(users.total_users),
            active: positiveNumber(users.active_users),
            banned: positiveNumber(users.banned_users),
            newLast30Days: positiveNumber(users.new_users_last_30_days),
            averageExperience: roundedNumber(users.average_experience, 1),
          },
          userGrowthDaily,
        };
      } else if (panel === "traffic-trends") {
        facts = {
          dataWindow,
          trafficLast30Days: {
            activeDays: positiveNumber(traffic.active_days),
            activeUsers: positiveNumber(traffic.active_users),
            uploadGiB: bytesToGiB(traffic.upload_bytes),
            downloadGiB: bytesToGiB(traffic.download_bytes),
            totalGiB: bytesToGiB(
              positiveNumber(traffic.upload_bytes) + positiveNumber(traffic.download_bytes),
            ),
          },
          trafficDaily,
        };
      } else {
        facts = {
          dataWindow,
          devices: {
            total: positiveNumber(devices.total_devices),
            activeLast30Days: positiveNumber(devices.active_devices_30d),
            brandCount: positiveNumber(devices.brand_count),
            appVersionCount: positiveNumber(devices.app_version_count),
            topBrands: aggregateLabels(
              aggregate[5] && aggregate[5].results,
              positiveNumber(devices.total_devices),
            ),
            topAppVersions: aggregateLabels(
              aggregate[6] && aggregate[6].results,
              positiveNumber(devices.total_devices),
            ),
          },
        };
      }
      const panelTask = DASHBOARD_AI_TASKS.get(panel);
      const prompt = [
        "你是 VPN 运营后台的 AI 助手。本次只分析“" + panelLabel + "”板块，禁止引入其他板块的指标。",
        "你的主要职责是：" + panelTask,
        "请用中文 Markdown 输出，必须包含：1. 核心结论；2. 预测或更新判断；3. 判断依据和关键数据；4. 风险、置信度与数据不足说明；5. 可执行的运营建议；6. 数据范围和口径。",
        "预测结果必须明确标注预测日期、点估计和合理区间，预测不是已发生事实；不要编造数字，不要把相关性说成因果关系。",
        AI_ANALYSIS_STYLES.get(analysisStyle),
        customInstruction ? "用户补充要求：" + customInstruction : "",
        "如果数据不足以支持某个判断，请明确写出‘数据不足’，不要把相关性说成因果关系。不要输出任何个人信息，也不要要求补充 UID、昵称、邮箱等个人明细。",
        `数据 JSON：${JSON.stringify(facts)}`,
      ].join("\n");
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);
      let response;
      try {
        response = await fetch(
          "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":" +
            (stream ? "streamGenerateContent?alt=sse" : "generateContent"),
          {
            method: "POST",
            headers: {
              "content-type": "application/json",
              "x-goog-api-key": apiKey,
            },
            body: JSON.stringify({
              contents: [{ parts: [{ text: prompt }] }],
              generationConfig: {
                maxOutputTokens,
                thinkingConfig: {
                  thinkingLevel,
                },
              },
            }),
            signal: controller.signal,
          },
        );
      } catch (_) {
        return json({ ok: false, error: "gemini_request_failed" }, 502);
      } finally {
        clearTimeout(timeoutId);
      }
      if (!response.ok) {
        const error = response.status === 401 || response.status === 403
          ? "gemini_auth_failed"
          : response.status === 404
          ? "gemini_model_unavailable"
          : response.status === 429
          ? "gemini_quota_exceeded"
          : "gemini_request_failed";
        return json({ ok: false, error, providerStatus: response.status }, 502);
      }
      if (stream) {
        if (!response.body) {
          return json({ ok: false, error: "gemini_stream_unavailable" }, 502);
        }
        const upstreamBody = response.body;
        const encoder = new TextEncoder();
        const streamedResponse = new ReadableStream({
          async start(controller) {
            const reader = upstreamBody.getReader();
            try {
              while (true) {
                const chunk = await reader.read();
                if (chunk.done) break;
                controller.enqueue(chunk.value);
              }
              controller.enqueue(encoder.encode(
                "\nevent: ai-meta\ndata: " + JSON.stringify({
                  panel,
                  model: modelAlias,
                  providerModel: model,
                  dataWindow: facts.dataWindow,
                  settings: {
                    thinkingLevel,
                    maxOutputTokens,
                    analysisStyle,
                  },
                }) + "\n\n",
              ));
              controller.close();
            } catch (error) {
              controller.error(error);
            } finally {
              reader.releaseLock();
            }
          },
          cancel(reason) {
            return upstreamBody.cancel(reason);
          },
        });
        return new Response(streamedResponse, {
          status: 200,
          headers: {
            "access-control-allow-origin": "*",
            "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
            "access-control-allow-headers": "Content-Type, Authorization",
            "content-type": "text/event-stream; charset=utf-8",
            "cache-control": "no-cache, no-transform",
            "x-accel-buffering": "no",
          },
        });
      }
      const result = await response.json();
      const candidate = result.candidates && result.candidates[0] || {};
      const analysis = (candidate.content && candidate.content.parts || [])
        .map((part) => cleanText(part && part.text))
        .filter(Boolean)
        .join("\n")
        .trim();
      if (!analysis) {
        return json({ ok: false, error: "gemini_empty_response" }, 502);
      }
      const usageMetadata = result.usageMetadata || {};
      return json({
        ok: true,
        panel,
        model: modelAlias,
        providerModel: model,
        analysis,
        finishReason: cleanText(candidate.finishReason) || "STOP",
        modelVersion: cleanText(result.modelVersion),
        usage: {
          promptTokens: positiveNumber(usageMetadata.promptTokenCount),
          outputTokens: positiveNumber(usageMetadata.candidatesTokenCount),
          thoughtsTokens: positiveNumber(usageMetadata.thoughtsTokenCount),
        },
        settings: {
          thinkingLevel,
          maxOutputTokens,
          analysisStyle,
        },
        dataWindow: facts.dataWindow,
        generatedAt: beijingTimestamp(),
      });
    }

    return null;
  };
}
