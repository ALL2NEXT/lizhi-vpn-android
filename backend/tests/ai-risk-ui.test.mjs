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
  for (const id of ["aiRiskEnabled", "aiRiskModel", "aiRiskThinkingLevel", "aiRiskApiKeys", "aiRiskPromptTemplate", "aiRiskPromptReset", "aiRiskLoadModels", "aiRiskSave", "aiRiskTest", "aiRiskAnalyze", "aiRiskPreview", "aiRiskRefresh", "aiRiskSettingsPanel", "aiRiskSettingsToggle", "aiRiskSearch"]) {
    assert.ok(documentHtml.includes(`id="${id}"`), `缺少 ${id}`);
  }
  assert.ok(documentHtml.includes('data-tab="ai-risk"'));
  assert.ok(documentHtml.includes('data-ai-risk-filter="pending"'));
});

test("dashboard data route populates the IP risk view", async () => {
  const h = harness();
  h.setHandler(async url => {
    if (url.includes("/ai-risk/dashboard-data")) return {
      ok: true,
      dataset: {
        generatedAt: "2026-10-11T21:00:00+08:00",
        summary: {requestCount: 12},
        coverage: {requestRowsTruncated: false},
        subjects: [{ip: "203.0.113.9", uid: "acct-1", requestCount: 12, subscriptionRequests: 9, successfulSubscriptions: 8, clientErrors: 1, serverErrors: 0, firstSeenAt: "2026-10-11T08:00:00+08:00", lastSeenAt: "2026-10-11T21:00:00+08:00"}],
        hourlyActivity: [{ip: "203.0.113.9", hours: [[2, 3, 2, 0], [8, 9, 7, 1]]}],
        endpointActivity: [{ip: "203.0.113.9", method: "GET", path: "/api/subscription/list", requests: 9, successfulRequests: 8, clientErrors: 1, serverErrors: 0}],
        ipRelations: [{ip: "203.0.113.9", knownAccountCount: 1, users: [{uid: "acct-1", requestCount: 12, subscriptionCount: 9, loginCount: 1, lastSeenAt: "2026-10-11T21:00:00+08:00", accountStatus: "active"}]}],
      },
    };
    return {ok: true};
  });
  h.el("aiRiskWindowHours").value = "24";
  await h.run("loadAiRiskDashboard(false)");
  assert.ok(h.calls.some(call => call.url.includes("/ai-risk/dashboard-data?windowHours=24")));
  assert.equal(h.el("aiRiskMetricIps").textContent, "1");
  assert.equal(h.el("aiRiskMetricRequests").textContent, "12");
  assert.ok(h.el("aiRiskResults").innerHTML.includes("203.0.113.9"));
  assert.ok(h.el("aiRiskResults").innerHTML.includes('data-ip="203.0.113.9"'));
  assert.equal(h.el("aiRiskResults").innerHTML.includes("/api/subscription/list"), false);
});

test("IP 风险卡片使用模型分数，并通过 IP 标识处理展开事件", () => {
  const h = harness();
  h.run(`aiRiskDashboard = {
    summary: {requestCount: 12}, coverage: {},
    subjects: [{ip: "203.0.113.9", uid: "acct-1", requestCount: 12, subscriptionRequests: 9, successfulSubscriptions: 8, clientErrors: 1, serverErrors: 0, firstSeenAt: "", lastSeenAt: ""}],
    hourlyActivity: [], endpointActivity: [],
    ipRelations: [{ip: "203.0.113.9", knownAccountCount: 1, users: [{uid: "acct-1", requestCount: 12, subscriptionCount: 9, loginCount: 1, lastSeenAt: "", accountStatus: "active"}] }],
  }; aiRiskAnalysis = {findings: [{ip: "203.0.113.9", uids: ["acct-1"], riskLevel: "medium", riskScore: 65, title: "review", rationale: "reason", evidence: [], recommendation: "review"}]}; aiRiskRenderDashboard()`);
  let rendered = h.el("aiRiskResults").innerHTML;
  assert.ok(rendered.includes("65/100"));
  assert.ok(rendered.includes("建议人工复核"));
  assert.ok(rendered.includes("<p>reason</p>"));
  assert.ok(rendered.includes("<p>review</p>"));
  assert.ok(rendered.includes('data-ip="203.0.113.9"'));
  h.run("aiRiskAnalysis.findings[0].riskScore = 101; aiRiskRenderDashboard()");
  rendered = h.el("aiRiskResults").innerHTML;
  assert.equal(rendered.includes("101/100"), false);
  assert.ok(rendered.includes("风险级别"));
  h.run("aiRiskAnalysis.findings[0].riskScore = 65; aiRiskRenderDashboard()");
  h.run(`aiRiskHandlePageClick({target: {closest: () => ({
    getAttribute: name => name === "data-ai-risk-action" ? "expand" : name === "aria-expanded" ? "true" : null,
    closest: () => ({getAttribute: () => "203.0.113.9"}),
  })}})`);
  rendered = h.el("aiRiskResults").innerHTML;
  assert.equal(rendered.includes('class="ai-risk-ip-card risk-border-medium expanded"'), false);
});

