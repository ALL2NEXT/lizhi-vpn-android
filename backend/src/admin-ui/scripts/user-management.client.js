let adminUserData = null;
let adminUserLoading = false;
let adminUserUidHidden = true;
let adminUserBatchBusy = false;
let adminUserDetailUid = '';
let adminUserSecuritySource = null;
let adminUserSecurityIndex = new Map();
const adminUserSaving = new Set();
const adminUserSelection = new Set();
const adminUserDirty = new Set();
const adminUserDrafts = new Map();
const adminUserErrors = new Map();
const adminUserZeroFilters = {today: false, month: false, total: false};
const ADMIN_USER_SORTS = {
  created_desc: {valueKey: 'createdAt', direction: -1, label: '注册时间（新到旧）'},
  created_asc: {valueKey: 'createdAt', direction: 1, label: '注册时间（旧到新）'},
  today_desc: {valueKey: 'todayMb', direction: -1, label: '今日流量（高到低）'},
  today_asc: {valueKey: 'todayMb', direction: 1, label: '今日流量（低到高）'},
  month_desc: {valueKey: 'monthMb', direction: -1, label: '本月流量（高到低）'},
  month_asc: {valueKey: 'monthMb', direction: 1, label: '本月流量（低到高）'},
  total_desc: {valueKey: 'totalMb', direction: -1, label: '累计流量（高到低）'},
  total_asc: {valueKey: 'totalMb', direction: 1, label: '累计流量（低到高）'},
  last_report_desc: {valueKey: 'lastReportAt', direction: -1, label: '最后上报（新到旧）'},
  last_report_asc: {valueKey: 'lastReportAt', direction: 1, label: '最后上报（旧到新）'},
  nickname_asc: {valueKey: 'nickname', direction: 1, label: '昵称（A-Z）'},
};

function resetAdminUsers() {
  adminUserData = null;
  adminUserLoading = false;
  adminUserUidHidden = true;
  adminUserBatchBusy = false;
  adminUserDetailUid = '';
  adminUserSecuritySource = null;
  adminUserSecurityIndex.clear();
  const dialog = el('adminUserDetailDialog');
  if (dialog && dialog.open) dialog.close();
  adminUserSaving.clear();
  adminUserSelection.clear();
  adminUserDirty.clear();
  adminUserDrafts.clear();
  adminUserErrors.clear();
  adminUserZeroFilters.today = false;
  adminUserZeroFilters.month = false;
  adminUserZeroFilters.total = false;
}

function toggleAdminUserUidVisibility() {
  adminUserUidHidden = !adminUserUidHidden;
  renderAdminUsers();
}

function adminUserDisplayUid(uid) {
  return adminUserUidHidden ? '••••••••' : uid;
}

function adminUserIdentityLabel(identityType) {
  return identityType === 'bot' ? '机器人' : '真人';
}

function adminUserSecurity(user) {
  const accounts = adminUserData && Array.isArray(adminUserData.securityAccounts)
    ? adminUserData.securityAccounts : [];
  if (adminUserSecuritySource !== accounts) {
    adminUserSecuritySource = accounts;
    adminUserSecurityIndex = new Map(accounts.map(function(account) {
      return [String(account.uid || ''), account];
    }));
  }
  return adminUserSecurityIndex.get(String(user.uid || '')) ||
    {devices: [], historicalDeviceCount: 0, bannedDeviceCount: 0};
}

function adminUserDevices(user) {
  const security = adminUserSecurity(user);
  return Array.isArray(security.devices) ? security.devices : [];
}

function adminUserSortValue(user, key) {
  switch (key) {
    case 'todayMb': return Number(user.today && user.today.totalMb || 0);
    case 'monthMb': return Number(user.month && user.month.totalMb || 0);
    case 'totalMb': return Number(user.totalMb || 0);
    case 'nickname': return String(user.nickname || '').toLowerCase();
    case 'lastReportAt': return String(user.lastReportAt || '');
    case 'createdAt': return String(user.createdAt || '');
    default: return '';
  }
}

