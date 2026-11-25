import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

test("客户端按设备获取可用订阅，下载时再次校验完整权限", async (t) => {
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
    d1Databases: ["USERS_DB", "SUBSCRIPTIONS_DB"],
    bindings: { ADMIN_PATH: "local-admin", ADMIN_TOKEN: "local-test-token" },
  }));
  t.after(() => mf.dispose());

  const subscriptionsDb = await mf.getD1Database("SUBSCRIPTIONS_DB");
  const migrationsDir = fileURLToPath(new URL("../migrations/subscriptions/", import.meta.url));
  for (const file of readdirSync(migrationsDir).filter((name) => name.endsWith(".sql")).sort()) {
    const statements = readFileSync(join(migrationsDir, file), "utf8").split(";")
      .map((sql) => sql.trim()).filter(Boolean);
    await subscriptionsDb.batch(statements.map((sql) => subscriptionsDb.prepare(sql)));
  }

  async function call(path, { method = "GET", body, admin = false, status = 200 } = {}) {
    const response = await mf.dispatchFetch("https://worker.test" + path, {
      method,
      headers: {
        ...(body === undefined ? {} : { "content-type": "application/json" }),
        ...(admin ? { authorization: "Bearer local-test-token" } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    assert.equal(response.status, status, `${method} ${path}: ${text}`);
    return {
      response,
      data: response.headers.get("content-type")?.includes("application/json") ? JSON.parse(text) : text,
    };
  }

  const humanDevice = "a".repeat(64);
  const robotDevice = "b".repeat(64);
  const unregisteredDevice = "c".repeat(64);
  const content = "vless://id@example.com:443#test";
  const state = {
    settings: { websiteUrl: "https://example.com" },
    subscriptionGroups: [
      { id: "human", allowedIdentityTypes: ["human"], subscriptions: [{ id: "11", name: "真人线路", content }] },
      { id: "robot", allowedIdentityTypes: ["bot"], subscriptions: [{ id: "22", name: "机器人线路", content }] },
      { id: "vip", allowedMembershipTypes: ["member"], subscriptions: [{ id: "33", name: "会员线路", content }] },
      { id: "common", subscriptions: [{ id: "44", name: "公共账号线路", content }] },
      { id: "version", minAppVersion: "1.2", maxAppVersion: "1.2.0", subscriptions: [{ id: "55", name: "版本线路", content }] },
      { id: "disabled", enabled: false, subscriptions: [{ id: "66", content }] },
      { id: "disabled-source", subscriptions: [{ id: "77", enabled: false, content }] },
      { id: "multi", name: "Multi-line", subscriptions: [{ id: "88", content }, { id: "89", content }] },
      { id: "banned", allowedStatuses: ["banned"], subscriptions: [{ id: "99", name: "封禁线路", content }] },
    ],
  };
  await call("/api/account/register", { method: "POST", body: { deviceId: humanDevice, email: "human@example.com", password: "test-password", appVersion: "1.2.0" } });
  await call("/api/account/register", { method: "POST", body: { deviceId: robotDevice, email: "robot@example.com", password: "test-password", appVersion: "1.0.0" } });
  const usersDb = await mf.getD1Database("USERS_DB");
  await usersDb.prepare("UPDATE users_mb SET identity_type = 'bot', email = 'robot@example.com', vip_expires_at = ? WHERE id = (SELECT user_id FROM user_devices_mb WHERE device_id = ?)")
    .bind(new Date(Date.now() + 86_400_000).toISOString(), robotDevice).run();
  await call("/local-admin/api/state", { method: "PUT", admin: true, body: state });
  const list = async (deviceId) => (await call(`/api/client?deviceId=${deviceId}`)).data;

  await t.test("设备 ID 必填且必须已注册，查询参数不能伪造权限", async () => {
    assert.equal((await call("/api/client", { status: 401 })).data.error, "device_id_required");
    await call("/api/client?deviceId=invalid", { status: 401 });
    await call(`/api/client?deviceId=${unregisteredDevice}`, { status: 403 });
    const result = await call(`/api/client?deviceId=${humanDevice}&identityType=bot&vipActive=true&accountType=formal`);
    assert.deepEqual(result.data.subscriptions.map((source) => source.id), ["11", "44", "55", "88", "89"]);
    assert.equal(result.response.headers.get("cache-control"), "no-store");
  });

  await t.test("按真人、机器人、会员及版本筛选，身份和权限规则不下发", async () => {
    const human = await list(humanDevice);
    const robot = await list(robotDevice);
    assert.deepEqual(human.subscriptions.map((source) => source.id), ["11", "44", "55", "88", "89"]);
    assert.deepEqual(robot.subscriptions.map((source) => source.id), ["22", "33", "44", "88", "89"]);
    for (const result of [human, robot]) {
      for (const source of result.subscriptions) {
        assert.deepEqual(Object.keys(source).sort(), ["enabled", "id", "name", "url"]);
        const download = await call(new URL(source.url).pathname + `?deviceId=${result === human ? humanDevice : robotDevice}`);
        assert.equal(download.data, content);
      }
      assert.equal(Object.hasOwn(result, "notice"), false);
      assert.equal(Object.hasOwn(result, "appUpdate"), false);
      assert.equal(result.settings.websiteUrl, "https://example.com");
    }
    await call(`/api/subscription/22?deviceId=${humanDevice}`, { status: 403 });
    await call(`/api/subscription/11?deviceId=${robotDevice}`, { status: 403 });
  });

  await t.test("设备版本区间包含边界，并排除过低或过高的版本", async () => {
    for (const version of ["1.1.9", "1.2.1"]) {
      await call("/api/account/sync", { method: "POST", body: { deviceId: humanDevice, appVersion: version } });
      assert.equal((await list(humanDevice)).subscriptions.some((source) => source.id === "55"), false);
      await call(`/api/subscription/55?deviceId=${humanDevice}`, { status: 403 });
    }
    await call("/api/account/sync", { method: "POST", body: { deviceId: humanDevice, appVersion: "1.2.0" } });
    assert.equal((await list(humanDevice)).subscriptions.some((source) => source.id === "55"), true);
  });

  await t.test("账号或设备封禁后拒绝订阅请求", async () => {
    await usersDb.prepare("INSERT INTO device_bans_mb (device_row_id, source_uid, reason, banned_at) SELECT d.id, u.uid, 'test_device_ban', ? FROM user_devices_mb d JOIN users_mb u ON u.id = d.user_id WHERE d.device_id = ?")
      .bind(new Date().toISOString(), humanDevice).run();
    await call(`/api/client?deviceId=${humanDevice}`, { status: 403 });
    await usersDb.prepare("UPDATE device_bans_mb SET lifted_at = ? WHERE reason = 'test_device_ban'")
      .bind(new Date().toISOString()).run();
    await usersDb.prepare("UPDATE users_mb SET status = 'banned' WHERE id = (SELECT user_id FROM user_devices_mb WHERE device_id = ?)").bind(humanDevice).run();
    await call(`/api/client?deviceId=${humanDevice}`, { status: 403 });
    await usersDb.prepare("UPDATE users_mb SET status = 'active' WHERE id = (SELECT user_id FROM user_devices_mb WHERE device_id = ?)").bind(humanDevice).run();
  });

  await t.test("获取列表后权限被撤销仍拒绝下载，空列表仍返回客户端设置", async () => {
    assert.ok((await list(humanDevice)).subscriptions.length > 0);
    const updated = { ...state, subscriptionGroups: state.subscriptionGroups.map((group) => ({ ...group, allowedIdentityTypes: [] })) };
    await call("/local-admin/api/state", { method: "PUT", admin: true, body: updated });
    await call(`/api/subscription/11?deviceId=${humanDevice}`, { status: 403 });
    const empty = await list(humanDevice);
    assert.deepEqual(empty.subscriptions, []);
    assert.equal(empty.settings.websiteUrl, "https://example.com");
    assert.equal(Object.hasOwn(empty, "notice"), false);
    assert.equal(Object.hasOwn(empty, "appUpdate"), false);
  });
});
