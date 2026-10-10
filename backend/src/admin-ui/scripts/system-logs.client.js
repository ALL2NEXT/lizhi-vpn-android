let systemLogsReloadQueued = false;
let systemLogsAutoRefreshTimer = null;
let systemLogsSearchTimer = null;
let systemLogsRangeTimer = null;
let systemLogsFilterRevision = 0;
let systemLogDetailRecord = null;
let systemLogDetailTab = 'basic';

function systemLogCategoryName(category) {
  return ({request: '网络请求', operation: '后台操作', database: '数据库', outbound: '出站请求'})[category] || '系统';
}

function systemLogLevelName(level) {
  return ({error: 'ERROR', warning: 'WARN', info: 'INFO'})[level] || String(level || '-').toUpperCase();
}

function systemLogDateTime(value) {
  const text = String(value || '-');
  if (text.indexOf('T') < 0) return text;
  const parts = text.split('T');
  return parts[0] + ' ' + parts[1].replace(/(?:Z|[+-]\d{2}:\d{2})$/, '');
}

function systemLogStatusClass(status) {
  const code = Number(status);
  if (code >= 500) return 'error';
  if (code >= 400) return 'warning';
  if (code >= 200 && code < 400) return 'success';
  return 'neutral';
}

function updateSystemLogStats(stats) {
  const values = stats || {};
  const fields = [
    ['systemLogsTotalStat', values.total],
    ['systemLogsErrorStat', values.errorCount],
    ['systemLogsWarningStat', values.warningCount],
    ['systemLogsAverageStat', values.averageDurationMs]
  ];
  fields.forEach(function(entry) {
    const node = el(entry[0]);
    if (node) node.textContent = Number(entry[1] || 0).toLocaleString();
  });
}

function renderSystemLogsPager(page, pageCount, total) {
  const pager = el('systemLogsPager');
  if (!pager) return;
  const buttons = [];
  buttons.push('<button type="button" class="secondary" data-system-log-page="' + (page - 1) + '" aria-label="上一页" ' + (page <= 1 ? 'disabled' : '') + '>‹</button>');
  let pages;
  if (pageCount <= 7) {
    pages = Array.from({length: pageCount}, function(_, index) { return index + 1; });
  } else {
    const start = Math.max(2, page - 1);
    const end = Math.min(pageCount - 1, page + 1);
    pages = [1];
    if (start > 2) pages.push('…');
    for (let item = start; item <= end; item += 1) pages.push(item);
    if (end < pageCount - 1) pages.push('…');
    pages.push(pageCount);
  }
  pages.forEach(function(item) {
    if (item === '…') buttons.push('<span class="system-logs-page-ellipsis">…</span>');
    else buttons.push('<button type="button" class="secondary ' + (item === page ? 'active' : '') + '" data-system-log-page="' + item + '" aria-current="' + (item === page ? 'page' : 'false') + '">' + item + '</button>');
  });
  buttons.push('<button type="button" class="secondary" data-system-log-page="' + (page + 1) + '" aria-label="下一页" ' + (page >= pageCount || !total ? 'disabled' : '') + '>›</button>');
  pager.innerHTML = buttons.join('');
}

