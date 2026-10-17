import assert from "node:assert/strict";
import test from "node:test";
import {
  accountTypeForUser, canAccessAccountType, normalizeAccountId,
  normalizeAccountPassword, normalizeRegistrationCode, reportBytes,
} from "../src/domain/account.ts";
import {
  buildUserActivityPoints, buildUserGrowthPoints, buildUserTrafficPoints,
  normalizeGrowthDateKey,
} from "../src/domain/analytics.ts";
import { beijingTimestamp, normalizeReportTimestamp, usagePeriodKeys } from "../src/domain/time.ts";
import { readJsonObject } from "../src/http/request.ts";
import { DEFAULT_SUBSCRIPTION_CACHE_MINUTES, MAX_MANAGED_SUBSCRIPTION_BYTES } from "../src/config.ts";
import {
  autoRenameSubscriptionContent, decodeExternalSubscriptionContent,
  isAppVersionAtLeast, normalizeSubscriptionCacheMinutes,
} from "../src/services/subscriptions.ts";
import { normalizeState, readState, writeState } from "../src/services/state.ts";

const now = new Date("2026-10-07T02:00:00Z");
const node = "vless://id@example.com:443#日本";

test("账号输入限制、等级访问和封禁优先级", () => {
  assert.equal(normalizeAccountId(" A" + "a".repeat(63) + " "), "a".repeat(64));
  assert.equal(normalizeAccountId({ deviceId: "a".repeat(64) }), "");
  assert.equal(normalizeAccountPassword("short"), "");
  assert.equal(normalizeAccountPassword("  password  "), "  password  ");
  assert.equal(normalizeRegistrationCode("reg-abcd-efgh-jkmn"), "REGABCDEFGHJKMN");
  assert.equal(accountTypeForUser({ email: "user@example.com", deviceBlocked: true }), "banned");
  assert.equal(accountTypeForUser({ email: "user@example.com" }), "formal");
  assert.equal(canAccessAccountType("guest", "formal"), false);
  assert.equal(canAccessAccountType("formal", "guest"), true);
});

test("流量兼容旧 MB 字段，字节字段优先且有上限", () => {
  assert.equal(reportBytes(undefined, 2), 2 * 1024 * 1024);
  assert.equal(reportBytes(0, 2), 0);
  assert.equal(reportBytes(-1, 2), 0);
  assert.equal(reportBytes(2e15, 2), 1e15);
});

test("北京时间跨日、跨月和非法上报时间", () => {
  const boundary = new Date("2026-09-30T16:00:00Z");
  assert.deepEqual(usagePeriodKeys(boundary), {
    day: "2026-10-01", hour: "2026-10-01T00", month: "2026-10",
  });
  assert.equal(beijingTimestamp(boundary), "2026-10-01T00:00:00+08:00");
  assert.equal(normalizeReportTimestamp("bad-date"), null);
  assert.equal(normalizeReportTimestamp(null, "fallback"), "fallback");
  assert.equal(normalizeGrowthDateKey("2026-02-30"), null);
  assert.equal(normalizeGrowthDateKey("2024-02-29"), "2024-02-29");
});

test("增长补齐无新增日期并保留查询范围之前的累计人数", () => {
  assert.deepEqual(buildUserGrowthPoints([
    { date_key: "2026-10-01", new_users: 5 },
    { date_key: "2026-10-03", new_users: 2 },
    { date_key: "2026-10-05", new_users: 1 },
  ], "day", now, "2026-10-03", "2026-10-05"), [
    { key: "2026-10-03", newUsers: 2, totalUsers: 7 },
    { key: "2026-10-04", newUsers: 0, totalUsers: 7 },
    { key: "2026-10-05", newUsers: 1, totalUsers: 8 },
  ]);
});

test("同一用户一周内的活跃记录去重，流量按周累计", () => {
  assert.deepEqual(buildUserActivityPoints([
    { date_key: "2026-10-05", user_id: 1 },
    { date_key: "2026-10-06", user_id: 1 },
    { date_key: "2026-10-06", user_id: 2 },
    { date_key: "2026-10-07", user_id: null },
  ], "week", now), [{ key: "2026-10-05", activeUsers: 2 }]);
  assert.deepEqual(buildUserTrafficPoints([
    { date_key: "2026-10-05", upload_bytes: 1048576, download_bytes: 2097152 },
    { date_key: "2026-10-06", upload_bytes: 1048576, download_bytes: 0 },
  ], "week", now), [{ key: "2026-10-05", uploadMb: 2, downloadMb: 2, totalMb: 4 }]);
});

