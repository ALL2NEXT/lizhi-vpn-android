import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout } from "node:timers/promises";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { normalizeClientIp } from "../src/domain/ip.ts";
import { initializeUserSchemaDatabase } from "../src/services/user-store.ts";
import { writeUserIpLink } from "../src/services/user-ip-links.ts";
import { SYSTEM_LOG_SETTINGS_KEY, USER_SCHEMA_VERSION } from "../src/config.ts";

test("IP 规范化合并 IPv6 别名，并拒绝无效地址和转发链", () => {
  assert.equal(normalizeClientIp(" 192.0.2.65 "), "192.0.2.65");
  assert.equal(normalizeClientIp("2001:0DB8:0000:0000:0000:0000:0000:0001"), "2001:db8::1");
  assert.equal(normalizeClientIp("::ffff:192.0.2.65"), "192.0.2.65");
  assert.equal(normalizeClientIp("::ffff:c000:241"), "192.0.2.65");
  for (const value of ["", null, {}, "256.1.1.1", "192.0.2.65, 198.51.100.1", "host.test", "::1%eth0", "[::1]", ":::", "1.2.3"]) {
    assert.equal(normalizeClientIp(value), "", String(value));
  }
});

test("IP 与用户关联按成功请求采集，可双向查询并保留历史", async (t) => {
  const buildDir = fileURLToPath(new URL("../.wrangler/build/", import.meta.url));
  const assets = readdirSync(buildDir).filter(name => /\.(html|css|client\.js)$/.test(name));
  const mf = new Miniflare(convertV4MiniflareOptions({
    cf: false,
    modulesRoot: buildDir,
    modules: [
      { type: "ESModule", path: join(buildDir, "serverless.js") },
      ...assets.map(name => ({ type: "Text", path: join(buildDir, name) })),
    ],
    compatibilityDate: "2026-08-14",
    compatibilityFlags: ["nodejs_compat"],
    kvNamespaces: ["CONFIG"],
    d1Databases: ["USERS_DB", "LEGACY_DB"],
    bindings: { ADMIN_PATH: "local-admin", ADMIN_TOKEN: "local-test-token" },
  }));
  t.after(() => mf.dispose());
  const db = await mf.getD1Database("USERS_DB");
  const ip1 = "192.0.2.65";
  const ip2 = "198.51.100.2";
  const ip3 = "203.0.113.3";
  const deviceA = "a".repeat(64);
  const deviceB = "b".repeat(64);
  let uidA;
  let uidB;

  async function call(path, { method = "GET", body, admin = false, status = 200, ip = ip1, extraHeaders = {} } = {}) {
    const response = await mf.dispatchFetch("https://worker.test" + path, {
      method,
      headers: {
        "CF-Connecting-IP": ip,
        ...extraHeaders,
        ...(body === undefined ? {} : { "content-type": "application/json" }),
        ...(admin ? { authorization: "Bearer local-test-token" } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    assert.equal(response.status, status, method + " " + path + ": " + text);
    return response.headers.get("content-type")?.includes("application/json") ? JSON.parse(text) : text;
  }
  async function eventually(check) {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (await check()) return;
      await setTimeout(20);
    }
    assert.fail("后台关联写入未在预期时间内完成");
  }
  const links = (query = "") => call("/local-admin/api/ip-relations" + query, { admin: true });
  const row = (uid, ip) => db.prepare("SELECT * FROM user_ip_links_mb WHERE user_uid = ? AND ip = ?").bind(uid, ip).first();

  await t.test("schema v19 无损升级，旧日志不会被虚构用户关联", async () => {
    const legacy = await mf.getD1Database("LEGACY_DB");
    await legacy.batch([
      legacy.prepare("CREATE TABLE app_schema_mb (component TEXT PRIMARY KEY, version INTEGER, applied_at TEXT)"),
      legacy.prepare("INSERT INTO app_schema_mb VALUES ('users', 19, '2026-10-01')"),
      legacy.prepare("CREATE TABLE users_mb (id INTEGER PRIMARY KEY, uid TEXT UNIQUE, email TEXT)"),
      legacy.prepare("CREATE TABLE user_usage_mb (user_id INTEGER)"),
      legacy.prepare("CREATE TABLE device_account_links_mb (user_uid TEXT)"),
      legacy.prepare("CREATE TABLE device_bans_mb (reason TEXT, lifted_at TEXT)"),
      legacy.prepare("INSERT INTO users_mb VALUES (1, 'guest-uid', NULL), (2, 'formal-uid', 'user@example.com')"),
      legacy.prepare("INSERT INTO user_usage_mb (user_id) VALUES (1), (2)"),
      legacy.prepare("INSERT INTO device_account_links_mb VALUES ('guest-uid'), ('formal-uid')"),
      legacy.prepare("INSERT INTO device_bans_mb VALUES ('manual', NULL)"),
      legacy.prepare("CREATE TABLE system_logs_mb (id INTEGER PRIMARY KEY, occurred_at TEXT, ip TEXT, level TEXT, category TEXT, request_id TEXT)"),
      legacy.prepare("INSERT INTO system_logs_mb VALUES (1, '2026-10-01T00:00:00+08:00', '192.0.2.65', 'info', 'request', '')"),
    ]);
    assert.equal(await initializeUserSchemaDatabase(legacy), USER_SCHEMA_VERSION);
    const old = await legacy.prepare("SELECT * FROM system_logs_mb WHERE id = 1").first();
    assert.equal(old.ip, ip1);
    assert.equal(old.user_uid, "");
    assert.equal(old.device_id, "");
    assert.equal(old.identity_source, "");
    assert.equal((await legacy.prepare("SELECT COUNT(*) AS total FROM user_ip_links_mb").first()).total, 0);
    assert.deepEqual((await legacy.prepare("SELECT uid FROM users_mb").all()).results.map(row => row.uid), ['formal-uid']);
    assert.deepEqual((await legacy.prepare("SELECT user_id FROM user_usage_mb").all()).results.map(row => row.user_id), [2]);
    assert.deepEqual((await legacy.prepare("SELECT user_uid FROM device_account_links_mb").all()).results.map(row => row.user_uid), ['formal-uid']);
    assert.equal((await legacy.prepare("SELECT COUNT(*) AS total FROM device_bans_mb WHERE lifted_at IS NULL").first()).total, 1);
    await assert.rejects(legacy.prepare("INSERT INTO users_mb (uid, email) VALUES ('new-guest', '')").run(), /user_email_required/);
  });

  await t.test("注册、配置和订阅请求建立多对多关系，管理接口鉴权且统计随查询变化", async () => {
    assert.equal((await call("/health")).database.schemaVersion, USER_SCHEMA_VERSION);
    await call("/local-admin/api/state", {
      method: "PUT", admin: true,
      body: { subscriptionGroups: [{ id: "public", subscriptions: [{ id: "11", content: "vless://id@example.com:443#test" }] }] },
    });
    uidA = (await call("/api/account/register", { method: "POST", body: { deviceId: deviceA, email: "ip-user@example.com", password: "ip-test-password-123" } })).account.uid;
    uidB = (await call("/api/account/register", { method: "POST", body: { deviceId: deviceB, email: "ip-user-b@example.com", password: "ip-test-password-123" } })).account.uid;
    await call("/api/client?deviceId=" + deviceA);
    await call("/api/subscription/11?deviceId=" + deviceA);
    await call("/api/client?deviceId=" + deviceA, { ip: ip2 });
    await eventually(async () => Number((await row(uidA, ip1))?.request_count) === 3 && Boolean(await row(uidA, ip2)) && Boolean(await row(uidB, ip1)));
    await call("/local-admin/api/ip-relations", { status: 401 });
    const byIp = await links("?ip=" + ip1);
    assert.equal(byIp.total, 2);
    assert.deepEqual(new Set(byIp.relations.map(link => link.userUid)), new Set([uidA, uidB]));
    assert.deepEqual(byIp.stats, { ipCount: 1, userCount: 2, requestCount: 4 });
    const byUser = await links("?uid=" + uidA);
    assert.deepEqual(new Set(byUser.relations.map(link => link.ip)), new Set([ip1, ip2]));
    assert.deepEqual(byUser.stats, { ipCount: 2, userCount: 1, requestCount: 4 });
    assert.equal(byIp.relations.find(link => link.userUid === uidA).subscriptionCount, 1);
    const paged = await links("?ip=" + ip1 + "&limit=1&offset=1");
    assert.equal(paged.total, 2);
    assert.equal(paged.relations.length, 1);
    assert.deepEqual(paged.stats, byIp.stats);
    await call("/local-admin/api/ip-relations?ip=invalid", { admin: true, status: 400 });
    await call("/local-admin/api/ip-relations?uid=invalid", { admin: true, status: 400 });
    await eventually(async () => (await db.prepare("SELECT COUNT(*) AS total FROM system_logs_mb WHERE category = 'request' AND user_uid = ?").bind(uidA).first()).total >= 4);
    const logs = await call("/local-admin/api/system-logs?category=request&q=" + uidA, { admin: true });
    assert.equal(logs.stats.total, logs.total);
    assert.ok(logs.records.every(log => log.userUid === uidA && log.deviceId === deviceA));
    assert.ok(logs.records.every(log => log.identitySource === "device"));
    // 延迟清理不能导致查询总数与当前显示行数不一致。
    await db.prepare("UPDATE system_logs_mb SET occurred_at = ? WHERE id = ?")
      .bind("2020-01-01T00:00:00+08:00", logs.records[0].id).run();
    const retained = await call("/local-admin/api/system-logs?category=request&q=" + uidA, { admin: true });
    assert.equal(retained.stats.total, retained.total);
  });

  await t.test("失败登录不关联目标账户，成功切换账户保留当次身份与旧关系", async () => {
    const credentials = { deviceId: deviceA, email: "ip-user@example.com", password: "ip-test-password-123" };
    await call("/api/account/login", { method: "POST", ip: ip3, body: { ...credentials, deviceId: deviceB, password: "wrong-password" }, status: 401 });
    assert.equal((await links("?ip=" + ip3)).total, 0);
    await call("/api/account/login", { method: "POST", ip: ip3, body: { ...credentials, deviceId: deviceB } });
    await eventually(async () => Number((await row(uidA, ip3))?.login_count) === 1);
    assert.equal(await row(uidB, ip3), null);
    assert.ok(await row(uidB, ip1));
    await call("/api/client?deviceId=" + deviceB, { ip: ip3 });
    await eventually(async () => Number((await row(uidA, ip3))?.request_count) === 2);
    const spoofIp = "203.0.113.4";
    await call("/api/client?deviceId=" + deviceA + "&uid=" + uidB, { ip: spoofIp });
    await eventually(async () => Boolean(await row(uidA, spoofIp)));
    assert.equal(await row(uidB, spoofIp), null);
    const historical = await call("/local-admin/api/system-logs?category=request&q=" + uidB, { admin: true });
    assert.ok(historical.records.some(log => log.deviceId === deviceB && log.userUid === uidB));
  });

  await t.test("关闭或按时段停止日志不会停止关联采集；无效 IP 不使用 XFF 猜测", async () => {
    const config = await mf.getKVNamespace("CONFIG");
    await config.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify({
      categories: { request: false, outbound: false },
    }));
    const independentIp = "203.0.113.5";
    await call("/api/client?deviceId=" + deviceA, { ip: independentIp });
    await eventually(async () => Boolean(await row(uidA, independentIp)));
    assert.equal((await db.prepare("SELECT COUNT(*) AS total FROM system_logs_mb WHERE ip = ?").bind(independentIp).first()).total, 0);
    const minute = (new Date().getUTCHours() * 60 + new Date().getUTCMinutes() + 480 + 10) % 1440;
    const clock = n => String(Math.floor((n % 1440) / 60)).padStart(2, "0") + ":" + String(n % 60).padStart(2, "0");
    await config.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify({
      schedule: { enabled: true, startTime: clock(minute), endTime: clock(minute + 1) },
    }));
    await call("/api/client?deviceId=" + deviceA, { ip: independentIp });
    await eventually(async () => Number((await row(uidA, independentIp))?.request_count) === 2);
    const before = (await links()).total;
    for (const ip of ["invalid", ""]) {
      await call("/api/client?deviceId=" + deviceA, { ip, extraHeaders: { "X-Forwarded-For": "203.0.113.99" } });
    }
    assert.equal((await links()).total, before);
    assert.equal((await links("?ip=203.0.113.99")).total, 0);
  });

  await t.test("等价 IPv6 地址合并，并发请求不会覆盖累计次数", async () => {
    const ip = "2001:db8::1";
    await call("/api/client?deviceId=" + deviceA, { ip: "2001:0DB8:0000:0000:0000:0000:0000:0001" });
    await call("/api/client?deviceId=" + deviceA, { ip });
    await eventually(async () => Number((await row(uidA, ip))?.request_count) === 2);
    await Promise.all(Array.from({ length: 12 }, () => call("/api/client?deviceId=" + deviceA, { ip })));
    await eventually(async () => Number((await row(uidA, ip))?.request_count) === 14);
    const data = await links("?ip=2001:0DB8:0:0:0:0:0:1");
    assert.equal(data.total, 1);
    assert.equal(data.relations[0].ip, ip);
    assert.equal(data.relations[0].requestCount, 14);
  });

  await t.test("异步写入乱序仍保留正确首末时间及最后设备", async () => {
    const user = await db.prepare("SELECT id FROM users_mb WHERE uid = ?").bind(uidB).first();
    const identity = { userId: user.id, uid: uidB, deviceId: deviceB, source: "device" };
    const ip = "203.0.113.6";
    await writeUserIpLink({ USERS_DB: db }, identity, { ip, path: "/api/client", status: 200, occurredAt: "2026-10-01T12:00:00+08:00" });
    await writeUserIpLink({ USERS_DB: db }, { ...identity, deviceId: deviceA }, { ip, path: "/api/client", status: 200, occurredAt: "2026-10-01T11:00:00+08:00" });
    const relation = await row(uidB, ip);
    assert.equal(relation.first_seen_at, "2026-10-01T11:00:00+08:00");
    assert.equal(relation.last_seen_at, "2026-10-01T12:00:00+08:00");
    assert.equal(relation.last_device_id, deviceB);
    assert.equal(relation.request_count, 2);
  });

  await t.test("删除用户后保留历史关联并明确标记为已删除", async () => {
    await call("/api/account/delete", {
      method: "POST", body: { deviceId: deviceA, uid: uidA, password: "ip-test-password-123" },
    });
    const history = await links("?uid=" + uidA);
    assert.ok(history.total >= 2);
    assert.ok(history.relations.every(link => link.accountStatus === "deleted"));
    assert.equal((await db.prepare("SELECT COUNT(*) AS total FROM user_ip_links_mb WHERE user_uid = ? AND user_id IS NOT NULL").bind(uidA).first()).total, 0);
  });

  await t.test("关联存储异常只记录采集故障，不传播到业务请求", async (t) => {
    const errors = [];
    t.mock.method(console, "error", message => errors.push(JSON.parse(message)));
    await assert.doesNotReject(writeUserIpLink(
      { USERS_DB: { prepare() { throw new Error("database unavailable"); } } },
      { userId: 1, uid: uidA, deviceId: deviceA, source: "device" },
      { ip: ip1, path: "/api/client", status: 200, occurredAt: "2026-10-11T12:00:00+08:00" },
    ));
    assert.deepEqual(errors, [{ message: "user_ip_link_write_failed" }]);
  });
});
