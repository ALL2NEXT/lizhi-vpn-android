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
  const batchBanUsersButton = el('batchBanUsersButton');
  const batchDeleteUsersButton = el('batchDeleteUsersButton');
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
    loadActivityChart(activityPeriod);
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
    '账户哈希', '昵称', '品牌', '型号', 'Android', 'App版本',
    '今日活跃', '本月活跃', '今日上传(MB)', '今日下载(MB)', '今日总量(MB)',
    '本月上传(MB)', '本月下载(MB)', '本月总量(MB)',
    '累计上传(MB)', '累计下载(MB)', '累计总量(MB)',
    '注册时间', '最后上报'
  ];
  const records = users.map(function(user) {
    return [
      user.uid,
      user.nickname,
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
  link.download = 'chimera-users-' + (currentUsersData.date || 'export') + '.csv';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(function() { URL.revokeObjectURL(downloadUrl); }, 1000);
  show('用户详细表格已导出，可直接使用 Excel 打开');
}
