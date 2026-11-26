import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = path => readFileSync(new URL(path, import.meta.url), "utf8");

const adminUiSource = read("../src/admin-ui.ts");
const adminDocument = read("../src/admin-ui/document.html");
const userManagement = read("../src/admin-ui/scripts/user-management.client.js");

test("独立 IP 关联页面没有进入当前管理后台", () => {
  assert.doesNotMatch(adminUiSource, /ip-relations\.client\.js/);
  assert.doesNotMatch(adminDocument, /ipRelations|data-panel=["']ip-relations["']/);
});

test("用户总表继续展示后端提供的关联 IP", () => {
  assert.match(adminDocument, /data-panel="users"/);
  assert.match(userManagement, /function adminUserAssociatedIpCell\(user\)/);
  assert.match(userManagement, /user\.associatedIps/);
});

test("历史 IP 关联管理 API 保持兼容入口", () => {
  const adminSecurityRoute = read("../src/routes/admin/security.ts");
  const apiTests = read("./ip-relations.test.mjs");
  assert.match(adminSecurityRoute, /path === "\/api\/ip-relations"/);
  assert.match(apiTests, /api\/ip-relations/);
});