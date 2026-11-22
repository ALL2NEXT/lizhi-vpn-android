let deviceRelationDetailData = [];
let clientSecurityReportData = null;
let clientSecurityReportsLoading = false;

function clientSecurityFindingLabel(code) {
  const labels = {
    http_proxy_configured: '系统 HTTP 代理',
    xposed_artifact: 'Xposed 痕迹',
    lsposed_artifact: 'LSPosed 痕迹',
    frida_maps: 'Frida 内存映射',
    frida_thread: 'Frida 线程',
    frida_listener: 'Frida 监听端口',
    instrumentation_tracer: '进程调试跟踪',
    signature_unavailable: '签名信息不可用',
    signature_mismatch: '签名不匹配',
    adb_enabled: 'ADB 调试已开启',
    developer_options_enabled: '开发者选项已开启',
    screenshot_capture: '检测到截图',
    native_library_unavailable: 'Native 库不可用',
    native_scan_error: 'Native 检测失败'
  };
  if (labels[code]) return labels[code];
  if (code === 'blacklisted_packages') return '软件黑名单命中';
  if (String(code || '').indexOf('blacklisted_package:') === 0) {
    return '命中软件黑名单：' + String(code).slice('blacklisted_package:'.length);
  }
  return code;
}

function groupClientSecurityReports(reports) {
  const groupsByDevice = new Map();
  const groupedReports = [];
  reports.forEach(function(report) {
    const code = String(report.findingCode || '');
    const prefix = 'blacklisted_package:';
    if (code.indexOf(prefix) !== 0) {
      groupedReports.push(report);
      return;
    }

    const deviceKey = String(report.deviceId || ('report:' + report.id));
    let group = groupsByDevice.get(deviceKey);
    if (!group) {
      group = Object.assign({}, report, {
        findingCode: 'blacklisted_packages',
        blacklistedPackages: [],
        firstReportedAt: report.firstReportedAt || '',
        reportCount: 0
      });
      groupsByDevice.set(deviceKey, group);
      groupedReports.push(group);
    }

    const packageName = code.slice(prefix.length);
    if (group.blacklistedPackages.indexOf(packageName) < 0) {
      group.blacklistedPackages.push(packageName);
    }
    group.reportCount += Number(report.reportCount) || 0;
    if (report.firstReportedAt &&
        (!group.firstReportedAt || report.firstReportedAt < group.firstReportedAt)) {
      group.firstReportedAt = report.firstReportedAt;
    }
    if (report.lastReportedAt &&
        (!group.lastReportedAt || report.lastReportedAt > group.lastReportedAt)) {
      group.lastReportedAt = report.lastReportedAt;
    }
    if (report.status === 'pending') {
      if (group.status !== 'pending') group.id = report.id;
      group.status = 'pending';
    } else if (group.status !== 'pending') {
      group.status = report.status;
    }
    group.blocked = Boolean(group.blocked || report.blocked);
  });

  return groupedReports.sort(function(left, right) {
    const byTime = String(right.lastReportedAt || '').localeCompare(String(left.lastReportedAt || ''));
    return byTime || (Number(right.id) - Number(left.id));
  });
}

