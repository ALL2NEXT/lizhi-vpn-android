let adminUserData = null;
let adminUserLoading = false;
let adminUserUidHidden = true;
const adminUserSaving = new Set();
const adminUserSelection = new Set();
const adminUserDirty = new Set();
const adminUserDrafts = new Map();
const adminUserZeroFilters = {today: false, month: false, total: false};

function resetAdminUsers() {
  adminUserData = null;
  adminUserLoading = false;
  adminUserUidHidden = true;
  adminUserSaving.clear();
  adminUserSelection.clear();
  adminUserDirty.clear();
  adminUserDrafts.clear();
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

function adminUserLevel(accountType, user) {
  const level = user && String(user.email || '').trim() ? '正式用户' : '游客';
  if (accountType === 'banned') return level;
  if (accountType === 'formal' || accountType === 'guest') return level;
  return accountType || '未知';
}

function adminUserSecurity(user) {
  const accounts = adminUserData && Array.isArray(adminUserData.securityAccounts)
    ? adminUserData.securityAccounts : [];
  return accounts.find(function(account) {
    return String(account.uid || '') === String(user.uid || '');
  }) || {devices: [], historicalDeviceCount: 0, bannedDeviceCount: 0};
}

function adminUserDevices(user) {
  const security = adminUserSecurity(user);
  return Array.isArray(security.devices) ? security.devices : [];
}

function adminUserList() {
  const users = adminUserData && Array.isArray(adminUserData.users)
    ? adminUserData.users : [];
  const search = String(value('userAdminSearch') || '').toLowerCase();
  const filter = value('userAdminStatusFilter');
  return users.filter(function(user) {
    const security = adminUserSecurity(user);
    const deviceSearch = adminUserDevices(user).map(function(device) {
      return [device.deviceId, device.brand, device.model, device.androidVersion, device.appVersion].join(' ');
    }).join(' ');
    const searchable = [user.uid, user.nickname, user.email, user.brand, user.model,
      user.androidVersion, user.appVersion, user.accountType, deviceSearch].join(' ').toLowerCase();
    const todayTotal = Number(user.today && user.today.totalMb || 0);
    const monthTotal = Number(user.month && user.month.totalMb || 0);
    const total = Number(user.totalMb || 0);
    const accountLevel = String(user.email || '').trim() ? 'formal' : 'guest';
    if (search && searchable.indexOf(search) < 0) return false;
    if (filter === 'active' && user.accountType === 'banned') return false;
    if (filter && filter !== 'active' && accountLevel !== filter) return false;
    if (adminUserZeroFilters.today && todayTotal === 0) return false;
    if (adminUserZeroFilters.month && monthTotal === 0) return false;
    if (adminUserZeroFilters.total && total === 0) return false;
    return true;
  });
}

function adminUserField(row, field) {
  const control = row && row.querySelector('[data-admin-user-field="' + field + '"]');
  return control ? control.value.trim() : '';
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
  if (count) count.textContent = '已选择 ' + selectedTotal + ' 个用户';
  if (selectAll) {
    selectAll.disabled = filtered.length === 0;
    selectAll.checked = filtered.length > 0 && selectedVisible === filtered.length;
    selectAll.indeterminate = selectedVisible > 0 && selectedVisible < filtered.length;
  }
  const banButton = el('userAdminBatchBan');
  const unbanButton = el('userAdminBatchUnban');
  const saveButton = el('userAdminSaveSelected');
  const dirtySelected = Array.from(adminUserSelection).some(function(uid) {
    return adminUserDirty.has(uid);
  });
  if (saveButton) saveButton.disabled = !dirtySelected;
  if (banButton) banButton.disabled = selectedTotal === 0;
  if (unbanButton) unbanButton.disabled = selectedTotal === 0;
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
  const uidToggle = el('userAdminUidToggle');
  if (uidToggle) {
    uidToggle.textContent = adminUserUidHidden ? '显示 UID' : '隐藏 UID';
    uidToggle.setAttribute('aria-pressed', adminUserUidHidden ? 'true' : 'false');
  }
  const users = adminUserData && Array.isArray(adminUserData.users)
    ? adminUserData.users : [];
  const filtered = adminUserList();
  updateAdminUserSelectionControls(filtered);
  summary.textContent = '共 ' + users.length + ' 个用户 · 当前显示 ' + filtered.length + ' 个 · 已选择 ' + adminUserSelection.size + ' 个';
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
    return '<tr class="' + (banned ? 'banned' : '') + '" data-admin-user-row="' + esc(uid) + '">' +
      '<td><input class="user-admin-checkbox" type="checkbox" data-admin-user-select="' + esc(uid) + '"' + (adminUserSelection.has(uid) ? ' checked' : '') + ' aria-label="选择用户"></td>' +
      '<td><code>' + esc(adminUserDisplayUid(uid)) + '</code></td>' +
      '<td><input class="user-admin-nickname" data-admin-user-field="nickname" value="' + esc(adminUserDraftValue(user, draft, 'nickname')) + '" aria-label="' + esc(adminUserDisplayUid(uid) + ' 昵称') + '"></td>' +
      '<td>' + esc(user.email || '游客账户') + '</td>' +
      '<td>' + (banned ? '封禁' : '正常') + '</td>' +
      '<td>' + esc(adminUserLevel(user.accountType, user)) + '</td>' +
      '<td class="user-admin-device-cell">' + adminUserDeviceCell(user) + '</td>' +
      '<td><input data-admin-user-field="brand" value="' + esc(adminUserDraftValue(user, draft, 'brand')) + '" aria-label="品牌"></td>' +
      '<td><input data-admin-user-field="model" value="' + esc(adminUserDraftValue(user, draft, 'model')) + '" aria-label="型号"></td>' +
      '<td><input data-admin-user-field="androidVersion" value="' + esc(adminUserDraftValue(user, draft, 'androidVersion')) + '" aria-label="Android 版本"></td>' +
      '<td><input data-admin-user-field="appVersion" value="' + esc(adminUserDraftValue(user, draft, 'appVersion')) + '" aria-label="App 版本"></td>' +
      '<td>' + formatMegabytes(today.totalMb) + '</td>' +
      '<td>' + formatMegabytes(month.totalMb) + '</td>' +
      '<td>' + formatMegabytes(user.totalMb) + '</td>' +
      '<td>' + esc(user.lastReportAt || '暂无') + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table user-admin-table' + (adminUserUidHidden ? ' user-admin-hide-uid' : '') + '" aria-label="用户总表">' +
    '<thead><tr><th>选择</th><th>UID</th><th>昵称</th><th>邮箱</th><th>状态</th><th>账户类型</th><th>设备关系</th><th>品牌</th><th>型号</th><th>Android</th><th>App 版本</th><th>今日流量</th><th>本月流量</th><th>累计流量</th><th>最后上报</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-admin-user-select]').forEach(function(input) {
    input.addEventListener('change', function() {
      const uid = input.dataset.adminUserSelect;
      if (input.checked) adminUserSelection.add(uid);
      else adminUserSelection.delete(uid);
      renderAdminUsers();
    });
  });
  host.querySelectorAll('[data-admin-user-field]').forEach(function(input) {
    input.addEventListener('input', function() {
      const row = input.closest('[data-admin-user-row]');
      if (!row) return;
      const uid = row.dataset.adminUserRow;
      adminUserSelection.add(uid);
      adminUserDirty.add(uid);
      adminUserDrafts.set(uid, {
        nickname: adminUserField(row, 'nickname'),
        brand: adminUserField(row, 'brand'),
        model: adminUserField(row, 'model'),
        androidVersion: adminUserField(row, 'androidVersion'),
        appVersion: adminUserField(row, 'appVersion'),
      });
      const checkbox = row.querySelector('[data-admin-user-select]');
      if (checkbox) checkbox.checked = true;
      updateAdminUserSelectionControls(adminUserList());
    });
  });
}

