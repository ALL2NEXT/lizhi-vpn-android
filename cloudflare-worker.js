/*
 * FireflyVPN Cloudflare Worker backend (single-file deployment)
 *
 * Required Cloudflare configuration:
 *   - KV namespace binding: CONFIG
 *   - Worker secret: ADMIN_TOKEN
 *
 * Public client endpoint:
 *   GET /api/client
 *   GET /api/client?refresh=1   // refresh is handled by the app; query is tolerated for compatibility
 *
 * Admin endpoints:
 *   GET  /<ADMIN_UUID>
 *   GET  /<ADMIN_UUID>/api/state
 *   PUT  /<ADMIN_UUID>/api/state
 *   GET  /<ADMIN_UUID>/api/users
 */

const STATE_KEY = "fireflyvpn:state:v1";
const USERS_INDEX_KEY = "fireflyvpn:users:index:v1";
const USER_KEY_PREFIX = "fireflyvpn:user:v1:";
// Change this UUID to customize the private management route.
const ADMIN_UUID = "3e9a0eed-6a29-4d16-91b1-3adae4bd88e6";
const ADMIN_BASE_PATH = `/${ADMIN_UUID}`;

const DEFAULT_STATE = {
  version: 1,
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

      if (path === ADMIN_BASE_PATH && request.method === "GET") {
        return new Response(adminHtml(), {
          headers: { "content-type": "text/html; charset=utf-8" },
        });
      }

      if (path === "/api/client" && request.method === "GET") {
        return handleClient(request, env);
      }

      if (path.startsWith("/api/account/")) {
        return handleAccount(path, request, env);
      }

      if (path.startsWith(`${ADMIN_BASE_PATH}/api/`)) {
        if (!isAdmin(request, env)) {
          return json({ error: "unauthorized" }, 401);
        }
        return handleAdmin(path.slice(ADMIN_BASE_PATH.length), request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};

async function handleClient(request, env) {
  const state = await readState(env);

  return json({
    subscriptions: state.subscriptions
      .filter((source) => source.enabled)
      .map(({ id, name, enabled, url, encrypted }) => ({ id, name, enabled, url, encrypted })),
    notice: state.notice,
    appUpdate: state.appUpdate,
    settings: state.settings,
    generatedAt: new Date().toISOString(),
  });
}

async function handleAccount(path, request, env) {
  if (path === "/api/account/register" && request.method === "POST") {
    const body = await request.json();
    const accountId = normalizeAccountId(body && body.deviceId);
    if (!accountId) {
      return json({ ok: false, error: "invalid_device_id" }, 400);
    }

    const now = new Date().toISOString();
    let user = await readUser(env, accountId);
    if (!user) {
      user = createUser(accountId, now);
      await addUserToIndex(env, accountId);
    } else {
      user.lastSeenAt = now;
    }
    await writeUser(env, user);

    return json({ ok: true, account: publicAccount(user) });
  }

  if (path === "/api/account/report" && request.method === "POST") {
    const body = await request.json();
    const accountId = normalizeAccountId(body && body.accountId);
    const sessionId = cleanText(body && body.sessionId).slice(0, 128);
    if (!accountId || !sessionId) {
      return json({ ok: false, error: "invalid_report" }, 400);
    }

    const user = await readUser(env, accountId);
    if (!user) {
      return json({ ok: false, error: "account_not_found" }, 404);
    }
    if (user.reportedSessionIds.includes(sessionId)) {
      return json({ ok: true, duplicate: true });
    }

    const uploadBytes = boundedBytes(body && body.uploadBytes);
    const downloadBytes = boundedBytes(body && body.downloadBytes);
    const now = new Date();
    const { day, month } = usagePeriodKeys(now);
    addUsage(user.dailyUsage, day, uploadBytes, downloadBytes);
    addUsage(user.monthlyUsage, month, uploadBytes, downloadBytes);
    user.totalUploadBytes += uploadBytes;
    user.totalDownloadBytes += downloadBytes;
    user.lastSeenAt = now.toISOString();
    user.lastReportAt = now.toISOString();
    user.reportedSessionIds = [...user.reportedSessionIds, sessionId].slice(-200);
    pruneUsage(user.dailyUsage, 400);
    pruneUsage(user.monthlyUsage, 24);
    await writeUser(env, user);

    return json({ ok: true });
  }

  return json({ error: "account_route_not_found" }, 404);
}

async function handleAdmin(path, request, env) {
  if (path === "/api/users" && request.method === "GET") {
    const index = await readUserIndex(env);
    const users = (await Promise.all(index.map((id) => readUser(env, id))))
      .filter(Boolean);
    const { day, month } = usagePeriodKeys(new Date());
    const publicUsers = users.map((user) => publicAdminUser(user, day, month));

    return json({
      ok: true,
      date: day,
      month,
      users: publicUsers,
      totals: {
        today: aggregateUsage(users, "dailyUsage", day),
        month: aggregateUsage(users, "monthlyUsage", month),
        allTime: aggregateTotals(users),
      },
      generatedAt: new Date().toISOString(),
    });
  }

  if (path === "/api/state" && request.method === "GET") {
    return json(await readState(env));
  }

  if (path === "/api/state" && request.method === "PUT") {
    const incoming = await request.json();
    const next = normalizeState(incoming);
    next.updatedAt = new Date().toISOString();
    await writeState(env, next);
    return json({ ok: true, state: next });
  }

  return json({ error: "admin_route_not_found" }, 404);
}

function createUser(id, now) {
  return {
    id,
    createdAt: now,
    lastSeenAt: now,
    lastReportAt: null,
    updatedAt: now,
    totalUploadBytes: 0,
    totalDownloadBytes: 0,
    dailyUsage: {},
    monthlyUsage: {},
    reportedSessionIds: [],
  };
}

async function readUser(env, accountId) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const stored = await env.CONFIG.get(USER_KEY_PREFIX + accountId, "json");
  if (!stored || typeof stored !== "object") return null;
  const now = new Date().toISOString();
  return {
    id: accountId,
    createdAt: cleanText(stored.createdAt) || now,
    lastSeenAt: cleanText(stored.lastSeenAt) || now,
    lastReportAt: cleanText(stored.lastReportAt) || null,
    updatedAt: cleanText(stored.updatedAt) || now,
    totalUploadBytes: boundedBytes(stored.totalUploadBytes),
    totalDownloadBytes: boundedBytes(stored.totalDownloadBytes),
    dailyUsage: normalizeUsageMap(stored.dailyUsage),
    monthlyUsage: normalizeUsageMap(stored.monthlyUsage),
    reportedSessionIds: Array.isArray(stored.reportedSessionIds)
      ? stored.reportedSessionIds.map(cleanText).filter(Boolean).slice(-200)
      : [],
  };
}

async function writeUser(env, user) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  await env.CONFIG.put(USER_KEY_PREFIX + user.id, JSON.stringify(user));
}