function renderSystemLogs() {
  const host = el('systemLogsTable');
  const summary = el('systemLogsSummary');
  const pageStatus = el('systemLogsPageStatus');
  if (!host) return;

  const payload = systemLogsData || {records: [], total: 0, limit: 10, offset: systemLogsOffset};
  const records = Array.isArray(payload.records) ? payload.records : [];
  const total = Number(payload.total) || 0;
  const limit = Number(payload.limit) || 10;
  const offset = Number(payload.offset) || 0;
  const page = Math.floor(offset / limit) + 1;
  const pageCount = Math.max(1, Math.ceil(total / limit));
  updateSystemLogStats(payload.stats);
  if (summary) summary.textContent = '共 ' + total.toLocaleString() + ' 条日志';
  if (pageStatus) pageStatus.textContent = total
    ? '显示 ' + (offset + 1) + '–' + Math.min(offset + records.length, total) + ' 条，共 ' + total.toLocaleString() + ' 条'
    : '暂无日志';
  renderSystemLogsPager(page, pageCount, total);

  if (!records.length) {
    host.innerHTML = '<div class="system-logs-empty">' + (total ? '当前筛选条件没有日志' : '暂无系统日志') + '</div>';
    return;
  }

  host.innerHTML = '<table class="system-logs-table"><thead><tr>' +
    '<th>时间</th><th>类型</th><th>级别</th><th>方法 / 路径</th><th>状态</th><th>耗时</th><th>来源 IP</th><th>请求 ID</th><th>操作</th>' +
    '</tr></thead><tbody>' + records.map(function(record) {
      const level = String(record.level || 'info');
      const status = record.status == null ? '-' : String(record.status);
      const duration = record.durationMs == null ? '-' : Number(record.durationMs).toLocaleString() + ' ms';
      const method = String(record.method || '-').toUpperCase();
      const path = String(record.path || record.message || '-');
      const requestId = String(record.requestId || '-');
      const selected = systemLogDetailRecord && systemLogDetailRecord.id === record.id;
      return '<tr class="' + (selected ? 'selected' : '') + '">' +
        '<td class="system-logs-time">' + esc(systemLogDateTime(record.occurredAt)) + '</td>' +
        '<td><span class="system-log-category ' + esc(record.category || '') + '">' + esc(systemLogCategoryName(record.category)) + '</span></td>' +
        '<td><span class="system-log-level-badge system-log-level ' + esc(level) + '">' + esc(systemLogLevelName(level)) + '</span></td>' +
        '<td class="system-logs-path"><span class="system-log-method">' + esc(method) + '</span><span class="system-log-path-text" title="' + esc(path) + '">' + esc(path) + '</span></td>' +
        '<td><span class="system-log-status ' + systemLogStatusClass(record.status) + '">' + esc(status) + '</span></td>' +
        '<td class="system-logs-duration ' + (Number(record.durationMs) >= 500 ? 'slow' : '') + '">' + esc(duration) + '</td>' +
        '<td><span class="system-log-ip" title="' + esc(record.ip || '-') + '">' + esc(record.ip || '-') + '</span></td>' +
        '<td><code class="system-log-request-id" title="' + esc(requestId) + '">' + esc(requestId.length > 14 ? requestId.slice(0, 12) + '…' : requestId) + '</code></td>' +
        '<td><button type="button" class="system-log-view-button" data-system-log-record-id="' + Number(record.id) + '">查看 <span aria-hidden="true">›</span></button></td>' +
      '</tr>';
    }).join('') + '</tbody></table>';

  host.querySelectorAll('[data-system-log-record-id]').forEach(function(button) {
    button.addEventListener('click', function() {
      openSystemLogDetailsById(button.dataset.systemLogRecordId);
    });
  });
}

async function loadSystemLogs(force) {
  if (systemLogsLoading) {
    if (force) systemLogsReloadQueued = true;
    return;
  }
  if (systemLogsData && !force) {
    renderSystemLogs();
    return;
  }

  const host = el('systemLogsTable');
  const filterRevision = systemLogsFilterRevision;
  systemLogsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取系统日志</div>';
  const pageSize = Number(el('systemLogsPageSize') && el('systemLogsPageSize').value) || 10;
  const query = new URLSearchParams({limit: String(pageSize), offset: String(systemLogsOffset)});
  const level = el('systemLogLevel').value;
  const status = el('systemLogStatus').value;
  const search = el('systemLogSearch').value.trim();
  const startDate = el('systemLogStartDate').value;
  const endDate = el('systemLogEndDate').value;
  if (systemLogsCategory) query.set('category', systemLogsCategory);
  if (level) query.set('level', level);
  if (status) query.set('status', status);
  if (search) query.set('q', search);
  if (startDate) query.set('startDate', startDate);
  if (endDate) query.set('endDate', endDate);

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
    if (filterRevision !== systemLogsFilterRevision) return;
    systemLogsData = data;
    renderSystemLogs();
    if (systemLogDetailRecord) {
      const updatedRecord = (data.records || []).find(function(record) { return record.id === systemLogDetailRecord.id; });
      if (updatedRecord) {
        systemLogDetailRecord = updatedRecord;
        renderSystemLogDetails();
      }
    }
  } catch (error) {
    if (filterRevision === systemLogsFilterRevision && host) {
      host.innerHTML = '<div class="error-state">' + esc(error.message || '系统日志读取失败') + '</div>';
    }
  } finally {
    systemLogsLoading = false;
    if (systemLogsReloadQueued) {
      systemLogsReloadQueued = false;
      systemLogsData = null;
      loadSystemLogs(true);
    }
  }
}

