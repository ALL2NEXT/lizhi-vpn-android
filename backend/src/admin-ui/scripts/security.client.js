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
    return [account.uid, account.nickname, account.email, account.inviteCode, account.invitedByCode].concat(deviceIds)
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
      '<td><code>' + esc(account.inviteCode || '未生成') + '</code></td>' +
      '<td>' + esc(account.inviteeCount == null ? 0 : account.inviteeCount) + ' 人</td>' +
      '<td>Lv' + esc(account.level == null ? '-' : account.level) + '</td>' +
      '<td>' + esc(account.experienceTotal == null ? '-' : account.experienceTotal) + '</td>' +
      '<td>' + deviceCell + '</td>' +
      '<td>' + bannedDeviceCount + ' 台</td>' +
      '<td>' + esc(account.createdAt || '暂无') + '</td></tr>';
  }).join('');
  host.innerHTML = '<table class="security-account-table" aria-label="账户安全信息">' +
    '<thead><tr><th>选择</th><th>状态</th><th>昵称</th><th>UID</th><th>邮箱</th><th>邀请码</th>' +
    '<th>邀请人数</th><th>等级</th><th>累计经验</th><th>历史设备</th><th>本账户封禁设备</th>' +
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
