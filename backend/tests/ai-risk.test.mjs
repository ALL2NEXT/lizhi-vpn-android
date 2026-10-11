import test from "node:test";
import assert from "node:assert/strict";
import {
  AiRiskError,
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
  });
  assert.equal(JSON.stringify(first).includes("test-secret-key"), false);

  const second = await updateAiRiskSettings(env, { model: "gemini-3.5-flash-lite", apiKey: "" });
  assert.equal(second.apiKeyConfigured, true);
  assert.equal((await readAiRiskSettings(env)).model, "gemini-3.5-flash-lite");
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
    assert.equal(capturedRequest.init.headers["x-goog-api-key"], "test-secret-key");
    assert.equal(capturedRequest.init.body.includes("test-secret-key"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
