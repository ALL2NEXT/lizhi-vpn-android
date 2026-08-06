/*
 * FireflyVPN Cloudflare Worker backend (single-file deployment)
 *
 * Required Cloudflare configuration:
 *   - KV namespace binding: CONFIG
 *   - Worker secret: ADMIN_TOKEN
 *
 * Public client endpoint:
 *   GET /api/client
 *   GET /api/client?refresh=1   // force refresh, used by the app's manual refresh
 *
 * Admin endpoints:
 *   GET  /admin
 *   GET  /api/admin/state
 *   PUT  /api/admin/state
 *   POST /api/admin/refresh
 */

const STATE_KEY = "fireflyvpn:state:v1";
const UPSTREAM_TIMEOUT_MS = 15000;

const DEFAULT_STATE = {
  version: 1,
  update: 1,
  subscriptions: [],
  notice: null,
  appUpdate: null,
  settings: {
    websiteUrl: "",
    feedbackEmail: "",
    feedbackUrl: "",
    githubUrl: "",
    nodeRequestTimeoutMs: 25000,
  },
  lastRefreshAt: null,
  lastErrors: [],
};

export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";

      if (path === "/" || path === "/health") {
        return json({ ok: true, service: "fireflyvpn-worker" });
      }

      if (path === "/admin" && request.method === "GET") {
        return new Response(adminHtml(), {
          headers: { "content-type": "text/html; charset=utf-8" },
        });
      }

      if (path === "/api/client" && request.method === "GET") {
        return handleClient(request, env);
      }

      if (path.startsWith("/api/admin/")) {
        if (!isAdmin(request, env)) {
          return json({ error: "unauthorized" }, 401);
        }
        return handleAdmin(path, request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};

async function handleClient(request, env) {
  const state = await readState(env);
  const forceRefresh = new URL(request.url).searchParams.get("refresh") === "1";
  const firstFetch = state.subscriptions.some(
    (source) => source.enabled && !String(source.content || "").trim(),
  );

  // update=1 means every normal client pull may refresh upstream sources.
  // update=0 keeps the Worker cache until the app explicitly asks for refresh=1.
  if (forceRefresh || state.update === 1 || firstFetch) {
    await refreshSources(state, env);
  }

  return json({
    update: state.update,
    subscriptions: state.subscriptions
      .filter((source) => source.enabled)
      .map(({ id, name, enabled, content }) => ({ id, name, enabled, content })),
    notice: state.notice,
    appUpdate: state.appUpdate,
    settings: state.settings,
    generatedAt: new Date().toISOString(),
  });
}

async function handleAdmin(path, request, env) {
  if (path === "/api/admin/state" && request.method === "GET") {
    return json(await readState(env));
  }

  if (path === "/api/admin/state" && request.method === "PUT") {
    const incoming = await request.json();
    const next = normalizeState(incoming);
    next.updatedAt = new Date().toISOString();
    await writeState(env, next);
    return json({ ok: true, state: next });
  }

  if (path === "/api/admin/refresh" && request.method === "POST") {
    const state = await readState(env);
    await refreshSources(state, env);
    return json({ ok: true, state });
  }

  return json({ error: "admin_route_not_found" }, 404);
}

async function refreshSources(state, env) {
  const errors = [];
  const refreshed = await Promise.all(
    state.subscriptions.map(async (source) => {
      if (!source.enabled || !source.url) return source;
      try {
        const content = await fetchUpstream(source.url);
        return { ...source, content, lastFetchedAt: new Date().toISOString() };
      } catch (error) {
        errors.push({
          id: source.id,
          name: source.name,
          message: error instanceof Error ? error.message : String(error),
        });
        // Keep the last good content so a temporary upstream failure does not
        // make every existing node disappear from the app.
        return source;
      }
    }),
  );

  state.subscriptions = refreshed;
  state.lastRefreshAt = new Date().toISOString();
  state.lastErrors = errors;
  await writeState(env, state);
  return state;
}

async function fetchUpstream(sourceUrl) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const response = await fetch(sourceUrl, {
      method: "GET",
      redirect: "follow",
      signal: controller.signal,
      headers: {
        accept: "text/plain, text/html, application/json, */*",
        "user-agent": "FireflyVPN-Worker/1.0",
      },
    });
    if (!response.ok) {
      throw new Error(`upstream HTTP ${response.status}`);
    }
    const content = await response.text();
    if (!content.trim()) throw new Error("upstream returned empty content");
    return content;
  } finally {
    clearTimeout(timeout);
  }
}