function compareAdminUserSortValues(left, right) {
  if (typeof left === 'number' && typeof right === 'number') {
    return left === right ? 0 : (left < right ? -1 : 1);
  }
  return String(left).localeCompare(String(right));
}

function selectedAdminUserFilters(category) {
  return new Set(Array.from(document.querySelectorAll('[data-admin-user-filter="' + category + '"]:checked'))
    .map(function(control) { return control.value; }));
}

function adminUserFilterEnabled(category) {
  const toggle = document.querySelector('[data-admin-user-filter-toggle="' + category + '"]');
  return Boolean(toggle && toggle.checked);
}

function syncAdminUserFilterControls() {
  document.querySelectorAll('[data-admin-user-filter-toggle]').forEach(function(toggle) {
    const category = toggle.dataset.adminUserFilterToggle;
    document.querySelectorAll('[data-admin-user-filter="' + category + '"]').forEach(function(option) {
      option.disabled = !toggle.checked;
    });
  });
}

function adminUserList() {
  const users = adminUserData && Array.isArray(adminUserData.users)
    ? adminUserData.users : [];
  const search = String(value('userAdminSearch') || '').toLowerCase();
  const statusFilters = selectedAdminUserFilters('status');
  const typeFilters = selectedAdminUserFilters('type');
  const identityFilters = selectedAdminUserFilters('identity');
  const membershipFilters = selectedAdminUserFilters('membership');
  const filterStatus = adminUserFilterEnabled('status');
  const filterType = adminUserFilterEnabled('type');
  const filterIdentity = adminUserFilterEnabled('identity');
  const filterMembership = adminUserFilterEnabled('membership');
  const filtered = users.filter(function(user) {
    const identityType = user.identityType === 'bot' ? 'bot' : 'human';
    const deviceSearch = adminUserDevices(user).map(function(device) {
      return [device.deviceId, device.brand, device.model, device.androidVersion, device.appVersion].join(' ');
    }).join(' ');
    const searchable = [user.uid, user.nickname, user.email, user.brand, user.model,
      user.androidVersion, user.appVersion, user.accountType, identityType, deviceSearch,
      Array.isArray(user.associatedIps) ? user.associatedIps.join(' ') : ''].join(' ').toLowerCase();
    const todayTotal = Number(user.today && user.today.totalMb || 0);
    const monthTotal = Number(user.month && user.month.totalMb || 0);
    const total = Number(user.totalMb || 0);
    const accountLevel = String(user.email || '').trim() ? 'formal' : 'guest';
    const accountStatus = user.accountType === 'banned' ? 'banned' : 'active';
    const membership = user.vipActive === true ? 'member' : 'non_member';
    if (search && searchable.indexOf(search) < 0) return false;
    if (filterStatus && !statusFilters.has(accountStatus)) return false;
    if (filterType && !typeFilters.has(accountLevel)) return false;
    if (filterIdentity && !identityFilters.has(identityType)) return false;
    if (filterMembership && !membershipFilters.has(membership)) return false;
    if (adminUserZeroFilters.today && todayTotal === 0) return false;
    if (adminUserZeroFilters.month && monthTotal === 0) return false;
    if (adminUserZeroFilters.total && total === 0) return false;
    return true;
  });
  const sort = ADMIN_USER_SORTS[value('userAdminSort')] || ADMIN_USER_SORTS.created_desc;
  return filtered.map(function(user, index) {
    return {user: user, index: index};
  }).sort(function(left, right) {
    const result = compareAdminUserSortValues(
      adminUserSortValue(left.user, sort.valueKey),
      adminUserSortValue(right.user, sort.valueKey),
    );
    return (result * sort.direction) || left.index - right.index;
  }).map(function(item) {
    return item.user;
  });
}

function adminUserDraftValue(user, draft, field) {
  return draft && Object.prototype.hasOwnProperty.call(draft, field)
    ? draft[field] : (user[field] || '');
}