async function readUserIndex(env) {
  if (!env.CONFIG) {
    throw new Error("Missing KV binding CONFIG");
  }
  const stored = await env.CONFIG.get(USERS_INDEX_KEY, "json");
  return Array.isArray(stored)
    ? [...new Set(stored.map(normalizeAccountId).filter(Boolean))]
    : [];
}

async function addUserToIndex(env, accountId) {
  const index = await readUserIndex(env);
  if (!index.includes(accountId)) {
    index.push(accountId);
    await env.CONFIG.put(USERS_INDEX_KEY, JSON.stringify(index));
  }
}

function publicAccount(user) {
  return {
    id: user.id,
  };
}

function publicAdminUser(user, day, month) {
  const today = user.dailyUsage[day] || emptyUsage();
  const currentMonth = user.monthlyUsage[month] || emptyUsage();
  return {
    ...publicAccount(user),
    createdAt: user.createdAt,
    lastSeenAt: user.lastSeenAt,
    lastReportAt: user.lastReportAt,
    totalUploadBytes: user.totalUploadBytes,
    totalDownloadBytes: user.totalDownloadBytes,
    totalBytes: user.totalUploadBytes + user.totalDownloadBytes,
    today,
    month: currentMonth,
    dailyUsage: user.dailyUsage,
    monthlyUsage: user.monthlyUsage,
  };
}

