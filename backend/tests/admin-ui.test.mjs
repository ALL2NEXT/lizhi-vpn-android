import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const scripts = ['user-management', 'settings-drafts', 'settings'].map(name =>
  readFileSync(new URL('../src/admin-ui/scripts/' + name + '.client.js', import.meta.url), 'utf8'));

// Small DOM/API adapters keep these tests focused on browser-script behavior,
// without accessing a real Worker or substituting the save/filter functions.
function element() {
  const classes = new Set();
  return {
    value: '', checked: false, disabled: false, textContent: '', innerHTML: '',
    children: [], dataset: {}, listeners: {}, open: false,
    classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } },
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener(type, handler) { this.listeners[type] = handler; },
    setAttribute() {}, focus() {},
    showModal() { this.open = true; }, close() { this.open = false; },
  };
}

function harness() {
  const elements = new Map();
  const get = id => {
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  };
  const panels = new Map();
  for (const section of ['subscriptions', 'about-page', 'registration-control']) {
    const panel = element();
    panel.dataset.panel = section;
    const controls = new Map(['[data-settings-save-status]', '[data-settings-save]', '[data-settings-discard]'].map(selector => [selector, element()]));
    controls.get('[data-settings-discard]').dataset.settingsDiscard = section;
    panel.querySelector = selector => controls.get(selector) || null;
    panels.set(section, panel);
  }
  const fields = ['nickname', 'brand', 'model', 'androidVersion', 'appVersion'].map(name => {
    const input = element();
    input.dataset.adminUserField = name;
    input.matches = selector => selector === '[data-admin-user-field]';
    return input;
  });
  get('adminUserDetailForm').querySelectorAll = () => fields;
  const calls = [];
  const messages = [];
  const windowEvents = new Map();
  const documentEvents = new Map();
  const serverUsers = [{uid: 'a', nickname: 'Alice', accountType: 'guest'}, {uid: 'b', nickname: 'Bob', accountType: 'banned', email: 'bob@example.test'}];
  let requestHandler = async (url, options) => {
    if (url.endsWith('/state')) return {state: JSON.parse(options.body)};
    if (url.endsWith('/security/accounts')) return {accounts: []};
    if (url.endsWith('/users')) return {users: serverUsers};
    if (url.endsWith('/users/batch') && options.method === 'DELETE') {
      const { uids } = JSON.parse(options.body);
      for (let i = serverUsers.length - 1; i >= 0; i--) if (uids.includes(serverUsers[i].uid)) serverUsers.splice(i, 1);
      return {changed: uids.length};
    }
    if (options.method === 'PATCH') {
      Object.assign(serverUsers.find(user => url.endsWith('/' + user.uid)), JSON.parse(options.body));
      return {ok: true};
    }
    return {ok: true};
  };
  const context = vm.createContext({
    el: get, value: id => get(id).value.trim(),
    setValue: (id, value) => { get(id).value = String(value ?? ''); },
    checked: (id, value) => { get(id).checked = Boolean(value); },
    esc: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;'),
    headers: () => ({}), ADMIN_API_BASE: '/local-admin/api', adminToken: 'test',
    currentState: {subscriptionGroups: [], settings: {}},
    parseResponseJson: JSON.parse, adminErrorMessage: (error, fallback) => error || fallback,
    formatMegabytes: value => (value || 0) + ' MB',
    show: (message, error) => messages.push({message, error: Boolean(error)}), confirm: () => true,
    URL,
    document: {
      getElementById: get,
      querySelector(selector) { return panels.get(selector.match(/^\[data-panel="(.+)"\]$/)?.[1]) || null; },
      querySelectorAll(selector) {
        if (selector === '[data-settings-discard]') return [...panels.values()].map(panel => panel.querySelector(selector));
        return [];
      },
      addEventListener(type, handler) { documentEvents.set(type, handler); },
    },
    window: {location: {origin: 'https://worker.test'}, addEventListener(type, handler) { windowEvents.set(type, handler); }},
    initializeGrowthRange() {}, initializeActivityRange() {}, initializeTrafficRange() {},
    fetch: async (url, options = {}) => {
      calls.push({url, ...options});
      const data = await requestHandler(url, options);
      const ok = data.ok !== false;
      return {ok, status: ok ? 200 : 400, text: async () => JSON.stringify(data), json: async () => data};
    },
  });
  scripts.forEach((script, index) => {
    const source = index === 2 ? script.replace('initializeSubscriptionManager();', '') : script;
    vm.runInContext(source, context);
  });
  vm.runInContext('fillState(currentState)', context);
  context.fixtureUsers = serverUsers;
  vm.runInContext('adminUserData = {users: fixtureUsers, securityAccounts: []}', context);
  return {
    get, panels, calls, messages, fields, windowEvents, documentEvents, context,
    run: code => vm.runInContext(code, context),
    handleRequests: handler => { requestHandler = handler; },
  };
}

