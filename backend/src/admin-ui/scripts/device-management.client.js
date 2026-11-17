let adminDeviceData = null;
let adminDeviceLoading = false;
let adminDeviceBatchBusy = false;
const adminDeviceSelection = new Set();

function resetAdminDeviceManagement() {
  adminDeviceData = null;
  adminDeviceLoading = false;
  adminDeviceBatchBusy = false;
  adminDeviceSelection.clear();
}

function adminDeviceList() {
  const devices = adminDeviceData && Array.isArray(adminDeviceData.devices)
    ? adminDeviceData.devices : [];
  const search = String(value('adminDeviceSearch') || '').trim().toLowerCase();
  if (!search) return devices;
  return devices.filter(function(device) {
    const searchable = [
      device.deviceId,
      device.currentUid,
      device.currentAccountStatus,
      device.historicalAccountCount,
      Array.isArray(device.linkedUids) ? device.linkedUids.join(' ') : '',
      device.brand,
      device.model,
      device.androidVersion,
      device.androidSdk,
      device.appVersion,
      device.activeBans,
    ].join(' ').toLowerCase();
    return searchable.indexOf(search) >= 0;
  });
}

function adminDeviceBanLabels(device) {
  const activeBans = Array.isArray(device.activeBans) ? device.activeBans : [];
  return activeBans.map(function(value) {
    const parts = String(value || '').split(':');
    const reason = parts.slice(1).join(':');
    if (reason === 'admin_device_ban') return '设备管理封禁';
    if (reason === 'client_security_report') return '安全上报封禁';
    return reason || parts[0] || '未知来源';
  });
}

function updateAdminDeviceSelectionControls(filtered) {
  const devices = adminDeviceData && Array.isArray(adminDeviceData.devices)
    ? adminDeviceData.devices : [];
  const knownIds = new Set(devices.map(function(device) { return String(device.deviceId || ''); }));
  adminDeviceSelection.forEach(function(deviceId) {
    if (!knownIds.has(deviceId)) adminDeviceSelection.delete(deviceId);
  });

  const selectedVisible = filtered.filter(function(device) {
    return adminDeviceSelection.has(String(device.deviceId || ''));
  }).length;
  const hiddenSelected = adminDeviceSelection.size - selectedVisible;
  const summary = el('adminDeviceSummary');
  if (summary) {
    summary.textContent = '共 ' + devices.length + ' 台设备 · 当前匹配 ' + filtered.length +
      ' 台 · 已选 ' + adminDeviceSelection.size + ' 台' +
      (hiddenSelected ? '（其中 ' + hiddenSelected + ' 台被搜索条件隐藏）' : '');
  }

  const selectAll = el('adminDeviceSelectAll');
  if (selectAll) {
    selectAll.disabled = filtered.length === 0 || adminDeviceBatchBusy;
    selectAll.checked = filtered.length > 0 && selectedVisible === filtered.length;
    selectAll.indeterminate = selectedVisible > 0 && selectedVisible < filtered.length;
  }
  const clearButton = el('adminDeviceClearSelection');
  const banButton = el('adminDeviceBatchBan');
  const unbanButton = el('adminDeviceBatchUnban');
  if (clearButton) clearButton.disabled = adminDeviceSelection.size === 0 || adminDeviceBatchBusy;
  if (banButton) banButton.disabled = adminDeviceSelection.size === 0 || adminDeviceBatchBusy;
  if (unbanButton) unbanButton.disabled = adminDeviceSelection.size === 0 || adminDeviceBatchBusy;
  document.querySelectorAll('[data-admin-device-select]').forEach(function(input) {
    input.disabled = adminDeviceBatchBusy;
  });
}

