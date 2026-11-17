let adminUserData = null;
let adminUserLoading = false;
let adminUserUidHidden = true;
let adminUserBatchBusy = false;
let adminUserSecuritySource = null;
let adminUserSecurityIndex = new Map();
const adminUserSelection = new Set();
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
  adminUserSecuritySource = null;
  adminUserSecurityIndex.clear();
  adminUserSelection.clear();
  adminUserZeroFilters.today = false;
  adminUserZeroFilters.month = false;
  adminUserZeroFilters.total = false;
}

function toggleAdminUserUidVisibility() {
  adminUserUidHidden = !adminUserUidHidden;
  renderAdminUsers();
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
      return [device.deviceId, device.brand, device.model, device.androidVersion, device.androidSdk, device.appVersion].join(' ');
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

function adminUserDeviceCell(user) {
  const security = adminUserSecurity(user);
  const devices = adminUserDevices(user);
  const deviceCount = Number(security.historicalDeviceCount) || devices.length;
  const bannedCount = Number(security.bannedDeviceCount) || 0;
  if (devices.length === 0) {
    const latestName = [user.brand, user.model].filter(Boolean).join(' ');
    const latestSoftware = [
      user.androidVersion ? 'Android ' + user.androidVersion : '',
      Number(user.androidSdk) > 0 ? 'SDK ' + user.androidSdk : '',
      user.appVersion ? 'App ' + user.appVersion : '',
    ].filter(Boolean).join(' · ');
    const latestInfo = [latestName, latestSoftware].filter(Boolean).join(' · ');
    return latestInfo
      ? '<span class="user-admin-device-empty">' + esc(latestInfo) + ' · 暂无设备历史</span>'
      : '<span class="user-admin-device-empty">暂无历史设备关系</span>';
  }
  const displayDevices = devices.slice().sort(function(left, right) {
    return Number(Boolean(right.current)) - Number(Boolean(left.current));
  });
  const detailRows = displayDevices.map(function(device) {
    const name = [device.brand, device.model].filter(Boolean).join(' ') || '未登记设备';
    const relation = device.current ? '当前设备' : '历史设备';
    const blocked = device.blocked ? ' · ' + (device.blockedByAccount ? '账户封禁' : '其他来源封禁') : '';
    const software = [
      device.androidVersion ? 'Android ' + device.androidVersion : '',
      Number(device.androidSdk) > 0 ? 'SDK ' + device.androidSdk : '',
      device.appVersion ? 'App ' + device.appVersion : '',
    ].filter(Boolean).join(' · ') || '暂无软件版本信息';
    return '<div class="user-admin-device-detail"><strong>' + esc(name) + '</strong> · ' + esc(relation + blocked) +
      '<br><span>设备 ID：' + esc(device.deviceId || '暂无') + ' · ' + esc(software) + '</span></div>';
  }).join('');
  const summaryDevice = displayDevices.find(function(device) { return device.current; }) || displayDevices[0];
  const summaryName = [summaryDevice.brand, summaryDevice.model].filter(Boolean).join(' ') || '未登记设备';
  const summarySoftware = [
    summaryDevice.androidVersion ? 'Android ' + summaryDevice.androidVersion : '',
    Number(summaryDevice.androidSdk) > 0 ? 'SDK ' + summaryDevice.androidSdk : '',
    summaryDevice.appVersion ? 'App ' + summaryDevice.appVersion : '',
  ].filter(Boolean).join(' · ');
  const summaryDeviceInfo = ' · ' + (summaryDevice.current ? '当前：' : '最近：') + esc(summaryName) +
    (summarySoftware ? ' · ' + esc(summarySoftware) : '');
  return '<details><summary>' + deviceCount + ' 台设备' + summaryDeviceInfo +
    (bannedCount ? ' · ' + bannedCount + ' 台受限' : '') + '</summary>' + detailRows + '</details>';
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
  if (banButton) banButton.disabled = selectedTotal === 0 || adminUserBatchBusy;
  if (unbanButton) unbanButton.disabled = selectedTotal === 0 || adminUserBatchBusy;
  if (deleteButton) deleteButton.disabled = selectedTotal === 0 || adminUserBatchBusy;
  document.querySelectorAll('[data-admin-user-select]').forEach(function(input) { input.disabled = adminUserBatchBusy; });
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
    const bannedDeviceCount = Number(adminUserSecurity(user).bannedDeviceCount) || 0;
    const nickname = user.nickname || '未命名用户';
    const accountLevel = String(user.email || '').trim() ? '正式用户' : '游客';
    const identityType = user.identityType === 'bot' ? 'bot' : 'human';
    const membershipStatus = user.vipActive === true ? '会员' : '非会员';
    const vipExpiresAt = String(user.vipExpiresAt || '')
      .replace('T', ' ')
      .replace(/(?:Z|[+-]\d{2}:\d{2})$/, '')
      .slice(0, 16);
    return '<tr class="' + (banned ? 'banned' : '') + '" data-admin-user-row="' + esc(uid) + '">' +
      '<td><input class="user-admin-checkbox" type="checkbox" data-admin-user-select="' + esc(uid) + '"' + (adminUserSelection.has(uid) ? ' checked' : '') + ' aria-label="选择用户 ' + esc(nickname) + '"></td>' +
      '<td class="user-admin-nickname">' + esc(nickname) + '</td>' +
      '<td><span class="user-admin-cell-ellipsis">' + esc(user.email || '—') + '</span></td>' +
      '<td><span class="user-admin-cell-ellipsis user-admin-uid-value">' + (adminUserUidHidden ? '••••••••' : esc(uid)) + '</span></td>' +
      '<td>' + (banned ? '封禁' : '正常') + '</td>' +
      '<td>' + accountLevel + '</td>' +
      '<td>' + esc(adminUserIdentityLabel(identityType)) + '</td>' +
      '<td>' + esc(membershipStatus) + '</td>' +
      '<td>' + esc(vipExpiresAt || '—') + '</td>' +
      '<td class="user-admin-device-cell">' + adminUserDeviceCell(user) + '</td>' +
      '<td>' + bannedDeviceCount + '</td>' +
      '<td>' + formatMegabytes(today.totalMb) + '</td>' +
      '<td>' + formatMegabytes(month.totalMb) + '</td>' +
      '<td>' + formatMegabytes(user.totalMb) + '</td>' +
      '<td>' + esc(user.lastReportAt || '暂无') + '</td>' +
      '<td class="user-admin-ip-cell">' + adminUserAssociatedIpCell(user) + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table user-admin-table' + (adminUserUidHidden ? ' user-admin-hide-uid' : '') + '" aria-label="用户总表">' +
    '<thead><tr><th>选择</th><th>昵称</th><th>邮箱</th><th>UID</th><th>封禁状态</th><th>注册状态</th><th>风控状态</th><th>会员状态</th><th>VIP 到期时间</th><th>设备信息</th><th>受限设备数</th><th>今日流量</th><th>本月流量</th><th>累计流量</th><th>最后上报</th><th>关联 IP</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-admin-user-select]').forEach(function(input) {
    input.addEventListener('change', function() {
      const uid = input.dataset.adminUserSelect;
      if (input.checked) adminUserSelection.add(uid);
      else adminUserSelection.delete(uid);
      updateAdminUserSelectionControls(adminUserList());
    });
  });
}

function clearAdminUserSelection() {
  if (adminUserBatchBusy) return;
  adminUserSelection.clear();
  renderAdminUsers();
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

async function batchSetAdminUserBan(banned) {
  if (adminUserBatchBusy) return false;
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
  if (adminUserBatchBusy) return false;
  const uids = Array.from(adminUserSelection);
  if (uids.length === 0) {
    show('请先选择用户', true);
    return false;
  }
  if (uids.length > 100) {
    show('单次最多选择 100 个用户', true);
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
    });
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
