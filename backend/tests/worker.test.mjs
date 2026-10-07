import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { createServer } from "node:http";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

test("本地 Worker 的账号、订阅和管理接口", async (t) => {
  const buildDir = fileURLToPath(new URL("../.wrangler/build/", import.meta.url));
  const assets = readdirSync(buildDir).filter((name) => /\.(html|css|client\.js)$/.test(name));
  const mf = new Miniflare(convertV4MiniflareOptions({
    cf: false,
    modulesRoot: buildDir,
    modules: [
      { type: "ESModule", path: join(buildDir, "serverless.js") },
      ...assets.map((name) => ({ type: "Text", path: join(buildDir, name) })),
    ],
    compatibilityDate: "2026-08-14",
    compatibilityFlags: ["nodejs_compat"],
    kvNamespaces: ["CONFIG"],
    d1Databases: ["USERS_DB"],
    bindings: { ADMIN_PATH: "local-admin", ADMIN_TOKEN: "local-test-token" },
  }));
  t.after(() => mf.dispose());

  async function call(path, { method = "GET", body, admin = false, status = 200 } = {}) {
    const response = await mf.dispatchFetch("https://worker.test" + path, {
      method,
      headers: {
        "CF-Connecting-IP": "192.0.2.65",
        ...(body === undefined ? {} : { "content-type": "application/json" }),
        ...(admin ? { authorization: "Bearer local-test-token" } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const content = await response.text();
    assert.equal(response.status, status, `${method} ${path}: ${content}`);
    return {
      response,
      data: response.headers.get("content-type")?.includes("application/json") ? JSON.parse(content) : content,
    };
  }

  const deviceId = "a".repeat(64);
  const secondDeviceId = "b".repeat(64);
  const source = "vless://id@example.com:443#test";
  let uid;
  let debugToken;

  await t.test("健康检查初始化 schema v12，CORS 和管理资源可用", async () => {
    const { data } = await call("/health");
    assert.equal(data.database.initialized, true);
    assert.equal(data.database.schemaVersion, 12);
    const { response } = await call("/api/client", { method: "OPTIONS", status: 204 });
    assert.equal(response.headers.get("access-control-allow-origin"), "*");
    const html = (await call("/local-admin")).data;
    assert.ok(html.includes("/local-admin"));
    assert.equal(html.includes("__ADMIN_BASE_PATH__"), false);
    await call("/missing", { status: 404 });
  });

  await t.test("智能导入遵循管理鉴权，只预览不保存，推送后可下发转换的节点", async t => {
    const upstream = createServer((request, response) => {
      if (request.url === "/failed") { response.writeHead(503); response.end("unavailable"); return; }
      response.writeHead(200, { "content-type": "application/yaml", "profile-title": "Remote YAML" });
      response.end("proxies:\n  - type: trojan\n    name: yaml-node\n    server: yaml.example\n    port: 443\n    password: secret");
    });
    await new Promise(resolve => upstream.listen(0, "127.0.0.1", resolve));
    t.after(() => new Promise(resolve => upstream.close(resolve)));
    const origin = `http://127.0.0.1:${upstream.address().port}`;
    const route = "/local-admin/api/subscriptions/import";
    await call(route, { method: "POST", body: { input: source }, status: 401 });
    await call(route, { method: "POST", admin: true, body: { input: 12 }, status: 400 });
    await call(route, { method: "POST", admin: true, body: { input: "no nodes" }, status: 400 });
    const initial = (await call("/local-admin/api/state", { admin: true })).data;
    const before = (await call("/local-admin/api/state", { method: "PUT", admin: true, body: initial })).data.state;
    const preview = (await call(route, { method: "POST", admin: true, body: { input: source + `\n${origin}/sub\n${origin}/failed`, minAccountType: "banned" } })).data;
    assert.equal(preview.ok, true);
    assert.equal(preview.nodeCount, 2);
    assert.equal(preview.sources.length, 2);
    assert.equal(preview.sources[1].name, "Remote YAML");
    assert.equal(preview.sources[1].url, origin + "/sub");
    assert.equal(preview.sources[1].content, "");
    assert.ok(preview.sources.every(source => source.minAccountType === "banned"));
    assert.equal(preview.failedSources.length, 1);
    assert.deepEqual((await call("/local-admin/api/state", { admin: true })).data, before);
    const allFailed = (await call(route, { method: "POST", admin: true, body: { input: origin + "/failed" }, status: 422 })).data;
    assert.equal(allFailed.sources.length, 0);
    const saved = (await call("/local-admin/api/state", { method: "PUT", admin: true, body: { ...before, subscriptions: preview.sources } })).data.state;
    saved.settings.debugInterface.subscriptionIds = ["1", "2"];
    await call("/local-admin/api/state", { method: "PUT", admin: true, body: saved });
    const combined = (await call("/api/subscription/debug/" + saved.settings.debugInterface.token)).data;
    assert.ok(combined.includes(source));
    assert.ok(combined.includes("@yaml.example:443"));
    await call("/local-admin/api/state", { method: "PUT", admin: true, body: before });
  });

  await t.test("游客注册可重复调用，非法 JSON 字段不会绕过校验", async () => {
    await call("/api/account/register", { method: "POST", body: { deviceId: 12 }, status: 400 });
    await call("/api/account/register", { method: "POST", body: null, status: 400 });
    const { data } = await call("/api/account/register", {
      method: "POST", body: { deviceId, brand: "Test", model: "Phone", androidSdk: 35, appVersion: "1.2.0" },
    });
    uid = data.account.uid;
    assert.equal(data.account.accountType, "guest");
    assert.equal(data.account.androidSdk, 35);
    const repeat = await call("/api/account/register", { method: "POST", body: { deviceId } });
    assert.equal(repeat.data.account.uid, uid);
  });

  await t.test("管理鉴权、KV 保存、调试订阅和账号访问控制", async () => {
    await call("/local-admin/api/state", { status: 401 });
    const { data } = await call("/local-admin/api/state", {
      method: "PUT", admin: true,
      body: { subscriptions: [{ content: source, name: "formal", minAccountType: "formal", minAppVersion: "1.2" }], settings: { requireAdminRegistrationCode: true } },
    });
    debugToken = data.state.settings.debugInterface.token;
    const state = data.state;
    state.settings.debugInterface.subscriptionIds = ["1"];
    await call("/local-admin/api/state", { method: "PUT", admin: true, body: state });
    await call(`/api/subscription/1?deviceId=${deviceId}`, { status: 403 });
    assert.equal((await call("/api/subscription/debug/" + debugToken)).data, source);
    const client = (await call("/api/client")).data;
    assert.equal("debugInterface" in client.settings, false);
    assert.equal(client.subscriptions[0].encrypted, true);
  });

  await t.test("一次性注册码绑定邮箱，密码登录附加第二台设备", async () => {
    const credentials = { deviceId, uid, email: "user@example.com", password: "test-password-123" };
    await call("/api/account/bind", { method: "POST", body: credentials, status: 400 });
    const codes = (await call("/local-admin/api/registration-codes", { method: "POST", admin: true, body: { count: 1 } })).data.codes;
    const bound = await call("/api/account/bind", { method: "POST", body: { ...credentials, registrationCode: codes[0] } });
    assert.equal(bound.data.account.accountType, "formal");
    const usedCodes = (await call("/local-admin/api/registration-codes", { admin: true })).data.codes;
    assert.equal(usedCodes[0].usedByUid, uid);
    await call("/api/account/login", { method: "POST", body: { ...credentials, deviceId: secondDeviceId, password: "wrong-password" }, status: 401 });
    const loggedIn = await call("/api/account/login", { method: "POST", body: { ...credentials, deviceId: secondDeviceId } });
    assert.equal(loggedIn.data.account.uid, uid);
    const subscription = await call(`/api/subscription/1?deviceId=${deviceId}`);
    assert.notEqual(subscription.data, source);
    assert.equal(subscription.response.headers.get("x-chimera-client-ip"), "192.0.2.65");
    assert.equal(subscription.response.headers.get("x-firefly-client-ip"), "192.0.2.65");
    await call(`/api/subscription/1?deviceId=${secondDeviceId}`, { status: 403 });
  });

  await t.test("重复上报以会话最大值更新，报表 SQL 和账号流量一致", async () => {
    const time = Date.now() - 60000;
    const report = { deviceId, sessionId: "session-1", startedAt: time - 60000, endedAt: time, uploadBytes: 1048576, downloadBytes: 2097152 };
    await call("/api/account/report", { method: "POST", body: report });
    await call("/api/account/report", { method: "POST", body: { ...report, uploadBytes: 1 } });
    const profile = (await call("/api/account/profile", { method: "PATCH", body: { deviceId, nickname: "Tester" } })).data.account;
    assert.equal(profile.traffic.total.uploadBytes, 1048576);
    assert.equal(profile.traffic.total.downloadBytes, 2097152);
    for (const suffix of ["users", "users/growth", "users/traffic", "users/activity", "dashboard/devices", "device-relations", "devices", "security/accounts"]) {
      assert.equal((await call("/local-admin/api/" + suffix, { admin: true })).data.ok, true);
    }
    const edited = await call(`/local-admin/api/users/${uid}`, { method: "PATCH", admin: true, body: { model: "Edited", nickname: "Admin edit" } });
    assert.equal(edited.data.user.model, "Edited");
    assert.equal(edited.data.user.nickname, "Admin edit");
    await call("/api/account/report", { method: "POST", body: { ...report, endedAt: Date.now() + 600000 }, status: 400 });
  });

  await t.test("封禁关联设备并撤销封禁，批量更新和密码删除可用", async () => {
    const banned = (await call(`/local-admin/api/users/${uid}/ban`, { method: "POST", admin: true })).data;
    assert.equal(banned.blockedDeviceCount, 2);
    await call(`/api/subscription/1?deviceId=${deviceId}`, { status: 403 });
    await call("/api/account/login", { method: "POST", body: { deviceId, email: "user@example.com", password: "test-password-123" }, status: 403 });
    await call(`/local-admin/api/users/${uid}/ban`, { method: "DELETE", admin: true });
    const batch = (await call("/local-admin/api/users/batch", { method: "PATCH", admin: true, body: { uids: [uid], accountType: "active" } })).data;
    assert.equal(batch.changed, 1);
    await call("/api/account/delete", { method: "POST", body: { deviceId, uid, password: "wrong" }, status: 401 });
    await call("/api/account/delete", { method: "POST", body: { deviceId, uid, password: "test-password-123" } });
    assert.deepEqual((await call("/local-admin/api/users", { admin: true })).data.users, []);
  });

  await t.test("蜜罐固定拒绝登录，记录可读取和清空", async () => {
    await call("/admin/login", { method: "POST", body: {}, status: 401 });
    const { data } = await call("/local-admin/api/admin-login-records", { admin: true });
    assert.ok(data.records.some((record) => record.honeypot && record.path === "/admin/login"));
    await call("/local-admin/api/admin-login-records", { method: "DELETE", admin: true });
    assert.deepEqual((await call("/local-admin/api/admin-login-records", { admin: true })).data.records, []);
  });
});
