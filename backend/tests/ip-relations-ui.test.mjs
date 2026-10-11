import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";

const script = readFileSync(new URL("../src/admin-ui/scripts/ip-relations.client.js", import.meta.url), "utf8");
const uid = "11111111-1111-4111-8111-111111111111";

function harness() {
  const elements = new Map();
  function el(id) {
    if (!elements.has(id)) elements.set(id, {
      value: "", textContent: "", innerHTML: "", disabled: false, listeners: {},
      setAttribute() {}, querySelectorAll() { return []; },
      addEventListener(type, fn) { this.listeners[type] = fn; },
    });
    return elements.get(id);
  }
  el("ipRelationsDirection").value = "ip";
  const calls = [];
  let handler = async () => response({ total: 0, stats: {}, relations: [], limit: 50, offset: 0 });
  const context = vm.createContext({
    el, value: id => el(id).value.trim(), URLSearchParams,
    ADMIN_API_BASE: "/local-admin/api", headers: () => ({ authorization: "Bearer test-token" }),
    parseResponseJson: JSON.parse,
    systemLogDateTime: value => value || "-",
    esc: value => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]),
    show() {}, activateTab() {},
    fetch: (url, options) => { calls.push({ url, options }); return handler(url, options); },
  });
  vm.runInContext(script, context);
  return { el, calls, run: code => vm.runInContext(code, context), setHandler: fn => { handler = fn; } };
}
const response = (data, status = 200) => ({ ok: status === 200, status, text: async () => JSON.stringify(data) });

test("关联页按 IP/用户构造查询，方向切换与分页保留正确条件", async () => {
  const h = harness();
  h.setHandler(async url => {
    const params = new URL(url, "https://test").searchParams;
    return response({ total: 51, stats: { ipCount: 2, userCount: 3, requestCount: 7 }, relations: [], limit: 50, offset: Number(params.get("offset")) });
  });
  h.el("ipRelationsQuery").value = "192.0.2.65";
  await h.run("loadIpRelations(true)");
  let params = new URL(h.calls.at(-1).url, "https://test").searchParams;
  assert.equal(params.get("ip"), "192.0.2.65");
  assert.equal(params.has("uid"), false);
  assert.equal(h.calls.at(-1).options.headers.authorization, "Bearer test-token");
  await h.run("changeIpRelationsPage(1)");
  await new Promise(setImmediate);
  params = new URL(h.calls.at(-1).url, "https://test").searchParams;
  assert.equal(params.get("offset"), "50");
  h.el("ipRelationsDirection").value = "user";
  h.el("ipRelationsQuery").value = uid;
  await h.run("loadIpRelations(true)");
  params = new URL(h.calls.at(-1).url, "https://test").searchParams;
  assert.equal(params.get("uid"), uid);
  assert.equal(params.get("offset"), "0");
  assert.equal(params.has("ip"), false);
  assert.ok(h.el("ipRelationsSummary").textContent.includes("用户 3 个"));
});

test("慢查询不会覆盖最新结果，退出登录会清空并拒绝迟到结果", async () => {
  const h = harness();
  const pending = [];
  h.setHandler(() => new Promise(resolve => pending.push(resolve)));
  const first = h.run("loadIpRelations(true)");
  h.el("ipRelationsDirection").value = "user";
  h.el("ipRelationsQuery").value = uid;
  const second = h.run("loadIpRelations(true)");
  pending[1](response({ total: 2, stats: { userCount: 1, requestCount: 17 }, relations: [] }));
  await second;
  pending[0](response({ total: 99, stats: { userCount: 99 }, relations: [] }));
  await first;
  assert.ok(h.el("ipRelationsSummary").textContent.includes("用户 1 个"));
  assert.ok(h.el("ipRelationsSummary").textContent.includes("17 次"));
  const late = h.run("loadIpRelations(true)");
  h.run("resetIpRelations()");
  pending[2](response({ total: 99, stats: {}, relations: [] }));
  await late;
  assert.equal(h.run("ipRelationsData"), null);
  assert.equal(h.el("ipRelationsTable").innerHTML, "");
  assert.equal(h.el("ipRelationsQuery").value, "");
  assert.equal(h.el("ipRelationsRefresh").disabled, false);
});

test("关联记录正确转义，查询参数错误可提示并重新提交", async () => {
  const h = harness();
  h.setHandler(async () => response({
    total: 1, stats: { ipCount: 1, userCount: 1, requestCount: 4 },
    relations: [{ ip: "192.0.2.65", userUid: uid, nickname: "<img onerror='alert(1)'>", accountStatus: "deleted", requestCount: 4, subscriptionCount: 2, loginCount: 1 }],
  }));
  await h.run("loadIpRelations(true)");
  assert.ok(h.el("ipRelationsTable").innerHTML.includes("&lt;img"));
  assert.equal(h.el("ipRelationsTable").innerHTML.includes("<img"), false);
  assert.ok(h.el("ipRelationsTable").innerHTML.includes("已删除"));
  h.setHandler(async () => response({ error: "invalid_ip" }, 400));
  await h.run("loadIpRelations(true)");
  assert.ok(h.el("ipRelationsTable").innerHTML.includes("请输入有效的完整 IP 地址"));
  assert.equal(h.el("ipRelationsRefresh").disabled, false);
});
