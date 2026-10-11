import test from "node:test";
import assert from "node:assert/strict";
import {
  AiRiskError,
  buildAiRiskPrompt,
  buildGeminiGenerationConfig,
  DEFAULT_AI_RISK_PROMPT_TEMPLATE,
  listGeminiModels,
  readAiRiskSettings,
  testGeminiConnection,
  updateAiRiskSettings,
} from "../src/services/ai-risk.ts";

class MemoryConfig {
  values = new Map();

  async get(key, type) {
    const value = this.values.get(key);
    if (value === undefined) return null;
    return type === "json" ? JSON.parse(value) : value;
  }

  async put(key, value) {
    this.values.set(key, value);
  }
}

test("AI 风控设置不会向管理页面回传 API Key，留空时保留密钥", async () => {
  const env = { CONFIG: new MemoryConfig() };
  const first = await updateAiRiskSettings(env, {
    enabled: true,
    model: "gemini-3.8-flash",
    apiKey: "test-secret-key",
  });

  assert.deepEqual(first, {
    enabled: true,
    model: "gemini-3.8-flash",
    apiKeyConfigured: true,
    apiKeyCount: 1,
    thinkingLevel: "medium",
    promptTemplate: DEFAULT_AI_RISK_PROMPT_TEMPLATE,
    defaultPromptTemplate: DEFAULT_AI_RISK_PROMPT_TEMPLATE,
  });
  assert.equal(JSON.stringify(first).includes("test-secret-key"), false);

  const second = await updateAiRiskSettings(env, { model: "gemini-3.5-flash-lite", apiKey: "" });
  assert.equal(second.apiKeyConfigured, true);
  assert.equal(second.apiKeyCount, 1);
  assert.equal((await readAiRiskSettings(env)).model, "gemini-3.5-flash-lite");
});

test("默认提示词覆盖五类风控行为，模板可保存、恢复且要求唯一数据占位符", async () => {
  const env = { CONFIG: new MemoryConfig() };
  const defaults = await readAiRiskSettings(env);
  for (const phrase of ["24 小时持续获取订阅", "低峰期仍获取订阅", "只访问订阅接口", "单 IP 多账户", "非法请求参数"]) {
    assert.ok(defaults.promptTemplate.includes(phrase), `默认提示词缺少 ${phrase}`);
  }
  assert.equal(defaults.promptTemplate.split("{{DATASET_JSON}}").length - 1, 1);
  assert.match(defaults.promptTemplate, /无法判断/);

  const custom = "按我的规则分析，不确定就说明无法判断。\n{{DATASET_JSON}}";
  const saved = await updateAiRiskSettings(env, { promptTemplate: custom });
  assert.equal(saved.promptTemplate, custom);
  assert.equal(saved.defaultPromptTemplate, DEFAULT_AI_RISK_PROMPT_TEMPLATE);
  assert.equal((await updateAiRiskSettings(env, { model: "gemini-3.8-flash" })).promptTemplate, custom);

  for (const invalid of ["缺少占位符", "{{DATASET_JSON}} {{DATASET_JSON}}", "x".repeat(12_001) + "{{DATASET_JSON}}"]){
    await assert.rejects(
      updateAiRiskSettings(env, { promptTemplate: invalid }),
      (error) => error instanceof AiRiskError && error.code === "invalid_ai_risk_prompt_template",
    );
  }
});

test("风控分析提示词将数据集 JSON 插入唯一占位符", () => {
  const dataset = { subjects: [{ ipRef: "ip-1", requestCount: 2 }] };
  assert.equal(buildAiRiskPrompt("分析：{{DATASET_JSON}}。", dataset), `分析：${JSON.stringify(dataset)}。`);
});

test("保存多个 Gemini Key 时去重，空输入保留并允许显式清除", async () => {
  const env = { CONFIG: new MemoryConfig() };
  const saved = await updateAiRiskSettings(env, { apiKeys: " key-one, key-two;key-one " });
  assert.equal(saved.apiKeyCount, 2);
  assert.equal((await updateAiRiskSettings(env, { apiKeys: "" })).apiKeyCount, 2);
  assert.equal((await updateAiRiskSettings(env, { clearApiKeys: true })).apiKeyCount, 0);
});

