function renderAdminLoginRecords() {
  const host = el('adminLoginRecordsTable');
  if (!host) return;
  const records = Array.isArray(adminLoginRecordsData) ? adminLoginRecordsData : [];
  if (!records.length) {
    host.innerHTML = '<div class="admin-login-empty">暂无后台登录记录</div>';
    return;
  }
  host.innerHTML = '<table class="admin-login-table">' +
    '<thead><tr><th>时间</th><th>结果</th><th>IP 地址</th><th>客户端</th></tr></thead>' +
    '<tbody>' + records.map(function(record) {
      const success = Boolean(record && record.success);
      return '<tr>' +
        '<td>' + esc(record && record.at || '-') + '</td>' +
        '<td><span class="admin-login-badge ' + (success ? 'success' : 'failed') + '">' +
          (success ? '登录成功' : '密码错误') +
        '</span></td>' +
        '<td><code>' + esc(record && record.ip || '-') + '</code></td>' +
        '<td class="admin-login-user-agent">' + esc(record && record.userAgent || '-') + '</td>' +
      '</tr>';
    }).join('') + '</tbody></table>';
}

async function loadAdminLoginRecords(force) {
  if (adminLoginRecordsLoading) return;
  if (adminLoginRecordsData && !force) {
    renderAdminLoginRecords();
    return;
  }
  const host = el('adminLoginRecordsTable');
  adminLoginRecordsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取后台登录记录</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/admin-login-records', {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) {
      throw new Error(adminErrorMessage(data.error, '后台登录记录读取失败（HTTP ' + response.status + '）'));
    }
    adminLoginRecordsData = Array.isArray(data.records) ? data.records : [];
    renderAdminLoginRecords();
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">' + esc(error.message || '后台登录记录读取失败') + '</div>';
  } finally {
    adminLoginRecordsLoading = false;
  }
}