function syncSystemLogCategorySelection() {
  document.querySelectorAll('[data-system-log-category]').forEach(function(button) {
    const selected = button.dataset.systemLogCategory === systemLogsCategory;
    button.classList.toggle('active', selected);
    if (selected) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
}

function selectSystemLogCategory(category) {
  const allowedCategories = ['', 'request', 'operation', 'database', 'outbound'];
  const selectedCategory = allowedCategories.includes(category) ? category : '';
  if (selectedCategory === systemLogsCategory) return;
  systemLogsCategory = selectedCategory;
  syncSystemLogCategorySelection();
  applySystemLogFilters();
}

function applySystemLogFilters() {
  systemLogsFilterRevision += 1;
  if (systemLogDetailRecord) closeSystemLogDetails();
  systemLogsOffset = 0;
  systemLogsData = null;
  loadSystemLogs(true);
}

function systemLogBeijingDate(date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(date);
  const values = {};
  parts.forEach(function(part) { values[part.type] = part.value; });
  return values.year + '-' + values.month + '-' + values.day;
}

function shiftSystemLogDate(value, days) {
  const date = new Date(value + 'T00:00:00.000Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function setDefaultSystemLogDateRange() {
  const endDate = systemLogBeijingDate(new Date());
  el('systemLogEndDate').value = endDate;
  el('systemLogStartDate').value = shiftSystemLogDate(endDate, -9);
}

function resetSystemLogFilters() {
  el('systemLogSearch').value = '';
  el('systemLogLevel').value = '';
  el('systemLogStatus').value = '';
  systemLogsCategory = '';
  setDefaultSystemLogDateRange();
  syncSystemLogCategorySelection();
  applySystemLogFilters();
}

function changeSystemLogsPage(page) {
  const total = Number(systemLogsData && systemLogsData.total) || 0;
  const limit = Number(systemLogsData && systemLogsData.limit) || 10;
  const pageCount = Math.max(1, Math.ceil(total / limit));
  const nextPage = Math.max(1, Math.min(pageCount, Number(page) || 1));
  if (nextPage === Math.floor(systemLogsOffset / limit) + 1) return;
  systemLogsFilterRevision += 1;
  if (systemLogDetailRecord) closeSystemLogDetails();
  systemLogsOffset = (nextPage - 1) * limit;
  systemLogsData = null;
  loadSystemLogs(true);
}

function openSystemLogDetailsById(id) {
  const record = (systemLogsData && systemLogsData.records || []).find(function(item) {
    return Number(item.id) === Number(id);
  });
  if (!record) return;
  systemLogDetailRecord = record;
  systemLogDetailTab = 'basic';
  const drawer = el('systemLogDetailDrawer');
  drawer.hidden = false;
  drawer.setAttribute('aria-hidden', 'false');
  document.querySelectorAll('[data-system-log-detail-tab]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.systemLogDetailTab === systemLogDetailTab);
  });
  renderSystemLogDetails();
  renderSystemLogs();
}

function closeSystemLogDetails() {
  const drawer = el('systemLogDetailDrawer');
  if (!drawer) return;
  drawer.hidden = true;
  drawer.setAttribute('aria-hidden', 'true');
  systemLogDetailRecord = null;
  renderSystemLogs();
}

function systemLogDetailField(label, value, copyValue) {
  const shown = value == null || value === '' ? '-' : String(value);
  const copy = copyValue == null ? shown : String(copyValue);
  return '<div class="system-log-detail-field"><span>' + esc(label) + '</span><div><strong>' + esc(shown) + '</strong>' +
    (copy !== '-' ? '<button type="button" class="system-log-copy-inline" data-system-log-copy="' + esc(copy) + '" aria-label="复制' + esc(label) + '">▢</button>' : '') +
    '</div></div>';
}

function renderSystemLogDetails() {
  const record = systemLogDetailRecord;
  if (!record) return;
  const summary = el('systemLogDrawerSummary');
  const content = el('systemLogDrawerContent');
  const method = String(record.method || '-').toUpperCase();
  const status = record.status == null ? '-' : String(record.status);
  const path = record.path || record.message || '-';
  if (summary) summary.innerHTML = '<div class="system-log-drawer-badges"><span class="system-log-level-badge system-log-level ' + esc(record.level || 'info') + '">' + esc(systemLogLevelName(record.level)) + '</span><time>' + esc(systemLogDateTime(record.occurredAt)) + '</time></div>' +
    '<div class="system-log-drawer-route"><span class="system-log-method">' + esc(method) + '</span><strong>' + esc(path) + '</strong></div>';
  const detail = systemLogDetailTab;
  document.querySelectorAll('[data-system-log-detail-tab]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.systemLogDetailTab === detail);
  });
  if (!content) return;
  if (detail === 'basic') {
    content.innerHTML = '<div class="system-log-detail-fields">' +
      systemLogDetailField('状态码', status) +
      systemLogDetailField('耗时', record.durationMs == null ? '-' : Number(record.durationMs).toLocaleString() + ' ms') +
      systemLogDetailField('来源 IP', record.ip, record.ip) +
      systemLogDetailField('请求 ID', record.requestId, record.requestId) +
      systemLogDetailField('类型', systemLogCategoryName(record.category)) +
      systemLogDetailField('级别', systemLogLevelName(record.level)) +
      systemLogDetailField('时间', systemLogDateTime(record.occurredAt)) +
      systemLogDetailField('操作者', record.actor) +
      systemLogDetailField('User-Agent', record.userAgent) +
      systemLogDetailField('请求路径', record.path) +
      '</div>';
  } else if (detail === 'request') {
    content.innerHTML = '<section class="system-log-detail-note"><strong>已保存的请求信息</strong><p>日志仅保留来源 IP 和 User-Agent，没有保存完整请求头、Cookie 或认证信息。</p>' +
      '<dl><div><dt>来源 IP</dt><dd>' + esc(record.ip || '-') + '</dd></div><div><dt>User-Agent</dt><dd>' + esc(record.userAgent || '-') + '</dd></div></dl></section>';
  } else if (detail === 'response') {
    content.innerHTML = '<section class="system-log-detail-note"><strong>响应摘要</strong><p>响应正文不会写入系统日志。这里展示当前日志中保留的状态和说明字段。</p>' +
      '<dl><div><dt>状态码</dt><dd>' + esc(status) + '</dd></div><div><dt>说明</dt><dd>' + esc(record.message || '-') + '</dd></div><div><dt>附加详情</dt><dd>' + esc(record.details || '-') + '</dd></div></dl></section>';
  } else {
    const raw = JSON.stringify(record, null, 2);
    content.innerHTML = '<section class="system-log-json-card"><div class="system-log-json-heading"><strong>原始日志 (JSON)</strong><button type="button" class="secondary" data-system-log-copy="' + esc(raw) + '">▢ 复制</button></div><pre>' + esc(raw) + '</pre></section>';
  }
  content.querySelectorAll('[data-system-log-copy]').forEach(function(button) {
    button.addEventListener('click', function() { copySystemLogText(button.dataset.systemLogCopy || ''); });
  });
}

async function copySystemLogText(value) {
  try {
    await navigator.clipboard.writeText(String(value || ''));
    show('日志信息已复制');
  } catch (_) {
    show('复制失败，请检查浏览器剪贴板权限', true);
  }
}

function setSystemLogAutoRefresh(enabled) {
  if (systemLogsAutoRefreshTimer) clearInterval(systemLogsAutoRefreshTimer);
  systemLogsAutoRefreshTimer = null;
  if (!enabled) return;
  systemLogsAutoRefreshTimer = setInterval(function() {
    const panel = document.querySelector('[data-panel="system-logs"]');
    if (!adminToken || !panel || !panel.classList.contains('active')) return;
    loadSystemLogs(true);
  }, 10000);
}

function stopSystemLogAutoRefresh() {
  if (systemLogsAutoRefreshTimer) clearInterval(systemLogsAutoRefreshTimer);
  systemLogsAutoRefreshTimer = null;
  const toggle = el('systemLogsAutoRefreshToggle');
  if (toggle) toggle.checked = false;
}

document.querySelectorAll('[data-system-log-category]').forEach(function(button) {
  button.addEventListener('click', function() {
    selectSystemLogCategory(button.dataset.systemLogCategory || '');
  });
});

document.querySelectorAll('[data-system-log-detail-tab]').forEach(function(button) {
  button.addEventListener('click', function() {
    systemLogDetailTab = button.dataset.systemLogDetailTab || 'basic';
    renderSystemLogDetails();
  });
});

el('systemLogsPager').addEventListener('click', function(event) {
  const button = event.target.closest('[data-system-log-page]');
  if (button && !button.disabled) changeSystemLogsPage(button.dataset.systemLogPage);
});

el('systemLogSearch').addEventListener('input', function() {
  if (systemLogsSearchTimer) clearTimeout(systemLogsSearchTimer);
  systemLogsSearchTimer = setTimeout(applySystemLogFilters, 350);
});

['systemLogLevel', 'systemLogStatus'].forEach(function(id) {
  el(id).addEventListener('change', applySystemLogFilters);
});

function scheduleSystemLogRangeUpdate() {
  if (systemLogsRangeTimer) clearTimeout(systemLogsRangeTimer);
  systemLogsRangeTimer = setTimeout(function() {
    systemLogsRangeTimer = null;
    applySystemLogFilters();
  }, 120);
}

['systemLogStartDate', 'systemLogEndDate'].forEach(function(id) {
  el(id).addEventListener('input', scheduleSystemLogRangeUpdate);
  el(id).addEventListener('change', scheduleSystemLogRangeUpdate);
});

el('systemLogsPageSize').addEventListener('change', function() {
  systemLogsOffset = 0;
  applySystemLogFilters();
});

el('systemLogsAutoRefreshToggle').addEventListener('change', function(event) {
  setSystemLogAutoRefresh(event.target.checked);
});

document.addEventListener('keydown', function(event) {
  if (event.key === 'Escape' && systemLogDetailRecord) closeSystemLogDetails();
});

setDefaultSystemLogDateRange();
syncSystemLogCategorySelection();
