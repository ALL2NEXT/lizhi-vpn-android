import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout } from "node:timers/promises";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { SYSTEM_LOG_SETTINGS_KEY } from "../src/config.ts";

test("AI 风控管理接口鉴权、隐藏密钥并提供脱敏请求数据", async (t) => {
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
    d1Databases: ["USERS_DB"],
    bindings: { ADMIN_PATH: "local-admin", ADMIN_TOKEN: "local-test-token" },
  }));
  t.after(() => mf.dispose());

  const db = await mf.getD1Database("USERS_DB");
  const config = await mf.getKVNamespace("CONFIG");
  const clientIp = "203.0.113.88";
  async function call(path, { method = "GET", body, admin = false, status = 200, ip = clientIp } = {}) {
    const response = await mf.dispatchFetch("https://worker.test" + path, {
      method,
      headers: {
        "CF-Connecting-IP": ip,
        ...(body === undefined ? {} : { "content-type": "application/json" }),
        ...(admin ? { authorization: "Bearer local-test-token" } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    assert.equal(response.status, status, `${method} ${path}: ${text}`);
    return text && response.headers.get("content-type")?.includes("application/json") ? JSON.parse(text) : text;
  }
  async function eventually(check) {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (await check()) return;
      await setTimeout(20);
    }
    assert.fail("后台日志或 IP 关联记录未及时写入");
  }

  await call("/health");
  await call("/local-admin/api/ai-risk/settings", { status: 401 });
  const initial = await call("/local-admin/api/ai-risk/settings", { admin: true });
  assert.equal(initial.settings.apiKeyConfigured, false);

  await config.put(SYSTEM_LOG_SETTINGS_KEY, JSON.stringify({
    mode: "full",
    categories: { request: true, operation: true, database: true, outbound: true },
    schedule: { enabled: false, startTime: "00:00", endTime: "23:59" },
  }));
  const configured = await call("/local-admin/api/ai-risk/settings", {
    method: "PUT", admin: true,
    body: { enabled: false, model: "gemini-3.8-flash", apiKey: "gemini-secret-test-key" },
  });
  assert.equal(configured.settings.apiKeyConfigured, true);
  assert.equal(JSON.stringify(configured).includes("gemini-secret-test-key"), false);
  await eventually(async () => {
    const row = await db.prepare(
      "SELECT request_id FROM system_logs_mb WHERE path = '/local-admin/api/ai-risk/settings' ORDER BY id DESC LIMIT 1",
    ).first();
    return Boolean(row);
  });
  const capturedBodies = await db.prepare(
    `SELECT COUNT(*) AS total FROM system_log_payload_parts_mb p
     JOIN system_logs_mb l ON l.request_id = p.request_id
     WHERE l.path = '/local-admin/api/ai-risk/settings'`,
  ).first();
  assert.equal(Number(capturedBodies.total), 0, "AI API Key 不应进入系统日志完整请求载荷");

  const deviceId = "c".repeat(64);
  const registered = await call("/api/account/register", { method: "POST", body: { deviceId } });
  await call("/api/client?deviceId=" + deviceId);
  await eventually(async () => Boolean(await db.prepare(
    "SELECT 1 FROM user_ip_links_mb WHERE user_uid = ? AND ip = ?",
  ).bind(registered.account.uid, clientIp).first()));

  const data = await call("/local-admin/api/ai-risk/data?windowHours=24", { admin: true });
  assert.equal(data.ok, true);
  assert.ok(data.dataset.subjects.some(subject => subject.userRef));
  assert.ok(data.dataset.ipRelations.some(relation => relation.knownAccountCount >= 1));
  const serialized = JSON.stringify(data.dataset);
  assert.equal(serialized.includes(clientIp), false, "AI 数据接口不应回传原始 IP");
  assert.equal(serialized.includes(registered.account.uid), false, "AI 数据接口不应回传原始 UID");
  await call("/local-admin/api/ai-risk/analyze", {
    method: "POST", admin: true, body: { windowHours: 24 }, status: 409,
  });
});
