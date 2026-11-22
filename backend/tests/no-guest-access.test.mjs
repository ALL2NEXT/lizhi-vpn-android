import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { USER_SCHEMA_STATEMENTS } from "../src/config.ts";

test("残留的空邮箱记录不能用于设备同步、资料修改或订阅", async (t) => {
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
  }));
  t.after(() => mf.dispose());
  const db = await mf.getD1Database("USERS_DB");
  // Simulate a malformed legacy database that still contains an unregistered user.
  const schema = USER_SCHEMA_STATEMENTS
    .filter((sql) => !sql.includes("trg_users_require_email_"))
    .map((sql) => sql.replace("email TEXT NOT NULL COLLATE NOCASE CHECK (trim(email) <> '')", "email TEXT COLLATE NOCASE"));
  await db.batch(schema.map((sql) => db.prepare(sql)));
  const deviceId = "f".repeat(64);
  await db.batch([
    db.prepare(`INSERT INTO users_mb (id, uid, nickname, email, created_at)
      VALUES (1, '11111111-1111-4111-8111-111111111111', 'legacy', '', '2026-01-01')`),
    db.prepare(`INSERT INTO user_devices_mb (user_id, device_id, created_at, last_seen_at)
      VALUES (1, ?, '2026-01-01', '2026-01-01')`).bind(deviceId),
  ]);

  async function request(path, method = "GET", body) {
    return mf.dispatchFetch("https://worker.test" + path, {
      method,
      ...(body ? { headers: { "content-type": "application/json" }, body: JSON.stringify(body) } : {}),
    });
  }
  assert.equal((await request("/api/account/sync", "POST", { deviceId })).status, 404);
  assert.equal((await request("/api/account/profile", "PATCH", { deviceId, nickname: "changed" })).status, 400);
  assert.equal((await request("/api/account/security-report", "POST", { deviceId, codes: ["root_uid"] })).status, 404);
  assert.equal((await request(`/api/client?deviceId=${deviceId}`)).status, 403);
  assert.equal((await db.prepare("SELECT nickname FROM users_mb WHERE id = 1").first()).nickname, "legacy");
});
