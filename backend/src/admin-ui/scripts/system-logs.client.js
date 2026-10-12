let systemLogsReloadQueued = false;
let systemLogsAutoRefreshTimer = null;
let systemLogsSearchTimer = null;
let systemLogsRangeTimer = null;
let systemLogsFilterRevision = 0;
let systemLogsEndpointPath = '';
let systemLogsEndpointMethod = '';
let systemLogEndpointExpandedPaths = new Set(['request:/', 'request:/api', 'outbound:/']);
let systemLogEndpointLoading = false;
let systemLogEndpointError = '';
let systemLogEndpointRowsCache = null;
let systemLogEndpointRowsCacheKey = '';
let systemLogDetailRecord = null;
let systemLogDetailTab = 'basic';
let systemLogSelectedIds = new Set();
let systemLogsDeleting = false;
let systemLogRecordingSettings = null;
let systemLogRecordingSettingsLoading = false;
let systemLogPayload = null;
let systemLogPayloadLoading = false;
let systemLogPayloadError = '';

function systemLogCategoryName(category) {
  return ({request: '入站网络', outbound: '出站网络'})[category] || '系统';
}

function systemLogIpLabel(category) {
  return category === 'outbound' ? '触发 IP' : '客户端 IP';
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

function updateSystemLogSelectionControls(records) {
  const rows = Array.isArray(records) ? records : [];
  const rowIds = new Set(rows.map(function(record) { return Number(record.id); }));
  systemLogSelectedIds.forEach(function(id) {
    if (!rowIds.has(id)) systemLogSelectedIds.delete(id);
  });
  const selectedCount = systemLogSelectedIds.size;
  const selectedOnPage = rows.reduce(function(count, record) {
    return count + (systemLogSelectedIds.has(Number(record.id)) ? 1 : 0);
  }, 0);
  const allSelected = rows.length > 0 && selectedOnPage === rows.length;
  const busy = systemLogsLoading || systemLogsDeleting;
  const selectionSummary = el('systemLogsSelectionSummary');
  const selectPageButton = el('systemLogsSelectPageButton');
  const deleteButton = el('systemLogsDeleteSelectedButton');
  const pageCheckbox = document.querySelector('[data-system-log-select-page]');

  if (selectionSummary) selectionSummary.textContent = selectedCount ? '已选 ' + selectedCount + ' 条' : '未选择日志';
  if (selectPageButton) {
    selectPageButton.disabled = busy || rows.length === 0;
    selectPageButton.textContent = allSelected ? '取消本页全选' : '全选当前页';
  }
  if (deleteButton) {
    deleteButton.disabled = busy || selectedCount === 0;
    deleteButton.textContent = selectedCount ? '删除所选（' + selectedCount + '）' : '删除所选';
  }
  if (pageCheckbox) {
    pageCheckbox.checked = allSelected;
    pageCheckbox.indeterminate = selectedOnPage > 0 && !allSelected;
    pageCheckbox.disabled = busy || rows.length === 0;
  }
  document.querySelectorAll('[data-system-log-select-id]').forEach(function(checkbox) {
    checkbox.checked = systemLogSelectedIds.has(Number(checkbox.dataset.systemLogSelectId));
    checkbox.disabled = busy;
  });
}

function clearSystemLogSelection() {
  systemLogSelectedIds.clear();
  updateSystemLogSelectionControls(systemLogsData && systemLogsData.records || []);
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

  const payload = systemLogsData || {records: [], total: 0, limit: 20, offset: systemLogsOffset};
  const records = Array.isArray(payload.records) ? payload.records : [];
  const total = Number(payload.total) || 0;
  const limit = Number(payload.limit) || 20;
  const offset = Number(payload.offset) || 0;
  const page = Math.floor(offset / limit) + 1;
  const pageCount = Math.max(1, Math.ceil(total / limit));
  if (summary) summary.textContent = '共 ' + total.toLocaleString() + ' 条日志';
  if (pageStatus) pageStatus.textContent = total
    ? '显示 ' + (offset + 1) + '–' + Math.min(offset + records.length, total) + ' 条，共 ' + total.toLocaleString() + ' 条'
    : '暂无日志';
  renderSystemLogsPager(page, pageCount, total);
  renderSystemLogEndpointNavigation(payload.endpointsByCategory);

  if (!records.length) {
    host.innerHTML = '<div class="system-logs-empty">' + (total ? '当前筛选条件没有日志' : '暂无网络日志') + '</div>';
    updateSystemLogSelectionControls(records);
    return;
  }

  const ipLabel = systemLogIpLabel(records[0].category);
  host.innerHTML = '<table class="system-logs-table"><thead><tr>' +
    '<th class="system-log-select-cell"><input type="checkbox" class="system-logs-select-checkbox" data-system-log-select-page aria-label="全选当前页网络日志"></th>' +
    '<th>时间</th><th>方法</th><th>路径</th><th>状态</th><th>耗时</th><th>' + esc(ipLabel) + '</th><th>更多</th>' +
    '</tr></thead><tbody>' + records.map(function(record) {
      const status = record.status == null ? '-' : String(record.status);
      const duration = record.durationMs == null ? '-' : Number(record.durationMs).toLocaleString() + ' ms';
      const method = String(record.method || '-').toUpperCase();
      const path = String(record.path || record.message || '-');
      const selected = systemLogDetailRecord && systemLogDetailRecord.id === record.id;
      return '<tr class="' + (selected ? 'selected' : '') + '">' +
        '<td class="system-log-select-cell"><input type="checkbox" class="system-logs-select-checkbox" data-system-log-select-id="' + Number(record.id) + '" aria-label="选择网络日志 ' + Number(record.id) + '"></td>' +
        '<td class="system-logs-time">' + esc(systemLogDateTime(record.occurredAt)) + '</td>' +
        '<td><span class="system-log-method">' + esc(method) + '</span></td>' +
        '<td class="system-logs-path-cell"><span class="system-log-path-text" title="' + esc(path) + '">' + esc(path) + '</span></td>' +
        '<td><span class="system-log-status ' + systemLogStatusClass(record.status) + '">' + esc(status) + '</span></td>' +
        '<td class="system-logs-duration ' + (Number(record.durationMs) >= 500 ? 'slow' : '') + '">' + esc(duration) + '</td>' +
        '<td><span class="system-log-ip" title="' + esc(record.ip || '-') + '">' + esc(record.ip || '-') + '</span></td>' +
        '<td><button type="button" class="system-log-view-button" data-system-log-record-id="' + Number(record.id) + '">更多 <span aria-hidden="true">›</span></button></td>' +
      '</tr>';
    }).join('') + '</tbody></table>';

  host.querySelectorAll('[data-system-log-record-id]').forEach(function(button) {
    button.addEventListener('click', function() {
      openSystemLogDetailsById(button.dataset.systemLogRecordId);
    });
  });
  host.querySelectorAll('[data-system-log-select-id]').forEach(function(checkbox) {
    checkbox.addEventListener('change', function() {
      const id = Number(checkbox.dataset.systemLogSelectId);
      if (checkbox.checked) systemLogSelectedIds.add(id);
      else systemLogSelectedIds.delete(id);
      updateSystemLogSelectionControls(records);
    });
  });
  const pageCheckbox = host.querySelector('[data-system-log-select-page]');
  if (pageCheckbox) {
    pageCheckbox.addEventListener('change', function() {
      records.forEach(function(record) {
        const id = Number(record.id);
        if (pageCheckbox.checked) systemLogSelectedIds.add(id);
        else systemLogSelectedIds.delete(id);
      });
      updateSystemLogSelectionControls(records);
    });
  }
  updateSystemLogSelectionControls(records);
}

async function loadSystemLogs(force) {
  if (systemLogsDeleting) return;
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
  updateSystemLogSelectionControls(systemLogsData && systemLogsData.records || []);
  if (host) host.innerHTML = '<div class="loading-state">正在读取网络日志</div>';
  const pageSize = Number(el('systemLogsPageSize') && el('systemLogsPageSize').value) || 20;
  const query = new URLSearchParams({limit: String(pageSize), offset: String(systemLogsOffset)});
  const status = el('systemLogStatus').value;
  const search = el('systemLogSearch').value.trim();
  const startDate = el('systemLogStartDate').value;
  const endDate = el('systemLogEndDate').value;
  const endpointCacheKey = startDate + '|' + endDate;
  if (systemLogsCategory) query.set('category', systemLogsCategory);
  if (status) query.set('status', status);
  if (search) query.set('q', search);
  if (startDate) query.set('startDate', startDate);
  if (endDate) query.set('endDate', endDate);
  if (systemLogsEndpointPath) {
    query.set('endpointPath', systemLogsEndpointPath);
    query.set('endpointMethod', systemLogsEndpointMethod);
  }
  query.set('includeEndpoints', 'true');
  const hasEndpointGroups = function(groups) {
    return groups && Array.isArray(groups.request) && Array.isArray(groups.outbound);
  };
  systemLogEndpointLoading = systemLogEndpointRowsCacheKey !== endpointCacheKey || !hasEndpointGroups(systemLogEndpointRowsCache);
  systemLogEndpointError = '';
  if (systemLogEndpointLoading) renderSystemLogEndpointNavigation();

  try {
    const response = await fetch(ADMIN_API_BASE + '/system-logs?' + query.toString(), {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) {
      throw new Error(adminErrorMessage(data.error, '网络日志读取失败（HTTP ' + response.status + '）'));
    }
    if (filterRevision !== systemLogsFilterRevision) return;
    const endpointGroups = data.endpointsByCategory;
    if (hasEndpointGroups(endpointGroups)) {
      systemLogEndpointRowsCache = {
        request: endpointGroups.request,
        outbound: endpointGroups.outbound,
      };
    } else {
      const legacyEndpoints = Array.isArray(data.endpoints) ? data.endpoints : [];
      systemLogEndpointRowsCache = {
        request: systemLogsCategory === 'request' ? legacyEndpoints : [],
        outbound: systemLogsCategory === 'outbound' ? legacyEndpoints : [],
      };
    }
    systemLogEndpointRowsCacheKey = endpointCacheKey;
    systemLogEndpointLoading = false;
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
    if (filterRevision === systemLogsFilterRevision) {
      systemLogEndpointLoading = false;
      const hasCurrentEndpointCache = systemLogEndpointRowsCacheKey === endpointCacheKey && hasEndpointGroups(systemLogEndpointRowsCache);
      systemLogEndpointError = hasCurrentEndpointCache ? '' : error.message || '端点统计读取失败';
      renderSystemLogEndpointNavigation();
    }
    if (filterRevision === systemLogsFilterRevision && host) {
      host.innerHTML = '<div class="error-state">' + esc(error.message || '网络日志读取失败') + '</div>';
      updateSystemLogSelectionControls([]);
    }
  } finally {
    systemLogsLoading = false;
    if (systemLogsReloadQueued) {
      systemLogsReloadQueued = false;
      systemLogsData = null;
      loadSystemLogs(true);
    } else {
      updateSystemLogSelectionControls(systemLogsData && systemLogsData.records || []);
    }
  }
}

function systemLogCreateEndpointNode(name, path) {
  return {name: name, path: path, requestCount: 0, routes: [], children: new Map()};
}

function systemLogBuildEndpointTree(endpoints) {
  const root = systemLogCreateEndpointNode('/', '/');
  (Array.isArray(endpoints) ? endpoints : []).forEach(function(endpoint) {
    const method = String(endpoint.method || '-').toUpperCase();
    const rawPath = String(endpoint.path || '/');
    const path = rawPath.charAt(0) === '/' ? rawPath : '/' + rawPath;
    const route = {method: method, path: path, requestCount: Number(endpoint.requestCount) || 0};
    const segments = path.split('/').filter(Boolean);
    let node = root;
    node.requestCount += route.requestCount;
    segments.forEach(function(segment) {
      const childPath = node.path === '/' ? '/' + segment : node.path + '/' + segment;
      if (!node.children.has(segment)) node.children.set(segment, systemLogCreateEndpointNode(segment, childPath));
      node = node.children.get(segment);
      node.requestCount += route.requestCount;
    });
    node.routes.push(route);
  });
  return root;
}

function systemLogEndpointNodeMatches(node, query) {
  if (!query) return true;
  if (node.routes.some(function(route) {
    return (route.method + ' ' + route.path).toLowerCase().includes(query);
  })) return true;
  return Array.from(node.children.values()).some(function(child) {
    return systemLogEndpointNodeMatches(child, query);
  });
}

function systemLogEndpointRouteMarkup(route, category) {
  const selected = systemLogsCategory === category && systemLogsEndpointPath === route.path && systemLogsEndpointMethod === route.method;
  return '<button type="button" class="system-log-endpoint-item' + (selected ? ' active' : '') + '"' +
    (selected ? ' aria-current="page"' : '') +
    ' data-system-log-endpoint-category="' + category + '" data-system-log-endpoint-method="' + esc(route.method) + '" data-system-log-endpoint-path="' + esc(route.path) + '"' +
    ' title="' + esc(route.method + ' ' + route.path) + '"><span class="system-log-endpoint-method">' + esc(route.method) +
    '</span><span class="system-log-endpoint-path">' + esc(route.path) + '</span><span class="system-log-endpoint-count">' +
    route.requestCount.toLocaleString() + '</span></button>';
}

function systemLogEndpointEntriesMarkup(node, query, category) {
  const entries = [];
  node.routes.filter(function(route) {
    return !query || (route.method + ' ' + route.path).toLowerCase().includes(query);
  }).forEach(function(route) {
    entries.push({kind: 'route', count: route.requestCount, name: route.path, markup: systemLogEndpointRouteMarkup(route, category)});
  });
  Array.from(node.children.values()).filter(function(child) {
    return systemLogEndpointNodeMatches(child, query);
  }).forEach(function(child) {
    if (child.children.size) {
      entries.push({kind: 'folder', count: child.requestCount, name: child.name, node: child});
    } else {
      child.routes.filter(function(route) {
        return !query || (route.method + ' ' + route.path).toLowerCase().includes(query);
      }).forEach(function(route) {
        entries.push({kind: 'route', count: route.requestCount, name: route.path, markup: systemLogEndpointRouteMarkup(route, category)});
      });
    }
  });
  entries.sort(function(left, right) {
    return right.count - left.count || left.name.localeCompare(right.name);
  });
  return entries.map(function(entry) {
    return entry.kind === 'folder'
      ? systemLogEndpointFolderMarkup(entry.node, query, false, category)
      : entry.markup;
  }).join('');
}

function systemLogEndpointFolderMarkup(node, query, root, category) {
  const expandedKey = category + ':' + node.path;
  const expanded = Boolean(query) || systemLogEndpointExpandedPaths.has(expandedKey);
  const groupId = 'systemLogEndpointChildren_' + category + '_' + encodeURIComponent(node.path).replace(/%/g, '_');
  const label = root ? '/' : node.name;
  const folderButton = root
    ? '<div class="system-log-endpoint-root-row"><button type="button" class="system-log-tree-toggle" data-system-log-tree-toggle="' + esc(node.path) + '" data-system-log-tree-toggle-category="' + category + '" aria-expanded="' + expanded + '" aria-controls="' + groupId + '" aria-label="' + (expanded ? '收起' : '展开') + '根目录"><svg class="subscription-icon" aria-hidden="true"><use href="#subscription-icon-next"></use></svg></button><button type="button" class="system-log-tree-root' + (systemLogsCategory === category && !systemLogsEndpointPath ? ' active' : '') + '" data-system-log-endpoint-root data-system-log-endpoint-category="' + category + '" title="显示全部' + systemLogCategoryName(category) + '请求"><svg class="subscription-icon" aria-hidden="true"><use href="#subscription-icon-all"></use></svg><span>' + label + '</span><span class="system-log-endpoint-count">' + node.requestCount.toLocaleString() + '</span></button></div>'
    : '<button type="button" class="system-log-tree-folder" data-system-log-tree-toggle="' + esc(node.path) + '" data-system-log-tree-toggle-category="' + category + '" aria-expanded="' + expanded + '" aria-controls="' + groupId + '"><svg class="subscription-icon system-log-tree-caret" aria-hidden="true"><use href="#subscription-icon-next"></use></svg><svg class="subscription-icon system-log-tree-folder-icon" aria-hidden="true"><use href="#subscription-icon-folder"></use></svg><span class="system-log-tree-name" title="' + esc(node.path) + '">' + esc(label) + '</span><span class="system-log-endpoint-count">' + node.requestCount.toLocaleString() + '</span></button>';
  const entries = systemLogEndpointEntriesMarkup(node, query, category);
  return '<div class="system-log-tree-branch' + (root ? ' root' : '') + '">' + folderButton +
    '<div id="' + groupId + '" class="system-log-tree-children"' + (expanded ? '' : ' hidden') + '>' + entries + '</div></div>';
}

function renderSystemLogEndpointNavigation(endpointGroups) {
  const query = String(el('systemLogEndpointSearch') && el('systemLogEndpointSearch').value || '').trim().toLowerCase();
  const activeEndpointCacheKey = (el('systemLogStartDate') && el('systemLogStartDate').value || '') + '|' +
    (el('systemLogEndDate') && el('systemLogEndDate').value || '');
  let groups = endpointGroups;
  if (!groups || !Array.isArray(groups.request) || !Array.isArray(groups.outbound)) {
    if (systemLogEndpointRowsCacheKey === activeEndpointCacheKey && systemLogEndpointRowsCache &&
      Array.isArray(systemLogEndpointRowsCache.request) && Array.isArray(systemLogEndpointRowsCache.outbound)) {
      groups = systemLogEndpointRowsCache;
    }
  }
  const validGroups = groups && Array.isArray(groups.request) && Array.isArray(groups.outbound);
  const categories = [
    {key: 'request', listId: 'systemLogInboundEndpointList', summaryId: 'systemLogInboundEndpointSummary'},
    {key: 'outbound', listId: 'systemLogOutboundEndpointList', summaryId: 'systemLogOutboundEndpointSummary'},
  ];
  categories.forEach(function(item) {
    const list = el(item.listId);
    const summary = el(item.summaryId);
    if (!list) return;
    if (!validGroups && systemLogEndpointLoading) {
      if (summary) summary.textContent = '统计中…';
      list.innerHTML = '<div class="system-log-endpoint-state">正在统计' + systemLogCategoryName(item.key) + '端点…</div>';
      return;
    }
    if (!validGroups && systemLogEndpointError) {
      if (summary) summary.textContent = '';
      list.innerHTML = '<div class="system-log-endpoint-state error">' + esc(systemLogEndpointError) + '</div>';
      return;
    }
    const rows = validGroups ? groups[item.key] : [];
    const visibleCount = rows.filter(function(route) {
      return !query || (String(route.method || '') + ' ' + String(route.path || '/')).toLowerCase().includes(query);
    }).length;
    if (summary) summary.textContent = query
      ? visibleCount.toLocaleString() + ' / ' + rows.length.toLocaleString() + ' 个端点'
      : rows.length.toLocaleString() + ' 个端点';
    if (!rows.length) {
      list.innerHTML = '<div class="system-log-endpoint-state">当前时间范围暂无' + systemLogCategoryName(item.key) + '端点</div>';
      return;
    }
    if (!visibleCount) {
      list.innerHTML = '<div class="system-log-endpoint-state">没有匹配的端点</div>';
      return;
    }
    list.innerHTML = systemLogEndpointFolderMarkup(systemLogBuildEndpointTree(rows), query, true, item.key);
  });
}

function selectSystemLogEndpoint(category, method, path) {
  if (!path) return;
  systemLogsCategory = category === 'outbound' ? 'outbound' : 'request';
  systemLogsEndpointMethod = method;
  systemLogsEndpointPath = path;
  renderSystemLogEndpointNavigation();
  applySystemLogFilters();
}

function selectSystemLogAllEndpoints(category) {
  systemLogsCategory = category === 'outbound' ? 'outbound' : 'request';
  systemLogsEndpointMethod = '';
  systemLogsEndpointPath = '';
  renderSystemLogEndpointNavigation();
  applySystemLogFilters();
}

function renderSystemLogRecordingSettings() {
  if (!systemLogRecordingSettings) return;
  document.querySelectorAll('[data-system-log-recording]').forEach(function(input) {
    const category = input.dataset.systemLogRecording;
    input.checked = systemLogRecordingSettings.categories[category] !== false;
    input.disabled = false;
  });
  document.querySelectorAll('[data-system-log-detail]').forEach(function(input) {
    const detail = input.dataset.systemLogDetail;
    input.checked = systemLogRecordingSettings.details[detail] === true;
    input.disabled = false;
  });
  const schedule = systemLogRecordingSettings.schedule || {enabled: false, startTime: '00:00', endTime: '23:59'};
  const scheduleToggle = el('systemLogScheduleToggle');
  if (scheduleToggle) {
    scheduleToggle.checked = schedule.enabled === true;
    scheduleToggle.disabled = false;
  }
  document.querySelectorAll('[data-system-log-schedule-time]').forEach(function(input) {
    input.value = schedule[input.dataset.systemLogScheduleTime] || '';
    input.disabled = schedule.enabled !== true;
  });
}

function disableSystemLogSettingControls(disabled) {
  document.querySelectorAll('[data-system-log-recording], [data-system-log-detail], [data-system-log-schedule-toggle], [data-system-log-schedule-time]').forEach(function(input) {
    input.disabled = disabled;
  });
}

async function loadSystemLogRecordingSettings(force) {
  if (systemLogRecordingSettingsLoading || (systemLogRecordingSettings && !force)) return;
  systemLogRecordingSettingsLoading = true;
  disableSystemLogSettingControls(true);
  try {
    const response = await fetch(ADMIN_API_BASE + '/system-log-settings', {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok || !data.settings) {
      throw new Error(adminErrorMessage(data.error, '日志记录设置读取失败'));
    }
    systemLogRecordingSettings = data.settings;
    renderSystemLogRecordingSettings();
  } catch (error) {
    show(error.message || '日志记录设置读取失败', true);
  } finally {
    systemLogRecordingSettingsLoading = false;
  }
}

async function updateSystemLogRecordingSetting(input) {
  const category = input.dataset.systemLogRecording;
  if (!category || !systemLogRecordingSettings) return;
  const previous = systemLogRecordingSettings.categories[category] !== false;
  const enabled = input.checked;
  disableSystemLogSettingControls(true);
  try {
    const response = await fetch(ADMIN_API_BASE + '/system-log-settings', {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify({category: category, enabled: enabled}),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok || !data.settings) {
      throw new Error(adminErrorMessage(data.error, '日志记录设置保存失败'));
    }
    systemLogRecordingSettings = data.settings;
    renderSystemLogRecordingSettings();
    show(enabled ? '已开启该类型日志记录' : '已关闭该类型日志记录');
  } catch (error) {
    systemLogRecordingSettings.categories[category] = previous;
    renderSystemLogRecordingSettings();
    show(error.message || '日志记录设置保存失败', true);
  }
}

async function updateSystemLogDetailSetting(input) {
  if (!systemLogRecordingSettings) return;
  const detail = input.dataset.systemLogDetail;
  if (!detail) return;
  const previous = systemLogRecordingSettings.details[detail] === true;
  const enabled = input.checked;
  disableSystemLogSettingControls(true);
  try {
    const response = await fetch(ADMIN_API_BASE + '/system-log-settings', {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify({detail: detail, enabled: enabled}),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok || !data.settings) {
      throw new Error(adminErrorMessage(data.error, '详细记录设置保存失败'));
    }
    systemLogRecordingSettings = data.settings;
    renderSystemLogRecordingSettings();
    show(enabled ? '已开启该项详细记录' : '已关闭该项详细记录');
  } catch (error) {
    systemLogRecordingSettings.details[detail] = previous;
    renderSystemLogRecordingSettings();
    show(error.message || '详细记录设置保存失败', true);
  }
}

async function updateSystemLogRecordingSchedule() {
  if (!systemLogRecordingSettings) return;
  const scheduleToggle = el('systemLogScheduleToggle');
  const startInput = document.querySelector('[data-system-log-schedule-time="startTime"]');
  const endInput = document.querySelector('[data-system-log-schedule-time="endTime"]');
  const schedule = {
    enabled: scheduleToggle.checked,
    startTime: startInput.value,
    endTime: endInput.value
  };
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(schedule.startTime) ||
      !/^([01]\d|2[0-3]):[0-5]\d$/.test(schedule.endTime) ||
      schedule.startTime === schedule.endTime) {
    renderSystemLogRecordingSettings();
    show('开始时间和结束时间必须有效且不能相同', true);
    return;
  }
  disableSystemLogSettingControls(true);
  try {
    const response = await fetch(ADMIN_API_BASE + '/system-log-settings', {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify({schedule: schedule}),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok || !data.settings) {
      throw new Error(adminErrorMessage(data.error, '定时记录设置保存失败'));
    }
    systemLogRecordingSettings = data.settings;
    renderSystemLogRecordingSettings();
    show(schedule.enabled ? '定时记录已开启' : '定时记录已关闭');
  } catch (error) {
    renderSystemLogRecordingSettings();
    show(error.message || '定时记录设置保存失败', true);
  }
}

function openSystemLogSettings() {
  const modal = el('systemLogSettingsModal');
  if (!modal) return;
  modal.hidden = false;
  modal.setAttribute('aria-hidden', 'false');
  loadSystemLogRecordingSettings(true);
  const closeButton = modal.querySelector('[data-system-log-settings-close]');
  if (closeButton) closeButton.focus();
}

function closeSystemLogSettings() {
  const modal = el('systemLogSettingsModal');
  if (!modal) return;
  modal.hidden = true;
  modal.setAttribute('aria-hidden', 'true');
  el('systemLogsSettingsButton').focus();
}

function applySystemLogFilters() {
  clearSystemLogSelection();
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

function systemLogBeijingDateTime(date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  }).formatToParts(date);
  const values = {};
  parts.forEach(function(part) { values[part.type] = part.value; });
  return values.year + '-' + values.month + '-' + values.day + 'T' + values.hour + ':' + values.minute;
}

function shiftSystemLogDate(value, days) {
  const date = new Date(value + 'T00:00:00.000Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function setDefaultSystemLogDateRange() {
  const now = new Date();
  const endDateTime = systemLogBeijingDateTime(now);
  const startDate = shiftSystemLogDate(systemLogBeijingDate(now), -9);
  el('systemLogEndDate').value = endDateTime;
  el('systemLogStartDate').value = startDate + 'T00:00';
}

function resetSystemLogFilters() {
  el('systemLogSearch').value = '';
  el('systemLogStatus').value = '';
  systemLogsCategory = 'request';
  systemLogsEndpointPath = '';
  systemLogsEndpointMethod = '';
  setDefaultSystemLogDateRange();
  applySystemLogFilters();
}

function changeSystemLogsPage(page) {
  const total = Number(systemLogsData && systemLogsData.total) || 0;
  const limit = Number(systemLogsData && systemLogsData.limit) || 20;
  const pageCount = Math.max(1, Math.ceil(total / limit));
  const nextPage = Math.max(1, Math.min(pageCount, Number(page) || 1));
  if (nextPage === Math.floor(systemLogsOffset / limit) + 1) return;
  clearSystemLogSelection();
  systemLogsFilterRevision += 1;
  if (systemLogDetailRecord) closeSystemLogDetails();
  systemLogsOffset = (nextPage - 1) * limit;
  systemLogsData = null;
  loadSystemLogs(true);
}

async function deleteSelectedSystemLogs() {
  const currentRecords = systemLogsData && Array.isArray(systemLogsData.records) ? systemLogsData.records : [];
  const ids = currentRecords.map(function(record) { return Number(record.id); })
    .filter(function(id) { return systemLogSelectedIds.has(id); });
  if (!ids.length || systemLogsDeleting || systemLogsLoading) return;
  if (!window.confirm('确认删除选中的 ' + ids.length + ' 条网络日志及其请求/响应详细内容？此操作不可撤销。')) return;

  systemLogsDeleting = true;
  updateSystemLogSelectionControls(currentRecords);
  try {
    const response = await fetch(ADMIN_API_BASE + '/system-logs', {
      method: 'DELETE',
      headers: headers(),
      body: JSON.stringify({ids: ids}),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok || data.ok === false) {
      throw new Error(adminErrorMessage(data.error, '网络日志删除失败（HTTP ' + response.status + '）'));
    }

    const deletedCount = Math.max(0, Number(data.deleted) || 0);
    if (systemLogDetailRecord && ids.includes(Number(systemLogDetailRecord.id))) closeSystemLogDetails();
    systemLogSelectedIds.clear();
    systemLogEndpointRowsCache = null;
    systemLogEndpointRowsCacheKey = '';
    const pageSize = Number(el('systemLogsPageSize').value) || 20;
    const remaining = Math.max(0, (Number(systemLogsData && systemLogsData.total) || 0) - deletedCount);
    const lastPageOffset = Math.max(0, (Math.ceil(remaining / pageSize) - 1) * pageSize);
    systemLogsOffset = Math.min(systemLogsOffset, lastPageOffset);
    systemLogsData = null;
    systemLogsDeleting = false;
    await loadSystemLogs(true);
    show('已删除 ' + deletedCount + ' 条网络日志');
  } catch (error) {
    show(error.message || '网络日志删除失败', true);
  } finally {
    systemLogsDeleting = false;
    updateSystemLogSelectionControls(systemLogsData && systemLogsData.records || []);
  }
}

function toggleSystemLogPageSelection() {
  if (systemLogsDeleting || systemLogsLoading) return;
  const records = systemLogsData && Array.isArray(systemLogsData.records) ? systemLogsData.records : [];
  if (!records.length) return;
  const allSelected = records.every(function(record) { return systemLogSelectedIds.has(Number(record.id)); });
  records.forEach(function(record) {
    const id = Number(record.id);
    if (allSelected) systemLogSelectedIds.delete(id);
    else systemLogSelectedIds.add(id);
  });
  updateSystemLogSelectionControls(records);
}

function openSystemLogDetailsById(id) {
  const record = (systemLogsData && systemLogsData.records || []).find(function(item) {
    return Number(item.id) === Number(id);
  });
  if (!record) return;
  systemLogDetailRecord = record;
  systemLogDetailTab = 'basic';
  systemLogPayload = null;
  systemLogPayloadError = '';
  systemLogPayloadLoading = Boolean(record.requestId);
  const drawer = el('systemLogDetailDrawer');
  drawer.hidden = false;
  drawer.setAttribute('aria-hidden', 'false');
  document.querySelectorAll('[data-system-log-detail-tab]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.systemLogDetailTab === systemLogDetailTab);
  });
  renderSystemLogDetails();
  renderSystemLogs();
  if (systemLogPayloadLoading) loadSystemLogPayload(record.requestId);
}

function closeSystemLogDetails() {
  const drawer = el('systemLogDetailDrawer');
  if (!drawer) return;
  drawer.hidden = true;
  drawer.setAttribute('aria-hidden', 'true');
  systemLogDetailRecord = null;
  systemLogPayload = null;
  systemLogPayloadLoading = false;
  systemLogPayloadError = '';
  renderSystemLogs();
}

async function loadSystemLogPayload(requestId) {
  const requestedId = String(requestId || '');
  try {
    const query = new URLSearchParams({requestId: requestedId});
    const response = await fetch(ADMIN_API_BASE + '/system-logs/payload?' + query.toString(), {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok || data.ok === false) {
      throw new Error(adminErrorMessage(data.error, '请求与响应详情读取失败'));
    }
    if (systemLogDetailRecord && systemLogDetailRecord.requestId === requestedId) {
      systemLogPayload = data.payload || null;
    }
  } catch (error) {
    if (systemLogDetailRecord && systemLogDetailRecord.requestId === requestedId) {
      systemLogPayloadError = error.message || '请求与响应详情读取失败';
    }
  } finally {
    if (systemLogDetailRecord && systemLogDetailRecord.requestId === requestedId) {
      systemLogPayloadLoading = false;
      renderSystemLogDetails();
    }
  }
}

function systemLogDetailField(label, value, copyValue) {
  const shown = value == null || value === '' ? '-' : String(value);
  const copy = copyValue == null ? shown : String(copyValue);
  return '<div class="system-log-detail-field"><span>' + esc(label) + '</span><div><strong>' + esc(shown) + '</strong>' +
    (copy !== '-' ? '<button type="button" class="system-log-copy-inline" data-system-log-copy="' + esc(copy) + '" aria-label="复制' + esc(label) + '" title="复制' + esc(label) + '">' + systemLogCopyIcon() + '</button>' : '') +
    '</div></div>';
}

function systemLogCopyIcon() {
  return '<svg class="system-log-copy-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"></path></svg>';
}

function systemLogPayloadText(kind) {
  if (!systemLogPayload || !systemLogPayload[kind]) return '';
  const message = systemLogPayload[kind];
  const lines = [];
  if (kind === 'request') {
    lines.push(String(message.method || '-') + ' ' + String(message.url || '(URL 未记录)'));
  } else {
    lines.push('HTTP ' + String(message.status || '-') + ' ' + String(message.statusText || ''));
  }
  lines.push('', 'Headers:');
  if (Array.isArray(message.headers)) {
    const headers = message.headers.map(function(header) {
      return String(header[0] || '') + ': ' + String(header[1] || '');
    }).join('\n');
    lines.push(headers || '(empty)');
  } else {
    lines.push('(未记录)');
  }
  lines.push('');
  if (message.body) {
    lines.push('Body (' + String(message.body.contentType || 'unknown content type') + '; ' + String(message.body.encoding || 'utf-8') + '):', String(message.body.data || ''));
  } else {
    lines.push('Body: (未记录)');
  }
  return lines.join('\n');
}

function systemLogPayloadCard(title, kind) {
  const text = systemLogPayloadText(kind);
  return '<section class="system-log-json-card"><div class="system-log-json-heading"><strong>' + esc(title) + '</strong><button type="button" class="secondary" data-system-log-copy-full="' + esc(kind) + '">复制</button></div><pre>' + esc(text) + '</pre></section>';
}

function renderSystemLogDetails() {
  const record = systemLogDetailRecord;
  if (!record) return;
  const content = el('systemLogDrawerContent');
  const detail = systemLogDetailTab;
  document.querySelectorAll('[data-system-log-detail-tab]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.systemLogDetailTab === detail);
  });
  if (!content) return;
  if (detail === 'basic') {
    content.innerHTML = '<div class="system-log-detail-fields">' +
      systemLogDetailField('类型', systemLogCategoryName(record.category)) +
      systemLogDetailField('用户 UID', record.userUid, record.userUid) +
      systemLogDetailField('设备 ID', record.deviceId, record.deviceId) +
      systemLogDetailField('身份来源', record.identitySource === 'login' ? '密码登录' : record.identitySource === 'device' ? '设备关联' : '-') +
      systemLogDetailField('请求关联 ID', record.requestId, record.requestId) +
      systemLogDetailField('操作者', record.actor) +
      systemLogDetailField('User-Agent', record.userAgent) +
      '</div>';
  } else if (detail === 'request' && systemLogPayloadLoading) {
    content.innerHTML = '<div class="loading-state">正在读取请求详细内容…</div>';
  } else if (detail === 'request' && systemLogPayloadError) {
    content.innerHTML = '<div class="error-state">' + esc(systemLogPayloadError) + '</div>';
  } else if (detail === 'request' && systemLogPayload && systemLogPayload.request) {
    content.innerHTML = systemLogPayloadCard('已记录的请求内容', 'request');
  } else if (detail === 'request') {
    content.innerHTML = '<section class="system-log-detail-note"><strong>请求摘要</strong><p>完整请求内容未单独保存；以下仅展示 User-Agent。</p>' +
      '<dl><div><dt>User-Agent</dt><dd>' + esc(record.userAgent || '-') + '</dd></div></dl></section>';
  } else if (detail === 'response' && systemLogPayloadLoading) {
    content.innerHTML = '<div class="loading-state">正在读取响应详细内容…</div>';
  } else if (detail === 'response' && systemLogPayloadError) {
    content.innerHTML = '<div class="error-state">' + esc(systemLogPayloadError) + '</div>';
  } else if (detail === 'response' && systemLogPayload && systemLogPayload.response) {
    content.innerHTML = systemLogPayloadCard('已记录的响应内容', 'response');
  } else if (detail === 'response') {
    content.innerHTML = '<section class="system-log-detail-note"><strong>响应摘要</strong><p>完整响应内容未单独保存，以下为日志中额外保留的说明字段。</p>' +
      '<dl><div><dt>说明</dt><dd>' + esc(record.message || '-') + '</dd></div><div><dt>附加详情</dt><dd>' + esc(record.details || '-') + '</dd></div></dl></section>';
  } else {
    const raw = JSON.stringify(record, null, 2);
    content.innerHTML = '<section class="system-log-json-card"><div class="system-log-json-heading"><strong>原始日志 (JSON)</strong><button type="button" class="secondary" data-system-log-copy="' + esc(raw) + '" aria-label="复制原始日志" title="复制原始日志">' + systemLogCopyIcon() + ' 复制</button></div><pre>' + esc(raw) + '</pre></section>';
  }
  content.querySelectorAll('[data-system-log-copy]').forEach(function(button) {
    button.addEventListener('click', function() { copySystemLogText(button.dataset.systemLogCopy || ''); });
  });
  content.querySelectorAll('[data-system-log-copy-full]').forEach(function(button) {
    button.addEventListener('click', function() {
      copySystemLogText(systemLogPayloadText(button.dataset.systemLogCopyFull || ''));
    });
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

el('systemLogEndpointGroups').addEventListener('click', function(event) {
  const folderToggle = event.target.closest('[data-system-log-tree-toggle]');
  if (folderToggle) {
    const path = folderToggle.dataset.systemLogTreeToggle || '/';
    const category = folderToggle.dataset.systemLogTreeToggleCategory === 'outbound' ? 'outbound' : 'request';
    const expandedKey = category + ':' + path;
    const expanded = folderToggle.getAttribute('aria-expanded') === 'true';
    const nextExpanded = !expanded;
    if (nextExpanded) systemLogEndpointExpandedPaths.add(expandedKey);
    else systemLogEndpointExpandedPaths.delete(expandedKey);
    folderToggle.setAttribute('aria-expanded', String(nextExpanded));
    folderToggle.setAttribute('aria-label', (nextExpanded ? '收起' : '展开') + (path === '/' ? '根目录' : path));
    const childGroup = el(folderToggle.getAttribute('aria-controls'));
    if (childGroup) childGroup.hidden = !nextExpanded;
    return;
  }
  const root = event.target.closest('[data-system-log-endpoint-root]');
  if (root) {
    selectSystemLogAllEndpoints(root.dataset.systemLogEndpointCategory);
    return;
  }
  const endpoint = event.target.closest('[data-system-log-endpoint-path]');
  if (endpoint) selectSystemLogEndpoint(
    endpoint.dataset.systemLogEndpointCategory,
    endpoint.dataset.systemLogEndpointMethod || '',
    endpoint.dataset.systemLogEndpointPath || '',
  );
});

el('systemLogEndpointSearch').addEventListener('input', function() {
  renderSystemLogEndpointNavigation();
});

document.querySelectorAll('[data-system-log-recording]').forEach(function(input) {
  input.addEventListener('change', function() {
    updateSystemLogRecordingSetting(input);
  });
});

document.querySelectorAll('[data-system-log-detail]').forEach(function(input) {
  input.addEventListener('change', function() {
    updateSystemLogDetailSetting(input);
  });
});

el('systemLogScheduleToggle').addEventListener('change', updateSystemLogRecordingSchedule);
document.querySelectorAll('[data-system-log-schedule-time]').forEach(function(input) {
  input.addEventListener('change', updateSystemLogRecordingSchedule);
});

el('systemLogsSettingsButton').addEventListener('click', openSystemLogSettings);
document.querySelectorAll('[data-system-log-settings-close]').forEach(function(button) {
  button.addEventListener('click', closeSystemLogSettings);
});
el('systemLogSettingsModal').addEventListener('click', function(event) {
  if (event.target === event.currentTarget || event.target.classList.contains('system-log-settings-backdrop')) {
    closeSystemLogSettings();
  }
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

el('systemLogsSelectPageButton').addEventListener('click', toggleSystemLogPageSelection);
el('systemLogsDeleteSelectedButton').addEventListener('click', deleteSelectedSystemLogs);

el('systemLogSearch').addEventListener('input', function() {
  if (systemLogsSearchTimer) clearTimeout(systemLogsSearchTimer);
  systemLogsSearchTimer = setTimeout(applySystemLogFilters, 350);
});

el('systemLogStatus').addEventListener('change', applySystemLogFilters);

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
  if (event.key !== 'Escape') return;
  const settingsModal = el('systemLogSettingsModal');
  if (settingsModal && !settingsModal.hidden) closeSystemLogSettings();
  else if (systemLogDetailRecord) closeSystemLogDetails();
});

setDefaultSystemLogDateRange();