async function readState(env) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const stored = await env.CONFIG.get(STATE_KEY, "json");
  return normalizeState(stored || DEFAULT_STATE);
}

async function writeState(env, state) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  await env.CONFIG.put(STATE_KEY, JSON.stringify(normalizeState(state)));
}

function normalizeState(input) {
  const value = input && typeof input === "object" ? input : {};
  const list = Array.isArray(value.subscriptions)
    ? value.subscriptions
    : Array.isArray(value.sources)
      ? value.sources
      : [];

  const subscriptions = list
    .filter((item) => item && typeof item === "object")
    .map((item, index) => {
      const url = typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
        ? item.url.trim()
        : "";
      const content = typeof item.content === "string" ? item.content : "";
      return {
        id: cleanText(item.id) || `line${index + 1}`,
        name: cleanText(item.name || item.title) || `线路${index + 1}`,
        url,
        enabled: parseFlag(item.enabled, true),
        content,
        ...(item.lastFetchedAt ? { lastFetchedAt: String(item.lastFetchedAt) } : {}),
      };
    });

  return {
    version: 1,
    update: parseFlag(value.update, true) ? 1 : 0,
    subscriptions,
    notice: plainObject(value.notice) ? value.notice : null,
    appUpdate: plainObject(value.appUpdate) ? value.appUpdate : null,
    settings: normalizeSettings(value.settings),
    lastRefreshAt: value.lastRefreshAt ? String(value.lastRefreshAt) : null,
    lastErrors: Array.isArray(value.lastErrors) ? value.lastErrors : [],
    ...(value.updatedAt ? { updatedAt: String(value.updatedAt) } : {}),
  };
}

function normalizeSettings(value) {
  const settings = plainObject(value) ? value : {};
  const timeout = Number(settings.nodeRequestTimeoutMs);
  return {
    websiteUrl: cleanText(settings.websiteUrl),
    feedbackEmail: cleanText(settings.feedbackEmail),
    feedbackUrl: cleanText(settings.feedbackUrl),
    githubUrl: cleanText(settings.githubUrl),
    nodeRequestTimeoutMs: Number.isFinite(timeout)
      ? Math.round(Math.min(Math.max(timeout, 5000), 60000))
      : 25000,
  };
}

function cleanText(value) {
  return typeof value === "string" ? value.trim() : "";
}

function plainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function parseFlag(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return !["0", "false", "off", "no"].includes(String(value).trim().toLowerCase());
}

function isAdmin(request, env) {
  const token = env.ADMIN_TOKEN;
  const authorization = request.headers.get("authorization") || "";
  return Boolean(token) && authorization === `Bearer ${token}`;
}