function adminUserDeviceCell(user) {
  const security = adminUserSecurity(user);
  const devices = adminUserDevices(user);
  const deviceCount = Number(security.historicalDeviceCount) || devices.length;
  const bannedCount = Number(security.bannedDeviceCount) || 0;
  if (devices.length === 0) {
    return '<span class="user-admin-device-empty">暂无历史设备关系</span>';
  }
  const detailRows = devices.map(function(device) {
    const name = [device.brand, device.model].filter(Boolean).join(' ') || '未登记设备';
    const relation = device.current ? '当前设备' : '历史设备';
    const blocked = device.blocked ? ' · ' + (device.blockedByAccount ? '账户封禁' : '其他来源封禁') : '';
    return '<div class="user-admin-device-detail"><strong>' + esc(name) + '</strong> · ' + esc(relation + blocked) +
      '<br><span>' + esc([device.deviceId, device.androidVersion, device.appVersion].filter(Boolean).join(' / ') || '暂无设备版本信息') + '</span></div>';
  }).join('');
  return '<details><summary>' + deviceCount + ' 台设备' + (bannedCount ? ' · ' + bannedCount + ' 台封禁' : '') + '</summary>' + detailRows + '</details>';
}

function adminUserAssociatedIpCell(user) {
  const ips = Array.isArray(user.associatedIps)
    ? user.associatedIps.filter(function(ip) { return typeof ip === 'string' && ip; }) : [];
  if (ips.length === 0) return '<span class="user-admin-ip-empty">暂无</span>';
  if (ips.length === 1) return '<code class="user-admin-ip-single">' + esc(ips[0]) + '</code>';
  return '<details class="user-admin-ip-details"><summary><code>' + esc(ips[0]) +
    '</code><span> +' + (ips.length - 1) + '</span></summary><div class="user-admin-ip-list">' +
    ips.map(function(ip) { return '<code>' + esc(ip) + '</code>'; }).join('') + '</div></details>';
}

function updateAdminUserSelectionControls(filtered) {
  const allUsers = adminUserData && Array.isArray(adminUserData.users) ? adminUserData.users : [];
  const knownUids = new Set(allUsers.map(function(user) { return String(user.uid || ''); }));
  adminUserSelection.forEach(function(uid) {
    if (!knownUids.has(uid)) adminUserSelection.delete(uid);
  });
  const selectedVisible = filtered.filter(function(user) {
    return adminUserSelection.has(String(user.uid || ''));
  }).length;
  const selectAll = el('userAdminSelectAll');
  const count = el('userAdminSelectionCount');
  const selectedTotal = adminUserSelection.size;
  const hiddenSelected = selectedTotal - selectedVisible;
  if (count) count.textContent = '已选择 ' + selectedTotal + ' 个用户' +
    (hiddenSelected ? '，其中 ' + hiddenSelected + ' 个不在当前结果中' : '');
  const clearButton = el('userAdminClearSelection');
  if (clearButton) clearButton.disabled = selectedTotal === 0 || adminUserBatchBusy;
  if (selectAll) {
    selectAll.disabled = filtered.length === 0 || adminUserBatchBusy;
    selectAll.checked = filtered.length > 0 && selectedVisible === filtered.length;
    selectAll.indeterminate = selectedVisible > 0 && selectedVisible < filtered.length;
  }
  const banButton = el('userAdminBatchBan');
  const unbanButton = el('userAdminBatchUnban');
  const deleteButton = el('userAdminBatchDelete');
  const saveButton = el('userAdminSaveSelected');
  const dirtySelected = Array.from(adminUserSelection).some(function(uid) {
    return adminUserDirty.has(uid);
  });
  if (saveButton) {
    saveButton.disabled = !dirtySelected || adminUserBatchBusy || adminUserSaving.size > 0;
    saveButton.textContent = adminUserBatchBusy ? '正在处理…' : '保存选中修改';
  }
  if (banButton) banButton.disabled = selectedTotal === 0 || adminUserBatchBusy || adminUserSaving.size > 0;
  if (unbanButton) unbanButton.disabled = selectedTotal === 0 || adminUserBatchBusy || adminUserSaving.size > 0;
  if (deleteButton) deleteButton.disabled = selectedTotal === 0 || dirtySelected || adminUserBatchBusy || adminUserSaving.size > 0;
  const dirtySummary = el('userAdminDraftCount');
  if (dirtySummary) dirtySummary.textContent = adminUserDirty.size ? adminUserDirty.size + ' 个用户有未保存修改' : '所有修改已保存';
  document.querySelectorAll('[data-admin-user-select]').forEach(function(input) { input.disabled = adminUserBatchBusy; });
  updateAdminUserDetailControls();
  document.querySelectorAll('[data-admin-zero-filter]').forEach(function(button) {
    const active = adminUserZeroFilters[button.dataset.adminZeroFilter] === true;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', active ? 'true' : 'false');
  });
}