function toggleAllAdminUsers(checked) {
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

function adminUserRow(uid) {
  return Array.from(document.querySelectorAll('[data-admin-user-row]')).find(function(row) {
    return row.dataset.adminUserRow === uid;
  });
}

async function saveAdminUser(uid, silent, refresh) {
  const row = adminUserRow(uid);
  if (!row || adminUserSaving.has(uid)) return false;
  const payload = adminUserDrafts.get(uid) || {
    nickname: adminUserField(row, 'nickname'),
    brand: adminUserField(row, 'brand'),
    model: adminUserField(row, 'model'),
    androidVersion: adminUserField(row, 'androidVersion'),
    appVersion: adminUserField(row, 'appVersion'),
  };
  if (!payload.nickname) {
    show('昵称不能为空', true);
    return false;
  }
  adminUserSaving.add(uid);
  if (refresh !== false) renderAdminUsers();
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/' + encodeURIComponent(uid), {
      method: 'PATCH', headers: headers(), body: JSON.stringify(payload),
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) {
      show(adminErrorMessage(data.error, '用户信息保存失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    adminUserDirty.delete(uid);
    adminUserDrafts.delete(uid);
    if (!silent) show('用户信息已保存');
    if (refresh !== false) await loadAdminUsers(true);
    return true;
  } catch (_) {
    show('用户信息保存失败，请检查网络', true);
    return false;
  } finally {
    adminUserSaving.delete(uid);
    if (adminUserData && refresh !== false) renderAdminUsers();
  }
}

async function saveSelectedAdminUsers() {
  const uids = Array.from(adminUserSelection).filter(function(uid) {
    return adminUserDirty.has(uid);
  });
  if (uids.length === 0) {
    show('当前选择中没有未保存的修改', true);
    return false;
  }
  let saved = 0;
  for (const uid of uids) {
    if (await saveAdminUser(uid, true, false)) saved += 1;
  }
  await loadAdminUsers(true);
  show('已保存 ' + saved + ' 个用户的修改', saved !== uids.length);
  return saved === uids.length;
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
  if (!confirm('确定要' + action + '选中的 ' + uids.length + ' 个用户吗？')) return false;
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
  const lines = [['UID', '昵称', '邮箱', '状态', '账户类型', '历史设备数', '封禁设备数', '品牌', '型号', 'Android', 'App版本', '今日流量MB', '本月流量MB', '累计流量MB', '最后上报']];
  users.forEach(function(user) {
    const security = adminUserSecurity(user);
    lines.push([user.uid, user.nickname, user.email, user.accountType === 'banned' ? '封禁' : '正常',
      adminUserLevel(user.accountType, user), security.historicalDeviceCount, security.bannedDeviceCount,
      user.brand, user.model, user.androidVersion, user.appVersion,
      user.today && user.today.totalMb, user.month && user.month.totalMb, user.totalMb, user.lastReportAt]);
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