function renderClientSecurityReports() {
  const host = el('clientSecurityReports');
  const summary = el('clientSecurityReportsSummary');
  if (!host || !summary) return;
  if (!clientSecurityReportData) {
    host.innerHTML = '<div class="loading-state">尚未读取客户端上报</div>';
    summary.textContent = '尚未读取客户端上报';
    return;
  }
  const reports = groupClientSecurityReports(Array.isArray(clientSecurityReportData.reports)
    ? clientSecurityReportData.reports : []);
  const pendingCount = reports.filter(function(report) { return report.status === 'pending'; }).length;
  const bannedCount = reports.filter(function(report) { return report.status === 'banned'; }).length;
  summary.textContent = '待审核 ' + pendingCount + ' 项 · 已拉黑 ' + bannedCount + ' 项' +
    (clientSecurityReportData.generatedAt ? ' · 更新于 ' + clientSecurityReportData.generatedAt : '');
  if (reports.length === 0) {
    host.innerHTML = '<div class="security-device-empty">当前没有待审核的客户端安全上报</div>';
    return;
  }
  const rows = reports.map(function(report) {
    const deviceName = [report.brand, report.model].filter(Boolean).join(' ') || '未知设备';
    const platform = [report.androidVersion ? 'Android ' + report.androidVersion : '',
      report.appVersion ? 'App ' + report.appVersion : ''].filter(Boolean).join(' · ') || '版本未知';
    const isBlacklistGroup = Array.isArray(report.blacklistedPackages) &&
      report.blacklistedPackages.length > 0;
    const findingLabel = isBlacklistGroup
      ? '命中软件黑名单（' + report.blacklistedPackages.length + ' 个）'
      : clientSecurityFindingLabel(report.findingCode);
    const findingDetails = isBlacklistGroup
      ? '<br><small class="table-secondary-line">包名：' +
        report.blacklistedPackages.map(esc).join('、') + '</small>'
      : '<br><small class="table-secondary-line">' + esc(report.findingCode) + '</small>';
    const actions = report.status === 'banned'
      ? '<span class="security-badge banned">已拉黑</span> ' +
        '<button class="secondary" type="button" data-client-report-action="unban" data-client-report-id="' +
        esc(report.id) + '" data-client-report-blacklist-group="' + isBlacklistGroup + '">解除拉黑</button>'
      : '<button class="danger" type="button" data-client-report-action="ban" data-client-report-id="' +
        esc(report.id) + '" data-client-report-blacklist-group="' + isBlacklistGroup + '">拉黑设备</button> ' +
        '<button class="secondary" type="button" data-client-report-action="dismiss" data-client-report-id="' +
        esc(report.id) + '" data-client-report-blacklist-group="' + isBlacklistGroup + '">忽略</button>';
    return '<tr>' +
      '<td><strong>' + esc(findingLabel) + '</strong>' + findingDetails + '</td>' +
      '<td>' + esc(deviceName) + '<small class="table-secondary-line">' + esc(platform) + '</small></td>' +
      '<td><code>' + esc(report.deviceId) + '</code>' +
        (report.uid ? '<small class="table-secondary-line">UID ' + esc(report.uid) + '</small>' : '') + '</td>' +
      '<td>' + esc(report.reportCount) + '</td>' +
      '<td>' + esc(report.lastReportedAt || '—') + '</td>' +
      '<td>' + actions + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="security-account-table" aria-label="客户端安全上报待审核列表">' +
    '<thead><tr><th>风险信号</th><th>设备</th><th>设备 ID / UID</th><th>上报次数</th><th>最近上报</th><th>审核</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-client-report-action]').forEach(function(button) {
    button.onclick = function() {
      reviewClientSecurityReport(
        Number(button.dataset.clientReportId),
        button.dataset.clientReportAction,
        button.dataset.clientReportBlacklistGroup === 'true'
      );
    };
  });
}

async function loadClientSecurityReports(force) {
  if (clientSecurityReportsLoading) return false;
  if (clientSecurityReportData && !force) {
    renderClientSecurityReports();
    return true;
  }
  clientSecurityReportsLoading = true;
  const host = el('clientSecurityReports');
  if (host) host.innerHTML = '<div class="loading-state">正在读取待审核上报…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/security/client-reports', {headers: headers()});
    const data = parseResponseJson(await response.text());
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    clientSecurityReportData = data;
    renderClientSecurityReports();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">读取客户端安全上报失败</div>';
    show('读取客户端安全上报失败：' + (error.message || error), true);
    return false;
  } finally {
    clientSecurityReportsLoading = false;
  }
}

async function reviewClientSecurityReport(reportId, action, isBlacklistGroup) {
  if (!Number.isSafeInteger(reportId) || reportId < 1 || !['ban', 'dismiss', 'unban'].includes(action)) return;
  const prompt = action === 'ban'
    ? '确认拉黑这台设备吗？'
    : action === 'unban' ? '确认解除这台设备的客户端风险拉黑吗？'
      : isBlacklistGroup ? '确认忽略这台设备命中的全部软件黑名单吗？'
        : '确认忽略这条客户端风险上报吗？';
  if (!confirm(prompt)) return;
  try {
    const response = await fetch(
      ADMIN_API_BASE + '/security/client-reports/' + encodeURIComponent(reportId) + '/review',
      {method: 'POST', headers: headers(), body: JSON.stringify({action: action})}
    );
    const data = parseResponseJson(await response.text());
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    clientSecurityReportData = null;
    await loadClientSecurityReports(true);
    show(action === 'ban' ? '设备已拉黑' : action === 'unban' ? '设备已解除拉黑' : '已忽略该风险上报');
  } catch (error) {
    show('审核客户端安全上报失败：' + (error.message || error), true);
  }
}

function securityFilteredAccounts() {
  const accounts = accountSecurityData && Array.isArray(accountSecurityData.accounts)
    ? accountSecurityData.accounts : [];
  const search = String(value('securitySearch') || '').toLowerCase();
  return accounts.filter(function(account) {
    if (!search) return true;
    return [account.uid, account.nickname, account.email]
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
  const bannedAccounts = accounts.filter(function(account) { return account.accountType === 'banned'; }).length;
  const search = String(value('securitySearch') || '').toLowerCase();
  summary.textContent = '账户 ' + accounts.length + ' 个 · 已封禁 ' + bannedAccounts +
    ' 个' +
    (search ? ' · 当前匹配 ' + filtered.length + ' 个' : '');
  if (filtered.length === 0) {
    host.innerHTML = '<div class="security-device-empty">没有匹配的账户</div>';
    return;
  }
  const rows = filtered.map(function(account) {
    const banned = account.accountType === 'banned';
    const email = account.email || '—';
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
      '<td>' + esc(account.createdAt || '暂无') + '</td></tr>';
  }).join('');
  host.innerHTML = '<table class="security-account-table" aria-label="Account security">' +
    '<thead><tr><th>Select</th><th>Status</th><th>Nickname</th><th>UID</th><th>Email</th>' +
    '<th>Created at</th></tr></thead>' +
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
  const prompt = shouldBan
    ? '确定封禁账户“' + name + '”吗？'
    : '确定解除账户“' + name + '”的封禁吗？';
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
    show(shouldBan ? '账户已封禁' : '账户已解除封禁');
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
  const blockStatus = relation.blocked ? '设备已封禁' : '正常';
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