function renderAdminUsers() {
  const host = el('userAdminTable');
  const summary = el('userAdminSummary');
  if (!host || !summary) return;
  syncAdminUserFilterControls();
  const uidToggle = el('userAdminUidToggle');
  if (uidToggle) {
    uidToggle.textContent = adminUserUidHidden ? '显示 UID' : '隐藏 UID';
    uidToggle.setAttribute('aria-pressed', adminUserUidHidden ? 'true' : 'false');
  }
  const users = adminUserData && Array.isArray(adminUserData.users)
    ? adminUserData.users : [];
  const filtered = adminUserList();
  updateAdminUserSelectionControls(filtered);
  const sort = ADMIN_USER_SORTS[value('userAdminSort')] || ADMIN_USER_SORTS.created_desc;
  summary.textContent = '共 ' + users.length + ' 个用户 · 当前显示 ' + filtered.length + ' 个 · 已选择 ' + adminUserSelection.size + ' 个 · ' + sort.label;
  if (filtered.length === 0) {
    host.innerHTML = '<div class="user-table-empty">没有匹配的用户</div>';
    return;
  }
  const rows = filtered.map(function(user) {
    const uid = String(user.uid || '');
    const banned = user.accountType === 'banned';
    const today = user.today || {};
    const month = user.month || {};
    const draft = adminUserDrafts.get(uid);
    const security = adminUserSecurity(user);
    const deviceCount = Number(security.historicalDeviceCount) || adminUserDevices(user).length;
    const bannedDeviceCount = Number(security.bannedDeviceCount) || 0;
    const nickname = adminUserDraftValue(user, draft, 'nickname') || '未命名用户';
    const accountLevel = String(user.email || '').trim() ? '正式用户' : '游客';
    const identityType = user.identityType === 'bot' ? 'bot' : 'human';
    const membershipStatus = user.vipActive === true ? '会员' : '非会员';
    const vipExpiresAt = String(user.vipExpiresAt || '')
      .replace('T', ' ')
      .replace(/(?:Z|[+-]\d{2}:\d{2})$/, '')
      .slice(0, 16);
    return '<tr class="' + (banned ? 'banned ' : '') + (adminUserDirty.has(uid) ? 'dirty' : '') + '" data-admin-user-row="' + esc(uid) + '">' +
      '<td><input class="user-admin-checkbox" type="checkbox" data-admin-user-select="' + esc(uid) + '"' + (adminUserSelection.has(uid) ? ' checked' : '') + ' aria-label="选择用户 ' + esc(nickname) + '"></td>' +
      '<td class="user-admin-identity"><button class="user-detail-link" type="button" data-admin-user-detail="' + esc(uid) + '">' + esc(nickname) + '</button></td>' +
      '<td><span class="user-admin-cell-ellipsis">' + esc(user.email || '—') + '</span></td>' +
      '<td><span class="user-admin-cell-ellipsis user-admin-uid-value">' + (adminUserUidHidden ? '••••••••' : esc(uid)) + '</span></td>' +
      '<td>' + (banned ? '封禁' : '正常') + '</td>' +
      '<td>' + accountLevel + '</td>' +
      '<td>' + esc(adminUserIdentityLabel(identityType)) + '</td>' +
      '<td>' + esc(membershipStatus) + '</td>' +
      '<td>' + esc(vipExpiresAt || '—') + '</td>' +
      '<td>' + esc(user.brand || '—') + '</td>' +
      '<td>' + esc(user.model || '—') + '</td>' +
      '<td>' + esc(user.androidVersion || '暂无') + '</td>' +
      '<td>' + esc(user.appVersion || '暂无') + '</td>' +
      '<td>' + (deviceCount || 0) + '</td>' +
      '<td>' + bannedDeviceCount + '</td>' +
      '<td>' + formatMegabytes(today.totalMb) + '</td>' +
      '<td>' + formatMegabytes(month.totalMb) + '</td>' +
      '<td>' + formatMegabytes(user.totalMb) + '</td>' +
      '<td>' + esc(user.lastReportAt || '暂无') + '</td>' +
      '<td class="user-admin-ip-cell">' + adminUserAssociatedIpCell(user) + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table user-admin-table' + (adminUserUidHidden ? ' user-admin-hide-uid' : '') + '" aria-label="用户总表">' +
    '<thead><tr><th>选择</th><th>昵称</th><th>邮箱</th><th>UID</th><th>封禁状态</th><th>注册状态</th><th>风控状态</th><th>会员状态</th><th>VIP 到期时间</th><th>品牌</th><th>型号</th><th>Android 版本</th><th>App 版本</th><th>设备数</th><th>受限设备数</th><th>今日流量</th><th>本月流量</th><th>累计流量</th><th>最后上报</th><th>关联 IP</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-admin-user-select]').forEach(function(input) {
    input.addEventListener('change', function() {
      const uid = input.dataset.adminUserSelect;
      if (input.checked) adminUserSelection.add(uid);
      else adminUserSelection.delete(uid);
      updateAdminUserSelectionControls(adminUserList());
    });
  });
  host.querySelectorAll('[data-admin-user-detail]').forEach(function(button) {
    button.addEventListener('click', function() { openAdminUserDetail(button.dataset.adminUserDetail); });
  });
}

function clearAdminUserSelection() {
  if (adminUserBatchBusy) return;
  adminUserSelection.clear();
  renderAdminUsers();
}

function updateAdminUserDetailControls() {
  const form = el('adminUserDetailForm');
  if (!form) return;
  const saving = adminUserSaving.has(adminUserDetailUid);
  form.querySelectorAll('[data-admin-user-field]').forEach(function(control) { control.disabled = saving || adminUserBatchBusy; });
  el('adminUserDetailSave').disabled = saving || !adminUserDirty.has(adminUserDetailUid) || adminUserBatchBusy;
  el('adminUserDetailSave').textContent = saving ? '保存中…' : '保存用户修改';
  const error = adminUserErrors.get(adminUserDetailUid);
  const status = el('adminUserDetailStatus');
  status.textContent = saving ? '正在保存…' : error || (adminUserDirty.has(adminUserDetailUid) ? '有未保存修改，关闭详情会保留草稿' : '资料已保存');
  status.classList.toggle('error', Boolean(error));
}

function openAdminUserDetail(uid) {
  const user = adminUserData && adminUserData.users.find(function(item) { return String(item.uid) === uid; });
  if (!user) return;
  adminUserDetailUid = uid;
  const draft = adminUserDrafts.get(uid);
  const form = el('adminUserDetailForm');
  el('adminUserDetailTitle').textContent = user.nickname || '用户详情';
  el('adminUserDetailIdentity').textContent = (user.email || '游客账户') + ' · UID ' + adminUserDisplayUid(uid);
  form.querySelectorAll('[data-admin-user-field]').forEach(function(input) {
    const value = adminUserDraftValue(user, draft, input.dataset.adminUserField);
    input.value = input.dataset.adminUserField === 'identityType'
      ? (value === 'bot' ? 'bot' : 'human')
      : value;
  });
  el('adminUserDetailUsage').textContent = '今日 ' + formatMegabytes(user.today && user.today.totalMb) +
    ' · 本月 ' + formatMegabytes(user.month && user.month.totalMb) + ' · 累计 ' + formatMegabytes(user.totalMb);
  el('adminUserDetailDevices').innerHTML = adminUserDeviceCell(user);
  updateAdminUserDetailControls();
  const dialog = el('adminUserDetailDialog');
  if (!dialog.open) dialog.showModal();
}

const adminUserDetailForm = el('adminUserDetailForm');
if (adminUserDetailForm) {
  function updateAdminUserDetailDraft(event) {
    if (!event.target.matches('[data-admin-user-field]') || !adminUserDetailUid) return;
    const draft = {};
    adminUserDetailForm.querySelectorAll('[data-admin-user-field]').forEach(function(input) {
      draft[input.dataset.adminUserField] = input.value.trim();
    });
    adminUserDrafts.set(adminUserDetailUid, draft);
    adminUserErrors.delete(adminUserDetailUid);
    adminUserDirty.add(adminUserDetailUid);
    adminUserSelection.add(adminUserDetailUid);
    updateAdminUserDetailControls();
    renderAdminUsers();
  }
  adminUserDetailForm.addEventListener('input', function(event) {
    if (event.target.matches('select[data-admin-user-field]')) return;
    updateAdminUserDetailDraft(event);
  });
  adminUserDetailForm.addEventListener('change', function(event) {
    if (event.target.matches('select[data-admin-user-field]')) updateAdminUserDetailDraft(event);
  });
  adminUserDetailForm.addEventListener('submit', async function(event) {
    event.preventDefault();
    await saveAdminUser(adminUserDetailUid);
    updateAdminUserDetailControls();
  });
}

function toggleAllAdminUsers(checked) {
  if (adminUserBatchBusy) return;
  adminUserList().forEach(function(user) {
    const uid = String(user.uid || '');
    if (checked) adminUserSelection.add(uid);
    else adminUserSelection.delete(uid);
  });
  renderAdminUsers();
}

function toggleAdminUserZeroFilter(key) {
  if (!Object.prototype.hasOwnProperty.call(adminUserZeroFilters, key)) return;
  adminUserZeroFilters[key] = !adminUserZeroFilters[key];
  renderAdminUsers();
}

async function loadAdminUsers(force) {
  if (adminUserLoading) return false;
  if (adminUserData && !force) {
    renderAdminUsers();
    return true;
  }
  const host = el('userAdminTable');
  adminUserLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取用户、安全和设备数据…</div>';
  try {
    const responses = await Promise.all([
      fetch(ADMIN_API_BASE + '/users', {headers: headers()}),
      fetch(ADMIN_API_BASE + '/security/accounts', {headers: headers()}),
    ]);
    const userData = parseResponseJson(await responses[0].text());
    const securityData = parseResponseJson(await responses[1].text());
    if (!responses[0].ok) {
      throw new Error(adminErrorMessage(userData.error, '用户数据读取失败（HTTP ' + responses[0].status + '）'));
    }
    if (!responses[1].ok) {
      throw new Error(adminErrorMessage(securityData.error, '安全数据读取失败（HTTP ' + responses[1].status + '）'));
    }
    adminUserData = Object.assign({}, userData, {securityAccounts: securityData.accounts || []});
    renderAdminUsers();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">用户总表读取失败</div>';
    show((error && error.message) || '用户总表读取失败，请检查网络', true);
    return false;
  } finally {
    adminUserLoading = false;
  }
}

async function saveAdminUser(uid, silent, refresh) {
  const draft = adminUserDrafts.get(uid);
  if (!draft || adminUserSaving.has(uid)) return false;
  const payload = Object.assign({}, draft);
  if (!payload.nickname) {
    adminUserErrors.set(uid, '昵称不能为空');
    updateAdminUserDetailControls();
    show('昵称不能为空', true);
    return false;
  }
  adminUserSaving.add(uid);
  adminUserErrors.delete(uid);
  updateAdminUserDetailControls();
  if (refresh !== false) renderAdminUsers();
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(uid), {
      method: 'PATCH', headers: headers(), body: JSON.stringify(payload),
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) {
      const message = adminErrorMessage(data.error, '用户信息保存失败（HTTP ' + response.status + '）');
      adminUserErrors.set(uid, message);
      show(message, true);
      return false;
    }
    if (adminUserDrafts.get(uid) === draft) {
      adminUserDirty.delete(uid);
      adminUserDrafts.delete(uid);
    }
    const user = adminUserData && adminUserData.users.find(function(item) { return String(item.uid) === uid; });
    if (user) Object.assign(user, payload);
    if (!silent) show('用户信息已保存');
    if (refresh !== false) await loadAdminUsers(true);
    return true;
  } catch (_) {
    adminUserErrors.set(uid, '用户信息保存失败，请检查网络后重试');
    show('用户信息保存失败，请检查网络', true);
    return false;
  } finally {
    adminUserSaving.delete(uid);
    updateAdminUserDetailControls();
    if (adminUserData && refresh !== false) renderAdminUsers();
  }
}

