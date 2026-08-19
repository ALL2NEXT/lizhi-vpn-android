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
  let inviteRelationsData = null;
  let inviteRelationsLoading = false;
  let speedShareResultsData = null;
  let speedShareResultsLoading = false;
  let adminLoginRecordsData = null;
  let adminLoginRecordsLoading = false;
  let aiAnalysisLoading = false;
  let aiFabDragState = null;
  let aiFabDragMoved = false;
  const AI_ANALYSIS_FAB_POSITION_KEY = 'firefly:ai-analysis-fab-position:v1';
  let activeDashboardTab = 'user-trends';
  const DASHBOARD_AI_CONTEXT = {
    'user-trends': {
      title: 'AI 助手 · 用户趋势',
      task: '分析新增用户和活跃用户趋势，预测明天的用户变化',
    },
    'traffic-trends': {
      title: 'AI 助手 · 流量趋势',
      task: '预测明天的上传、下载和总流量',
    },
    'profile-ratio': {
      title: 'AI 助手 · 占比画像',
      task: '分析是否需要更新客户端版本',
    },
  };
  let trafficDetailsSortKey = '';
  let trafficDetailsSortDirection = 'asc';
  const trafficZeroFilters = {today: false, month: false, total: false};
  let draggedSourceRow = null;
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
 const ADMIN_API_BASE = "__ADMIN_BASE_PATH__/api";
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
   if (error === 'invalid_registration_code_count') return '注册码数量必须是 1 到 100 之间的整数';
   if (error === 'formal_registration_closed') return '正式用户注册暂未开放';
   if (error === 'invalid_experience_award') return '请填写正整数经验值和审核说明';
   if (error === 'account_not_found') return '用户不存在，请检查 UID';
   if (error === 'account_banned') return '账户已封禁，无法发放经验';
   if (error === 'email_binding_required') return '该账户尚未绑定邮箱，无法获取经验';
    if (error === 'invalid_batch_user_update') return '批量修改参数无效，最多选择 100 个用户，等级范围为 Lv0～Lv6';
     if (error === 'invalid_batch_user_delete') return '批量删除参数无效，最多选择 100 个用户';
    if (error === 'subscription_must_be_plaintext_nodes') return '订阅必须返回可识别的明文节点，不能使用已加密接口';
    if (error === 'invalid_min_app_version') return '最低应用版本格式无效，请填写类似 2.40 的数字版本';
   if (error === 'external_subscription_unreachable') return '外部明文订阅接口无法访问';
   if (error === 'invalid_notice_start_at') return '公告开始时间格式无效';
   if (error === 'invalid_notice_end_at') return '公告结束时间格式无效';
   if (error === 'invalid_notice_time_order') return '公告结束时间不能早于开始时间';
   if (error === 'gemini_not_configured') return '尚未配置 Cloudflare Secret：GEMINI_API_KEY';
   if (error === 'gemini_auth_failed') return 'Gemini API 密钥无效，或该密钥没有 Gemini API 权限';
   if (error === 'gemini_model_unavailable') return '当前 Gemini 模型不可用，请检查 API 项目权限或模型名称';
   if (error === 'gemini_quota_exceeded') return 'Gemini API 配额已用尽或触发限流，请检查配额后重试';
   if (error === 'gemini_request_failed') return 'Gemini 请求失败，请检查 API 密钥、模型权限或稍后重试';
   if (error === 'gemini_empty_response') return 'Gemini 未返回有效分析内容，请稍后重试';
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
       activateTab('user-trends');
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
   deviceRelationsData = null;
   deviceRelationsLoading = false;
   trafficDetailsData = null;
   inviteRelationsData = null;
   inviteRelationsLoading = false;
   closeDeviceRelationDetail();
   speedShareResultsData = null;
   adminLoginRecordsData = null;
   adminLoginRecordsLoading = false;
   aiAnalysisLoading = false;
   closeAiAnalysis();
   trafficZeroFilters.today = false;
   trafficZeroFilters.month = false;
   trafficZeroFilters.total = false;
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
   const isDashboardTab = name === 'user-trends' || name === 'traffic-trends' || name === 'profile-ratio';
   const aiAnalysisFab = el('aiAnalysisFab');
   if (aiAnalysisFab) aiAnalysisFab.hidden = !isDashboardTab;
   if (isDashboardTab) {
     activeDashboardTab = name;
     updateAiAnalysisContext(true);
   }
   if (!isDashboardTab) closeAiAnalysis();
   if (name === 'account-security' && !accountSecurityData && !accountSecurityLoading) {
     loadAccountSecurity();
   }
   if (name === 'device-relations' && !deviceRelationsData && !deviceRelationsLoading) {
     loadDeviceRelations();
   }
   if (name === 'traffic-query' && !trafficDetailsData && !trafficDetailsLoading) {
     loadTrafficDetails();
   }
   if (name === 'invite-relations' && !inviteRelationsData && !inviteRelationsLoading) {
     loadInviteRelations();
   }
   if (name === 'speed-share-results' && !speedShareResultsData && !speedShareResultsLoading) {
     loadSpeedShareResults();
   }
   if (name === 'admin-login-records' && !adminLoginRecordsData && !adminLoginRecordsLoading) {
     loadAdminLoginRecords();
   }
   if (name === 'experience-rewards' && !experienceAwardsData && !experienceAwardsLoading) {
     loadExperienceAwards();
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
 document.addEventListener('keydown', function(event) {
   if (event.key === 'Escape') closeAiAnalysis();
 });

function clampAiAnalysisFabPosition(left, top) {
  const fab = el('aiAnalysisFab');
  if (!fab) return;
  const width = fab.offsetWidth || 48;
  const height = fab.offsetHeight || 48;
  const maxLeft = Math.max(8, window.innerWidth - width - 8);
  const maxTop = Math.max(8, window.innerHeight - height - 8);
  const nextLeft = Math.min(Math.max(Number(left) || 0, 8), maxLeft);
  const nextTop = Math.min(Math.max(Number(top) || 0, 8), maxTop);
  fab.style.left = Math.round(nextLeft) + 'px';
  fab.style.top = Math.round(nextTop) + 'px';
  fab.style.right = 'auto';
  fab.style.bottom = 'auto';
}
function saveAiAnalysisFabPosition() {
  const fab = el('aiAnalysisFab');
  if (!fab || !fab.style.left || !fab.style.top) return;
  try {
    localStorage.setItem(AI_ANALYSIS_FAB_POSITION_KEY, JSON.stringify({
      left: parseFloat(fab.style.left),
      top: parseFloat(fab.style.top),
    }));
  } catch (_) {
    // 忽略浏览器禁用本地存储的情况。
  }
}
function restoreAiAnalysisFabPosition() {
  const fab = el('aiAnalysisFab');
  if (!fab) return;
  try {
    const saved = JSON.parse(localStorage.getItem(AI_ANALYSIS_FAB_POSITION_KEY) || 'null');
    if (saved && Number.isFinite(Number(saved.left)) && Number.isFinite(Number(saved.top))) {
      clampAiAnalysisFabPosition(saved.left, saved.top);
    }
  } catch (_) {
    // 忽略无效或不可用的本地存储数据。
  }
}
function positionAiAnalysisDialog() {
  const overlay = el('aiAnalysisOverlay');
  const fab = el('aiAnalysisFab');
  const dialog = overlay && overlay.querySelector('.ai-analysis-dialog');
  if (!overlay || !fab || !dialog || overlay.hidden || fab.hidden) return;
  const fabRect = fab.getBoundingClientRect();
  const dialogRect = dialog.getBoundingClientRect();
  const margin = 12;
  const maxLeft = Math.max(margin, window.innerWidth - dialogRect.width - margin);
  const maxTop = Math.max(margin, window.innerHeight - dialogRect.height - margin);
  let left = fabRect.left;
  let top = fabRect.bottom + margin;
  if (top > maxTop) top = fabRect.top - dialogRect.height - margin;
  left = Math.min(Math.max(left, margin), maxLeft);
  top = Math.min(Math.max(top, margin), maxTop);
  dialog.style.left = Math.round(left) + 'px';
  dialog.style.top = Math.round(top) + 'px';
  dialog.style.right = 'auto';
  dialog.style.bottom = 'auto';
}
function setupAiAnalysisFabDrag() {
  const fab = el('aiAnalysisFab');
  if (!fab) return;
  fab.addEventListener('pointerdown', function(event) {
    if (event.button !== undefined && event.button !== 0) return;
    aiFabDragMoved = false;
    const rect = fab.getBoundingClientRect();
    aiFabDragState = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startLeft: rect.left,
      startTop: rect.top,
    };
    fab.classList.remove('is-dragging');
    if (fab.setPointerCapture) fab.setPointerCapture(event.pointerId);
  });
  fab.addEventListener('pointermove', function(event) {
    if (!aiFabDragState || event.pointerId !== aiFabDragState.pointerId) return;
    const deltaX = event.clientX - aiFabDragState.startX;
    const deltaY = event.clientY - aiFabDragState.startY;
    if (!aiFabDragMoved && Math.hypot(deltaX, deltaY) < 5) return;
    aiFabDragMoved = true;
    fab.classList.add('is-dragging');
    clampAiAnalysisFabPosition(
      aiFabDragState.startLeft + deltaX,
      aiFabDragState.startTop + deltaY,
    );
    positionAiAnalysisDialog();
    event.preventDefault();
  });
  function finishDrag(event) {
    if (!aiFabDragState || event.pointerId !== aiFabDragState.pointerId) return;
    if (fab.releasePointerCapture && fab.hasPointerCapture && fab.hasPointerCapture(event.pointerId)) {
      fab.releasePointerCapture(event.pointerId);
    }
    fab.classList.remove('is-dragging');
    if (aiFabDragMoved) saveAiAnalysisFabPosition();
    aiFabDragState = null;
  }
  fab.addEventListener('pointerup', finishDrag);
  fab.addEventListener('pointercancel', finishDrag);
  fab.addEventListener('click', function(event) {
    if (!aiFabDragMoved) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    aiFabDragMoved = false;
  }, true);
  restoreAiAnalysisFabPosition();
  window.addEventListener('resize', function() {
    if (fab.style.left && !fab.hidden) {
      clampAiAnalysisFabPosition(fab.style.left, fab.style.top);
      saveAiAnalysisFabPosition();
    }
    positionAiAnalysisDialog();
  });
}
setupAiAnalysisFabDrag();