test("启用 AI 时要求 API Key，并拒绝非法模型名称", async () => {
  const env = { CONFIG: new MemoryConfig() };
  await assert.rejects(
    updateAiRiskSettings(env, { enabled: true }),
    (error) => error instanceof AiRiskError && error.code === "ai_risk_api_key_required",
  );
  await assert.rejects(
    updateAiRiskSettings(env, { model: "../unexpected" }),
    (error) => error instanceof AiRiskError && error.code === "invalid_ai_risk_model",
  );
  await assert.rejects(
    updateAiRiskSettings(env, { thinkingLevel: "extreme" }),
    (error) => error instanceof AiRiskError && error.code === "invalid_ai_risk_thinking_level",
  );
});

test("Gemini 2.5 与 Gemini 3 分别使用兼容的思考参数映射", () => {
  const gemini3 = buildGeminiGenerationConfig("gemini-3.8-flash", true, "high");
  assert.deepEqual(gemini3.thinkingConfig, { thinkingLevel: "high" });
  assert.equal(Object.hasOwn(gemini3, "temperature"), false);
  assert.equal(gemini3.maxOutputTokens, 8192);

  const gemini25 = buildGeminiGenerationConfig("gemini-2.5-pro", true, "medium");
  assert.deepEqual(gemini25.thinkingConfig, { thinkingBudget: 8192 });
  assert.equal(gemini25.temperature, 0.1);
  assert.equal(gemini25.responseMimeType, "application/json");
  assert.equal(Object.hasOwn(gemini25, "responseFormat"), false);

  assert.equal(gemini3.responseFormat.text.mimeType, "application/json");
  assert.equal(Object.hasOwn(gemini3, "responseMimeType"), false);
  const findingSchema = gemini3.responseFormat.text.schema.properties.findings.items;
  assert.deepEqual(findingSchema.properties.riskScore, {
    type: "INTEGER",
    minimum: 0,
    maximum: 100,
    description: "0 到 100 的风控排查优先级分数，不代表风险发生概率。",
  });
  assert.ok(findingSchema.required.includes("riskScore"));

  const modelDefault = buildGeminiGenerationConfig("gemini-2.0-flash", true, "auto");
  assert.equal(Object.hasOwn(modelDefault, "thinkingConfig"), false);
});