test("订阅解码支持明文、UTF-8 Base64 和 URL-safe Base64", () => {
  const encoded = Buffer.from(node).toString("base64");
  assert.equal(decodeExternalSubscriptionContent(node), node);
  assert.equal(decodeExternalSubscriptionContent(encoded), node);
  assert.equal(decodeExternalSubscriptionContent(encoded.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")), node);
  assert.throws(() => decodeExternalSubscriptionContent("garbage"), /subscription_must_be_plaintext_nodes/);
  assert.throws(() => decodeExternalSubscriptionContent("x".repeat(MAX_MANAGED_SUBSCRIPTION_BYTES + 1)), /managed_subscription_too_large/);
});

test("节点国家重命名兼容 VMess，并保留非节点行", () => {
  const vmess = "vmess://" + Buffer.from(JSON.stringify({ ps: "US west", add: "example.com" })).toString("base64");
  const renamed = autoRenameSubscriptionContent(node + "\n" + node + "\n# comment\n" + vmess).split("\n");
  assert.equal(decodeURIComponent(renamed[0].split("#")[1]), "日本1");
  assert.equal(decodeURIComponent(renamed[1].split("#")[1]), "日本2");
  assert.equal(renamed[2], "# comment");
  assert.equal(JSON.parse(Buffer.from(renamed[3].slice(8), "base64").toString()).ps, "美国1");
});

test("version comparison and cache bounds", () => {
  assert.equal(isAppVersionAtLeast("v1.10", "1.2.0"), true);
  assert.equal(isAppVersionAtLeast("1.2", "1.2.1"), false);
  assert.equal(isAppVersionAtLeast("bad", "1"), false);
  assert.equal(normalizeSubscriptionCacheMinutes(""), DEFAULT_SUBSCRIPTION_CACHE_MINUTES);
  assert.equal(normalizeSubscriptionCacheMinutes(-1), 0);
  assert.equal(normalizeSubscriptionCacheMinutes(1e8), 10080);
});

test("旧 KV 字段可迁移，内联订阅不会自动重命名", () => {
  const state = normalizeState({
    sources: [null, { title: "line", nodesText: node, autoRename: true, minimumAccountType: "formal" }],
  });
  assert.deepEqual(state.subscriptionGroups[0].allowedAccountTypes, ["formal"]);
  assert.equal(state.subscriptionGroups[0].subscriptions[0].autoRename, false);
  assert.equal(state.settings.registrationEnabled, true);
  assert.equal(normalizeState(null).subscriptionGroups.length, 0);
});

test("旧公告和客户端更新字段不会进入归一化状态", () => {
  const state = normalizeState({
    notice: { hasNotice: true, title: "旧公告" },
    appUpdate: { version: "9.9.9" },
  });
  assert.equal(Object.hasOwn(state, "notice"), false);
  assert.equal(Object.hasOwn(state, "appUpdate"), false);
});

test("KV 使用较新的旧键数据并同步双写", async () => {
  const storage = new Map([
    ["chimera:state:v1", { updatedAt: "2026-10-01", subscriptions: [] }],
    ["fireflyvpn:state:v1", { updatedAt: "2026-10-02", sources: [{ title: "legacy", content: node }] }],
  ]);
  const env = { CONFIG: {
    get: async (key) => storage.get(key) ?? null,
    put: async (key, value) => storage.set(key, JSON.parse(value)),
  } };
  const state = await readState(env);
  assert.equal(state.subscriptionGroups[0].subscriptions[0].name, "legacy");
  await writeState(env, state);
  assert.deepEqual(storage.get("chimera:state:v1"), storage.get("fireflyvpn:state:v1"));
  storage.delete("chimera:state:v1");
  await readState(env);
  assert.equal(storage.get("chimera:state:v1").subscriptionGroups[0].subscriptions[0].name, "legacy");
});

test("JSON 请求字段保持 unknown 边界，非对象输入采用空对象，坏 JSON 抛错", async () => {
  const request = (body) => new Request("https://worker.test", { method: "POST", body });
  assert.deepEqual(await readJsonObject(request('{"deviceId":123}')), { deviceId: 123 });
  assert.deepEqual(await readJsonObject(request("null")), {});
  assert.deepEqual(await readJsonObject(request("[]")), {});
  await assert.rejects(readJsonObject(request("{")), SyntaxError);
});
