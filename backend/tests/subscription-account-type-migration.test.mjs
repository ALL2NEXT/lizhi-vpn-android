import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

test("旧分组权限在移除账户类型字段前后保持一致", async (t) => {
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

  const db = await mf.getD1Database("SUBSCRIPTIONS_DB");
  const migrationsDir = fileURLToPath(new URL("../migrations/subscriptions/", import.meta.url));
  const migrations = readdirSync(migrationsDir).filter((name) => name.endsWith(".sql")).sort();
  async function apply(name) {
    const statements = readFileSync(join(migrationsDir, name), "utf8").split(";")
      .map((sql) => sql.trim()).filter(Boolean);
    await db.batch(statements.map((sql) => db.prepare(sql)));
  }
  for (const name of migrations.filter((name) => name < "0009")) await apply(name);
  await db.prepare("INSERT INTO subscription_store_meta(key, value) VALUES ('legacy_kv_imported', '1')").run();
  const groups = [
    ["formal", '["formal"]'],
    ["guest-only", '["guest"]'],
    ["none", '[]'],
    ["invalid", 'not-json'],
  ];
  for (const [index, [id, allowed]] of groups.entries()) {
    await db.batch([
      db.prepare(`INSERT INTO subscription_groups
        (id, group_order, name, group_type, enabled, allowed_statuses_json, allowed_account_types_json,
         update_enabled, cache_minutes, min_app_version, max_app_version, max_users_per_subscription, reset_period_days)
        VALUES (?, ?, ?, 'standard', 1, '["normal"]', ?, 1, 30, '', '', 10, 30)`).bind(id, index, id, allowed),
      db.prepare(`INSERT INTO subscription_sources
        (id, group_id, source_order, name, source_type, content, enabled, auto_rename)
        VALUES (?, ?, 0, ?, 'content', 'vless://id@example.com:443#test', 1, 0)`).bind(String(index + 1), id, id),
    ]);
    if (index === 0) {
      await db.prepare(`INSERT INTO subscription_sources
        (id, group_id, source_order, name, source_type, url, enabled, auto_rename, deduplicate)
        VALUES ('100', ?, 1, 'Remote', 'url', 'https://example.com/sub', 0, 0, 1)`).bind(id).run();
    }
  }

  async function state(method = "GET", body) {
    const response = await mf.dispatchFetch("https://worker.test/local-admin/api/state", {
      method,
      headers: { authorization: "Bearer local-test-token", "content-type": "application/json" },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    const data = await response.json();
    assert.equal(response.status, 200, JSON.stringify(data));
    return method === "GET" ? data : data.state;
  }
  for (const [id, allowed] of groups.slice(1)) {
    await db.prepare("UPDATE subscription_groups SET enabled = 1, allowed_account_types_json = ? WHERE id = ?")
      .bind(allowed, id).run();
  }
  await apply(migrations.find((name) => name.startsWith("0009")));
  const columns = (await db.prepare("PRAGMA table_info(subscription_groups)").all()).results;
  assert.equal(columns.some((column) => column.name === "allowed_account_types_json"), false);

  for (const name of migrations.filter((name) => name > "0009")) await apply(name);
  const after = await state();
  assert.deepEqual(after.subscriptionGroups.map((group) => group.enabled), [true, false, false, false]);
  assert.ok(after.subscriptionGroups.every((group) => !Object.hasOwn(group, "allowedAccountTypes")));
  const migratedRemote = after.subscriptionGroups[0].subscriptions.find((source) => source.id === "100");
  assert.equal(migratedRemote.updateEnabled, true);
  assert.equal(migratedRemote.cacheMinutes, 30);
  assert.equal(after.subscriptionGroups[0].subscriptions[0].updateEnabled, false);
  await state("PUT", after);
  assert.deepEqual((await state()).subscriptionGroups.map((group) => group.enabled), [true, false, false, false]);
});
