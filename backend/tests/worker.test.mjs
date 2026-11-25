import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { createServer } from "node:http";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { SYSTEM_LOG_SETTINGS_KEY, USER_SCHEMA_VERSION } from "../src/config.ts";

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

  await t.test("健康检查初始化当前 schema，CORS 和管理资源可用", async () => {
    const { data } = await call("/health");
    assert.equal(data.database.initialized, true);
    assert.equal(data.database.schemaVersion, USER_SCHEMA_VERSION);
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
    assert.ok(preview.sources.every(source => !Object.hasOwn(source, "minAccountType")));
    assert.equal(preview.failedSources.length, 1);
    assert.deepEqual((await call("/local-admin/api/state", { admin: true })).data, before);
    const allFailed = (await call(route, { method: "POST", admin: true, body: { input: origin + "/failed" }, status: 422 })).data;
    assert.equal(allFailed.sources.length, 0);
  });

  await t.test("游客注册被拒绝且不会创建账户", async () => {
    await call("/api/account/register", { method: "POST", body: { deviceId: 12 }, status: 400 });
    await call("/api/account/register", { method: "POST", body: null, status: 400 });
    await call("/api/account/register", { method: "POST", body: { deviceId }, status: 404 });
    await call("/api/account/sync", { method: "POST", body: { deviceId }, status: 404 });
    await call("/api/account/bind", { method: "POST", body: { deviceId }, status: 404 });
    assert.deepEqual((await call("/local-admin/api/users", { admin: true })).data.users, []);
  });

  await t.test("管理鉴权、KV 保存和账号访问控制", async () => {
    await call("/local-admin/api/state", { status: 401 });
    await call("/local-admin/api/state", {
      method: "PUT", admin: true,
      body: { subscriptions: [{ content: source, name: "formal", minAccountType: "formal", minAppVersion: "1.2" }], settings: { requireAdminRegistrationCode: true } },
    });
    await call(`/api/subscription/1?deviceId=${deviceId}`, { status: 403 });
    await call(`/api/client?deviceId=${deviceId}`, { status: 403 });
  });

  await t.test("注册码直接创建邮箱账户，设备同步及密码登录正常", async () => {
    const credentials = { deviceId, email: "user@example.com", password: "test-password-123", brand: "Test", model: "Phone", androidSdk: 35, appVersion: "1.2.0" };
    await call("/api/account/register", { method: "POST", body: credentials, status: 400 });
    const codes = (await call("/local-admin/api/registration-codes", { method: "POST", admin: true, body: { count: 1 } })).data.codes;
    const bound = await call("/api/account/register", { method: "POST", body: { ...credentials, registrationCode: codes[0] } });
    uid = bound.data.account.uid;
    assert.equal(bound.data.account.accountType, "formal");
    assert.equal(bound.data.account.androidSdk, 35);
    await call("/api/account/register", { method: "POST", body: { ...credentials, deviceId: secondDeviceId, email: "other@example.com", registrationCode: codes[0] }, status: 409 });
    await call("/api/account/register", { method: "POST", body: { ...credentials, registrationCode: codes[0] }, status: 409 });
    assert.equal((await call("/api/account/sync", { method: "POST", body: { deviceId } })).data.account.uid, uid);
    assert.equal((await call("/api/account/register", { method: "POST", body: { deviceId } })).data.account.uid, uid);
    const usedCodes = (await call("/local-admin/api/registration-codes", { admin: true })).data.codes;
    assert.equal(usedCodes[0].usedByUid, uid);
    await call("/api/account/login", { method: "POST", body: { ...credentials, deviceId: secondDeviceId, password: "wrong-password" }, status: 401 });
    const loggedIn = await call("/api/account/login", { method: "POST", body: { ...credentials, appVersion: "", deviceId: secondDeviceId } });
    assert.equal(loggedIn.data.account.uid, uid);
    const client = (await call(`/api/client?deviceId=${deviceId}`)).data;
    assert.equal(Object.hasOwn(client.subscriptions[0], "encrypted"), false);
    const subscription = await call(`/api/subscription/1?deviceId=${deviceId}`);
    assert.equal(subscription.data, source);
    assert.equal(subscription.response.headers.get("x-chimera-client-ip"), null);
    assert.equal(subscription.response.headers.get("x-firefly-client-ip"), null);
    await call(`/api/subscription/1?deviceId=${secondDeviceId}`, { status: 403 });
  });

  await t.test("单次注册码并发使用时只创建一个账号", async () => {
    const codes = (await call("/local-admin/api/registration-codes", { method: "POST", admin: true, body: { count: 1 } })).data.codes;
    const attempts = ["c", "d"].map((letter) => ({
      deviceId: letter.repeat(64), email: `${letter}@example.com`,
      password: "test-password-123", registrationCode: codes[0],
    }));
    const responses = await Promise.all(attempts.map((body) => mf.dispatchFetch("https://worker.test/api/account/register", {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body),
    })));
    assert.deepEqual(responses.map((response) => response.status).sort(), [200, 409]);
    const winner = responses.findIndex((response) => response.status === 200);
    const account = (await responses[winner].json()).account;
    await call("/api/account/delete", { method: "POST", body: {
      deviceId: attempts[winner].deviceId, uid: account.uid, password: attempts[winner].password,
    } });
  });

  await t.test("开启详细请求日志时不会保存账户密码", async () => {
    const kv = await mf.getKVNamespace("CONFIG");
    const db = await mf.getD1Database("USERS_DB");
    await kv.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify({
      categories: { request: true, outbound: false }, details: { requestBody: true },
    }));
    const password = "secret-not-for-logs";
    await call("/api/account/login", { method: "POST", status: 401, body: {
      deviceId, email: "user@example.com", password,
    } });
    let captures = [];
    for (let attempt = 0; attempt < 50 && !captures.length; attempt += 1) {
      captures = (await db.prepare(
        "SELECT p.content FROM system_log_payload_parts_mb p JOIN system_logs_mb l ON l.request_id = p.request_id WHERE l.path = '/api/account/login' ORDER BY l.id DESC",
      ).all()).results;
      if (!captures.length) await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.ok(captures.length > 0);
    assert.equal(captures.some((row) => row.content.includes(password)), false);
    await kv.delete(SYSTEM_LOG_SETTINGS_KEY);
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
    assert.equal(banned.status, "banned");
    await call(`/api/subscription/1?deviceId=${deviceId}`, { status: 403 });
    await call("/api/account/login", { method: "POST", body: { deviceId, email: "user@example.com", password: "test-password-123" }, status: 403 });
    await call(`/local-admin/api/users/${uid}/ban`, { method: "DELETE", admin: true });
    const batch = (await call("/local-admin/api/users/batch", { method: "PATCH", admin: true, body: { uids: [uid], accountType: "active" } })).data;
    assert.equal(batch.changed, 1);
    await call("/api/account/delete", { method: "POST", body: { deviceId, uid, password: "wrong" }, status: 401 });
    await call("/api/account/delete", { method: "POST", body: { deviceId, uid, password: "test-password-123" } });
    assert.deepEqual((await call("/local-admin/api/users", { admin: true })).data.users, []);
  });

  await t.test("旧登录记录接口已移除", async () => {
    await call("/local-admin/api/admin-login-records", { admin: true, status: 404 });
  });
});
