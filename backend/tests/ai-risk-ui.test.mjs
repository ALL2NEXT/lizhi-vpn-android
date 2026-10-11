import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";

const script = readFileSync(new URL("../src/admin-ui/scripts/ai-risk.client.js", import.meta.url), "utf8");
const documentHtml = readFileSync(new URL("../src/admin-ui/document.html", import.meta.url), "utf8");

function harness() {
  const elements = new Map();
  function el(id) {
    if (!elements.has(id)) {
      const classes = new Set();
      elements.set(id, {
        value: "", checked: false, disabled: false, placeholder: "", textContent: "", innerHTML: "", hidden: false,
        listeners: {}, classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } },
        addEventListener(type, handler) { this.listeners[type] = handler; },
      });
    }
    return elements.get(id);
  }
  const calls = [];
  let handler = async () => ({ ok: true, settings: { enabled: false, model: "gemini-3.8-flash", apiKeyConfigured: false } });
  const context = vm.createContext({
    el,
    value: id => el(id).value.trim(),
    headers: () => ({ authorization: "Bearer test" }),
    ADMIN_API_BASE: "/local-admin/api",
    parseResponseJson: JSON.parse,
    esc: value => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]),
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      const data = await handler(url, options);
      return { ok: data.ok !== false, status: data.ok === false ? 400 : 200, text: async () => JSON.stringify(data) };
    },
  });
  vm.runInContext(script, context);
  return { el, calls, run: code => vm.runInContext(code, context), setHandler: fn => { handler = fn; } };
}

test("后台包含 Gemini 风控设置、模型获取与分析入口", () => {
  for (const id of ["aiRiskEnabled", "aiRiskModel", "aiRiskThinkingLevel", "aiRiskApiKeys", "aiRiskLoadModels", "aiRiskSave", "aiRiskTest", "aiRiskAnalyze", "aiRiskPreview"]) {
    assert.ok(documentHtml.includes(`id="${id}"`), `缺少 ${id}`);
  }
  assert.ok(documentHtml.includes('data-tab="ai-risk"'));
});

test("加载配置不回填 API Key，保存提交多 Key 输入并保留掩码", async () => {
  const h = harness();
  h.setHandler(async url => {
    if (url.endsWith("/ai-risk/settings")) return { ok: true, settings: { enabled: true, model: "gemini-3.8-flash", apiKeyConfigured: true, apiKeyCount: 2 } };
    return { ok: true, settings: { enabled: true, model: "gemini-3.8-flash", apiKeyConfigured: true, apiKeyCount: 2 } };
  });
  await h.run("loadAiRiskSettings(true)");
  assert.equal(h.el("aiRiskApiKeys").value, "");
  assert.equal(h.el("aiRiskThinkingLevel").value, "medium");
  assert.match(h.el("aiRiskApiKeys").placeholder, /2 个 Key/);
  h.el("aiRiskApiKeys").value = "new-key-1,new-key-2";
  await h.run("saveAiRiskSettings()");
  const body = JSON.parse(h.calls.at(-1).options.body);
  assert.equal(body.apiKeys, "new-key-1,new-key-2");
  assert.equal(body.thinkingLevel, "medium");
  assert.equal(h.el("aiRiskApiKeys").value, "");
});

test("获取模型前先保存配置，并展示 API 返回的 generateContent 模型", async () => {
  const h = harness();
  h.setHandler(async (url, options) => {
    if (url.endsWith("/ai-risk/settings")) {
      return { ok: true, settings: { enabled: false, model: "gemini-3.8-flash", apiKeyConfigured: true, apiKeyCount: 1 } };
    }
    if (url.endsWith("/ai-risk/models")) {
      return { ok: true, keyCount: 2, successfulKeyCount: 2, models: [{ id: "gemini-3.8-flash", displayName: "Gemini 3.8 Flash", description: "Fast model", inputTokenLimit: 100000, outputTokenLimit: 8000, supportsThinking: true, availableKeyCount: 2 }] };
    }
    return { ok: false, error: String(options.method || "GET") };
  });
  h.el("aiRiskApiKeys").value = "test-key";
  h.el("aiRiskThinkingLevel").value = "high";
  h.el("aiRiskModel").value = "gemini-3.8-flash";
  await h.run("loadAiRiskModels()");
  assert.ok(h.calls.some(call => call.url.endsWith("/ai-risk/settings") && call.options.method === "PUT"));
  assert.equal(JSON.parse(h.calls.find(call => call.url.endsWith("/ai-risk/settings") && call.options.method === "PUT").options.body).thinkingLevel, "high");
  assert.ok(h.calls.some(call => call.url.endsWith("/ai-risk/models")));
  assert.match(h.el("aiRiskModelOptions").innerHTML, /gemini-3\.8-flash/);
  assert.match(h.el("aiRiskModelOptions").innerHTML, /2\/2 keys/);
  assert.match(h.el("aiRiskModelStatus").textContent, /1 个/);
  assert.match(h.el("aiRiskModelDetails").textContent, /输入上限 100,000/);
});

test("分析结果转义 IP、UID 与模型文本，且要求人工复核", async () => {
  const h = harness();
  h.el("aiRiskEnabled").checked = true;
  h.el("aiRiskWindowHours").value = "24";
  h.run("aiRiskSettingsLoaded = true");
  h.setHandler(async () => ({
    ok: true,
    model: "gemini-3.8-flash",
    analyzedAt: "now",
    summary: "<script>summary</script>",
    usage: {promptTokenCount: 120, candidatesTokenCount: 45, thoughtsTokenCount: 20},
    findings: [{
      ip: "<img src=x>", uids: ["<svg onload=x>"], riskLevel: "high", title: "<b>suspect</b>",
      rationale: "reason", evidence: ["evidence"], recommendation: "review", requiresManualReview: true,
    }],
  }));
  await h.run("runAiRiskAnalysis()");
  const rendered = h.el("aiRiskResults").innerHTML;
  assert.ok(rendered.includes("&lt;img"));
  assert.ok(rendered.includes("&lt;svg"));
  assert.equal(rendered.includes("<img"), false);
  assert.equal(rendered.includes("<script>"), false);
  assert.ok(rendered.includes("需人工复核"));
  assert.match(h.el("aiRiskAnalysisStatus").textContent, /输入 120 \/ 输出 45 \/ 思考 20 tokens/);
});
