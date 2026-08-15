let deviceRelationDetailData = [];

function securityFilteredAccounts() {
  const accounts = accountSecurityData && Array.isArray(accountSecurityData.accounts)
    ? accountSecurityData.accounts : [];
  const search = String(value('securitySearch') || '').toLowerCase();
  return accounts.filter(function(account) {
    if (!search) return true;
    return [account.uid, account.nickname, account.email, account.inviteCode, account.invitedByCode]
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
  const search = String(value('securitySearch') || '').toLowerCase();
  summary.textContent = '账户 ' + accounts.length + ' 个 · 已封禁 ' + bannedAccounts +
    ' 个' +
    (search ? ' · 当前匹配 ' + filtered.length + ' 个' : '');
  if (filtered.length === 0) {
    host.innerHTML = '<div class="security-device-empty">没有匹配的账户</div>';
    return;
  }
  const rows = filtered.map(function(account) {
    const banned = account.status === 'banned';
    const email = account.email || '游客账户';
    const uid = String(account.uid || '');
    return '<tr class="' + (banned ? 'banned' : '') + '">' +
      '<td><input class="security-account-checkbox" type="checkbox" data-security-select-uid="' +
      esc(uid) + '"' + (accountSecuritySelection.has(uid) ? ' checked' : '') +
      ' aria-label="选择账户 ' + esc(account.nickname || uid) + '"></td>' +
      '<td><span class="security-badge' + (banned ? ' banned' : '') + '">' +
      (banned ? '已封禁' : '正常') + '</span></td>' +
      '<td><span class="security-account-name">' + esc(account.nickname || account.uid) + '</span></td>' +
      '<td><code>' + esc(account.uid) + '</code></td>' +
      '<td><span class="security-account-email">' + esc(email) + '</span></td>' +
      '<td><code>' + esc(account.inviteCode || '未生成') + '</code></td>' +
      '<td>' + esc(account.inviteeCount == null ? 0 : account.inviteeCount) + ' 人</td>' +
      '<td>Lv' + esc(account.level == null ? '-' : account.level) + '</td>' +
      '<td>' + esc(account.experienceTotal == null ? '-' : account.experienceTotal) + '</td>' +
      '<td>' + esc(account.createdAt || '暂无') + '</td></tr>';
  }).join('');
  host.innerHTML = '<table class="security-account-table" aria-label="账户基本信息">' +
    '<thead><tr><th>选择</th><th>状态</th><th>昵称</th><th>UID</th><th>邮箱</th><th>邀请码</th>' +
    '<th>邀请人数</th><th>等级</th><th>累计经验</th>' +
    '<th>注册时间</th></tr></thead>' +
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
  if (host) host.innerHTML = '<div class="loading-state">正在读取账户基本信息…</div>';
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
    ? '确定批量封禁 ' + selectedUids.length + ' 个账户及其历史设备吗？\n' + previewNames + suffix
    : '确定批量解除这 ' + selectedUids.length + ' 个账户产生的封禁吗？\n' + previewNames + suffix;
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

function renderDeviceRelations() {
  const host = el('deviceRelationsTable');
  const summary = el('deviceRelationSummary');
  if (!host || !summary) return;
  const relations = deviceRelationsData && Array.isArray(deviceRelationsData.relations)
    ? deviceRelationsData.relations : [];
  const search = String(value('deviceRelationSearch') || '').toLowerCase();
  const filtered = relations.filter(function(relation) {
    if (!search) return true;
    return [
      relation.accountUid,
      relation.accountNickname,
      relation.deviceId,
      relation.brand,
      relation.model,
      relation.androidVersion,
      relation.appVersion
    ].join(' ').toLowerCase().indexOf(search) >= 0;
  });
  const grouped = new Map();
  filtered.forEach(function(relation) {
    const key = String(relation.accountUid || relation.accountNickname || 'unknown-account');
    const group = grouped.get(key) || {account: relation, devices: []};
    group.devices.push(relation);
    grouped.set(key, group);
  });
  const accountGroups = Array.from(grouped.values());
  deviceRelationDetailData = [];
  const currentCount = filtered.filter(function(relation) { return relation.current; }).length;
  const blockedCount = filtered.filter(function(relation) { return relation.blocked; }).length;
  summary.textContent = '共 ' + relations.length + ' 条关系 · 当前匹配 ' + accountGroups.length +
    ' 个账户、' + filtered.length + ' 台设备 · 当前关联 ' + currentCount +
    ' 条 · 已封禁设备 ' + blockedCount + ' 条';
  if (filtered.length === 0) {
    host.innerHTML = '<div class="user-table-empty">暂无匹配的账户与设备关系</div>';
    return;
  }
  const rows = accountGroups.map(function(group) {
    const account = group.account;
    const devices = group.devices;
    const accountStatus = account.accountStatus === 'banned'
      ? '账户已封禁' : account.accountStatus === 'deleted' ? '账户已删除' : '正常';
    const firstBoundAt = devices.map(function(relation) { return relation.firstBoundAt || ''; })
      .filter(Boolean).sort()[0] || '-';
    const lastBoundAt = devices.map(function(relation) { return relation.lastBoundAt || ''; })
      .filter(Boolean).sort().reverse()[0] || '-';
    const lastSeenAt = devices.map(function(relation) { return relation.lastSeenAt || ''; })
      .filter(Boolean).sort().reverse()[0] || '-';
    const deviceChips = devices.map(function(relation) {
      const deviceName = [relation.brand, relation.model].filter(Boolean).join(' ') || '未登记设备';
      const detailIndex = deviceRelationDetailData.push(relation) - 1;
      return '<button class="device-relation-chip" type="button" data-device-relation-index="' +
        detailIndex + '" aria-label="' + esc(deviceName) + '，点击查看设备详情">' +
        esc(deviceName) + '</button>';
    }).join('');
    return '<tr>' +
      '<td><code>' + esc(account.accountUid || '-') + '</code></td>' +
      '<td>' + esc(account.accountNickname || '-') + '</td>' +
      '<td>' + esc(accountStatus) + '</td>' +
      '<td>' + devices.length + ' 台</td>' +
      '<td class="device-relation-devices"><div class="device-relation-device-list">' + deviceChips + '</div></td>' +
      '<td>' + esc(firstBoundAt) + '</td>' +
      '<td>' + esc(lastBoundAt) + '</td>' +
      '<td>' + esc(lastSeenAt) + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table" aria-label="账户与设备关系">' +
    '<thead><tr><th scope="col">账户 UID</th><th scope="col">账户昵称</th>' +
    '<th scope="col">账户状态</th><th scope="col">设备数量</th><th scope="col">设备列表</th>' +
    '<th scope="col">首次绑定</th><th scope="col">最后绑定</th>' +
    '<th scope="col">最后活动</th></tr></thead><tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-device-relation-index]').forEach(function(button) {
    button.onclick = function() {
      openDeviceRelationDetail(Number(button.dataset.deviceRelationIndex));
    };
  });
}

function closeDeviceRelationDetail() {
  const modal = el('deviceRelationDetailModal');
  if (!modal) return;
  modal.hidden = true;
  modal.setAttribute('aria-hidden', 'true');
}

function openDeviceRelationDetail(index) {
  const relation = deviceRelationDetailData[index];
  const modal = el('deviceRelationDetailModal');
  const body = el('deviceRelationDetailBody');
  if (!relation || !modal || !body) return;
  const accountStatus = relation.accountStatus === 'banned'
    ? '账户已封禁' : relation.accountStatus === 'deleted' ? '账户已删除' : '正常';
  const relationStatus = relation.current ? '当前关联' : '历史关联';
  const blockStatus = relation.blocked
    ? (relation.blockedByAccount ? '本账户封禁' : '其他来源封禁') : '正常';
  const deviceName = [relation.brand, relation.model].filter(Boolean).join(' ') || '未登记设备';
  const fields = [
    ['账户 UID', relation.accountUid || '-'],
    ['账户昵称', relation.accountNickname || '-'],
    ['账户状态', accountStatus],
    ['设备厂商', relation.brand || '未登记'],
    ['设备型号', relation.model || '未登记'],
    ['设备 ID', relation.deviceId || '-'],
    ['Android 版本', (relation.androidVersion || '未登记') + ' / SDK ' + (relation.androidSdk || '-')],
    ['App 版本', relation.appVersion || '未登记'],
    ['关联状态', relationStatus],
    ['设备封禁状态', blockStatus],
    ['首次绑定', relation.firstBoundAt || '-'],
    ['最后绑定', relation.lastBoundAt || '-'],
    ['最后活动', relation.lastSeenAt || '-']
  ];
  body.innerHTML = '<p class="device-relation-modal-device-name">' + esc(deviceName) + '</p>' +
    '<div class="device-relation-detail-grid">' +
    fields.map(function(field) {
      return '<div class="device-relation-detail-item"><span>' + esc(field[0]) +
        '</span><strong>' + esc(field[1]) + '</strong></div>';
    }).join('') + '</div>';
  modal.hidden = false;
  modal.setAttribute('aria-hidden', 'false');
}

async function loadDeviceRelations(force) {
  if (deviceRelationsLoading) return false;
  if (deviceRelationsData && !force) {
    renderDeviceRelations();
    return true;
  }
  const host = el('deviceRelationsTable');
  deviceRelationsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取账户与设备关系…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/device-relations?limit=1000', {headers: headers()});
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    deviceRelationsData = data;
    renderDeviceRelations();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">账户与设备关系读取失败</div>';
    show('账户与设备关系读取失败：' + (error.message || error), true);
    return false;
  } finally {
    deviceRelationsLoading = false;
  }
}