test("加载配置不回填 API Key，保存提交多 Key 输入并保留掩码", async () => {
  const h = harness();
  const defaultPrompt = "默认建议 {{DATASET_JSON}}";
  const savedPrompt = "自定义分析 {{DATASET_JSON}}";
  h.setHandler(async (url, options) => {
    if (url.endsWith("/ai-risk/settings") && options.method !== "PUT") return { ok: true, settings: { enabled: true, model: "gemini-3.8-flash", apiKeyConfigured: true, apiKeyCount: 2, promptTemplate: defaultPrompt, defaultPromptTemplate: defaultPrompt } };
    return { ok: true, settings: { enabled: true, model: "gemini-3.8-flash", apiKeyConfigured: true, apiKeyCount: 2, promptTemplate: savedPrompt, defaultPromptTemplate: defaultPrompt } };
  });
  await h.run("loadAiRiskSettings(true)");
  assert.equal(h.el("aiRiskApiKeys").value, "");
  assert.equal(h.el("aiRiskThinkingLevel").value, "medium");
  assert.equal(h.el("aiRiskPromptTemplate").value, defaultPrompt);
  assert.match(h.el("aiRiskApiKeys").placeholder, /2 个 Key/);
  h.el("aiRiskApiKeys").value = "new-key-1,new-key-2";
  h.el("aiRiskPromptTemplate").value = savedPrompt;
  await h.run("saveAiRiskSettings()");
  const body = JSON.parse(h.calls.at(-1).options.body);
  assert.equal(body.apiKeys, "new-key-1,new-key-2");
  assert.equal(body.thinkingLevel, "medium");
  assert.equal(body.promptTemplate, savedPrompt);
  assert.equal(h.el("aiRiskPromptTemplate").value, savedPrompt);
  assert.equal(h.el("aiRiskApiKeys").value, "");

  h.el("aiRiskPromptReset").listeners.click();
  assert.equal(h.el("aiRiskPromptTemplate").value, defaultPrompt);
  assert.match(h.el("aiRiskSettingsStatus").textContent, /点击“保存配置”后生效/);
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
  h.run("aiRiskSettingsLoaded = true; aiRiskDashboard = {subjects: [], ipRelations: [], hourlyActivity: [], endpointActivity: [], summary: {requestCount: 0}, coverage: {}}");
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
  h.run('aiRiskActiveViews["<img src=x>"] = "accounts"; aiRiskRenderDashboard()');
  const rendered = h.el("aiRiskResults").innerHTML;
  assert.ok(rendered.includes("&lt;img"));
  assert.ok(rendered.includes("&lt;svg"));
  assert.equal(rendered.includes("<img"), false);
  assert.equal(rendered.includes("<script>"), false);
  assert.ok(rendered.includes("需人工复核"));
  assert.match(h.el("aiRiskAnalysisStatus").textContent, /输入 120 \/ 输出 45 \/ 思考 20 tokens/);
});