function renderAdminDevices() {
  const host = el('adminDevicesTable');
  if (!host) return;
  const devices = adminDeviceList();
  updateAdminDeviceSelectionControls(devices);
  if (devices.length === 0) {
    host.innerHTML = '<div class="user-table-empty">当前没有匹配的设备</div>';
    return;
  }

  const rows = devices.map(function(device) {
    const deviceId = String(device.deviceId || '');
    const name = [device.brand, device.model].filter(Boolean).join(' ') || '未登记设备';
    const linkedUids = Array.isArray(device.linkedUids) ? device.linkedUids : [];
    const linkedPreview = linkedUids.slice(0, 4).map(esc).join('、') || '—';
    const extraLinks = linkedUids.length > 4 ? ' +' + (linkedUids.length - 4) : '';
    const banLabels = adminDeviceBanLabels(device);
    const blocked = Boolean(device.blocked) || Number(device.activeBanCount) > 0;
    const accountStatus = device.currentAccountStatus === 'banned'
      ? '账户已封禁' : device.currentAccountStatus === 'active' ? '正常' : '无当前账户';
    const android = [
      device.androidVersion ? 'Android ' + device.androidVersion : '',
      Number(device.androidSdk) > 0 ? 'SDK ' + device.androidSdk : '',
    ].filter(Boolean).join(' · ') || '—';
    const blockDescription = banLabels.length ? banLabels.join('、') : '—';
    return '<tr>' +
      '<td class="admin-device-select-cell"><input class="user-admin-checkbox" type="checkbox" data-admin-device-select="' +
        esc(deviceId) + '"' + (adminDeviceSelection.has(deviceId) ? ' checked' : '') +
        (adminDeviceBatchBusy ? ' disabled' : '') +
        ' aria-label="选择设备 ' + esc(name) + '"></td>' +
      '<td><strong>' + esc(name) + '</strong><br><span class="admin-device-account-status">' + esc(accountStatus) + '</span></td>' +
      '<td class="admin-device-id"><code>' + esc(deviceId) + '</code></td>' +
      '<td><code>' + esc(device.currentUid || '—') + '</code></td>' +
      '<td><span class="admin-device-linked-uids">' + linkedPreview + esc(extraLinks) + '</span>' +
        '<br><span class="admin-device-account-status">' + (Number(device.historicalAccountCount) || 0) + ' 个历史关联账户</span></td>' +
      '<td><span class="security-badge' + (blocked ? ' banned' : '') + '">' + (blocked ? '已封禁' : '正常') + '</span>' +
        '<br><span class="admin-device-account-status">' + esc(blockDescription) + '</span></td>' +
      '<td>' + esc(android) + '</td>' +
      '<td>' + esc(device.appVersion || '—') + '</td>' +
      '<td>' + esc(device.createdAt || '—') + '</td>' +
      '<td>' + esc(device.lastSeenAt || '—') + '</td>' +
      '</tr>';
  }).join('');

  host.innerHTML = '<table class="traffic-detail-table admin-device-table" aria-label="设备管理表">' +
    '<thead><tr><th scope="col"><input class="user-admin-checkbox" id="adminDeviceSelectAll" type="checkbox" aria-label="全选当前结果" title="全选当前结果" onchange="toggleAllAdminDevices(this.checked)"></th><th scope="col">设备</th><th scope="col">设备 ID</th>' +
    '<th scope="col">当前账户 UID</th><th scope="col">历史关联账户</th><th scope="col">设备封禁状态</th>' +
    '<th scope="col">Android 版本</th><th scope="col">App 版本</th><th scope="col">首次登记</th>' +
    '<th scope="col">最近活动</th></tr></thead><tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-admin-device-select]').forEach(function(input) {
    input.addEventListener('change', function() {
      const deviceId = input.dataset.adminDeviceSelect;
      if (input.checked) adminDeviceSelection.add(deviceId);
      else adminDeviceSelection.delete(deviceId);
      updateAdminDeviceSelectionControls(adminDeviceList());
    });
  });
  updateAdminDeviceSelectionControls(devices);
}

function toggleAllAdminDevices(checked) {
  if (adminDeviceBatchBusy) return;
  adminDeviceList().forEach(function(device) {
    const deviceId = String(device.deviceId || '');
    if (checked) adminDeviceSelection.add(deviceId);
    else adminDeviceSelection.delete(deviceId);
  });
  renderAdminDevices();
}

function clearAdminDeviceSelection() {
  if (adminDeviceBatchBusy) return;
  adminDeviceSelection.clear();
  renderAdminDevices();
}

async function batchSetAdminDeviceBan(action) {
  if (adminDeviceBatchBusy) return false;
  const deviceIds = Array.from(adminDeviceSelection);
  if (deviceIds.length === 0) {
    show('请先选择设备', true);
    return false;
  }
  if (deviceIds.length > 100) {
    show('单次最多选择 100 台设备', true);
    return false;
  }
  const banning = action === 'ban';
  const confirmMessage = banning
    ? '确定批量封禁 ' + deviceIds.length + ' 台设备吗？这些设备将无法登录、注册或注销账户。'
    : '确定解除所选设备的设备管理封禁吗？安全审核产生的其他封禁仍会生效。';
  if (!confirm(confirmMessage)) return false;

  adminDeviceBatchBusy = true;
  updateAdminDeviceSelectionControls(adminDeviceList());
  try {
    const response = await fetch(ADMIN_API_BASE + '/devices/batch', {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify({deviceIds: deviceIds, action: action}),
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) {
      show(adminErrorMessage(data.error, '设备封禁操作失败（HTTP ' + response.status + '）'), true);
      return false;
    }
    adminDeviceSelection.clear();
    const refreshed = await loadAdminDevices(true);
    const changed = Number(data.changedDeviceCount) || 0;
    if (changed === 0 && !banning) {
      show('未发现可解除的设备管理封禁；其他来源的封禁仍保持有效', false);
    } else {
      show((banning ? '设备封禁完成：' : '设备解封完成：') + changed + ' 台' +
        (refreshed ? '' : '；设备列表刷新失败'), !refreshed);
    }
    return true;
  } catch (error) {
    show('设备封禁操作失败：' + ((error && error.message) || '请检查网络'), true);
    return false;
  } finally {
    adminDeviceBatchBusy = false;
    updateAdminDeviceSelectionControls(adminDeviceList());
  }
}

async function loadAdminDevices(force) {
  if (adminDeviceLoading) return false;
  if (adminDeviceData && !force) {
    renderAdminDevices();
    return true;
  }
  const host = el('adminDevicesTable');
  adminDeviceLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取设备数据…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/devices?limit=1000', {headers: headers()});
    const data = parseResponseJson(await response.text());
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    adminDeviceData = data;
    renderAdminDevices();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">设备数据读取失败</div>';
    show('设备数据读取失败：' + ((error && error.message) || '请检查网络'), true);
    return false;
  } finally {
    adminDeviceLoading = false;
  }
}
