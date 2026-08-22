function renderAdminLoginRecords() {
  const host = el('adminLoginRecordsTable');
  if (!host) return;
  const records = Array.isArray(adminLoginRecordsData) ? adminLoginRecordsData : [];
  if (!records.length) {
    host.innerHTML = '<div class="admin-login-empty">暂无后台登录记录</div>';
    return;
  }
  host.innerHTML = '<table class="admin-login-table">' +
    '<thead><tr><th>时间</th><th>结果</th><th>路径</th><th>方法</th><th>IP 地址</th><th>客户端</th></tr></thead>' +
    '<tbody>' + records.map(function(record) {
      const success = Boolean(record && record.success);
      return '<tr>' +
        '<td>' + esc(record && record.at || '-') + '</td>' +
        '<td><span class="admin-login-badge ' + (success ? 'success' : 'failed') + '">' +
          (record && record.honeypot ? '蜜罐命中' : success ? '登录成功' : '密码错误') +
        '</span></td>' +
        '<td class="admin-login-path"><code>' + esc(record && record.path || '-') + '</code></td>' +
        '<td>' + esc(record && record.method || '-') + '</td>' +
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

async function clearAdminLoginRecords() {
  if (adminLoginRecordsLoading) return;
  const count = Array.isArray(adminLoginRecordsData) ? adminLoginRecordsData.length : 0;
  if (!confirm('确定清理当前 ' + count + ' 条后台登录记录吗？此操作不可恢复。')) return;
  try {
    const response = await fetch(ADMIN_API_BASE + '/admin-login-records', {
      method: 'DELETE',
      headers: headers(),
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) {
      throw new Error(adminErrorMessage(data.error, '后台登录记录清理失败（HTTP ' + response.status + '）'));
    }
    adminLoginRecordsData = [];
    renderAdminLoginRecords();
    show('后台登录记录已清理');
  } catch (error) {
    show(error.message || '后台登录记录清理失败', true);
  }
}