function draft(h, uid, nickname) {
  h.context.draftUid = uid;
  h.context.draftNickname = nickname;
  h.run('adminUserDrafts.set(draftUid, {nickname: draftNickname}); adminUserDirty.add(draftUid); adminUserSelection.add(draftUid)');
}

test('账户状态和类型可独立筛选及组合，封禁用户不会被类型判定排除', () => {
  const h = harness();
  h.get('userAdminStatusFilter').value = 'banned';
  assert.equal(h.run('adminUserList().map(user => user.uid).join()'), 'b');
  h.get('userAdminTypeFilter').value = 'formal';
  assert.equal(h.run('adminUserList().map(user => user.uid).join()'), 'b');
  h.get('userAdminTypeFilter').value = 'guest';
  assert.equal(h.run('adminUserList().length'), 0);
  h.get('userAdminStatusFilter').value = 'active';
  assert.equal(h.run('adminUserList().map(user => user.uid).join()'), 'a');
});

test('保存筛选隐藏的用户草稿不依赖表格行，并明确隐藏选择数量', async () => {
  const h = harness();
  draft(h, 'a', 'Edited Alice');
  h.get('userAdminSearch').value = 'Bob';
  h.run('renderAdminUsers()');
  assert.match(h.get('userAdminSelectionCount').textContent, /1 个不在当前结果/);
  assert.equal(await h.run('saveSelectedAdminUsers()'), true);
  const patch = h.calls.find(call => call.url.endsWith('/users/a'));
  assert.equal(JSON.parse(patch.body).nickname, 'Edited Alice');
  assert.equal(h.run('adminUserDrafts.has("a")'), false);
  assert.equal(h.run('adminUserDirty.has("a")'), false);
});

test('删除其他用户保留未选中用户的草稿及未保存状态', async () => {
  const h = harness();
  draft(h, 'a', 'Keep this draft');
  h.run('adminUserSelection.clear(); adminUserSelection.add("b")');
  assert.equal(await h.run('batchDeleteAdminUsers()'), true);
  assert.equal(h.run('adminUserDrafts.get("a").nickname'), 'Keep this draft');
  assert.equal(h.run('adminUserDirty.has("a")'), true);
  assert.equal(h.run('adminUserData.users.some(user => user.uid === "b")'), false);
});

test('管理端设置仅保存仍支持的模块，不提交更新或公告字段', async () => {
  const h = harness();
  assert.equal(h.run('SETTINGS_SECTIONS.join(",")'), 'subscriptions,about-page,registration-control');
  assert.equal(h.run('SETTINGS_SECTIONS.includes("notice") || SETTINGS_SECTIONS.includes("app-update")'), false);
  h.get('websiteUrl').value = 'https://example.test';
  h.run('markSettingsDirty("about-page")');
  assert.equal(await h.run('pushSection("about-page")'), true);
  const request = h.calls.find(call => call.method === 'PUT');
  const payload = JSON.parse(request.body);
  assert.equal(Object.hasOwn(payload, 'notice'), false);
  assert.equal(Object.hasOwn(payload, 'appUpdate'), false);
});


test('详情编辑和关闭保留草稿，保存失败可在详情中读取错误', async () => {
  const h = harness();
  h.run('openAdminUserDetail("a")');
  assert.equal(h.get('adminUserDetailDialog').open, true);
  assert.equal(h.fields[0].value, 'Alice');
  h.fields[0].value = 'Edited in details';
  h.get('adminUserDetailForm').listeners.input({target: h.fields[0]});
  h.get('adminUserDetailDialog').close();
  assert.equal(h.run('adminUserDrafts.get("a").nickname'), 'Edited in details');
  assert.equal(h.run('adminUserData.users[0].nickname'), 'Alice');
  h.handleRequests(async () => ({ok: false, error: 'Save failed'}));
  h.run('openAdminUserDetail("a")');
  assert.equal(await h.run('saveAdminUser("a")'), false);
  assert.equal(h.get('adminUserDetailStatus').textContent, 'Save failed');
  assert.equal(h.run('adminUserDirty.has("a")'), true);
  assert.equal(h.get('adminUserDetailSave').disabled, false);
});

test('离开提示只在存在用户或设置草稿时触发', () => {
  const h = harness();
  let prevented = false;
  const event = {preventDefault() { prevented = true; }};
  const beforeUnload = h.windowEvents.get('beforeunload');
  beforeUnload(event);
  assert.equal(prevented, false);
  draft(h, 'a', 'Unsaved');
  beforeUnload(event);
  assert.equal(prevented, true);
  h.run('resetAdminUsers(); resetSettingsDrafts()');
  assert.equal(h.run('hasUnsavedAdminChanges()'), false);
});
