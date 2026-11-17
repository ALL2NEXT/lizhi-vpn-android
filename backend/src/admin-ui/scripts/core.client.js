  let currentState = null;
  let adminToken = '';
  let currentUsersData = null;
  let accountSecurityData = null;
  let accountSecurityLoading = false;
  const accountSecuritySelection = new Set();
  let deviceRelationsData = null;
  let deviceRelationsLoading = false;
  let trafficDetailsData = null;
  let trafficDetailsLoading = false;
  let systemLogsData = null;
  let systemLogsLoading = false;
  let systemLogsOffset = 0;
  let systemLogsCategory = 'request';
  let trafficDetailsSortKey = '';
  let trafficDetailsSortDirection = 'asc';
  const trafficZeroFilters = {today: false, month: false, total: false};
 let growthPeriod = 'day';
 let growthRequestId = 0;
 let activityPeriod = 'day';
 let activityRequestId = 0;
 let trafficPeriod = 'day';
 let trafficRequestId = 0;
 const dirtyUserIds = new Set();
 const selectedUserIds = new Set();
 let savingAllUsers = false;
 let userSearchMatches = [];
 let userSearchIndex = -1;
 let userSortKey = '';
 let userSortDirection = 'desc';
 const USER_COLUMN_WIDTHS_KEY = 'chimera:user-sheet-column-widths:v1';
 const LEGACY_USER_COLUMN_WIDTHS_KEY = 'firefly:user-sheet-column-widths:v1';
 const USER_SHEET_COLUMNS = [
   {key: 'select', label: '选择', width: 48, min: 44},
   {key: 'accountId', label: '用户 UID', width: 150, min: 110},
   {key: 'nickname', label: '昵称', width: 110, min: 70},
   {key: 'accountType', label: '用户类型', width: 90, min: 70},
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
 const ADMIN_API_BASE = "__ADMIN_BASE_PATH__/api";
 const ADMIN_PANEL_AUTO_REFRESH_STORAGE_KEY = 'chimera.admin.panel-auto-refresh.v1';
 const ADMIN_PANEL_AUTO_REFRESH_INTERVAL_MS = 30000;
 let adminPanelAutoRefreshPreferences = readAdminPanelAutoRefreshPreferences();
 let adminPanelAutoRefreshTimer = null;
 let adminPanelRefreshInFlight = false;
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
 function show(message, error) { statusEl.textContent = message; statusEl.style.color = error ? '#c42b1c' : '#107c10'; }
 // 页面标题与临时操作提示分开，保存控件可以和标题保持同一行。
 function showDashboardTitle(name) {
   const tab = document.querySelector('.tab-button[data-tab="' + name + '"]');
   const title = document.getElementById('dashboardTitle');
   if (title) title.textContent = tab ? tab.textContent.trim() : '';
   statusEl.textContent = '';
   statusEl.style.color = '#107c10';
 }
 function showLoginError(message) { loginStatusEl.textContent = message || ''; }
 function parseResponseJson(responseText) {
   try { return JSON.parse(responseText); } catch (_) { return {}; }
 }
 function adminApiUrl(path) {
   return new URL(ADMIN_API_BASE + path, window.location.origin).toString();
 }
 function readAdminPanelAutoRefreshPreferences() {
   try {
     const saved = JSON.parse(window.localStorage.getItem(ADMIN_PANEL_AUTO_REFRESH_STORAGE_KEY) || '{}');
     return saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
   } catch (_) {
     return {};
   }
 }
 function currentAdminPanelName() {
   const panel = document.querySelector('.tab-panel.active[data-panel]');
   return panel ? panel.dataset.panel : '';
 }
 function settingsSectionHasUnsavedChanges(section) {
   return typeof settingsDirtySections !== 'undefined'
     ? settingsDirtySections.has(section)
     : typeof hasUnsavedAdminChanges === 'function' && hasUnsavedAdminChanges();
 }
 function adminPanelRefreshBlockReason(panelName) {
   const settingsSection = ['subscriptions', 'about-page', 'update-management', 'registration-control'].includes(panelName)
     ? panelName : '';
   if (settingsSection && (settingsSectionHasUnsavedChanges(settingsSection) ||
       (typeof settingsSaveInFlight !== 'undefined' && (settingsSaveInFlight === settingsSection || settingsManualSaveRun)))) {
     return '当前模块有未保存修改或正在保存，请稍后刷新';
   }
   if ((panelName === 'user-trends' || panelName === 'profile-ratio') &&
       typeof dirtyUserIds !== 'undefined' && dirtyUserIds.size > 0) {
     return '用户信息有未保存修改，请先保存后刷新';
   }
   if (panelName === 'users' && typeof adminUserBatchBusy !== 'undefined' && adminUserBatchBusy) {
     return '账户批量操作进行中，请稍后再刷新';
   }
   if (panelName === 'device-management' && typeof adminDeviceBatchBusy !== 'undefined' && adminDeviceBatchBusy) {
     return '设备批量操作进行中，请稍后再刷新';
   }
   if (panelName === 'ai-risk' && typeof aiRiskOperationInFlight !== 'undefined' && aiRiskOperationInFlight) {
     return '行为异常分析操作进行中，请稍后再刷新';
   }
   const notificationEditor = document.getElementById('notificationEditor');
   if (panelName === 'notifications' && notificationEditor && !notificationEditor.hidden) {
     return '通知编辑窗口打开时暂停刷新';
   }
   if (panelName === 'subscriptions' && typeof subscriptionRefreshInFlight !== 'undefined' && subscriptionRefreshInFlight) {
     return '订阅更新进行中，请稍后再刷新';
   }
   return '';
 }
 async function refreshAdminSettingsPanel(section) {
   const response = await fetch(ADMIN_API_BASE + '/state', {
     headers: headers(),
     cache: 'no-store'
   });
   const data = parseResponseJson(await response.text());
   if (!response.ok) {
     show(data.error || ('配置读取失败（HTTP ' + response.status + '）'), true);
     return false;
   }
   currentState = data;
   fillState(data, section);
   return true;
 }
 async function refreshCurrentAdminPanel(automatic) {
   const panelName = currentAdminPanelName();
   const button = document.getElementById('dashboardRefreshButton');
   const refreshLabel = button && button.querySelector('[data-dashboard-refresh-label]');
   const refreshers = {
     'user-trends': function() { return loadUsers(true); },
     'traffic-trends': function() { return loadTrafficChart(trafficPeriod); },
     'profile-ratio': function() { return loadUsers(true); },
     'users': function() { return loadAdminUsers(true); },
     'device-management': function() { return loadAdminDevices(true); },
     'registration-control': function() { return refreshAdminSettingsPanel('registration-control'); },
     'subscriptions': function() { return refreshAdminSettingsPanel('subscriptions'); },
     'about-page': function() { return refreshAdminSettingsPanel('about-page'); },
     'update-management': function() { return refreshAdminSettingsPanel('update-management'); },
     'notifications': function() { return loadAdminNotifications(); },
     'environment-anomalies': function() { return loadClientSecurityReports(true); },
     'ai-risk': function() { return loadAiRiskDashboard(false); },
     'system-logs': function() { return loadSystemLogs(true); }
   };
   if (!adminToken || adminPanelRefreshInFlight) return false;
   const refresh = refreshers[panelName];
   if (!refresh) {
     if (!automatic) show('当前页面没有可刷新的数据');
     return false;
   }
   const blockReason = adminPanelRefreshBlockReason(panelName);
   if (blockReason) {
     if (!automatic) show(blockReason, true);
     return false;
   }

   adminPanelRefreshInFlight = true;
   if (button) {
     button.disabled = true;
     button.setAttribute('aria-busy', 'true');
     if (refreshLabel) refreshLabel.textContent = '刷新中…';
   }
   try {
     const refreshed = await refresh();
     if (refreshed === false) return false;
     return true;
   } catch (error) {
     if (!automatic) show('当前页面刷新失败：' + ((error && error.message) || '请检查网络'), true);
     return false;
   } finally {
     adminPanelRefreshInFlight = false;
     if (button) {
       button.disabled = false;
       button.removeAttribute('aria-busy');
       if (refreshLabel) refreshLabel.textContent = '刷新';
     }
   }
 }
 function syncAdminPanelAutoRefresh(panelName) {
   if (adminPanelAutoRefreshTimer) clearInterval(adminPanelAutoRefreshTimer);
   adminPanelAutoRefreshTimer = null;
   const activePanel = panelName || currentAdminPanelName();
   const toggle = document.getElementById('dashboardAutoRefreshToggle');
   const enabled = adminPanelAutoRefreshPreferences[activePanel] === true;
   if (toggle) toggle.checked = enabled;
   if (!enabled || !activePanel) return;
   adminPanelAutoRefreshTimer = setInterval(function() {
     if (!adminToken || document.hidden || currentAdminPanelName() !== activePanel) return;
     void refreshCurrentAdminPanel(true);
   }, ADMIN_PANEL_AUTO_REFRESH_INTERVAL_MS);
 }
 function setAdminPanelAutoRefresh(enabled) {
   const panelName = currentAdminPanelName();
   if (!panelName) return;
   adminPanelAutoRefreshPreferences[panelName] = Boolean(enabled);
   try {
     window.localStorage.setItem(ADMIN_PANEL_AUTO_REFRESH_STORAGE_KEY, JSON.stringify(adminPanelAutoRefreshPreferences));
   } catch (_) {}
   syncAdminPanelAutoRefresh(panelName);
 }
 function stopAdminPanelAutoRefresh() {
   if (adminPanelAutoRefreshTimer) clearInterval(adminPanelAutoRefreshTimer);
   adminPanelAutoRefreshTimer = null;
 }
 function adminErrorMessage(error, fallback) {
   if (error === 'invalid_latest_app_version') return '最新应用版本格式无效，请使用类似 1.2.3 的数字版本号';
   if (error === 'invalid_app_download_url') return '应用下载链接必须是有效的 HTTP 或 HTTPS 地址';
   if (error === 'incomplete_app_update_config') return '请同时填写最新应用版本和应用下载链接，或同时清空';
   if (error === 'invalid_registration_code_count') return '注册码数量必须是 1 到 100 之间的整数';
   if (error === 'invalid_registration_code_max_uses') return '单个注册码可用次数必须是 1 到 1000 之间的整数';
   if (error === 'registration_code_not_found') return '注册码不存在';
   if (error === 'formal_registration_closed') return '正式用户注册暂未开放';
   if (error === 'account_not_found') return '用户不存在，请检查 UID';
   if (error === 'account_banned') return '账户已被封禁';
   if (error === 'device_banned') return '设备已被封禁';
   if (error === 'invalid_batch_device_update') return '设备批量封禁参数无效，单次最多选择 100 台设备';
     if (error === 'invalid_batch_user_delete') return '批量删除参数无效，最多选择 100 个用户';
    if (error === 'subscription_must_be_plaintext_nodes') return '订阅没有可用节点，支持 URI、Base64、Clash、sing-box 和 Xray 格式';
    if (error === 'subscription_import_no_nodes') return '未识别到可用节点或订阅地址，请检查输入内容';
    if (error === 'invalid_subscription_import_input') return '请输入有效的订阅内容';
    if (error === 'invalid_subscription_url') return '请填写有效的 HTTP 或 HTTPS 订阅地址';
    if (error === 'subscription_import_too_many_urls') return '每次最多导入 20 个远程订阅地址，请分批导入';
    if (error === 'managed_subscription_too_large') return '订阅内容或文件不能超过 2 MB';
    if (error === 'invalid_min_app_version') return '最低应用版本格式无效，请填写类似 2.40 的数字版本';
   if (error === 'external_subscription_unreachable') return '外部订阅接口无法访问或请求超时';
    if (error === 'subscription_not_found') return '订阅已不存在，请刷新管理页后重试';
   if (error === 'external_subscription_url_required') return '请填写外部订阅地址';
   if (String(error || '').indexOf('external_subscription_http_') === 0) {
     return '外部订阅接口请求失败（HTTP ' + String(error).slice('external_subscription_http_'.length) + '）';
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
       activateTab('user-trends');
     } catch (error) {
       show('后台已登录，但页面初始化失败，请刷新页面', true);
       console.error('admin dashboard initialization failed', error);
     }
     const usersLoaded = await loadUsers();
     if (!usersLoaded) show('已进入后台，但用户数据读取失败', true);
     passwordInput.value = '';
     showLoginError('');
   } catch (error) {
     adminToken = '';
     showLoginError('无法连接后台，请检查网络或确认管理地址正确');
   }
 };
 function logout() {
   if ((typeof settingsSaveInFlight !== 'undefined' && settingsSaveInFlight) ||
       (typeof adminUserBatchBusy !== 'undefined' && adminUserBatchBusy) ||
       (typeof adminDeviceBatchBusy !== 'undefined' && adminDeviceBatchBusy)) {
     show('正在保存或处理用户，请等待完成后退出', true);
     return;
   }
   if (typeof hasUnsavedAdminChanges === 'function' && hasUnsavedAdminChanges() &&
       !confirm('还有未保存修改，确定放弃并退出后台？')) return;
   adminToken = '';
   currentState = null;
   if (typeof resetSettingsDrafts === 'function') resetSettingsDrafts();
   if (typeof resetAdminUsers === 'function') resetAdminUsers();
   if (typeof resetAdminDeviceManagement === 'function') resetAdminDeviceManagement();
   if (typeof resetAdminNotifications === 'function') resetAdminNotifications();
   accountSecurityData = null;
   accountSecuritySelection.clear();
   deviceRelationsData = null;
   deviceRelationsLoading = false;
   if (typeof resetIpRelations === 'function') resetIpRelations();
   trafficDetailsData = null;
   closeDeviceRelationDetail();
   stopAdminPanelAutoRefresh();
   if (typeof closeSystemLogDetails === 'function') closeSystemLogDetails();
   systemLogsData = null;
   systemLogsLoading = false;
   systemLogsOffset = 0;
   systemLogsCategory = 'request';
   trafficZeroFilters.today = false;
   trafficZeroFilters.month = false;
   trafficZeroFilters.total = false;
   statusEl.textContent = '';
   el('dashboard').hidden = true;
   loginGate.hidden = false;
   passwordInput.value = '';
   showLoginError('');
   passwordInput.focus();
 }
 function activateTab(name) {
   showDashboardTitle(name);
   document.body.classList.toggle('subscriptions-fullscreen', name === 'subscriptions');
   document.querySelectorAll('.tab-button[data-tab]').forEach(function(button) {
     const active = button.dataset.tab === name;
     button.classList.toggle('active', active);
     button.setAttribute('aria-selected', active ? 'true' : 'false');
   });
   document.querySelectorAll('.nav-group').forEach(function(group) {
     const isChildTab = Array.from(group.querySelectorAll('.tab-button[data-tab]')).some(function(button) {
       return button.dataset.tab === name;
     });
     if (!isChildTab) return;
     const parent = group.querySelector('.nav-parent');
     const submenu = group.querySelector('.nav-submenu');
     group.classList.add('expanded');
     if (parent) parent.setAttribute('aria-expanded', 'true');
     if (submenu) submenu.hidden = false;
   });
   document.querySelectorAll('.tab-panel').forEach(function(panel) {
     panel.classList.toggle('active', panel.dataset.panel === name);
   });
   syncAdminPanelAutoRefresh(name);
   if (name === 'account-security' && !accountSecurityData && !accountSecurityLoading) {
     loadAccountSecurity();
   }
   if (name === 'users' && typeof loadAdminUsers === 'function' && !adminUserData && !adminUserLoading) {
     loadAdminUsers();
   }
   if (name === 'device-management' && typeof loadAdminDevices === 'function' &&
       !adminDeviceData && !adminDeviceLoading) {
     loadAdminDevices();
   }
   if (name === 'traffic-query' && !trafficDetailsData && !trafficDetailsLoading) {
     loadTrafficDetails();
   }
   if (name === 'system-logs' && !systemLogsData && !systemLogsLoading) {
     loadSystemLogs();
   }
   if (name === 'environment-anomalies' && typeof loadClientSecurityReports === 'function') {
     loadClientSecurityReports();
   }
   if (name === 'ai-risk' && typeof loadAiRiskSettings === 'function') {
     loadAiRiskSettings();
     if (typeof loadAiRiskDashboard === 'function') loadAiRiskDashboard(false);
   }
   if (name === 'system-logs') {
     loadSystemLogRecordingSettings();
   }
 }
 document.getElementById('dashboardRefreshButton').addEventListener('click', function() {
   void refreshCurrentAdminPanel(false);
 });
 document.getElementById('dashboardAutoRefreshToggle').addEventListener('change', function(event) {
   setAdminPanelAutoRefresh(event.target.checked);
 });
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