function aggregateUsage(users, mapName, key) {
  return users.reduce((total, user) => {
    const usage = user[mapName] && user[mapName][key];
    if (usage) {
      total.uploadBytes += Number(usage.uploadBytes) || 0;
      total.downloadBytes += Number(usage.downloadBytes) || 0;
      total.totalBytes += Number(usage.totalBytes) || 0;
    }
    return total;
  }, emptyUsage());
}

function aggregateTotals(users) {
  return users.reduce((total, user) => {
    total.uploadBytes += user.totalUploadBytes;
    total.downloadBytes += user.totalDownloadBytes;
    total.totalBytes += user.totalUploadBytes + user.totalDownloadBytes;
    return total;
  }, emptyUsage());
}

function emptyUsage() {
  return { uploadBytes: 0, downloadBytes: 0, totalBytes: 0 };
}

function addUsage(map, key, uploadBytes, downloadBytes) {
  const current = map[key] || emptyUsage();
  current.uploadBytes += uploadBytes;
  current.downloadBytes += downloadBytes;
  current.totalBytes += uploadBytes + downloadBytes;
  map[key] = current;
}

function normalizeUsageMap(value) {
  if (!plainObject(value)) return {};
  return Object.fromEntries(Object.entries(value).map(([key, usage]) => {
    const uploadBytes = boundedBytes(usage && usage.uploadBytes);
    const downloadBytes = boundedBytes(usage && usage.downloadBytes);
    return [key, {
      uploadBytes,
      downloadBytes,
      totalBytes: uploadBytes + downloadBytes,
    }];
  }));
}

function pruneUsage(map, maxEntries) {
  const keys = Object.keys(map).sort();
  while (keys.length > maxEntries) {
    delete map[keys.shift()];
  }
}

function usagePeriodKeys(date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {});
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, month: `${parts.year}-${parts.month}` };
}

function normalizeAccountId(value) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

function boundedBytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number), 1e15)
    : 0;
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
    .map((item) => ({
      url: typeof item.url === "string" && /^https?:\/\//i.test(item.url.trim())
        ? item.url.trim()
        : "",
      name: cleanText(item.name || item.title),
      enabled: parseFlag(item.enabled, true),
      encrypted: parseFlag(
        item.encrypted !== undefined ? item.encrypted : item.isEncrypted,
        false,
      ),
    }))
    .filter((item) => item.url)
    .map((item, index) => ({
      id: String(index + 1),
      name: item.name || `线路${index + 1}`,
      url: item.url,
      enabled: item.enabled,
      encrypted: item.encrypted,
    }));

  return {
    version: 1,
    subscriptions,
    notice: plainObject(value.notice) ? value.notice : null,
    appUpdate: plainObject(value.appUpdate) ? value.appUpdate : null,
    settings: normalizeSettings(value.settings),
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
     .tab-nav { display: flex; gap: 8px; overflow-x: auto; padding: 6px; margin-top: 18px; background: #1f2937; border: 1px solid #374151; border-radius: 12px; scrollbar-width: thin; }
     .tab-button { flex: 0 0 auto; margin: 0; padding: 9px 14px; background: transparent; color: #aab4c5; border: 1px solid transparent; white-space: nowrap; }
     .tab-button:hover { background: #374151; color: #f9fafb; }
     .tab-button.active { background: #22c55e; color: #052e16; }
     .tab-panel { display: none; }
     .tab-panel.active { display: block; }
     .login-gate { max-width: 430px; margin: 12vh auto 0; }
     .login-gate h1 { margin-bottom: 10px; }
     .login-gate .card { margin-top: 18px; }
     .dashboard-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
     .dashboard-header button { flex: 0 0 auto; margin-top: 0; }
     #loginStatus { min-height: 24px; color: #fca5a5; margin-top: 10px; }
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
     .table-wrap { overflow-x: auto; }
     table { width: 100%; border-collapse: collapse; min-width: 760px; }
     th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid #374151; white-space: nowrap; }
     th { color: #aab4c5; font-size: 13px; font-weight: 600; }
     td code { color: #c4b5fd; }
     .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 10px; margin-top: 12px; }
     .stat { background: #111827; border-radius: 10px; padding: 12px; }
     .stat strong { display: block; color: #f9fafb; font-size: 17px; margin-top: 4px; }
     #status { min-height: 24px; color: #86efac; margin-top: 8px; }
     .hint { margin-top: 4px; }
  </style>
</head>
<body>
 <main>
   <section id="loginGate" class="login-gate">
     <h1>FireflyVPN 管理后台</h1>
     <p>请输入管理密码后进入后台。未验证前不会展示任何管理模块。</p>
     <div class="card">
       <form id="loginForm">
         <div class="field"><label>管理密码<input id="adminPassword" type="password" autocomplete="current-password" placeholder="输入 ADMIN_TOKEN"></label></div>
         <button type="submit">进入后台</button>
         <div id="loginStatus"></div>
       </form>
     </div>
   </section>

   <div id="dashboard" hidden>
     <div class="dashboard-header">
       <h1>FireflyVPN Worker 管理后台</h1>
       <button class="secondary" type="button" onclick="logout()">退出登录</button>
     </div>
     <p>在这里配置订阅线路、网站信息、联系方式、公告和版本更新。每个模块可单独推送到 Worker。</p>
     <div id="status"></div>

     <nav class="tab-nav" aria-label="后台模块">
     <button class="tab-button" type="button" data-tab="users">用户统计</button>
     <button class="tab-button" type="button" data-tab="subscriptions">订阅线路</button>
     <button class="tab-button" type="button" data-tab="app-settings">应用设置</button>
     <button class="tab-button" type="button" data-tab="notice">公告</button>
     <button class="tab-button" type="button" data-tab="app-update">版本更新</button>
   </nav>

   <section class="card tab-panel" data-panel="users">
     <h2>用户与流量统计</h2>
     <button class="secondary" onclick="loadUsers()">刷新用户列表</button>
     <div id="userSummary"><small>读取配置后显示用户统计。</small></div>
     <div id="usersTable" class="table-wrap"></div>
     <small class="hint">App 仅在 VPN 断开时上报本次会话累计流量。用户标识为设备标识的 SHA-256，不保存原始 MAC 或 Android ID。</small>
     <button type="button" onclick="pushSection('users')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="subscriptions">
    <h2>订阅线路</h2>
    <div id="subscriptionRows"></div>
     <button class="secondary" onclick="addSource()">添加线路</button>
     <small class="hint">Worker 只保存线路名称、订阅地址和加密标记，线路序号按有效线路自动生成 1、2、3…；详细节点内容由 App 直接请求并解析。</small>
     <button type="button" onclick="pushSection('subscriptions')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="app-settings">
    <h2>应用基础设置</h2>
    <div class="grid">
      <div class="field"><label>官方网站<input id="websiteUrl" type="url" placeholder="https://example.com"></label></div>
      <div class="field"><label>反馈邮箱<input id="feedbackEmail" type="email" placeholder="support@example.com"></label></div>
      <div class="field"><label>问题反馈链接<input id="feedbackUrl" type="url" placeholder="https://github.com/.../issues"></label></div>
      <div class="field"><label>项目源码链接<input id="githubUrl" type="url" placeholder="https://github.com/.../.../"></label></div>
      <div class="field"><label>后台请求超时（毫秒）<input id="nodeRequestTimeoutMs" type="number" min="5000" max="60000" step="1000"></label></div>
     </div>
     <small class="hint">留空的网站、邮箱或链接会在 App 中自动隐藏对应入口。超时范围为 5000～60000 毫秒。</small>
     <button type="button" onclick="pushSection('app-settings')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="notice">
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
     <button type="button" onclick="pushSection('notice')">推送</button>
   </section>

  <section class="card tab-panel" data-panel="app-update">
    <h2>版本更新</h2>
    <label class="check"><input id="appUpdateEnabled" type="checkbox">启用版本更新提示</label>
    <div class="grid">
      <div class="field"><label>版本名称<input id="appVersion" type="text" placeholder="1.1.0"></label></div>
      <div class="field"><label>版本号<input id="appVersionCode" type="number" min="1" step="1"></label></div>
      <div class="field"><label>APK 下载地址<input id="appDownloadUrl" type="url" placeholder="https://example.com/app.apk"></label></div>
     </div>
     <div class="field"><label>更新说明<textarea id="appChangelog"></textarea></label></div>
     <label class="check"><input id="appForce" type="checkbox">强制更新</label>
     <button type="button" onclick="pushSection('app-update')">推送</button>
   </section>
   </div>
 </main>
<script>
 let currentState = null;
 let adminToken = '';
 const ADMIN_API_BASE = "${ADMIN_BASE_PATH}/api";
 const loginGate = document.getElementById('loginGate');
 const loginForm = document.getElementById('loginForm');
 const passwordInput = document.getElementById('adminPassword');
 const loginStatusEl = document.getElementById('loginStatus');
 const statusEl = document.getElementById('status');
 function el(id) { return document.getElementById(id); }
 function value(id) { return el(id).value.trim(); }
 function setValue(id, data) { el(id).value = data == null ? '' : data; }
 function checked(id, data) { el(id).checked = Boolean(data); }
 function esc(data) { return String(data == null ? '' : data).replace(/[&<>"']/g, function(c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
 function headers() { return {'content-type':'application/json','authorization':'Bearer ' + adminToken}; }
 function show(message, error) { statusEl.textContent = message; statusEl.style.color = error ? '#fca5a5' : '#86efac'; }
 function showLoginError(message) { loginStatusEl.textContent = message || ''; }
 loginForm.onsubmit = async function(event) {
   event.preventDefault();
   const password = passwordInput.value.trim();
   if (!password) return showLoginError('请输入管理密码');
   adminToken = password;
   showLoginError('验证中…');
   try {
     const response = await fetch(ADMIN_API_BASE + '/state', {headers: headers()});
     const data = await response.json();
     if (!response.ok) {
       adminToken = '';
       return showLoginError('密码错误或后台地址无效');
     }
     currentState = data;
     loginGate.hidden = true;
     el('dashboard').hidden = false;
     fillState(data);
     activateTab('users');
     await loadUsers();
     show('登录成功');
     passwordInput.value = '';
     showLoginError('');
   } catch (error) {
     adminToken = '';
     showLoginError('无法连接后台，请检查网络');
   }
 };
 function logout() {
   adminToken = '';
   currentState = null;
   el('dashboard').hidden = true;
   loginGate.hidden = false;
   passwordInput.value = '';
   showLoginError('');
   passwordInput.focus();
 }
function activateTab(name) {
  document.querySelectorAll('.tab-button').forEach(function(button) {
    const active = button.dataset.tab === name;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', active ? 'true' : 'false');
  });
  document.querySelectorAll('.tab-panel').forEach(function(panel) {
    panel.classList.toggle('active', panel.dataset.panel === name);
  });
}
document.querySelectorAll('.tab-button').forEach(function(button) {
  button.onclick = function() { activateTab(button.dataset.tab); };
});
function formatBytes(bytes) {
  let value = Number(bytes) || 0;
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
  return (index === 0 ? Math.round(value) : value.toFixed(value >= 100 ? 0 : 2)) + ' ' + units[index];
}
function usageTotal(usage) { return formatBytes(usage && usage.totalBytes); }
function renderUsers(data) {
  const totals = data.totals || {};
  const today = totals.today || {};
  const month = totals.month || {};
  el('userSummary').innerHTML = '<div class="stats">' +
    '<div class="stat"><small>' + esc(data.date || '今日') + ' · 所有用户</small><strong>' + usageTotal(today) + '</strong></div>' +
    '<div class="stat"><small>' + esc(data.month || '本月') + ' · 所有用户</small><strong>' + usageTotal(month) + '</strong></div>' +
    '<div class="stat"><small>所有用户 · 累计</small><strong>' + usageTotal(totals.allTime) + '</strong></div>' +
    '<div class="stat"><small>账户数量</small><strong>' + ((data.users || []).length) + '</strong></div>' +
    '</div>';
  const rows = (data.users || []).map(function(user) {
    return '<tr>' +
      '<td><code title="' + esc(user.id) + '">' + esc(user.id.slice(0, 16) + '…') + '</code></td>' +
      '<td>' + usageTotal(user.today) + '</td>' +
      '<td>' + usageTotal(user.month) + '</td>' +
      '<td>' + usageTotal(user) + '</td>' +
      '<td>' + esc(user.lastReportAt || '暂无') + '</td>' +
      '</tr>';
  }).join('');
  el('usersTable').innerHTML = rows
    ? '<table><thead><tr><th>账户</th><th>今日</th><th>本月</th><th>累计</th><th>最后上报</th></tr></thead><tbody>' + rows + '</tbody></table>'
    : '<small>暂无注册用户。</small>';
}
async function loadUsers() {
  const response = await fetch(ADMIN_API_BASE + '/users', {headers: headers()});
  const data = await response.json();
  if (!response.ok) return show(data.error || '读取用户失败', true);
  renderUsers(data);
}
function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  row.innerHTML = '<div class="grid">' +
    '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
    '<div class="field"><label>订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '"></label></div>' +
    '</div><label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
    '<label class="check"><input data-field="encrypted" type="checkbox" ' + (source.encrypted ? 'checked' : '') + '>接口内容已加密（AES-GCM）</label>' +
    '<div class="row-actions"><small>App 将直接请求此地址</small><button class="danger" type="button">删除</button></div>';
  row.querySelector('.danger').onclick = function() { row.remove(); };
  el('subscriptionRows').appendChild(row);
}
function fillState(state) {
  currentState = state || {};
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
  const subscriptions = Array.from(document.querySelectorAll('.source-row')).map(function(row, index) {
    return {
      name: row.querySelector('[data-field="name"]').value.trim() || '线路' + (index + 1),
      url: row.querySelector('[data-field="url"]').value.trim(),
      enabled: row.querySelector('[data-field="enabled"]').checked,
      encrypted: row.querySelector('[data-field="encrypted"]').checked
    };
  }).filter(function(item) { return item.url; });
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
async function pushSection(section) {
  if (section === 'users') {
    await loadUsers();
    show('用户统计已刷新');
    return;
  }

  const form = formState();
  const state = {
    subscriptions: section === 'subscriptions' ? form.subscriptions : currentState.subscriptions,
    settings: section === 'app-settings' ? form.settings : currentState.settings,
    notice: section === 'notice' ? form.notice : currentState.notice,
    appUpdate: section === 'app-update' ? form.appUpdate : currentState.appUpdate
  };
  const response = await fetch(ADMIN_API_BASE + '/state', {
    method: 'PUT',
    headers: headers(),
    body: JSON.stringify(state)
  });
  const data = await response.json();
  if (!response.ok) return show(data.error || '推送失败', true);
  currentState = data.state;
  fillState(data.state);
  show('当前模块已推送');
}
addSource();
</script>
</body>
</html>`;
}
