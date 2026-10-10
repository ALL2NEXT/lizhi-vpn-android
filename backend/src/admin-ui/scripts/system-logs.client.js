function systemLogCategoryName(category) {
  return ({request: '网络请求', operation: '后台操作', database: '数据库', outbound: '出站请求'})[category] || '系统';
}

function systemLogLevelName(level) {
  return ({error: '错误', warning: '警告', info: '信息'})[level] || level || '-';
}

function renderSystemLogs() {
  const host = el('systemLogsTable');
  const summary = el('systemLogsSummary');
  const pageStatus = el('systemLogsPageStatus');
  if (!host) return;

  const payload = systemLogsData || {records: [], total: 0, limit: 100, offset: systemLogsOffset};
  const records = Array.isArray(payload.records) ? payload.records : [];
  const total = Number(payload.total) || 0;
  const limit = Number(payload.limit) || 100;
  const offset = Number(payload.offset) || 0;
  const page = Math.floor(offset / limit) + 1;
  const pageCount = Math.max(1, Math.ceil(total / limit));

  if (summary) summary.textContent = '共 ' + total + ' 条日志，保留最近 30 天';
  if (pageStatus) pageStatus.textContent = total
    ? '第 ' + page + ' / ' + pageCount + ' 页 · 显示 ' + (offset + 1) + '–' + Math.min(offset + records.length, total) + ' 条'
    : '暂无日志';

  if (!records.length) {
    host.innerHTML = '<div class="system-logs-empty">' + (total ? '当前筛选条件没有日志' : '暂无系统日志') + '</div>';
    return;
  }

  host.innerHTML = '<table class="system-logs-table">' +
    '<thead><tr><th>时间</th><th>类型</th><th>级别</th><th>请求 / 操作</th><th>结果</th><th>耗时</th><th>来源 IP</th><th>详情</th></tr></thead>' +
    '<tbody>' + records.map(function(record) {
      const level = String(record.level || 'info');
      const status = record.status == null ? '-' : String(record.status);
      const duration = record.durationMs == null ? '-' : String(record.durationMs) + ' ms';
      const detail = [record.details, record.userAgent ? '客户端：' + record.userAgent : '', record.requestId ? '请求 ID：' + record.requestId : '']
        .filter(Boolean).join('\n');
      return '<tr>' +
        '<td class="system-logs-time">' + esc(record.occurredAt || '-') + '</td>' +
        '<td><span class="system-log-category ' + esc(record.category || '') + '">' + esc(systemLogCategoryName(record.category)) + '</span></td>' +
        '<td><span class="system-log-level-badge ' + (level === 'info' ? 'success' : 'failed') + ' system-log-level ' + esc(level) + '">' + esc(systemLogLevelName(level)) + '</span></td>' +
        '<td class="system-logs-path"><strong>' + esc(record.message || '-') + '</strong><code>' + esc((record.method ? record.method + ' ' : '') + (record.path || '')) + '</code></td>' +
        '<td>' + esc(status) + '</td>' +
        '<td>' + esc(duration) + '</td>' +
        '<td><code>' + esc(record.ip || '-') + '</code></td>' +
        '<td>' + (detail ? '<details class="system-log-detail"><summary>查看</summary><pre>' + esc(detail) + '</pre></details>' : '-') + '</td>' +
      '</tr>';
    }).join('') + '</tbody></table>';
}

async function loadSystemLogs(force) {
  if (systemLogsLoading) return;
  if (systemLogsData && !force) {
    renderSystemLogs();
    return;
  }

  const host = el('systemLogsTable');
  systemLogsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取系统日志</div>';
  const query = new URLSearchParams({limit: '100', offset: String(systemLogsOffset)});
  const category = el('systemLogCategory').value;
  const level = el('systemLogLevel').value;
  const search = el('systemLogSearch').value.trim();
  if (category) query.set('category', category);
  if (level) query.set('level', level);
  if (search) query.set('q', search);

  try {
    const response = await fetch(ADMIN_API_BASE + '/system-logs?' + query.toString(), {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) {
      throw new Error(adminErrorMessage(data.error, '系统日志读取失败（HTTP ' + response.status + '）'));
    }
    systemLogsData = data;
    renderSystemLogs();
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">' + esc(error.message || '系统日志读取失败') + '</div>';
  } finally {
    systemLogsLoading = false;
  }
}

function applySystemLogFilters() {
  systemLogsOffset = 0;
  systemLogsData = null;
  loadSystemLogs(true);
}

function changeSystemLogsPage(direction) {
  const total = Number(systemLogsData && systemLogsData.total) || 0;
  const limit = Number(systemLogsData && systemLogsData.limit) || 100;
  const nextOffset = systemLogsOffset + direction * limit;
  if (nextOffset < 0 || nextOffset >= total) return;
  systemLogsOffset = nextOffset;
  systemLogsData = null;
  loadSystemLogs(true);
}

const systemLogSearchInput = el('systemLogSearch');
if (systemLogSearchInput) systemLogSearchInput.addEventListener('keydown', function(event) {
  if (event.key === 'Enter') applySystemLogFilters();
});
