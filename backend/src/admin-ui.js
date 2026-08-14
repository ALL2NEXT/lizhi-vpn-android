import { ADMIN_BASE_PATH } from "./config.js";

export function adminHtml() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>管理后台</title>
  <style>
    :root { color-scheme: dark; font-family: system-ui, -apple-system, sans-serif; }
     body { min-height: 100vh; margin: 0; background: #111827; color: #e5e7eb; }
     main { width: 100%; min-height: 100vh; margin: 0; }
     h1 { font-size: 24px; margin: 0 0 6px; }
     h2 { font-size: 18px; margin: 0 0 14px; }
     p, small, label { color: #aab4c5; line-height: 1.55; }
     .card { background: #1f2937; border: 1px solid #374151; border-radius: 14px; padding: 18px; margin-top: 16px; }
     .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
     .loading-state, .error-state { display: flex; min-height: 160px; align-items: center; justify-content: center; padding: 18px; color: #94a3b8; text-align: center; }
     .error-state { color: #fca5a5; }
     #dashboard { display: grid; grid-template-columns: clamp(170px, 16vw, 230px) minmax(0, 1fr); min-height: 100vh; }
     #dashboard[hidden] { display: none; }
     .dashboard-sidebar { position: sticky; top: 0; box-sizing: border-box; display: flex; flex-direction: column; width: 100%; height: 100vh; padding: 22px 14px 16px; overflow: hidden; background: #1f2937; border-right: 1px solid #374151; }
     .sidebar-brand { padding: 0 8px 20px; border-bottom: 1px solid #374151; }
     .sidebar-brand h1 { margin: 0; font-size: 20px; }
     .sidebar-brand small { display: block; margin-top: 4px; }
     .tab-nav { display: flex; flex: 1 1 auto; flex-direction: column; gap: 8px; overflow-y: auto; padding: 18px 0; margin: 0; scrollbar-width: thin; }
     .tab-button { flex: 0 0 auto; width: 100%; margin: 0; padding: 12px 14px; text-align: left; background: transparent; color: #aab4c5; border: 1px solid transparent; white-space: nowrap; }
     .tab-button:hover { background: #374151; color: #f9fafb; }
     .tab-button.active { background: #22c55e; color: #052e16; }
     .nav-group { flex: 0 0 auto; }
     .nav-parent { display: flex; align-items: center; justify-content: space-between; }
     .nav-chevron { transition: transform .18s ease; }
     .nav-parent[aria-expanded="true"] .nav-chevron { transform: rotate(180deg); }
     .nav-submenu { display: grid; gap: 4px; margin: -2px 0 4px 10px; padding-left: 10px; border-left: 1px solid #4b5563; }
     .nav-submenu[hidden] { display: none; }
     .nav-submenu .tab-button { padding: 9px 12px; font-size: 13px; }
     .nav-submenu .tab-button.active { background: #166534; color: #dcfce7; }

     .sidebar-logout { width: 100%; margin: 16px 0 0; }
     .dashboard-workspace { display: flex; flex-direction: column; min-width: 0; min-height: 100vh; background: #111827; }
     .tab-panel { display: none; box-sizing: border-box; flex: 1 1 auto; width: 100%; min-height: 0; padding: 28px; margin: 0; background: #111827; border: 0; border-radius: 0; }
     .tab-panel.active { display: block; }
     .dashboard-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
     .dashboard-heading h2 { margin-bottom: 6px; }
     .dashboard-heading p { margin: 0; }
     .dashboard-heading button { flex: 0 0 auto; margin: 0; }
     .growth-card { margin-bottom: 14px; padding: 18px; background: #1f2937; border: 1px solid #374151; border-radius: 14px; }
     .growth-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
     .growth-toolbar h3 { margin: 0 0 4px; color: #f8fafc; font-size: 16px; }
     .growth-toolbar small { display: block; }
     .growth-range-controls { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 8px; margin-top: 12px; }
     .growth-range-field { display: flex; flex-direction: column; gap: 4px; color: #aab4c5; font-size: 12px; }
     .growth-range-field input { width: 150px; padding: 8px 10px; }
     .growth-range-controls button { margin: 0; padding: 8px 12px; }
     .growth-period-switch { display: flex; flex-wrap: wrap; gap: 6px; }
     .growth-period-button { margin: 0; padding: 8px 12px; background: #374151; color: #cbd5e1; }
     .growth-period-button.active { background: #22c55e; color: #052e16; }
     .growth-chart-host { min-height: 360px; margin-top: 16px; overflow-x: auto; }
     .growth-chart { display: block; width: 100%; min-width: 680px; height: 330px; }
     .growth-summary { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 2px; color: #cbd5e1; font-size: 13px; }
     .growth-summary strong { color: #f8fafc; font-size: 16px; }
     .growth-legend { display: inline-flex; align-items: center; gap: 6px; }
     .growth-legend i { width: 18px; height: 3px; border-radius: 999px; background: #38bdf8; }
     .traffic-chart-host { min-height: 360px; margin-top: 16px; overflow-x: auto; }
     .traffic-chart { display: block; width: 100%; min-width: 680px; height: 330px; }
     .traffic-legend { display: inline-flex; align-items: center; gap: 6px; }
     .traffic-legend i { width: 18px; height: 3px; border-radius: 999px; }
     .traffic-upload i { background: #22c55e; }
     .traffic-download i { background: #a78bfa; }
     .traffic-total i { background: #f59e0b; }
     .growth-empty { min-height: 300px; border: 1px dashed #4b5563; border-radius: 12px; }
     .distribution-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
     .distribution-card { min-width: 0; padding: 18px; background: #1f2937; border: 1px solid #374151; border-radius: 14px; }
     .distribution-card h3 { margin: 0 0 4px; color: #f8fafc; font-size: 16px; }
     .distribution-card > small { display: block; min-height: 40px; }
     .distribution-list { display: grid; gap: 8px; margin-top: 16px; }
     .distribution-total { padding-bottom: 8px; color: #f8fafc; border-bottom: 1px solid #374151; font-size: 13px; }
     .distribution-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; min-width: 0; padding: 8px 0; color: #cbd5e1; font-size: 13px; }
     .distribution-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
     .distribution-value { color: #94a3b8; font-variant-numeric: tabular-nums; white-space: nowrap; }
     .distribution-empty { min-height: 120px; border: 1px dashed #4b5563; border-radius: 12px; }
     .login-gate { max-width: 430px; margin: 12vh auto 0; }
     .login-gate h1 { margin-bottom: 10px; }
     .login-gate .card { margin-top: 18px; }
     #loginStatus { min-height: 24px; color: #fca5a5; margin-top: 10px; }
     .field { display: flex; flex-direction: column; gap: 6px; }
    input, textarea, select { box-sizing: border-box; width: 100%; color: #e5e7eb; background: #111827; border: 1px solid #4b5563; border-radius: 8px; padding: 10px; }
    input[type="checkbox"] { width: auto; accent-color: #22c55e; transform: scale(1.15); margin-right: 8px; }
    textarea { min-height: 110px; resize: vertical; }
    button { cursor: pointer; border: 0; border-radius: 8px; padding: 10px 14px; margin: 10px 8px 0 0; background: #22c55e; color: #052e16; font-weight: 700; }
    button.secondary { background: #374151; color: #e5e7eb; }
    button.danger { background: #7f1d1d; color: #fee2e2; }
    .check { display: flex; align-items: center; margin: 8px 0; }
    .source-row { border: 1px solid #4b5563; border-radius: 10px; padding: 14px; margin: 10px 0; }
    .source-section { padding: 12px; background: #172033; border: 1px solid #374151; border-radius: 8px; }
    .source-section + .source-section { margin-top: 12px; }
    .source-section-title { margin: 0 0 10px; color: #f8fafc; font-size: 14px; }
    .source-identity-grid { grid-template-columns: repeat(2, minmax(220px, 1fr)); }
    .source-permissions-grid { align-items: end; }
    .source-permissions-grid .check { min-height: 42px; margin: 0; padding: 0 10px; background: #111827; border: 1px solid #4b5563; border-radius: 8px; }
    .source-details-grid { margin-bottom: 12px; }
    .source-mode { margin-top: 12px; }
    .source-mode[hidden] { display: none; }
    .source-type-field { max-width: 280px; }
     .source-row .row-actions { display: flex; align-items: center; justify-content: space-between; }
     .source-row small { display: block; margin-top: 8px; }
     .security-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
     .security-toolbar input { flex: 1 1 280px; max-width: 520px; }
     .security-toolbar button { margin: 0; }
     .security-batch-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: -4px 0 16px; padding: 10px 12px; background: #172033; border: 1px solid #374151; border-radius: 8px; }
     .security-batch-toolbar button { margin: 0; }
     .security-batch-toolbar label { display: inline-flex; align-items: center; margin: 0; color: #e5e7eb; white-space: nowrap; }
     .security-batch-toolbar input[type="checkbox"] { margin-right: 8px; }
     .security-selection-count { color: #94a3b8; font-size: 12px; }
     .security-summary { margin: 0 0 14px; color: #94a3b8; }
     .security-account-list { overflow: auto; border: 1px solid #374151; border-radius: 10px; background: #111827; }
     .security-account-table { width: max-content; min-width: 100%; border-collapse: collapse; font-size: 12px; }
     .security-account-table th, .security-account-table td { padding: 10px 12px; text-align: left; vertical-align: middle; border-right: 1px solid #374151; border-bottom: 1px solid #374151; white-space: nowrap; }
     .security-account-table th { position: sticky; top: 0; z-index: 1; color: #d1d5db; background: #273244; }
     .security-account-table td { color: #e5e7eb; }
     .security-account-table tr.banned td { background: #2a171d; }
     .security-account-table code { color: #c4b5fd; }
     .security-account-name { color: #f8fafc; font-weight: 700; }
     .security-account-email { color: #cbd5e1; }
     .security-account-devices { min-width: 180px; white-space: normal; }
     .security-account-devices summary { cursor: pointer; color: #cbd5e1; }
     .security-account-devices summary:hover { color: #f8fafc; }
     .security-badge { display: inline-flex; align-items: center; padding: 3px 8px; border-radius: 999px; color: #bbf7d0; background: #14532d; font-size: 12px; font-weight: 700; }
     .security-badge.banned { color: #fecaca; background: #7f1d1d; }
     .security-action { flex: 0 0 auto; margin: 0; }
     .security-device-wrap { margin-top: 14px; overflow: auto; border: 1px solid #374151; border-radius: 8px; }
     .security-device-table { width: 100%; min-width: 860px; border-collapse: collapse; font-size: 12px; }
     .security-device-table th, .security-device-table td { padding: 9px; text-align: left; border-right: 1px solid #374151; border-bottom: 1px solid #374151; vertical-align: top; }
     .security-device-table th { color: #d1d5db; background: #273244; white-space: nowrap; }
     .security-device-table code { color: #c4b5fd; word-break: break-all; white-space: normal; }
     .security-device-empty { margin-top: 14px; padding: 12px; color: #94a3b8; border: 1px dashed #4b5563; border-radius: 8px; }
     .user-sheet-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
     .user-sheet-toolbar button { margin: 0; }
     .user-sheet-toolbar small { margin-left: auto; }
     .batch-level-select { width: auto; min-width: 105px; margin: 0; padding: 9px 10px; }
     .user-search { flex: 1 1 220px; width: min(300px, 100%); min-width: 180px; padding: 9px 10px; }
      .table-wrap { max-height: calc(100vh - 210px); overflow: auto; border: 1px solid #4b5563; border-radius: 10px; background: #111827; }
      .user-tables { display: grid; gap: 14px; }
      .user-table-card { min-width: 0; padding: 14px; background: #111827; border: 1px solid #374151; border-radius: 10px; }
      .user-table-card h3 { margin: 0 0 8px; color: #f8fafc; font-size: 16px; }
      .user-table-meta { margin-bottom: 10px; color: #94a3b8; font-size: 12px; }
      .user-table-scroll { max-height: 520px; overflow: auto; border: 1px solid #374151; border-radius: 8px; }
      table.user-data-table { width: max-content; min-width: 100%; border-collapse: collapse; font-size: 12px; }
      .user-data-table th, .user-data-table td { padding: 8px; text-align: left; vertical-align: top; border-right: 1px solid #374151; border-bottom: 1px solid #374151; white-space: pre-wrap; word-break: break-word; }
      .user-data-table th { position: sticky; top: 0; z-index: 1; color: #d1d5db; background: #273244; white-space: nowrap; }
      .user-data-table td { min-width: 110px; color: #e5e7eb; }
      .user-data-table td:first-child, .user-data-table th:first-child { min-width: 42px; color: #94a3b8; }
      .user-table-empty { padding: 18px; color: #94a3b8; border: 1px dashed #4b5563; border-radius: 8px; }
      table.user-sheet { width: 100%; min-width: 100%; table-layout: fixed; border-collapse: separate; border-spacing: 0; font-size: 13px; }
     .user-sheet th, .user-sheet td { text-align: left; padding: 0; overflow: hidden; text-overflow: ellipsis; border-right: 1px solid #374151; border-bottom: 1px solid #374151; white-space: nowrap; background: #111827; }
     .user-sheet th { position: sticky; top: 0; z-index: 3; padding: 9px 12px 9px 8px; overflow: visible; color: #d1d5db; background: #273244; font-weight: 700; user-select: none; }
     .user-sheet th[data-sort-column] { cursor: pointer; }
     .user-sheet th[data-sort-column]:hover { background: #334155; color: #f8fafc; }
     .sort-indicator { display: inline-block; width: 14px; color: #4ade80; }
     .column-resizer { position: absolute; top: 0; right: -4px; width: 9px; height: 100%; z-index: 6; cursor: col-resize; touch-action: none; }
     .column-resizer::after { content: ''; position: absolute; top: 20%; bottom: 20%; left: 4px; width: 1px; background: #4b5563; }
     .column-resizer:hover::after, .column-resizer:focus::after, body.column-resizing .column-resizer::after { background: #22c55e; width: 2px; }
     body.column-resizing, body.column-resizing * { cursor: col-resize !important; user-select: none !important; }
     .user-sheet th:first-child, .user-sheet td:first-child { position: sticky; left: 0; z-index: 2; }
     .user-sheet th:first-child { z-index: 4; }
     .user-sheet td:first-child { padding: 8px; background: #172033; }
     .user-sheet .selection-cell { text-align: center; padding: 8px !important; }
     .user-sheet .selection-cell input { margin: 0; }
     .user-sheet td.readonly { padding: 8px; color: #cbd5e1; }
     .user-sheet td code { color: #c4b5fd; }
     .sheet-input, .sheet-select { width: 100%; min-width: 0; height: 36px; padding: 7px 8px; border: 0; border-radius: 0; background: transparent; }
     .sheet-input:focus, .sheet-select:focus { position: relative; z-index: 1; outline: 2px solid #22c55e; outline-offset: -2px; background: #0f172a; }
     .sheet-save, .sheet-delete { margin: 4px 2px; padding: 7px 10px; }
     .user-row.dirty td { background: #312e1f; }
     .user-row.dirty td:first-child { background: #3b321d; }
      .user-row.search-current td { box-shadow: inset 0 0 0 2px #38bdf8; }
      .user-row[hidden] { display: none; }
      button:disabled { cursor: not-allowed; opacity: .5; }
     #status { position: sticky; top: 0; z-index: 20; box-sizing: border-box; color: #86efac; background: #111827; }
     #status:not(:empty) { min-height: 44px; padding: 11px 28px; border-bottom: 1px solid #374151; }
     .hint { margin-top: 4px; }
     @media (max-width: 1200px) {
       .distribution-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
     }
     @media (max-width: 760px) {
       #dashboard { grid-template-columns: 148px minmax(0, 1fr); }
       .dashboard-sidebar { padding: 16px 10px 12px; }
       .sidebar-brand { padding: 0 6px 14px; }
       .sidebar-brand h1 { font-size: 17px; }
       .sidebar-brand small { display: none; }
       .tab-nav { padding: 14px 0; }
       .tab-button { padding: 11px 10px; }
       .tab-panel { padding: 18px; }
       #status:not(:empty) { padding: 10px 18px; }
       .grid { grid-template-columns: 1fr; }
       .dashboard-heading { align-items: stretch; flex-direction: column; }
       .dashboard-heading button { align-self: flex-start; }
       .growth-toolbar { flex-direction: column; }
       .growth-range-controls { align-items: stretch; }
       .growth-range-field, .growth-range-field input { width: 100%; }
       .growth-period-switch { align-self: flex-start; }
       .distribution-grid { grid-template-columns: 1fr; }
       .table-wrap { max-height: calc(100vh - 190px); }
     }
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
     <aside class="dashboard-sidebar">
       <div class="sidebar-brand">
         <h1>FireflyVPN</h1>
         <small>管理后台</small>
       </div>
       <nav class="tab-nav" role="tablist" aria-label="后台模块" aria-orientation="vertical">
          <button class="tab-button" role="tab" type="button" data-tab="data-dashboard">数据看板</button>
          <button class="tab-button" role="tab" type="button" data-tab="account-security">账户安全</button>
          <button class="tab-button" role="tab" type="button" data-tab="subscriptions">订阅线路</button>
          <div class="nav-group" data-nav-group="app-settings">
            <button class="tab-button nav-parent" type="button" aria-expanded="true" aria-controls="app-settings-subnav">
              <span>应用设置</span><span class="nav-chevron" aria-hidden="true">⌄</span>
            </button>
            <div id="app-settings-subnav" class="nav-submenu" role="group" aria-label="应用设置子菜单">
              <button class="tab-button" role="tab" type="button" data-tab="app-settings">应用基础设置</button>
              <button class="tab-button" role="tab" type="button" data-tab="notice">公告</button>
              <button class="tab-button" role="tab" type="button" data-tab="app-update">版本更新</button>
            </div>
          </div>
       </nav>
       <button class="secondary sidebar-logout" type="button" onclick="logout()">退出登录</button>
     </aside>

     <div class="dashboard-workspace">
       <div id="status" role="status" aria-live="polite"></div>

   <section class="card tab-panel" role="tabpanel" data-panel="data-dashboard">
     <div class="dashboard-heading">
       <div>
         <h2>数据看板</h2>
         <p>基于当前已注册账户，查看设备厂商、Android 系统和 App 版本分布。</p>
       </div>
       <button class="secondary" type="button" onclick="loadUsers()">刷新数据</button>
     </div>
    <article class="growth-card">
      <div class="growth-toolbar">
        <div>
          <h3>用户增长</h3>
          <small>按日、按周、按月统计注册新增用户，折线显示累计用户数量。</small>
          <div class="growth-range-controls" aria-label="用户增长时间范围">
            <label class="growth-range-field">开始日期<input id="growthStartDate" type="date"></label>
            <label class="growth-range-field">结束日期<input id="growthEndDate" type="date"></label>
            <button class="secondary" type="button" onclick="applyGrowthRange()">应用范围</button>
            <button class="secondary" type="button" onclick="clearGrowthRange()">全部时间</button>
          </div>
        </div>
        <div class="growth-period-switch" role="group" aria-label="用户增长统计粒度">
          <button class="growth-period-button active" type="button" data-growth-period="day" onclick="setGrowthPeriod('day')">按日</button>
          <button class="growth-period-button" type="button" data-growth-period="week" onclick="setGrowthPeriod('week')">按周</button>
          <button class="growth-period-button" type="button" data-growth-period="month" onclick="setGrowthPeriod('month')">按月</button>
        </div>
      </div>
      <div id="userGrowthChart" class="growth-chart-host"><div class="loading-state">正在读取用户增长数据…</div></div>
      <div id="userGrowthSummary" class="growth-summary" aria-live="polite"></div>
    </article>
    <article class="growth-card">
      <div class="growth-toolbar">
        <div>
          <h3>流量消耗</h3>
          <small>上传和下载使用堆叠柱状图，总流量使用折线显示。</small>
          <div class="growth-range-controls" aria-label="流量统计时间范围">
            <label class="growth-range-field">开始日期<input id="trafficStartDate" type="date"></label>
            <label class="growth-range-field">结束日期<input id="trafficEndDate" type="date"></label>
            <button class="secondary" type="button" onclick="applyTrafficRange()">应用范围</button>
            <button class="secondary" type="button" onclick="clearTrafficRange()">全部时间</button>
          </div>
        </div>
        <div class="growth-period-switch" role="group" aria-label="流量统计粒度">
          <button class="growth-period-button active" type="button" data-traffic-period="day" onclick="setTrafficPeriod('day')">按日</button>
          <button class="growth-period-button" type="button" data-traffic-period="week" onclick="setTrafficPeriod('week')">按周</button>
          <button class="growth-period-button" type="button" data-traffic-period="month" onclick="setTrafficPeriod('month')">按月</button>
        </div>
      </div>
      <div id="trafficChart" class="traffic-chart-host"><div class="loading-state">正在读取流量数据…</div></div>
      <div id="trafficSummary" class="growth-summary" aria-live="polite"></div>
    </article>
     <div class="distribution-grid">
       <article class="distribution-card">
         <h3>设备厂商</h3>
         <small>相同厂商的大小写和常见子品牌会自动合并。</small>
         <div id="brandDistribution" class="distribution-list"><div class="loading-state">正在读取厂商数据…</div></div>
       </article>
       <article class="distribution-card">

         <h3>Android 系统版本</h3>
         <small>按账户最近一次登记的 Android 版本统计。</small>
         <div id="androidDistribution" class="distribution-list"><div class="loading-state">正在读取系统版本…</div></div>
       </article>
       <article class="distribution-card">
         <h3>App 软件版本</h3>
         <small>按账户最近一次登记的 FireflyVPN 版本统计。</small>
         <div id="appVersionDistribution" class="distribution-list"><div class="loading-state">正在读取软件版本…</div></div>
       </article>
     </div>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="account-security">
    <div class="dashboard-heading">
      <div>
        <h2>账户与设备封禁</h2>
        <p>封禁账户会同时封禁该账户登录过的全部历史设备；解除后只移除由该账户产生的封禁。</p>
      </div>
      <button class="secondary" type="button" onclick="loadAccountSecurity(true)">刷新数据</button>
    </div>
    <div class="security-toolbar">
      <input id="securitySearch" type="search" placeholder="搜索 UID、昵称、邮箱或设备 ID" oninput="renderAccountSecurity()">
    </div>
    <div id="securityBatchToolbar" class="security-batch-toolbar">
      <label><input id="securitySelectAll" type="checkbox" onchange="toggleAllSecurityAccounts(this.checked)">全选当前筛选结果</label>
      <span id="securitySelectionCount" class="security-selection-count">已选择 0 个账户</span>
      <button id="securityBatchBan" class="danger" type="button" disabled onclick="batchSetAccountBan(true)">批量封禁</button>
      <button id="securityBatchUnban" class="secondary" type="button" disabled onclick="batchSetAccountBan(false)">批量解除封禁</button>
      <button class="secondary" type="button" onclick="clearSecuritySelection()">清除选择</button>
    </div>
    <div id="securitySummary" class="security-summary"></div>
    <div id="securityAccountList" class="security-account-list">
      <div class="loading-state">打开此模块后读取账户安全数据</div>
    </div>
  </section>

  <section class="card tab-panel" role="tabpanel" data-panel="subscriptions">
    <h2>订阅线路</h2>
    <div id="subscriptionRows"></div>
     <button class="secondary" onclick="addSource()">添加线路</button>
     <small class="hint">每条线路只能选择一种来源：外部接口或纯文本节点。外部接口可设置缓存时间；纯文本节点不使用缓存。还可以按账户等级和最低应用版本限制下发。</small>
     <button type="button" onclick="pushSection('subscriptions')">推送</button>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="app-settings">
    <h2>应用基础设置</h2>
    <div class="grid">
      <div class="field"><label>官方网站<input id="websiteUrl" type="url" placeholder="https://example.com"></label></div>
      <div class="field"><label>反馈邮箱<input id="feedbackEmail" type="email" placeholder="support@example.com"></label></div>
      <div class="field"><label>问题反馈链接<input id="feedbackUrl" type="url" placeholder="https://github.com/.../issues"></label></div>
       <div class="field"><label>项目源码链接<input id="githubUrl" type="url" placeholder="https://github.com/.../.../"></label></div>
       <div class="field"><label>后台请求超时（毫秒）<input id="nodeRequestTimeoutMs" type="number" min="5000" max="60000" step="1000"></label></div>
       <div class="field"><label>每日签到经验奖励<input id="dailyCheckinExperience" type="number" min="0" step="1"></label></div>
       <div class="field"><label>TG 群连接<input id="telegramGroupUrl" type="text" placeholder="https://t.me/your_group"></label></div>
       <div class="field"><label>TG 进群口令<input id="telegramJoinCode" type="text" placeholder="例如：FIREFLY2026"></label></div>
       <div class="field"><label>TG 任务经验奖励<input id="telegramTaskExperience" type="number" min="0" step="1"></label></div>
       <div class="field"><label>智能测速共享经验奖励<input id="speedShareSmartExperience" type="number" min="0" step="1"></label></div>
      <div class="field"><label>Lv1 → Lv2 所需经验<input id="experienceThresholdLv2" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv2 → Lv3 所需经验<input id="experienceThresholdLv3" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv3 → Lv4 所需经验<input id="experienceThresholdLv4" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv4 → Lv5 所需经验<input id="experienceThresholdLv5" type="number" min="1" step="1"></label></div>
      <div class="field"><label>Lv5 → Lv6 所需经验<input id="experienceThresholdLv6" type="number" min="1" step="1"></label></div>
     </div>
     <small class="hint">绑定邮箱可获得经验，测速共享成功后也会发放经验；达到对应门槛后自动升级。Lv0 不会获得经验。经验门槛是每个等级区间所需的经验，超出经验会保留到下一级。</small>
     <button type="button" onclick="pushSection('app-settings')">推送</button>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="notice">
    <h2>公告</h2>
    <label class="check"><input id="noticeEnabled" type="checkbox">显示公告</label>
    <div class="grid">
      <div class="field"><label>公告标题<input id="noticeTitle" type="text"></label></div>
      <div class="field"><label>公告 ID<input id="noticeId" type="text" placeholder="notice_20260806"></label></div>
      <div class="field"><label>开始显示时间<input id="noticeStartAt" type="datetime-local"></label></div>
      <div class="field"><label>结束显示时间<input id="noticeEndAt" type="datetime-local"></label></div>
    </div>
    <div class="field"><label>公告内容<textarea id="noticeContent" placeholder="支持普通文本和 HTML"></textarea></label></div>
    <label class="check"><input id="noticeShowOnce" type="checkbox">每个公告 ID 只显示一次</label>
    <small class="hint">时间按北京时间执行。开始时间留空表示立即显示，结束时间留空表示永不过期。</small>
     <button type="button" onclick="pushSection('notice')">推送</button>
   </section>

  <section class="card tab-panel" role="tabpanel" data-panel="app-update">
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
   </div>
 </main>
<script>
  let currentState = null;
  let adminToken = '';
  let currentUsersData = null;
  let accountSecurityData = null;
  let accountSecurityLoading = false;
  const accountSecuritySelection = new Set();
  let growthPeriod = 'day';
 let growthRequestId = 0;
 let trafficPeriod = 'day';
 let trafficRequestId = 0;
 const dirtyUserIds = new Set();
 const selectedUserIds = new Set();
 let savingAllUsers = false;
 let userSearchMatches = [];
 let userSearchIndex = -1;
 let userSortKey = '';
 let userSortDirection = 'desc';
 const USER_COLUMN_WIDTHS_KEY = 'firefly:user-sheet-column-widths:v1';
 const USER_SHEET_COLUMNS = [
   {key: 'select', label: '选择', width: 48, min: 44},
   {key: 'accountId', label: '用户 UID', width: 150, min: 110},
   {key: 'nickname', label: '昵称', width: 110, min: 70},
   {key: 'level', label: '等级', width: 80, min: 65},
   {key: 'brand', label: '品牌', width: 90, min: 60},
   {key: 'model', label: '型号', width: 120, min: 70},
   {key: 'androidVersion', label: 'Android', width: 85, min: 65},
   {key: 'appVersion', label: 'App 版本', width: 85, min: 65},
   {key: 'activeToday', label: '今日活跃', width: 70, min: 60},
   {key: 'activeMonth', label: '本月活跃', width: 70, min: 60},
   {key: 'trafficTodayUpload', label: '今日上传', width: 90, min: 65},
   {key: 'trafficTodayDownload', label: '今日下载', width: 90, min: 65},
   {key: 'trafficToday', label: '今日总流量', width: 90, min: 65},
   {key: 'trafficMonthUpload', label: '本月上传', width: 90, min: 65},
   {key: 'trafficMonthDownload', label: '本月下载', width: 90, min: 65},
   {key: 'trafficMonth', label: '本月总流量', width: 90, min: 65},
   {key: 'trafficTotalUpload', label: '累计上传', width: 90, min: 65},
   {key: 'trafficTotalDownload', label: '累计下载', width: 90, min: 65},
   {key: 'trafficTotal', label: '累计总流量', width: 90, min: 65},
   {key: 'createdAt', label: '注册时间', width: 140, min: 90},
   {key: 'lastReportAt', label: '最后上报', width: 140, min: 90},
   {key: 'actions', label: '操作', width: 130, min: 100}
 ];
 let userColumnWidths = loadUserColumnWidths();
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
 function parseResponseJson(responseText) {
   try { return JSON.parse(responseText); } catch (_) { return {}; }
 }
 function adminApiUrl(path) {
   return new URL(ADMIN_API_BASE + path, window.location.origin).toString();
 }
 function adminErrorMessage(error, fallback) {
    if (error === 'invalid_batch_user_update') return '批量修改参数无效，最多选择 100 个用户，等级范围为 Lv0～Lv6';
     if (error === 'invalid_batch_user_delete') return '批量删除参数无效，最多选择 100 个用户';
    if (error === 'subscription_must_be_plaintext_nodes') return '订阅必须返回可识别的明文节点，不能使用已加密接口';
    if (error === 'invalid_min_app_version') return '最低应用版本格式无效，请填写类似 2.40 的数字版本';
   if (error === 'external_subscription_unreachable') return '外部明文订阅接口无法访问';
   if (error === 'invalid_notice_start_at') return '公告开始时间格式无效';
   if (error === 'invalid_notice_end_at') return '公告结束时间格式无效';
   if (error === 'invalid_notice_time_order') return '公告结束时间不能早于开始时间';
   if (error === 'external_subscription_url_required') return '请填写外部明文订阅地址';
   if (String(error || '').indexOf('external_subscription_http_') === 0) {
     return '外部明文订阅接口请求失败（HTTP ' + String(error).slice('external_subscription_http_'.length) + '）';
   }
   return error || fallback;
 }
 loginForm.onsubmit = async function(event) {
   event.preventDefault();
   const password = passwordInput.value.trim();
   if (!password) return showLoginError('请输入管理密码');
   adminToken = password;
   showLoginError('验证中…');
   try {
     const response = await fetch(adminApiUrl('/state'), {
       method: 'GET',
       headers: headers(),
       cache: 'no-store'
     });
     const responseText = await response.text();
     const data = parseResponseJson(responseText);
     if (!response.ok) {
       adminToken = '';
       if (response.status === 401) {
         try {
           const healthResponse = await fetch('/health');
           const health = await healthResponse.json();
           if (health.bindings && health.bindings.adminToken === false) {
             return showLoginError('Worker 尚未配置 ADMIN_TOKEN');
           }
         } catch (_) {}
       }
       if (response.status === 404) {
         return showLoginError('后台接口路径不存在，请确认使用当前管理地址');
       }
       if (response.status >= 500) {
         return showLoginError('后台配置读取失败，请检查 CONFIG 绑定和 Worker 日志');
       }
       return showLoginError('密码错误或后台地址无效（HTTP ' + response.status + '）');
     }
     currentState = data;
     // 认证成功后立即进入后台；用户列表、图表等模块失败不应阻止登录。
     loginGate.hidden = true;
     el('dashboard').hidden = false;
     try {
       fillState(data);
       activateTab('data-dashboard');
     } catch (error) {
       show('后台已登录，但页面初始化失败，请刷新页面', true);
       console.error('admin dashboard initialization failed', error);
     }
     const usersLoaded = await loadUsers();
     show(usersLoaded ? '登录成功' : '已进入后台，但用户数据读取失败', !usersLoaded);
     passwordInput.value = '';
     showLoginError('');
   } catch (error) {
     adminToken = '';
     showLoginError('无法连接后台，请检查网络或确认管理地址正确');
   }
 };
 function logout() {
   adminToken = '';
   currentState = null;
   accountSecurityData = null;
   accountSecuritySelection.clear();
   el('dashboard').hidden = true;
   loginGate.hidden = false;
   passwordInput.value = '';
   showLoginError('');
   passwordInput.focus();
 }
 function activateTab(name) {
   document.querySelectorAll('.tab-button[data-tab]').forEach(function(button) {
     const active = button.dataset.tab === name;
     button.classList.toggle('active', active);
     button.setAttribute('aria-selected', active ? 'true' : 'false');
   });
   const appSettingsGroup = document.querySelector('[data-nav-group="app-settings"]');
   if (appSettingsGroup && ['app-settings', 'notice', 'app-update'].indexOf(name) >= 0) {
     const parent = appSettingsGroup.querySelector('.nav-parent');
     const submenu = appSettingsGroup.querySelector('.nav-submenu');
     appSettingsGroup.classList.add('expanded');
     if (parent) parent.setAttribute('aria-expanded', 'true');
     if (submenu) submenu.hidden = false;
   }
   document.querySelectorAll('.tab-panel').forEach(function(panel) {
     panel.classList.toggle('active', panel.dataset.panel === name);
   });
  if (name === 'account-security' && !accountSecurityData && !accountSecurityLoading) {
    loadAccountSecurity();
  }
}
   document.querySelectorAll('.tab-button[data-tab]').forEach(function(button) {
   button.onclick = function() {
     activateTab(button.dataset.tab);
     window.scrollTo({top: 0, behavior: 'auto'});
   };
 });
 document.querySelectorAll('.nav-parent').forEach(function(button) {
   button.onclick = function() {
     const group = button.closest('.nav-group');
     const submenu = group && group.querySelector('.nav-submenu');
     const expanded = button.getAttribute('aria-expanded') === 'true';
     button.setAttribute('aria-expanded', expanded ? 'false' : 'true');
     if (submenu) submenu.hidden = expanded;
   };
 });

function securityDeviceRows(devices) {
  if (!Array.isArray(devices) || devices.length === 0) {
    return '<div class="security-device-empty">该账户暂无历史登录设备</div>';
  }
  const rows = devices.map(function(device) {
    const blockState = device.blockedByAccount
      ? '被此账户封禁'
      : device.blocked ? '被其他账户封禁' : '正常';
    return '<tr>' +
      '<td><code>' + esc(device.deviceId || '') + '</code></td>' +
      '<td>' + esc([device.brand, device.model].filter(Boolean).join(' ') || '未登记') + '</td>' +
      '<td>' + esc(device.androidVersion || '未登记') + ' / SDK ' + esc(device.androidSdk || '-') + '</td>' +
      '<td>' + esc(device.appVersion || '未登记') + '</td>' +
      '<td>' + (device.current ? '当前登录' : '历史登录') + '</td>' +
      '<td>' + esc(blockState) + '</td>' +
      '<td>' + esc(device.lastSeenAt || device.lastBoundAt || '暂无') + '</td>' +
      '</tr>';
  }).join('');
  return '<div class="security-device-wrap"><table class="security-device-table">' +
    '<thead><tr><th>设备 ID</th><th>设备</th><th>Android</th><th>App</th>' +
    '<th>关联状态</th><th>封禁状态</th><th>最后活动</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table></div>';
}
function securityFilteredAccounts() {
  const accounts = accountSecurityData && Array.isArray(accountSecurityData.accounts)
    ? accountSecurityData.accounts : [];
  const search = String(value('securitySearch') || '').toLowerCase();
  return accounts.filter(function(account) {
    if (!search) return true;
    const deviceIds = (account.devices || []).map(function(device) { return device.deviceId; });
    return [account.uid, account.nickname, account.email].concat(deviceIds)
      .join(' ').toLowerCase().indexOf(search) >= 0;
  });
}
function updateSecuritySelectionControls(filtered) {
  const selectAll = el('securitySelectAll');
  const count = el('securitySelectionCount');
  const selectedVisible = filtered.filter(function(account) {
    return accountSecuritySelection.has(String(account.uid || ''));
  }).length;
  const selectedTotal = accountSecuritySelection.size;
  if (count) {
    count.textContent = '已选择 ' + selectedTotal + ' 个账户' +
      (selectedTotal !== selectedVisible ? '（当前筛选已选 ' + selectedVisible + ' 个）' : '');
  }
  if (selectAll) {
    selectAll.disabled = filtered.length === 0;
    selectAll.checked = filtered.length > 0 && selectedVisible === filtered.length;
    selectAll.indeterminate = selectedVisible > 0 && selectedVisible < filtered.length;
  }
  ['securityBatchBan', 'securityBatchUnban'].forEach(function(id) {
    const button = el(id);
    if (button) button.disabled = selectedTotal === 0;
  });
}
function toggleAllSecurityAccounts(checked) {
  securityFilteredAccounts().forEach(function(account) {
    const uid = String(account.uid || '');
    if (!uid) return;
    if (checked) accountSecuritySelection.add(uid);
    else accountSecuritySelection.delete(uid);
  });
  renderAccountSecurity();
}
function clearSecuritySelection() {
  accountSecuritySelection.clear();
  renderAccountSecurity();
}
function renderAccountSecurity() {
  const host = el('securityAccountList');
  const summary = el('securitySummary');
  if (!host || !summary) return;
  const accounts = accountSecurityData && Array.isArray(accountSecurityData.accounts)
    ? accountSecurityData.accounts : [];
  const knownUids = new Set(accounts.map(function(account) {
    return String(account.uid || '');
  }));
  accountSecuritySelection.forEach(function(uid) {
    if (!knownUids.has(uid)) accountSecuritySelection.delete(uid);
  });
  const filtered = securityFilteredAccounts();
  updateSecuritySelectionControls(filtered);
  const bannedAccounts = accounts.filter(function(account) { return account.status === 'banned'; }).length;
  const historicalDevices = accounts.reduce(function(total, account) {
    return total + Number(account.historicalDeviceCount || 0);
  }, 0);
  const search = String(value('securitySearch') || '').toLowerCase();
  summary.textContent = '账户 ' + accounts.length + ' 个 · 已封禁 ' + bannedAccounts +
    ' 个 · 历史登录设备关联 ' + historicalDevices + ' 条' +
    (search ? ' · 当前匹配 ' + filtered.length + ' 个' : '');
  if (filtered.length === 0) {
    host.innerHTML = '<div class="security-device-empty">没有匹配的账户</div>';
    return;
  }
  const rows = filtered.map(function(account) {
    const banned = account.status === 'banned';
    const email = account.email || '游客账户';
    const deviceCount = Number(account.historicalDeviceCount || 0);
    const bannedDeviceCount = Number(account.bannedDeviceCount || 0);
    const devices = Array.isArray(account.devices) ? account.devices : [];
    const uid = String(account.uid || '');
    const deviceCell = devices.length
      ? '<details class="security-account-devices"><summary>' + deviceCount + ' 台设备</summary>' +
        securityDeviceRows(devices) + '</details>'
      : '<span class="security-account-devices">' + deviceCount + ' 台设备</span>';
    return '<tr class="' + (banned ? 'banned' : '') + '">' +
      '<td><input class="security-account-checkbox" type="checkbox" data-security-select-uid="' +
      esc(uid) + '"' + (accountSecuritySelection.has(uid) ? ' checked' : '') +
      ' aria-label="选择账户 ' + esc(account.nickname || uid) + '"></td>' +
      '<td><span class="security-badge' + (banned ? ' banned' : '') + '">' +
      (banned ? '已封禁' : '正常') + '</span></td>' +
      '<td><span class="security-account-name">' + esc(account.nickname || account.uid) + '</span></td>' +
      '<td><code>' + esc(account.uid) + '</code></td>' +
      '<td><span class="security-account-email">' + esc(email) + '</span></td>' +
      '<td>Lv' + esc(account.level == null ? '-' : account.level) + '</td>' +
      '<td>' + esc(account.experienceTotal == null ? '-' : account.experienceTotal) + '</td>' +
      '<td>' + deviceCell + '</td>' +
      '<td>' + bannedDeviceCount + ' 台</td>' +
      '<td>' + esc(account.createdAt || '暂无') + '</td></tr>';
  }).join('');
  host.innerHTML = '<table class="security-account-table" aria-label="账户安全信息">' +
    '<thead><tr><th>选择</th><th>状态</th><th>昵称</th><th>UID</th><th>邮箱</th><th>等级</th>' +
    '<th>累计经验</th><th>历史设备</th><th>本账户封禁设备</th><th>注册时间</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-security-select-uid]').forEach(function(input) {
    input.onchange = function() {
      const uid = input.dataset.securitySelectUid;
      if (input.checked) accountSecuritySelection.add(uid);
      else accountSecuritySelection.delete(uid);
      updateSecuritySelectionControls(filtered);
    };
  });
}
async function loadAccountSecurity(force) {
  if (accountSecurityLoading) return false;
  if (accountSecurityData && !force) {
    renderAccountSecurity();
    return true;
  }
  const host = el('securityAccountList');
  accountSecurityLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取账户与历史设备…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/security/accounts', {headers: headers()});
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    accountSecurityData = data;
    renderAccountSecurity();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">账户安全数据读取失败</div>';
    show('账户安全数据读取失败：' + (error.message || error), true);
    return false;
  } finally {
    accountSecurityLoading = false;
  }
}
async function batchSetAccountBan(shouldBan) {
  const accounts = accountSecurityData && Array.isArray(accountSecurityData.accounts)
    ? accountSecurityData.accounts : [];
  const selectedUids = Array.from(accountSecuritySelection);
  if (selectedUids.length === 0) {
    show('请先勾选账户', true);
    return;
  }
  const selectedAccounts = accounts.filter(function(account) {
    return accountSecuritySelection.has(String(account.uid || ''));
  });
  const previewNames = selectedAccounts.slice(0, 3).map(function(account) {
    return account.nickname || account.uid;
  }).join('、');
  const suffix = selectedUids.length > 3 ? ' 等' : '';
  const prompt = shouldBan
    ? '确定批量封禁 ' + selectedUids.length + ' 个账户及其历史设备吗？\\n' + previewNames + suffix
    : '确定批量解除这 ' + selectedUids.length + ' 个账户产生的封禁吗？\\n' + previewNames + suffix;
  if (!confirm(prompt)) return;

  const buttons = [el('securityBatchBan'), el('securityBatchUnban')];
  buttons.forEach(function(button) {
    if (button) button.disabled = true;
  });
  let success = 0;
  let failed = 0;
  let firstError = '';
  for (let index = 0; index < selectedUids.length; index += 1) {
    const uid = selectedUids[index];
    try {
      const response = await fetch(
        ADMIN_API_BASE + '/users/' + encodeURIComponent(uid) + '/ban',
        {method: shouldBan ? 'POST' : 'DELETE', headers: headers()}
      );
      const responseText = await response.text();
      const data = parseResponseJson(responseText);
      if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
      success += 1;
      accountSecuritySelection.delete(uid);
    } catch (error) {
      failed += 1;
      if (!firstError) firstError = error.message || String(error);
    }
  }
  await loadAccountSecurity(true);
  const actionName = shouldBan ? '封禁' : '解除封禁';
  const message = '批量' + actionName + '完成：成功 ' + success + ' 个，失败 ' + failed + ' 个。' +
    (firstError ? ' 首个错误：' + firstError : '');
  show(message, failed > 0);
  updateSecuritySelectionControls(securityFilteredAccounts());
}
async function setAccountBan(uid, shouldBan) {
  const accounts = accountSecurityData && accountSecurityData.accounts || [];
  const account = accounts.find(function(item) { return item.uid === uid; });
  const name = account && account.nickname ? account.nickname : uid;
  const deviceCount = Number(account && account.historicalDeviceCount || 0);
  const prompt = shouldBan
    ? '确定封禁账户“' + name + '”及其登录过的 ' + deviceCount + ' 台设备吗？'
    : '确定解除账户“' + name + '”产生的账户及设备封禁吗？其他账户产生的设备封禁不会被解除。';
  if (!confirm(prompt)) return;
  try {
    const response = await fetch(
      ADMIN_API_BASE + '/users/' + encodeURIComponent(uid) + '/ban',
      {method: shouldBan ? 'POST' : 'DELETE', headers: headers()}
    );
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    await loadAccountSecurity(true);
    if (shouldBan) {
      show('账户已封禁，同时封禁 ' + Number(data.accountBannedDeviceCount || 0) + ' 台历史设备');
    } else {
      show('账户已解除封禁；当前仍被其他来源封禁的设备有 ' +
        Number(data.blockedDeviceCount || 0) + ' 台');
    }
  } catch (error) {
    show((shouldBan ? '封禁失败：' : '解除封禁失败：') + (error.message || error), true);
  }
}
function formatMegabytes(megabytes) {
  let value = Number(megabytes) || 0;
  const units = ['MB', 'GB', 'TB', 'PB'];
  let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
  if (value === 0) return '0 MB';
  if (index === 0 && value < 0.01) return '<0.01 MB';
  return value.toFixed(value >= 100 ? 0 : value >= 10 ? 1 : 2) + ' ' + units[index];
}
function csvMegabytes(megabytes) {
  return Math.round((Number(megabytes) || 0) * 10000) / 10000;
}
function usageTotal(usage) { return formatMegabytes(usage && usage.totalMb); }
function levelOptions(selectedLevel) {
  const selected = Number(selectedLevel);
  return [0, 1, 2, 3, 4, 5, 6].map(function(level) {
    return '<option value="' + level + '" ' + (level === selected ? 'selected' : '') +
      '>Lv' + level + (level === 0 ? '（最低）' : '') + '</option>';
  }).join('');
}
function userLevelOptions(selectedLevel) {
  const selected = Number(selectedLevel);
  return [0, 1, 2, 3, 4, 5, 6].map(function(level) {
    return '<option value="' + level + '" ' + (level === selected ? 'selected' : '') +
      '>Lv' + level + (level === 0 ? '（封禁）' : '') + '</option>';
  }).join('');
}
function loadUserColumnWidths() {
  try {
    const stored = JSON.parse(localStorage.getItem(USER_COLUMN_WIDTHS_KEY) || '{}');
    return stored && typeof stored === 'object' ? stored : {};
  } catch (_) {
    return {};
  }
}
function persistUserColumnWidths() {
  try { localStorage.setItem(USER_COLUMN_WIDTHS_KEY, JSON.stringify(userColumnWidths)); } catch (_) {}
}
function userColumnDefinition(columnKey) {
  return USER_SHEET_COLUMNS.find(function(column) { return column.key === columnKey; });
}
function resolvedUserColumnWidth(column) {
  const storedWidth = Number(userColumnWidths[column.key]);
  return Number.isFinite(storedWidth)
    ? Math.min(Math.max(Math.round(storedWidth), column.min), 500)
    : column.width;
}
function userSheetColgroup() {
  return '<colgroup>' + USER_SHEET_COLUMNS.map(function(column) {
    return '<col data-user-column="' + column.key + '" style="width:' + resolvedUserColumnWidth(column) + 'px">';
  }).join('') + '</colgroup>';
}
function userSheetHeader() {
  return USER_SHEET_COLUMNS.map(function(column) {
    const sortable = column.key !== 'select' && column.key !== 'actions';
    const sortAttributes = sortable
      ? ' data-sort-column="' + column.key + '" tabindex="0" aria-sort="none" title="Click to sort"'
      : '';
    const heading = column.key === 'select'
      ? '<input type="checkbox" data-select-all-users title="全选当前用户" aria-label="全选当前用户">'
      : esc(column.label);
    return '<th data-user-column-header="' + column.key + '"' + sortAttributes + '>' + heading +
      (sortable ? '<span class="sort-indicator" aria-hidden="true"></span>' : '') +
      '<span class="column-resizer" data-resize-column="' + column.key +
      '" role="separator" tabindex="0" title="拖动调整列宽，双击恢复默认宽度"></span></th>';
  }).join('');
}
function userSortValue(row, columnKey) {
  const accountId = row.dataset.userRow || '';
  const user = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users.find(function(item) { return item.uid === accountId; })
    : null;
  if (!user) return accountId;
  const field = function(name, fallback) {
    const control = row.querySelector('[data-user-field="' + name + '"]');
    return control ? control.value.trim() : fallback;
  };
  switch (columnKey) {
    case 'accountId': return user.uid;
    case 'nickname': return field('nickname', user.nickname);
    case 'level': return Number(field('level', user.level)) || 0;
    case 'brand': return field('brand', user.brand);
    case 'model': return field('model', user.model);
    case 'androidVersion': return field('androidVersion', user.androidVersion);
    case 'appVersion': return field('appVersion', user.appVersion);
    case 'activeToday': return user.activeToday ? 1 : 0;
    case 'activeMonth': return user.activeThisMonth ? 1 : 0;
    case 'trafficTodayUpload': return Number(user.today && user.today.uploadMb) || 0;
    case 'trafficTodayDownload': return Number(user.today && user.today.downloadMb) || 0;
    case 'trafficToday': return Number(user.today && user.today.totalMb) || 0;
    case 'trafficMonthUpload': return Number(user.month && user.month.uploadMb) || 0;
    case 'trafficMonthDownload': return Number(user.month && user.month.downloadMb) || 0;
    case 'trafficMonth': return Number(user.month && user.month.totalMb) || 0;
    case 'trafficTotalUpload': return Number(user.totalUploadMb) || 0;
    case 'trafficTotalDownload': return Number(user.totalDownloadMb) || 0;
    case 'trafficTotal': return Number(user.totalMb) || 0;
    case 'createdAt': return user.createdAt || '';
    case 'lastReportAt': return user.lastReportAt || '';
    default: return '';
  }
}
function compareUserSortValues(left, right, columnKey) {
  const numericColumns = [
    'level', 'activeToday', 'activeMonth',
    'trafficTodayUpload', 'trafficTodayDownload', 'trafficToday',
    'trafficMonthUpload', 'trafficMonthDownload', 'trafficMonth',
    'trafficTotalUpload', 'trafficTotalDownload', 'trafficTotal',
  ];
  if (numericColumns.includes(columnKey)) return Number(left) - Number(right);

  const leftText = String(left == null ? '' : left);
  const rightText = String(right == null ? '' : right);
  if (!leftText && rightText) return 1;
  if (leftText && !rightText) return -1;
  if (!leftText && !rightText) return 0;

  const dateColumns = ['createdAt', 'lastReportAt'];
  if (dateColumns.includes(columnKey)) {
    const leftTime = Date.parse(leftText);
    const rightTime = Date.parse(rightText);

    if (Number.isFinite(leftTime) && Number.isFinite(rightTime)) return leftTime - rightTime;
  }
  return leftText.localeCompare(rightText, 'zh-CN', {numeric: true, sensitivity: 'base'});
}
function updateUserSortIndicators(table) {
  if (!table) return;
  table.querySelectorAll('[data-sort-column]').forEach(function(header) {
    const active = header.dataset.sortColumn === userSortKey;
    const indicator = header.querySelector('.sort-indicator');
    header.setAttribute('aria-sort', active
      ? (userSortDirection === 'desc' ? 'descending' : 'ascending')
      : 'none');
    header.title = active
      ? (userSortDirection === 'desc' ? 'Descending; click to switch to ascending' : 'Ascending; click to switch to descending')
      : 'Click to sort descending';
    if (indicator) indicator.textContent = active ? (userSortDirection === 'desc' ? '↓' : '↑') : '';
  });
}
function applyUserSort() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table || !userSortKey) return;
  const body = table.querySelector('tbody');
  if (!body) return;
  const rows = Array.from(body.querySelectorAll('[data-user-row]'));
  rows.map(function(row, index) {
    return {row: row, index: index};
  }).sort(function(left, right) {
    const result = compareUserSortValues(
      userSortValue(left.row, userSortKey),
      userSortValue(right.row, userSortKey),
      userSortKey,
    );
    return (userSortDirection === 'desc' ? -result : result) || left.index - right.index;
  }).forEach(function(item) {
    body.appendChild(item.row);
  });
  updateUserSortIndicators(table);
}
function toggleUserSort(columnKey) {
  if (userSortKey === columnKey) {
    userSortDirection = userSortDirection === 'desc' ? 'asc' : 'desc';
  } else {
    userSortKey = columnKey;
    userSortDirection = 'desc';
  }
  applyUserSort();
}
function updateUserSheetWidth(table) {
  if (!table) return;
  const totalWidth = USER_SHEET_COLUMNS.reduce(function(total, column) {
    const col = table.querySelector('col[data-user-column="' + column.key + '"]');
    return total + (col ? Number.parseFloat(col.style.width) || column.width : column.width);
  }, 0);
  table.style.width = Math.ceil(totalWidth) + 'px';
}
function setUserColumnWidth(table, columnKey, requestedWidth, persist) {
  const column = userColumnDefinition(columnKey);
  const col = table && table.querySelector('col[data-user-column="' + columnKey + '"]');
  if (!column || !col) return;
  const width = Math.min(Math.max(Math.round(requestedWidth), column.min), 500);
  col.style.width = width + 'px';
  userColumnWidths[columnKey] = width;
  updateUserSheetWidth(table);
  if (persist) persistUserColumnWidths();
}
function resetUserColumnWidths() {
  userColumnWidths = {};
  try { localStorage.removeItem(USER_COLUMN_WIDTHS_KEY); } catch (_) {}
  const table = el('usersTable').querySelector('table.user-sheet');
  if (table) {
    USER_SHEET_COLUMNS.forEach(function(column) {
      const col = table.querySelector('col[data-user-column="' + column.key + '"]');
      if (col) col.style.width = column.width + 'px';
    });
    updateUserSheetWidth(table);
  }
  show('列宽已恢复为紧凑默认值');
}
function updateUserSelectionUi() {
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'));
  const visibleRows = rows.filter(function(row) { return !row.hidden; });
  const selectedVisible = visibleRows.filter(function(row) {
    return selectedUserIds.has(row.dataset.userRow);
  });
  const selectAll = el('usersTable').querySelector('[data-select-all-users]');
  if (selectAll) {
    selectAll.checked = visibleRows.length > 0 && selectedVisible.length === visibleRows.length;
    selectAll.indeterminate = selectedVisible.length > 0 && selectedVisible.length < visibleRows.length;
  }
  el('usersTable').querySelectorAll('[data-user-select]').forEach(function(control) {
    control.checked = selectedUserIds.has(control.dataset.userSelect);
  });
  const status = el('userSelectionStatus');
  if (status) status.textContent = selectedUserIds.size ? '已选 ' + selectedUserIds.size + ' 个用户' : '';
  updateUserToolbar();
}
function toggleUserSelection(accountId, isSelected) {
  if (isSelected) selectedUserIds.add(accountId);
  else selectedUserIds.delete(accountId);
  updateUserSelectionUi();
}
function toggleSelectAllUsers() {
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'))
    .filter(function(row) { return !row.hidden; });
  const shouldSelect = rows.some(function(row) { return !selectedUserIds.has(row.dataset.userRow); });
  rows.forEach(function(row) {
    if (shouldSelect) selectedUserIds.add(row.dataset.userRow);
    else selectedUserIds.delete(row.dataset.userRow);
  });
  updateUserSelectionUi();
}
function attachUserColumnResizers() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table) return;
  updateUserSheetWidth(table);
  updateUserSortIndicators(table);
  table.querySelectorAll('[data-sort-column]').forEach(function(header) {
    header.onclick = function(event) {
      if (event.target.closest('[data-resize-column]')) return;
      toggleUserSort(header.dataset.sortColumn);
    };
    header.onkeydown = function(event) {
      if (event.target.closest('[data-resize-column]')) return;
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      toggleUserSort(header.dataset.sortColumn);
    };
  });
  const selectAll = table.querySelector('[data-select-all-users]');
  if (selectAll) selectAll.onchange = toggleSelectAllUsers;
  table.querySelectorAll('[data-resize-column]').forEach(function(handle) {
    const columnKey = handle.dataset.resizeColumn;
    handle.onpointerdown = function(event) {
      if (event.button !== 0) return;
      event.preventDefault();
      const col = table.querySelector('col[data-user-column="' + columnKey + '"]');
      const startWidth = col ? Number.parseFloat(col.style.width) : 0;
      const startX = event.clientX;
      document.body.classList.add('column-resizing');
      function move(moveEvent) {
        setUserColumnWidth(table, columnKey, startWidth + moveEvent.clientX - startX, false);
      }
      function stop() {
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', stop);
        document.removeEventListener('pointercancel', stop);
        document.body.classList.remove('column-resizing');
        persistUserColumnWidths();
      }
      document.addEventListener('pointermove', move);
      document.addEventListener('pointerup', stop);
      document.addEventListener('pointercancel', stop);
    };
    handle.ondblclick = function(event) {
      event.preventDefault();
      const column = userColumnDefinition(columnKey);
      if (!column) return;
      delete userColumnWidths[columnKey];
      setUserColumnWidth(table, columnKey, column.width, false);
      persistUserColumnWidths();
    };
    handle.onkeydown = function(event) {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      const col = table.querySelector('col[data-user-column="' + columnKey + '"]');
      const currentWidth = col ? Number.parseFloat(col.style.width) : 0;
      setUserColumnWidth(table, columnKey, currentWidth + (event.key === 'ArrowRight' ? 10 : -10), true);
    };
  });
  applyUserSort();
}
function userRowSearchText(row) {
  const values = [row.dataset.userRow || ''];
  row.querySelectorAll('[data-user-field]').forEach(function(control) {
    values.push(control.value || '');
    if (control.dataset.userField === 'level') values.push('lv' + control.value);
  });
  row.querySelectorAll('td.readonly').forEach(function(cell) {
    values.push(cell.textContent || '');
  });
  return values.join(' ').toLowerCase();
}
function applyUserSearch(resetPosition) {
  const input = el('userSearch');
  const status = el('userSearchStatus');
  const query = input ? input.value.trim().toLowerCase() : '';
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'));
  userSearchMatches = [];
  rows.forEach(function(row) {
    const matched = !query || userRowSearchText(row).includes(query);
    row.hidden = !matched;
    row.classList.remove('search-current');
    if (matched) userSearchMatches.push(row);
  });
  if (resetPosition !== false || userSearchIndex >= userSearchMatches.length) {
    userSearchIndex = -1;
  }
  if (userSearchIndex >= 0 && userSearchMatches[userSearchIndex]) {
    userSearchMatches[userSearchIndex].classList.add('search-current');
  }
  if (status) {
    status.textContent = query
      ? '匹配 ' + userSearchMatches.length + ' / ' + rows.length
      : '';
  }
  updateUserSelectionUi();
}
function locateUserSearch(direction) {
  const input = el('userSearch');
  if (!input || !input.value.trim()) {
    if (input) input.focus();
    show('请先输入搜索内容', true);
    return;
  }
  applyUserSearch(false);
  if (userSearchMatches.length === 0) {
    show('没有找到匹配的用户', true);
    return;
  }
  userSearchMatches.forEach(function(row) { row.classList.remove('search-current'); });
  const step = direction < 0 ? -1 : 1;
  userSearchIndex = (userSearchIndex + step + userSearchMatches.length) % userSearchMatches.length;
  const row = userSearchMatches[userSearchIndex];
  row.classList.add('search-current');
  row.scrollIntoView({behavior: 'smooth', block: 'center', inline: 'nearest'});
  el('userSearchStatus').textContent =
    '第 ' + (userSearchIndex + 1) + ' / ' + userSearchMatches.length + ' 个匹配';
}
function handleUserSearchKey(event) {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  locateUserSearch(event.shiftKey ? -1 : 1);
}
function clearUserSearch() {
  const input = el('userSearch');
  if (!input) return;
  input.value = '';
  applyUserSearch();
  input.focus();
}
// 统一常见厂商及其子品牌，避免同一厂商因大小写或品牌写法不同被拆分。
function normalizedBrand(value) {
  const raw = String(value == null ? '' : value).trim();
  if (!raw) return '未知厂商';
  const brand = raw.toLowerCase().replace(/[\\s_-]+/g, '');
  if (/^(oppo|oneplus|一加|realme|真我)/.test(brand)) return 'OPPO';
  if (/^(vivo|iqoo)/.test(brand)) return 'vivo';
  if (/^(xiaomi|redmi|poco|mi|小米)/.test(brand)) return '小米';
  if (/^(huawei|华为)/.test(brand)) return '华为';
  if (/^(honor|荣耀)/.test(brand)) return '荣耀';

  if (/^(samsung|三星)/.test(brand)) return '三星';
  if (/^(google|pixel)/.test(brand)) return 'Google';
  if (/^(motorola|moto|lenovo|联想)/.test(brand)) return '联想 / Motorola';
  if (/^(zte|nubia|红魔|中兴)/.test(brand)) return '中兴 / nubia';
  if (/^(meizu|魅族)/.test(brand)) return '魅族';
  if (/^(sony|索尼)/.test(brand)) return 'Sony';
  if (/^(asus|rog|华硕)/.test(brand)) return '华硕';
  if (/^(nothing)/.test(brand)) return 'Nothing';
  return raw;
}
function normalizedAndroidVersion(value) {
  const version = String(value == null ? '' : value).trim().replace(/^android\\s*/i, '');
  return version ? 'Android ' + version : '未知版本';
}
function normalizedAppVersion(value) {
  const version = String(value == null ? '' : value).trim().replace(/^v/i, '');
  return version ? 'v' + version : '未知版本';
}
function distributionForUsers(users, labelForUser) {
  const groups = new Map();
  users.forEach(function(user) {
    const label = labelForUser(user);
    const key = label.toLocaleLowerCase('zh-CN');
    const current = groups.get(key);
    if (current) current.count++;
    else groups.set(key, {label: label, count: 1});
  });
  return Array.from(groups.values()).sort(function(left, right) {
    return right.count - left.count || left.label.localeCompare(right.label, 'zh-CN', {
      numeric: true,
      sensitivity: 'base'
    });
  });
}
function growthPointLabel(key, period) {
  const parts = key.split('-').map(Number);
  if (period === 'month') return parts[0] + '年' + parts[1] + '月';
  return parts[1] + '月' + parts[2] + '日';
}
function renderGrowthChart(data) {
  const host = el('userGrowthChart');
  const summary = el('userGrowthSummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      return {
        key: String(point.key || ''),
        newUsers: Math.max(0, Number(point.newUsers) || 0),
        totalUsers: Math.max(0, Number(point.totalUsers) || 0)
      };
    }).filter(function(point) { return /^\\d{4}-\\d{2}(-\\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">暂无可统计的注册数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 54};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.totalUsers);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.totalUsers).toFixed(2);
  }).join(' ');
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#374151" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#94a3b8" font-size="12">' + value + '</text>';
  }).join('');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#94a3b8" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.totalUsers).toFixed(2) +
      '" r="4" fill="#38bdf8" stroke="#0f172a" stroke-width="2"><title>' +
      esc(label + '：累计 ' + point.totalUsers + '，新增 ' + point.newUsers) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="growth-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('用户增长折线图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + (padding.top + plotHeight) + '" x2="' +
    (width - padding.right) + '" y2="' + (padding.top + plotHeight) + '" stroke="#64748b" stroke-width="1" />' +
    '<path d="' + linePath + '" fill="none" stroke="#38bdf8" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const latest = points[points.length - 1];
    summary.innerHTML = '<span>当前累计 <strong>' + latest.totalUsers + '</strong> 个用户</span>' +
      '<span>最近' + (period === 'day' ? '一天' : period === 'week' ? '一周' : '一月') +
      '新增 <strong>' + latest.newUsers + '</strong> 个</span>' +
      '<span class="growth-legend"><i></i>累计用户</span>';
  }
}
function renderTrafficChart(data) {
  const host = el('trafficChart');
  const summary = el('trafficSummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      const uploadMb = Math.max(0, Number(point.uploadMb) || 0);
      const downloadMb = Math.max(0, Number(point.downloadMb) || 0);
      return {
        key: String(point.key || ''),
        uploadMb: uploadMb,
        downloadMb: downloadMb,
        totalMb: uploadMb + downloadMb
      };
    }).filter(function(point) { return /^\\d{4}-\\d{2}(-\\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">当前范围暂无流量数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 68};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.totalMb);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#374151" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#94a3b8" font-size="12">' + esc(formatMegabytes(value)) + '</text>';
  }).join('');
  const groupWidth = plotWidth / Math.max(points.length, 1);
  const barWidth = Math.max(8, Math.min(36, groupWidth * 0.58));
  const baseline = yFor(0);
  const bars = points.map(function(point, index) {
    const x = xFor(index) - barWidth / 2;
    const downloadY = yFor(point.downloadMb);
    const totalY = yFor(point.totalMb);
    const downloadHeight = Math.max(0, baseline - downloadY);
    const uploadHeight = Math.max(0, downloadY - totalY);
    const label = growthPointLabel(point.key, period);
    return '<rect x="' + x.toFixed(2) + '" y="' + downloadY.toFixed(2) +
      '" width="' + barWidth.toFixed(2) + '" height="' + downloadHeight.toFixed(2) +
      '" rx="2" fill="#a78bfa"><title>' + esc(label + '：下载 ' + formatMegabytes(point.downloadMb)) +
      '</title></rect>' +
      '<rect x="' + x.toFixed(2) + '" y="' + totalY.toFixed(2) +
      '" width="' + barWidth.toFixed(2) + '" height="' + uploadHeight.toFixed(2) +
      '" rx="2" fill="#22c55e"><title>' + esc(label + '：上传 ' + formatMegabytes(point.uploadMb)) +
      '</title></rect>';
  }).join('');
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.totalMb).toFixed(2);
  }).join(' ');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#94a3b8" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.totalMb).toFixed(2) +
      '" r="4" fill="#f59e0b" stroke="#0f172a" stroke-width="2"><title>' +
      esc(label + '：总流量 ' + formatMegabytes(point.totalMb)) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="traffic-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('流量消耗图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + baseline + '" x2="' + (width - padding.right) +
    '" y2="' + baseline + '" stroke="#64748b" stroke-width="1" />' +
    bars +
    '<path d="' + linePath + '" fill="none" stroke="#f59e0b" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const totalUpload = points.reduce(function(total, point) { return total + point.uploadMb; }, 0);
    const totalDownload = points.reduce(function(total, point) { return total + point.downloadMb; }, 0);
    summary.innerHTML = '<span>范围上传 <strong>' + formatMegabytes(totalUpload) + '</strong></span>' +
      '<span>范围下载 <strong>' + formatMegabytes(totalDownload) + '</strong></span>' +
      '<span>范围总流量 <strong>' + formatMegabytes(totalUpload + totalDownload) + '</strong></span>' +
      '<span class="traffic-legend traffic-upload"><i></i>上传</span>' +
      '<span class="traffic-legend traffic-download"><i></i>下载</span>' +
      '<span class="traffic-legend traffic-total"><i></i>总流量</span>';
  }
}
function growthTodayDateKey() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(new Date()).reduce(function(result, part) {
    if (part.type !== 'literal') result[part.type] = part.value;
    return result;
  }, {});
  return parts.year + '-' + parts.month + '-' + parts.day;
}
function initializeGrowthRange() {
  const today = growthTodayDateKey();
  ['growthStartDate', 'growthEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyGrowthRange() {
  const start = value('growthStartDate');
  const end = value('growthEndDate');
  if (start && end && start > end) {

    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadGrowthChart(growthPeriod);
}
function clearGrowthRange() {
  setValue('growthStartDate', '');
  setValue('growthEndDate', '');
  loadGrowthChart(growthPeriod);
}
function growthRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('growthStartDate');
  const end = value('growthEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function initializeTrafficRange() {
  const today = growthTodayDateKey();
  ['trafficStartDate', 'trafficEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyTrafficRange() {
  const start = value('trafficStartDate');
  const end = value('trafficEndDate');
  if (start && end && start > end) {
    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadTrafficChart(trafficPeriod);
}
function clearTrafficRange() {
  setValue('trafficStartDate', '');
  setValue('trafficEndDate', '');
  loadTrafficChart(trafficPeriod);
}
function trafficRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('trafficStartDate');
  const end = value('trafficEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function setTrafficPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  trafficPeriod = period;
  document.querySelectorAll('[data-traffic-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.trafficPeriod === period);
  });
  loadTrafficChart(period);
}
async function loadTrafficChart(period) {
  const host = el('trafficChart');
  const summary = el('trafficSummary');
  const requestId = ++trafficRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取流量数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/traffic?' + trafficRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('流量数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === trafficRequestId) renderTrafficChart(data);
  } catch (error) {
    if (requestId !== trafficRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">流量数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
function setGrowthPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  growthPeriod = period;
  document.querySelectorAll('[data-growth-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.growthPeriod === period);
  });
  loadGrowthChart(period);
}
async function loadGrowthChart(period) {
  const host = el('userGrowthChart');
  const summary = el('userGrowthSummary');
  const requestId = ++growthRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取用户增长数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/growth?' + growthRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('用户增长数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === growthRequestId) renderGrowthChart(data);
  } catch (error) {
    if (requestId !== growthRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">用户增长数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
 function setDashboardState(className, message) {
   ['brandDistribution', 'androidDistribution', 'appVersionDistribution', 'userGrowthChart', 'trafficChart'].forEach(function(id) {
    const host = el(id);
    if (host) host.innerHTML = '<div class="' + className + '">' + esc(message) + '</div>';
  });
  const summary = el('userGrowthSummary');
  if (summary) summary.textContent = '';
  const trafficSummary = el('trafficSummary');
  if (trafficSummary) trafficSummary.textContent = '';
}
 function renderDistribution(hostId, groups, total) {
   const host = el(hostId);
   if (!host) return;
   if (!total || groups.length === 0) {
     host.innerHTML = '<div class="loading-state distribution-empty">暂无可统计的账户数据</div>';
     return;
   }
   const rows = groups.map(function(group) {
     const percent = Math.round(group.count / total * 1000) / 10;
     return '<div class="distribution-row" title="' + esc(group.label) + '：' + group.count +
       ' 个账户，占 ' + percent + '%">' +
       '<span class="distribution-label">' + esc(group.label) + '</span>' +
       '<span class="distribution-value">' + group.count + ' 个 · ' + percent + '%</span></div>';
   }).join('');
   host.innerHTML = '<div class="distribution-total">共 ' + total + ' 个账户</div>' + rows;
 }
 function renderDashboard(data) {
   const users = data && Array.isArray(data.users) ? data.users : [];
   renderDistribution('brandDistribution', distributionForUsers(users, function(user) {
     return normalizedBrand(user.brand);
   }), users.length);
   renderDistribution('androidDistribution', distributionForUsers(users, function(user) {
     return normalizedAndroidVersion(user.androidVersion);
   }), users.length);
   renderDistribution('appVersionDistribution', distributionForUsers(users, function(user) {
     return normalizedAppVersion(user.appVersion);
   }), users.length);
 }
const USER_TABLE_DEFINITIONS = [
  {
    key: 'users_mb',
    title: 'users_mb（用户表）',
    columns: ['id', 'uid', 'nickname', 'status', 'experience_total', 'email', 'created_at'],
  },
  {
    key: 'user_devices_mb',
    title: 'user_devices_mb（设备表）',
    columns: [
      'id', 'user_id', 'device_id', 'brand', 'model', 'android_version',
      'android_sdk', 'app_version', 'created_at', 'last_seen_at',
    ],
  },
  {
    key: 'user_usage_mb',
    title: 'user_usage_mb（流量统计表，Bytes）',
    columns: [
      'user_id',
      'session_id',
      'subscription_group_name',
      'started_at',
      'ended_at',
      'reported_at',
      'usage_day',
      'upload_bytes',
      'download_bytes',
    ],
  },
];
function rawUserTableValue(value) {
  if (value === null) return 'NULL';
  if (value === undefined) return '';
  return String(value);
}
function renderRawUserTable(definition, rows) {
  const values = Array.isArray(rows) ? rows : [];
  const head = '<tr><th>#</th>' + definition.columns.map(function(column) {
    return '<th>' + esc(column) + '</th>';
  }).join('') + '</tr>';
  const body = values.map(function(row, index) {
    return '<tr><td>' + (index + 1) + '</td>' + definition.columns.map(function(column) {
      return '<td>' + esc(rawUserTableValue(row && row[column])) + '</td>';
    }).join('') + '</tr>';
  }).join('');
  const content = values.length
    ? '<div class="user-table-scroll"><table class="user-data-table"><thead>' + head +
      '</thead><tbody>' + body + '</tbody></table></div>'
    : '<div class="user-table-empty">当前表暂无数据</div>';
  return '<article class="user-table-card"><h3>' + esc(definition.title) + '</h3>' +
    '<div class="user-table-meta">共 ' + values.length + ' 条记录</div>' + content + '</article>';
}
function renderUserTables(tables) {
  const host = el('usersTable');
  if (!host) return;
  const source = tables && typeof tables === 'object' ? tables : {};
  host.innerHTML = USER_TABLE_DEFINITIONS.map(function(definition) {
    return renderRawUserTable(definition, source[definition.key]);
  }).join('');
}
function renderUsers(data) {
  data = data || {users: []};
  currentUsersData = data;
  renderDashboard(data);
  return;
  dirtyUserIds.clear();
  selectedUserIds.clear();
  const batchLevel = el('batchLevel');
  if (batchLevel) batchLevel.value = '';
  const rows = (data.users || []).map(function(user) {
    return '<tr class="user-row" data-user-row="' + esc(user.uid) + '">' +
      '<td class="selection-cell"><input type="checkbox" data-user-select="' + esc(user.uid) +
        '" aria-label="选择用户"></td>' +
      '<td><code title="' + esc(user.uid) + '">' + esc(user.uid.slice(0, 16) + '…') + '</code></td>' +
      '<td><input class="sheet-input" data-user-field="nickname" maxlength="24" value="' + esc(user.nickname) + '"></td>' +
      '<td><select class="sheet-select" data-user-field="level">' + userLevelOptions(user.level) + '</select></td>' +
      '<td><input class="sheet-input" data-user-field="brand" maxlength="80" value="' + esc(user.brand) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="model" maxlength="120" value="' + esc(user.model) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="androidVersion" maxlength="80" value="' + esc(user.androidVersion) + '"></td>' +
      '<td><input class="sheet-input" data-user-field="appVersion" maxlength="40" value="' + esc(user.appVersion) + '"></td>' +
      '<td class="readonly">' + (user.activeToday ? '是' : '否') + '</td>' +
      '<td class="readonly">' + (user.activeThisMonth ? '是' : '否') + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.today && user.today.uploadMb) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.today && user.today.downloadMb) + '</td>' +
      '<td class="readonly">' + usageTotal(user.today) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.month && user.month.uploadMb) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.month && user.month.downloadMb) + '</td>' +
      '<td class="readonly">' + usageTotal(user.month) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.totalUploadMb) + '</td>' +
      '<td class="readonly">' + formatMegabytes(user.totalDownloadMb) + '</td>' +
      '<td class="readonly">' + usageTotal(user) + '</td>' +
      '<td class="readonly">' + esc(user.createdAt || '暂无') + '</td>' +
      '<td class="readonly">' + esc(user.lastReportAt || '暂无') + '</td>' +
      '<td><button class="sheet-save" type="button" data-save-user="' + esc(user.uid) + '">保存</button>' +
        '<button class="sheet-delete danger" type="button" data-delete-user="' + esc(user.uid) + '">删除</button></td>' +
      '</tr>';
  }).join('');
  el('usersTable').innerHTML = rows
    ? '<table class="user-sheet">' + userSheetColgroup() + '<thead><tr>' +
      userSheetHeader() + '</tr></thead><tbody>' + rows + '</tbody></table>'
    : '<small>暂无注册用户。</small>';
  attachUserColumnResizers();
  el('usersTable').querySelectorAll('[data-user-field]').forEach(function(control) {
    control.oninput = function() { markUserDirty(control); applyUserSearch(false); };
    control.onchange = function() { markUserDirty(control); applyUserSearch(false); };

  });
  el('usersTable').querySelectorAll('[data-user-select]').forEach(function(control) {
    control.onchange = function() {
      toggleUserSelection(control.dataset.userSelect, control.checked);
    };
  });
  el('usersTable').querySelectorAll('[data-save-user]').forEach(function(button) {
    button.onclick = function() { saveUser(button.dataset.saveUser); };
  });
  el('usersTable').querySelectorAll('[data-delete-user]').forEach(function(button) {
    button.onclick = function() { deleteUser(button.dataset.deleteUser); };
  });
  updateUserToolbar();
  applyUserSearch();
}
function markUserDirty(control) {
  const row = control.closest('[data-user-row]');
  if (!row) return;
  dirtyUserIds.add(row.dataset.userRow);
  row.classList.add('dirty');
  updateUserToolbar();
}
function updateUserToolbar() {
  const button = el('saveAllUsers');
  if (!button) return;
  button.disabled = savingAllUsers || dirtyUserIds.size === 0;
  button.textContent = savingAllUsers
    ? '保存中…'
    : dirtyUserIds.size
    ? '保存全部修改（' + dirtyUserIds.size + '）'
    : '保存全部修改';
  const hasSelection = selectedUserIds.size > 0;
  const batchLevel = el('batchLevel');
  const batchSetLevelButton = el('batchSetLevelButton');
  const batchBanUsersButton = el('batchBanUsersButton');
  const batchDeleteUsersButton = el('batchDeleteUsersButton');
  if (batchLevel) batchLevel.disabled = savingAllUsers || !hasSelection;
  if (batchSetLevelButton) batchSetLevelButton.disabled = savingAllUsers || !hasSelection;
  if (batchBanUsersButton) batchBanUsersButton.disabled = savingAllUsers || !hasSelection;
  if (batchDeleteUsersButton) batchDeleteUsersButton.disabled = savingAllUsers || !hasSelection;
}
function userRow(accountId) {
  return el('usersTable').querySelector('[data-user-row="' + accountId + '"]');
}
function userFieldValue(row, field) {
  const control = row && row.querySelector('[data-user-field="' + field + '"]');
  return control ? control.value.trim() : '';
}
async function loadUsers(force) {
  setDashboardState('loading-state', '正在读取看板数据…');
  try {
    const response = await fetch(ADMIN_API_BASE + '/dashboard/devices', {headers: headers()});
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      setDashboardState('error-state', '数据看板读取失败');
      show(data.error || ('读取用户失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    renderUsers(data);
    loadGrowthChart(growthPeriod);
    loadTrafficChart(trafficPeriod);
    return true;
  } catch (error) {
    setDashboardState('error-state', '数据看板读取失败');
    show('读取用户失败，请检查 USERS_DB 绑定', true);
    return false;
  }
}
async function saveUser(accountId, silent) {
  const row = userRow(accountId);
  if (!row) return false;
  const payload = {
    nickname: userFieldValue(row, 'nickname'),
    level: Number(userFieldValue(row, 'level')),
    brand: userFieldValue(row, 'brand'),
    model: userFieldValue(row, 'model'),
    androidVersion: userFieldValue(row, 'androidVersion'),
    appVersion: userFieldValue(row, 'appVersion')
  };
  if (!payload.nickname) {
    show('昵称不能为空', true);
    return false;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(accountId), {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify(payload)
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(data.error || ('用户信息保存失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    dirtyUserIds.delete(accountId);
    row.classList.remove('dirty');
    updateUserToolbar();
    if (!silent) show('用户信息已保存');
    return true;
  } catch (error) {
    show('用户信息保存失败，请检查网络', true);
    return false;
  }
}
async function deleteUser(accountId) {
  const row = userRow(accountId);
  if (!row) return false;
  const user = currentUsersData && (currentUsersData.users || []).find(function(item) {
    return item.uid === accountId;
  });
  if (dirtyUserIds.size > 0 && (dirtyUserIds.size > 1 || !dirtyUserIds.has(accountId))) {
    show('请先保存其他用户的修改，再删除用户', true);
    return false;
  }
  const displayName = user && user.nickname ? user.nickname : accountId.slice(0, 16) + '…';
  if (!confirm('确定删除用户“' + displayName + '”及其全部流量统计吗？此操作不可恢复。')) {
    return false;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(accountId), {
      method: 'DELETE',
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(data.error || ('用户删除失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    dirtyUserIds.delete(accountId);
    const refreshed = await loadUsers(true);
    show(refreshed ? '用户及其流量统计已删除' : '用户已删除，但用户列表刷新失败', !refreshed);
    return true;
  } catch (error) {
    show('用户删除失败，请检查网络', true);
    return false;
  }
}
function selectedUserAccountIds() {
  const accountIds = Array.from(selectedUserIds);
  if (accountIds.length === 0) {
    show('请先选择用户', true);
    return null;
  }
  if (accountIds.length > 100) {
    show('单次最多选择 100 个用户', true);
    return null;
  }
  return accountIds;
}
async function batchSetUserLevel(level, successMessage) {
  const accountIds = selectedUserAccountIds();
  if (!accountIds) return false;
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/batch', {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify({accountIds: accountIds, level: level})
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(adminErrorMessage(data.error, '批量修改失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    const refreshed = await loadUsers(true);
    show(refreshed ? successMessage : '批量修改成功，但用户列表刷新失败', !refreshed);
    return true;
  } catch (error) {
    show('批量修改失败，请检查网络', true);
    return false;
  }
}
async function batchSetLevel() {
  const rawLevel = el('batchLevel').value;
  const level = Number(rawLevel);
  if (rawLevel === '' || !Number.isInteger(level) || level < 0 || level > 6) {
    show('请选择要设置的等级', true);
    return;
  }
  if (level === 0 && !confirm('确定将选中的用户全部封禁吗？')) return;
  await batchSetUserLevel(level, '已批量修改用户等级');
}
async function batchBanUsers() {
  const accountIds = selectedUserAccountIds();
  if (!accountIds) return;
  if (!confirm('确定封禁选中的 ' + accountIds.length + ' 个用户吗？')) return;
  await batchSetUserLevel(0, '已批量封禁 ' + accountIds.length + ' 个用户');
}
async function batchDeleteUsers() {
  const accountIds = selectedUserAccountIds();
  if (!accountIds) return;
  if (dirtyUserIds.size > 0) {
    show('请先保存未保存的用户修改，再批量删除', true);
    return;
  }
  if (!confirm('确定永久删除选中的 ' + accountIds.length + ' 个用户及其全部流量统计吗？此操作不可恢复。')) {
    return;
  }
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/batch', {
      method: 'DELETE',
      headers: headers(),
      body: JSON.stringify({accountIds: accountIds})
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) {
      show(adminErrorMessage(data.error, '批量删除失败（HTTP ' + response.status + '）'), true);
      return;
    }
    const refreshed = await loadUsers(true);
    show(refreshed ? '已批量删除 ' + (Number(data.changed) || 0) + ' 个用户' : '批量删除成功，但用户列表刷新失败', !refreshed);
  } catch (error) {
    show('批量删除失败，请检查网络', true);
  }
}
async function saveAllUsers() {
  const accountIds = Array.from(dirtyUserIds);
  if (accountIds.length === 0) return;
  savingAllUsers = true;
  updateUserToolbar();
  let saved = 0;
  for (const accountId of accountIds) {
    if (await saveUser(accountId, true)) saved++;
  }
  savingAllUsers = false;
  updateUserToolbar();
  if (saved === accountIds.length) {
    show('已保存 ' + saved + ' 个用户的修改');
  } else {
    show('已保存 ' + saved + ' 个，仍有 ' + dirtyUserIds.size + ' 个保存失败', true);
  }
}
function csvCell(value) {
  return '"' + String(value == null ? '' : value).split('"').join('""') + '"';
}
function exportUsersCsv() {
  const users = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users
    : [];
  if (users.length === 0) {
    show('暂无可导出的用户数据', true);

    return;
  }
  const headers = [
    '账户哈希', '昵称', '等级', '品牌', '型号', 'Android', 'App版本',
    '今日活跃', '本月活跃', '今日上传(MB)', '今日下载(MB)', '今日总量(MB)',
    '本月上传(MB)', '本月下载(MB)', '本月总量(MB)',
    '累计上传(MB)', '累计下载(MB)', '累计总量(MB)',
    '注册时间', '最后上报'
  ];
  const records = users.map(function(user) {
    return [
      user.uid,
      user.nickname,
      user.level,
      user.brand,
      user.model,
      user.androidVersion,
      user.appVersion,
      user.activeToday ? '是' : '否',
      user.activeThisMonth ? '是' : '否',
      csvMegabytes(user.today && user.today.uploadMb),
      csvMegabytes(user.today && user.today.downloadMb),
      csvMegabytes(user.today && user.today.totalMb),
      csvMegabytes(user.month && user.month.uploadMb),
      csvMegabytes(user.month && user.month.downloadMb),
      csvMegabytes(user.month && user.month.totalMb),
      csvMegabytes(user.totalUploadMb),
      csvMegabytes(user.totalDownloadMb),
      csvMegabytes(user.totalMb),
      user.createdAt || '',
      user.lastReportAt || ''
    ];
  });
  const lineBreak = String.fromCharCode(13, 10);
  const csv = [headers].concat(records).map(function(record) {
    return record.map(csvCell).join(',');
  }).join(lineBreak);
  const blob = new Blob([String.fromCharCode(0xfeff), csv], {type: 'text/csv;charset=utf-8'});
  const downloadUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = downloadUrl;
  link.download = 'firefly-users-' + (currentUsersData.date || 'export') + '.csv';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(function() { URL.revokeObjectURL(downloadUrl); }, 1000);
  show('用户详细表格已导出，可直接使用 Excel 打开');
}
function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  const sourceType = source.content ? 'content' : 'url';
  row.innerHTML = '<section class="source-section source-identity-section">' +
    '<div class="grid source-identity-grid">' +
      '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
      '<div class="field"><label>后台备注（仅后台保存）<input data-field="remark" type="text" value="' + esc(source.remark) + '" placeholder="例如：备用线路、长期稳定"></label></div>' +
    '</div>' +
    '</section>' +
    '<section class="source-section source-permissions-section">' +
      '<h3 class="source-section-title">线路权限控制</h3>' +
      '<div class="grid source-permissions-grid">' +
        '<div class="field"><label>最低账户等级<select data-field="minLevel">' + levelOptions(source.minLevel == null ? 1 : source.minLevel) + '</select></label></div>' +
        '<div class="field"><label>最低应用版本<input data-field="minAppVersion" type="text" value="' + esc(source.minAppVersion) + '" placeholder="例如 2.40（留空不限制）"></label></div>' +
        '<label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
      '</div>' +
    '</section>' +
    '<section class="source-section source-details-section">' +
      '<h3 class="source-section-title">订阅详细设定</h3>' +
      '<div class="grid source-details-grid">' +
        '<div class="field source-type-field"><label>订阅来源<select data-field="sourceType">' +
          '<option value="url" ' + (sourceType === 'url' ? 'selected' : '') + '>外部接口</option>' +
          '<option value="content" ' + (sourceType === 'content' ? 'selected' : '') + '>纯文本节点</option>' +
        '</select></label></div>' +
        '<label class="check"><input data-field="refreshOnAppOpen" type="checkbox" ' + (source.refreshOnAppOpen === false ? '' : 'checked') + '>打开app立即更新</label>' +
      '</div>' +
      '<div class="source-mode" data-source-mode="url"><div class="grid">' +
        '<div class="field"><label>外部明文订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/plain-sub"></label></div>' +
        '<div class="field"><label>接口缓存时间（分钟）<input data-field="cacheMinutes" type="number" min="0" max="10080" step="1" value="' + esc(source.cacheMinutes == null ? 30 : source.cacheMinutes) + '"></label></div>' +
      '</div><small>接口必须返回可识别的明文节点，由 Worker 中转并加密；缓存时间设为 0 会每次请求上游。</small></div>' +
      '<div class="source-mode" data-source-mode="content"><div class="field"><label>纯文本节点（每行一个）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div><small>保存后由 Worker 直接加密下发，不经过外部接口缓存。</small></div>' +
    '</section>' +
    '<div class="row-actions"><button class="danger" type="button">删除</button></div>';
  row.querySelector('.danger').onclick = function() { row.remove(); };
  row.querySelector('[data-field="sourceType"]').onchange = function() { updateSourceMode(row); };
  updateSourceMode(row);
  el('subscriptionRows').appendChild(row);
}
function updateSourceMode(row) {
  const type = row.querySelector('[data-field="sourceType"]').value;
  row.querySelectorAll('[data-source-mode]').forEach(function(panel) {
    const active = panel.dataset.sourceMode === type;
    panel.hidden = !active;
    panel.querySelectorAll('input, textarea, select').forEach(function(control) {
      control.disabled = !active;
    });
  });
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
   setValue('dailyCheckinExperience', settings.dailyCheckinExperience == null ? 10 : settings.dailyCheckinExperience);
   setValue('telegramGroupUrl', settings.telegramGroupUrl);
   setValue('telegramJoinCode', settings.telegramJoinCode);
   setValue('telegramTaskExperience', settings.telegramTaskExperience == null ? 50 : settings.telegramTaskExperience);
   setValue('speedShareSmartExperience', settings.speedShareSmartExperience == null ? 5 : settings.speedShareSmartExperience);
  const experienceThresholds = Array.isArray(settings.experienceThresholds)
    ? settings.experienceThresholds
    : [100, 200, 400, 800, 1600];
  ['experienceThresholdLv2', 'experienceThresholdLv3', 'experienceThresholdLv4',
    'experienceThresholdLv5', 'experienceThresholdLv6'].forEach(function(id, index) {
    setValue(id, experienceThresholds[index]);
  });
  const notice = currentState.notice || {};
  checked('noticeEnabled', Boolean(currentState.notice && notice.hasNotice !== false));
  setValue('noticeTitle', notice.title);
  setValue('noticeId', notice.noticeId);
  setValue('noticeStartAt', noticeDateTimeValue(notice.startAt));
  setValue('noticeEndAt', noticeDateTimeValue(notice.endAt));
  setValue('noticeContent', notice.content);
  checked('noticeShowOnce', notice.showOnce !== false);
  const update = currentState.appUpdate || {};
  checked('appUpdateEnabled', Boolean(currentState.appUpdate));
  setValue('appVersion', update.version);
  setValue('appVersionCode', update.versionCode);
  setValue('appDownloadUrl', update.downloadUrl);
  setValue('appChangelog', update.changelog);
  checked('appForce', update.is_force === 1 || update.isForce === 1);
}
function noticeDateTimeValue(data) {
  const text = String(data == null ? '' : data).trim();
  const match = text.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return match ? match[1] : '';
}
function formState() {
  const subscriptions = Array.from(document.querySelectorAll('.source-row')).map(function(row, index) {
    const sourceType = row.querySelector('[data-field="sourceType"]').value;
    const url = row.querySelector('[data-field="url"]').value.trim();
    const content = row.querySelector('[data-field="content"]').value.trim();
    return {
      name: row.querySelector('[data-field="name"]').value.trim() || '线路' + (index + 1),
      url: sourceType === 'url' ? url : '',
      enabled: row.querySelector('[data-field="enabled"]').checked,
      minLevel: Number(row.querySelector('[data-field="minLevel"]').value || 1),
      minAppVersion: row.querySelector('[data-field="minAppVersion"]').value.trim(),
      cacheMinutes: sourceType === 'url'
        ? Number(row.querySelector('[data-field="cacheMinutes"]').value || 0)
        : 0,
      content: sourceType === 'content' ? content : '',
      refreshOnAppOpen: row.querySelector('[data-field="refreshOnAppOpen"]').checked,
      remark: row.querySelector('[data-field="remark"]').value.trim()
    };
  }).filter(function(item) { return item.url || item.content; });
  const notice = el('noticeEnabled').checked ? {
    hasNotice: true,
    title: value('noticeTitle'),
    content: value('noticeContent'),
    noticeId: value('noticeId'),
    showOnce: el('noticeShowOnce').checked,
    startAt: value('noticeStartAt'),
    endAt: value('noticeEndAt')
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
       nodeRequestTimeoutMs: Number(value('nodeRequestTimeoutMs') || 25000),
       dailyCheckinExperience: Number(value('dailyCheckinExperience') || 0),
       telegramGroupUrl: value('telegramGroupUrl'),
       telegramJoinCode: value('telegramJoinCode'),
       telegramTaskExperience: Number(value('telegramTaskExperience') || 0),
       speedShareSmartExperience: Number(value('speedShareSmartExperience') || 0),
       experienceThresholds: [
        Number(value('experienceThresholdLv2') || 100),
        Number(value('experienceThresholdLv3') || 200),
        Number(value('experienceThresholdLv4') || 400),
        Number(value('experienceThresholdLv5') || 800),
        Number(value('experienceThresholdLv6') || 1600)
      ]
    },
    notice: notice,
    appUpdate: appUpdate
  };
}
async function pushSection(section) {
  if (section === 'users') {
    if (await loadUsers()) show('用户表单已刷新');
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
  if (!response.ok) return show(adminErrorMessage(data.error, '推送失败'), true);
  currentState = data.state;
  fillState(data.state);
  show('当前模块已推送');
}
initializeGrowthRange();
initializeTrafficRange();
addSource();
</script>
</body>
</html>`;
}