test("Gemini 连接测试通过官方 REST 端点和请求头发送 Key", async () => {
  const env = { CONFIG: new MemoryConfig() };
  await updateAiRiskSettings(env, { apiKey: "test-secret-key" });
  const originalFetch = globalThis.fetch;
  let capturedRequest;
  globalThis.fetch = async (input, init) => {
    capturedRequest = { url: String(input), init };
    return new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: "GEMINI_OK" }] } }],
    }), { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const result = await testGeminiConnection(env);
    assert.deepEqual(result, { ok: true, model: "gemini-3.8-flash" });
    assert.match(capturedRequest.url, /models\/gemini-3\.8-flash:generateContent$/);
    assert.equal(new Headers(capturedRequest.init.headers).get("x-goog-api-key"), "test-secret-key");
    assert.equal(capturedRequest.init.body.includes("test-secret-key"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Gemini Key 按轮询起点使用并在配额错误时切换；模型目录分页且排除非对话模型", async () => {
  const env = { CONFIG: new MemoryConfig() };
  await updateAiRiskSettings(env, { apiKeys: ["key-one", "key-two"] });
  const originalFetch = globalThis.fetch;
  const requestedKeys = [];
  globalThis.fetch = async (input, init) => {
    const apiKey = new Headers(init.headers).get("x-goog-api-key");
    requestedKeys.push(apiKey);
    if (apiKey === "key-two") return new Response("quota exceeded", { status: 429 });
    const url = new URL(String(input));
    if (url.pathname.endsWith(":generateContent")) {
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "GEMINI_OK" }] } }] }), { status: 200 });
    }
    if (!url.searchParams.has("pageToken")) {
      return new Response(JSON.stringify({
        models: [
          { name: "models/gemini-3.8-flash", baseModelId: "gemini-3.8-flash", displayName: "Gemini 3.8 Flash", supportedGenerationMethods: ["generateContent"], inputTokenLimit: 1000 },
          { name: "models/gemini-embedding-001", baseModelId: "gemini-embedding-001", supportedGenerationMethods: ["embedContent"] },
          { name: "models/gemini-2.5-flash-preview-tts", supportedGenerationMethods: ["generateContent"] },
          { name: "models/gemini-3-live", supportedGenerationMethods: ["generateContent", "bidiGenerateContent"] },
          { name: "models/gemini-3-video", supportedGenerationMethods: ["generateContent", "predictLongRunning"] },
          { name: "models/gemma-3-27b-it", supportedGenerationMethods: ["generateContent"] },
        ],
        nextPageToken: "next-page",
      }), { status: 200 });
    }
    return new Response(JSON.stringify({ models: [
      { name: "models/gemini-3.7-flash", baseModelId: "gemini-3.7-flash", displayName: "Gemini 3.7 Flash", supportedGenerationMethods: ["generateContent"] },
    ] }), { status: 200 });
  };
  try {
    await testGeminiConnection(env);
    const result = await listGeminiModels(env);
    assert.deepEqual(result.models.map(model => model.id), ["gemini-3.7-flash", "gemini-3.8-flash"]);
    assert.equal(result.keyCount, 2);
    assert.equal(result.successfulKeyCount, 1);
    assert.equal(result.models.find(model => model.id === "gemini-3.8-flash").availableKeyCount, 1);
    assert.equal(result.models.some(model => model.id.includes("embedding") || model.id.startsWith("gemma-")), false);
    assert.equal(result.models.some(model => model.id.includes("tts") || model.id.includes("live") || model.id.includes("video")), false);
    assert.deepEqual(requestedKeys, ["key-one", "key-one", "key-two", "key-one"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("模型目录合并不同 Key 可访问的模型，并保留思考与 token 元数据", async () => {
  const env = { CONFIG: new MemoryConfig() };
  await updateAiRiskSettings(env, { apiKeys: ["project-a-key", "project-b-key"] });
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_input, init) => {
    const key = new Headers(init.headers).get("x-goog-api-key");
    const model = key === "project-a-key"
      ? { name: "models/gemini-3.8-flash", baseModelId: "gemini-3-base", displayName: "Gemini 3.8 Flash", description: "Fast model", inputTokenLimit: 100000, outputTokenLimit: 8000, thinking: true, supportedGenerationMethods: ["generateContent"] }
      : { name: "models/gemini-2.5-pro", displayName: "Gemini 2.5 Pro", inputTokenLimit: 200000, thinking: true, supportedGenerationMethods: ["generateContent"] };
    return new Response(JSON.stringify({ models: [model] }), { status: 200 });
  };
  try {
    const result = await listGeminiModels(env);
    assert.equal(result.keyCount, 2);
    assert.equal(result.successfulKeyCount, 2);
    assert.deepEqual(result.models.map(model => model.id), ["gemini-2.5-pro", "gemini-3.8-flash"]);
    assert.equal(result.models.find(model => model.id === "gemini-3.8-flash").availableKeyCount, 1);
    assert.equal(result.models.find(model => model.id === "gemini-3.8-flash").supportsThinking, true);
    assert.equal(result.models.find(model => model.id === "gemini-3.8-flash").inputTokenLimit, 100000);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("选中模型仅被部分 Key 提供时，连接测试会在 404 后切换 Key", async () => {
  const env = { CONFIG: new MemoryConfig() };
  await updateAiRiskSettings(env, { apiKeys: ["key-one"] });
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "GEMINI_OK" }] } }] }), { status: 200 });
  try {
    await testGeminiConnection(env);
    await updateAiRiskSettings(env, { apiKeys: ["key-one", "key-two"] });
    const requestedKeys = [];
    globalThis.fetch = async (_input, init) => {
      const key = new Headers(init.headers).get("x-goog-api-key");
      requestedKeys.push(key);
      if (key === "key-one") return new Response(JSON.stringify({ error: { message: "model not found" } }), { status: 404 });
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "GEMINI_OK" }] } }] }), { status: 200 });
    };
    const result = await testGeminiConnection(env);
    assert.equal(result.ok, true);
    assert.deepEqual(requestedKeys, ["key-one", "key-two"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