function corsHeaders() {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,PUT,OPTIONS",
    "access-control-allow-headers": "Content-Type, Authorization",
  };
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...corsHeaders(),
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function adminHtml() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FireflyVPN Worker 管理后台</title>
  <style>
    :root { color-scheme: dark; font-family: system-ui, -apple-system, sans-serif; }
    body { margin: 0; background: #111827; color: #e5e7eb; }
    main { width: min(980px, calc(100% - 28px)); margin: 24px auto 60px; }
    h1 { font-size: 24px; margin: 0 0 6px; }
    h2 { font-size: 18px; margin: 0 0 14px; }
    p, small, label { color: #aab4c5; line-height: 1.55; }
    .card { background: #1f2937; border: 1px solid #374151; border-radius: 14px; padding: 18px; margin-top: 16px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    input, textarea { box-sizing: border-box; width: 100%; color: #e5e7eb; background: #111827; border: 1px solid #4b5563; border-radius: 8px; padding: 10px; }
    input[type="checkbox"] { width: auto; accent-color: #22c55e; transform: scale(1.15); margin-right: 8px; }
    textarea { min-height: 110px; resize: vertical; }
    button { cursor: pointer; border: 0; border-radius: 8px; padding: 10px 14px; margin: 10px 8px 0 0; background: #22c55e; color: #052e16; font-weight: 700; }
    button.secondary { background: #374151; color: #e5e7eb; }
    button.danger { background: #7f1d1d; color: #fee2e2; }
    .check { display: flex; align-items: center; margin: 8px 0; }
    .source-row { border: 1px solid #4b5563; border-radius: 10px; padding: 12px; margin: 10px 0; }
    .source-row .row-actions { display: flex; align-items: center; justify-content: space-between; }
    .source-row small { display: block; margin-top: 8px; }
    #status { min-height: 24px; color: #86efac; margin-top: 8px; }
    .hint { margin-top: 4px; }
  </style>
</head>
<body>
<main>
  <h1>FireflyVPN Worker 管理后台</h1>
  <p>在这里配置订阅线路、网站信息、联系方式、公告和版本更新。App 只访问 Worker，不需要再修改多处接口。</p>

  <section class="card">
    <h2>登录与保存</h2>
    <div class="field"><label>管理密钥 ADMIN_TOKEN<input id="token" type="password" placeholder="输入 Worker Secret ADMIN_TOKEN"></label></div>
    <button class="secondary" onclick="loadState()">读取配置</button>
    <button onclick="saveState()">保存全部设置</button>
    <button class="secondary" onclick="refreshSources()">立即刷新订阅</button>
    <div id="status"></div>
  </section>

  <section class="card">
    <h2>订阅更新</h2>
    <label class="check"><input id="autoUpdate" type="checkbox">允许 App 自动更新订阅</label>
    <small class="hint">关闭后，App 只使用缓存；用户点击刷新时才拉取上游订阅。</small>
  </section>

  <section class="card">
    <h2>订阅线路</h2>
    <div id="subscriptionRows"></div>
    <button class="secondary" onclick="addSource()">添加线路</button>
    <small class="hint">订阅地址只保存在 Worker。拉取到的内容会缓存，App 只接收节点内容。</small>
  </section>

  <section class="card">
    <h2>应用基础设置</h2>
    <div class="grid">
      <div class="field"><label>官方网站<input id="websiteUrl" type="url" placeholder="https://example.com"></label></div>
      <div class="field"><label>反馈邮箱<input id="feedbackEmail" type="email" placeholder="support@example.com"></label></div>
      <div class="field"><label>问题反馈链接<input id="feedbackUrl" type="url" placeholder="https://github.com/.../issues"></label></div>
      <div class="field"><label>项目源码链接<input id="githubUrl" type="url" placeholder="https://github.com/.../.../"></label></div>
      <div class="field"><label>后台请求超时（毫秒）<input id="nodeRequestTimeoutMs" type="number" min="5000" max="60000" step="1000"></label></div>
    </div>
    <small class="hint">留空的网站、邮箱或链接会在 App 中自动隐藏对应入口。超时范围为 5000～60000 毫秒。</small>
  </section>

  <section class="card">
    <h2>公告</h2>
    <label class="check"><input id="noticeEnabled" type="checkbox">显示公告</label>
    <div class="grid">
      <div class="field"><label>公告标题<input id="noticeTitle" type="text"></label></div>
      <div class="field"><label>公告 ID<input id="noticeId" type="text" placeholder="notice_20260806"></label></div>
    </div>
    <div class="field"><label>公告内容<textarea id="noticeContent" placeholder="支持普通文本和 HTML"></textarea></label></div>
    <label class="check"><input id="noticeShowOnce" type="checkbox">每个公告 ID 只显示一次</label>
    <label class="check"><input id="scheduleEnabled" type="checkbox">用公告覆盖定时更新设置</label>
    <div class="grid">
      <div class="field"><label>更新间隔小时<input id="scheduleHours" type="number" min="0" max="168"></label></div>
      <div class="field"><label>更新间隔分钟<input id="scheduleMinutes" type="number" min="0" max="59"></label></div>
    </div>
    <label class="check"><input id="scheduleReconnect" type="checkbox">节点更新后自动重连</label>
    <label class="check"><input id="scheduleToast" type="checkbox">显示定时更新提示</label>
  </section>

  <section class="card">
    <h2>版本更新</h2>
    <label class="check"><input id="appUpdateEnabled" type="checkbox">启用版本更新提示</label>
    <div class="grid">
      <div class="field"><label>版本名称<input id="appVersion" type="text" placeholder="1.1.0"></label></div>
      <div class="field"><label>版本号<input id="appVersionCode" type="number" min="1" step="1"></label></div>
      <div class="field"><label>APK 下载地址<input id="appDownloadUrl" type="url" placeholder="https://example.com/app.apk"></label></div>
    </div>
    <div class="field"><label>更新说明<textarea id="appChangelog"></textarea></label></div>
    <label class="check"><input id="appForce" type="checkbox">强制更新</label>
  </section>
</main>
<script>
let currentState = null;
const tokenInput = document.getElementById('token');
const statusEl = document.getElementById('status');
tokenInput.value = localStorage.getItem('fireflyvpn_admin_token') || '';
function el(id) { return document.getElementById(id); }
function value(id) { return el(id).value.trim(); }
function setValue(id, data) { el(id).value = data == null ? '' : data; }
function checked(id, data) { el(id).checked = Boolean(data); }
function esc(data) { return String(data == null ? '' : data).replace(/[&<>"']/g, function(c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
function headers() { return {'content-type':'application/json','authorization':'Bearer ' + tokenInput.value.trim()}; }
function show(message, error) { statusEl.textContent = message; statusEl.style.color = error ? '#fca5a5' : '#86efac'; }
function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  row.innerHTML = '<div class="grid">' +
    '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
    '<div class="field"><label>线路 ID<input data-field="id" value="' + esc(source.id) + '"></label></div>' +
    '<div class="field"><label>订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '"></label></div>' +
    '</div><label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
    '<div class="row-actions"><small>已缓存内容：' + (source.content ? '有' : '无') + '</small><button class="danger" type="button">删除</button></div>';
  row.querySelector('.danger').onclick = function() { row.remove(); };
  el('subscriptionRows').appendChild(row);
}
function fillState(state) {
  currentState = state || {};
  checked('autoUpdate', currentState.update !== 0);
  el('subscriptionRows').innerHTML = '';
  (currentState.subscriptions || []).forEach(addSource);
  if (!currentState.subscriptions || currentState.subscriptions.length === 0) addSource();
  const settings = currentState.settings || {};
  setValue('websiteUrl', settings.websiteUrl);
  setValue('feedbackEmail', settings.feedbackEmail);
  setValue('feedbackUrl', settings.feedbackUrl);
  setValue('githubUrl', settings.githubUrl);
  setValue('nodeRequestTimeoutMs', settings.nodeRequestTimeoutMs || 25000);
  const notice = currentState.notice || {};
  checked('noticeEnabled', Boolean(currentState.notice && notice.hasNotice !== false));
  setValue('noticeTitle', notice.title);
  setValue('noticeId', notice.noticeId);
  setValue('noticeContent', notice.content);
  checked('noticeShowOnce', notice.showOnce !== false);
  const schedule = notice.scheduledNodeUpdate || {};
  checked('scheduleEnabled', Boolean(notice.scheduledNodeUpdate));
  setValue('scheduleHours', schedule.hours == null ? 0 : schedule.hours);
  setValue('scheduleMinutes', schedule.minutes == null ? 30 : schedule.minutes);
  checked('scheduleReconnect', schedule.nodeAutoReconnect);
  checked('scheduleToast', schedule.toastEnabled !== false);
  const update = currentState.appUpdate || {};
  checked('appUpdateEnabled', Boolean(currentState.appUpdate));
  setValue('appVersion', update.version);
  setValue('appVersionCode', update.versionCode);
  setValue('appDownloadUrl', update.downloadUrl);
  setValue('appChangelog', update.changelog);
  checked('appForce', update.is_force === 1 || update.isForce === 1);
}
function formState() {
  const oldSources = currentState && currentState.subscriptions || [];
  const subscriptions = Array.from(document.querySelectorAll('.source-row')).map(function(row, index) {
    const id = row.querySelector('[data-field="id"]').value.trim() || 'line' + (index + 1);
    const old = oldSources.find(function(item) { return item.id === id; });
    return {
      id: id,
      name: row.querySelector('[data-field="name"]').value.trim() || '线路' + (index + 1),
      url: row.querySelector('[data-field="url"]').value.trim(),
      enabled: row.querySelector('[data-field="enabled"]').checked,
      content: old && old.content || '',
      lastFetchedAt: old && old.lastFetchedAt || undefined
    };
  }).filter(function(item) { return item.url || item.content; });
  const notice = el('noticeEnabled').checked ? {
    hasNotice: true,
    title: value('noticeTitle'),
    content: value('noticeContent'),
    noticeId: value('noticeId'),
    showOnce: el('noticeShowOnce').checked,
    scheduledNodeUpdate: el('scheduleEnabled').checked ? {
      enabled: true,
      hours: Number(value('scheduleHours') || 0),
      minutes: Number(value('scheduleMinutes') || 30),
      nodeAutoReconnect: el('scheduleReconnect').checked,
      toastEnabled: el('scheduleToast').checked
    } : null
  } : null;
  const appUpdate = el('appUpdateEnabled').checked ? {
    version: value('appVersion'),
    versionCode: Number(value('appVersionCode') || 0),
    downloadUrl: value('appDownloadUrl'),
    changelog: value('appChangelog'),
    is_force: el('appForce').checked ? 1 : 0
  } : null;
  return {
    update: el('autoUpdate').checked ? 1 : 0,
    subscriptions: subscriptions,
    settings: {
      websiteUrl: value('websiteUrl'),
      feedbackEmail: value('feedbackEmail'),
      feedbackUrl: value('feedbackUrl'),
      githubUrl: value('githubUrl'),
      nodeRequestTimeoutMs: Number(value('nodeRequestTimeoutMs') || 25000)
    },
    notice: notice,
    appUpdate: appUpdate
  };
}
async function loadState() {
  localStorage.setItem('fireflyvpn_admin_token', tokenInput.value.trim());
  const response = await fetch('/api/admin/state', {headers: headers()});
  const data = await response.json();
  if (!response.ok) return show(data.error || '读取失败', true);
  fillState(data);
  show('配置已读取');
}
async function saveState() {
  localStorage.setItem('fireflyvpn_admin_token', tokenInput.value.trim());
  const response = await fetch('/api/admin/state', {method:'PUT', headers:headers(), body:JSON.stringify(formState())});
  const data = await response.json();
  if (!response.ok) return show(data.error || '保存失败', true);
  fillState(data.state);
  show('全部设置已保存');
}
async function refreshSources() {
  localStorage.setItem('fireflyvpn_admin_token', tokenInput.value.trim());
  const response = await fetch('/api/admin/refresh', {method:'POST', headers:headers()});
  const data = await response.json();
  if (!response.ok) return show(data.error || '刷新失败', true);
  fillState(data.state);
  show('订阅已刷新');
}
addSource();
</script>
</body>
</html>`;
}