async function saveSelectedAdminUsers() {
  if (adminUserBatchBusy || adminUserSaving.size > 0) return false;
  const uids = Array.from(adminUserSelection).filter(function(uid) {
    return adminUserDirty.has(uid);
  });
  if (uids.length === 0) {
    show('当前选择中没有未保存的修改', true);
    return false;
  }
  adminUserBatchBusy = true;
  updateAdminUserSelectionControls(adminUserList());
  let saved = 0;
  try {
    for (const uid of uids) {
      if (await saveAdminUser(uid, true, false)) saved += 1;
    }
    const refreshed = await loadAdminUsers(true);
    show('已保存 ' + saved + ' 个用户' + (saved < uids.length ? '，' + (uids.length - saved) + ' 个失败，草稿已保留' : '') +
      (!refreshed ? '；列表刷新失败，可稍后重试' : ''), saved !== uids.length || !refreshed);
    return saved === uids.length;
  } finally {
    adminUserBatchBusy = false;
    updateAdminUserSelectionControls(adminUserList());
    updateAdminUserDetailControls();
  }
}

async function setAdminUserBan(uid, banned) {
  if (adminUserSaving.has(uid)) return false;
  const action = banned ? '封禁' : '解封';
  if (!confirm('确定要' + action + '用户 ' + uid + ' 吗？')) return false;
  adminUserSaving.add(uid);
  renderAdminUsers();
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(uid) + '/ban', {
      method: banned ? 'POST' : 'DELETE', headers: headers(),
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) {
      show(adminErrorMessage(data.error, action + '失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    show('用户已' + action);
    await loadAdminUsers(true);
    return true;
  } catch (_) {
    show(action + '失败，请检查网络', true);
    return false;
  } finally {
    adminUserSaving.delete(uid);
    if (adminUserData) renderAdminUsers();
  }
}

async function batchSetAdminUserBan(banned) {
  if (adminUserBatchBusy || adminUserSaving.size > 0) return false;
  const uids = Array.from(adminUserSelection);
  if (uids.length === 0) {
    show('请先选择用户', true);
    return false;
  }
  if (uids.length > 100) {
    show('单次最多选择 100 个用户', true);
    return false;
  }
  const action = banned ? '封禁' : '解封';
  if (!confirm('确定要' + action + '选中的 ' + uids.length + ' 个用户吗？选择范围包括当前筛选隐藏的用户。')) return false;
  adminUserBatchBusy = true;
  updateAdminUserSelectionControls(adminUserList());
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/batch', {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify({uids: uids, accountType: banned ? 'banned' : 'active'}),
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) {
      show(adminErrorMessage(data.error, action + '失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    adminUserSelection.clear();
    show('已' + action + ' ' + (Number(data.changed) || uids.length) + ' 个用户');
    await loadAdminUsers(true);
    return true;
  } catch (_) {
    show(action + '失败，请检查网络', true);
    return false;
  } finally {
    adminUserBatchBusy = false;
    updateAdminUserSelectionControls(adminUserList());
  }
}

async function batchDeleteAdminUsers() {
  if (adminUserBatchBusy || adminUserSaving.size > 0) return false;
  const uids = Array.from(adminUserSelection);
  if (uids.length === 0) {
    show('请先选择用户', true);
    return false;
  }
  if (uids.length > 100) {
    show('单次最多选择 100 个用户', true);
    return false;
  }
  const dirtySelected = uids.some(function(uid) {
    return adminUserDirty.has(uid);
  });
  if (dirtySelected) {
    show('请先保存未保存的用户修改，再删除用户', true);
    return false;
  }
  if (!confirm('确定永久删除选中的 ' + uids.length + ' 个用户及其全部流量统计吗？包括当前筛选隐藏的已选用户，此操作不可恢复。')) {
    return false;
  }
  adminUserBatchBusy = true;
  updateAdminUserSelectionControls(adminUserList());
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/batch', {
      method: 'DELETE',
      headers: headers(),
      body: JSON.stringify({uids: uids}),
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) {
      show(adminErrorMessage(data.error, '删除用户失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    uids.forEach(function(uid) {
      adminUserSelection.delete(uid);
      adminUserDirty.delete(uid);
      adminUserDrafts.delete(uid);
      adminUserErrors.delete(uid);
    });
    if (uids.includes(adminUserDetailUid)) el('adminUserDetailDialog').close();
    const refreshed = await loadAdminUsers(true);
    show(refreshed
      ? '已删除 ' + (Number(data.changed) || 0) + ' 个用户及其流量统计'
      : '用户删除成功，但用户列表刷新失败', !refreshed);
    return true;
  } catch (_) {
    show('删除用户失败，请检查网络', true);
    return false;
  } finally {
    adminUserBatchBusy = false;
    updateAdminUserSelectionControls(adminUserList());
  }
}

function csvAdminCell(value) {
  return '"' + String(value == null ? '' : value).split('"').join('""') + '"';
}

function exportAdminUsersCsv() {
  const users = adminUserList();
  if (users.length === 0) {
    show('当前没有可导出的用户', true);
    return;
  }
  const lines = [['UID', '昵称', '邮箱', '封禁状态', '注册状态', '风控状态', '历史设备数', '封禁设备数', '品牌', '型号', 'Android', 'App版本', '今日流量MB', '本月流量MB', '累计流量MB', '最后上报', '关联IP']];
  users.forEach(function(user) {
    const security = adminUserSecurity(user);
    lines.push([user.uid, user.nickname, user.email, user.accountType === 'banned' ? '封禁' : '正常',
      String(user.email || '').trim() ? '正式用户' : '游客', adminUserIdentityLabel(user.identityType),
      security.historicalDeviceCount, security.bannedDeviceCount,
      user.brand, user.model, user.androidVersion, user.appVersion,
      user.today && user.today.totalMb, user.month && user.month.totalMb, user.totalMb, user.lastReportAt,
      Array.isArray(user.associatedIps) ? user.associatedIps.join('；') : '']);
  });
  const blob = new Blob(['\ufeff' + lines.map(function(line) { return line.map(csvAdminCell).join(','); }).join('\n')], {type: 'text/csv;charset=utf-8'});
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'users-' + new Date().toISOString().slice(0, 10) + '.csv';
  link.click();
  URL.revokeObjectURL(url);
  show('已导出 ' + users.length + ' 个用户');
}
