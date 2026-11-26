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
