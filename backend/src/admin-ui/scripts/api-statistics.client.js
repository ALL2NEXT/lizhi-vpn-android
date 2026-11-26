let apiStatisticsData = null;
let apiStatisticsLoading = false;
let apiStatisticsReloadQueued = false;
let apiStatisticsRequestId = 0;
let apiStatisticsFilterTimer = null;
let apiStatisticsExpandedPaths = new Set(['request:/', 'request:/api', 'outbound:/']);

function apiStatisticsCreateNode(name, path) {
  return {name: name, path: path, requestCount: 0, routes: [], children: new Map()};
}

function apiStatisticsBuildTree(endpoints, category) {
  const root = apiStatisticsCreateNode('/', '/');
  (Array.isArray(endpoints) ? endpoints : []).forEach(function(endpoint) {
    const method = String(endpoint.method || '-').toUpperCase();
    const rawPath = String(endpoint.path || '/');
    const path = category === 'outbound'
      ? rawPath
      : (rawPath.charAt(0) === '/' ? rawPath : '/' + rawPath);
    const route = {method: method, path: path, requestCount: Number(endpoint.requestCount) || 0};
    const segments = path.split('/').filter(Boolean);
    let node = root;
    node.requestCount += route.requestCount;
    segments.forEach(function(segment) {
      const childPath = node.path === '/' ? '/' + segment : node.path + '/' + segment;
      if (!node.children.has(segment)) node.children.set(segment, apiStatisticsCreateNode(segment, childPath));
      node = node.children.get(segment);
      node.requestCount += route.requestCount;
    });
    node.routes.push(route);
  });
  return root;
}

function apiStatisticsNodeMatches(node, query) {
  if (!query) return true;
  if (node.routes.some(function(route) {
    return (route.method + ' ' + route.path).toLowerCase().includes(query);
  })) return true;
  return Array.from(node.children.values()).some(function(child) {
    return apiStatisticsNodeMatches(child, query);
  });
}

function apiStatisticsRouteMarkup(route, category) {
  return '<button type="button" class="api-statistics-endpoint-item"' +
    ' data-api-statistics-endpoint-category="' + category + '"' +
    ' data-api-statistics-endpoint-method="' + esc(route.method) + '"' +
    ' data-api-statistics-endpoint-path="' + esc(route.path) + '"' +
    ' title="点击查看此端点的网络日志"><span class="api-statistics-endpoint-method">' + esc(route.method) +
    '</span><span class="api-statistics-endpoint-path">' + esc(route.path) + '</span><span class="api-statistics-endpoint-count">' +
    route.requestCount.toLocaleString() + '</span></button>';
}

function apiStatisticsEntriesMarkup(node, query, category) {
  const entries = [];
  node.routes.filter(function(route) {
    return !query || (route.method + ' ' + route.path).toLowerCase().includes(query);
  }).forEach(function(route) {
    entries.push({kind: 'route', count: route.requestCount, name: route.path, markup: apiStatisticsRouteMarkup(route, category)});
  });
  Array.from(node.children.values()).filter(function(child) {
    return apiStatisticsNodeMatches(child, query);
  }).forEach(function(child) {
    if (child.children.size) {
      entries.push({kind: 'folder', count: child.requestCount, name: child.name, node: child});
    } else {
      child.routes.filter(function(route) {
        return !query || (route.method + ' ' + route.path).toLowerCase().includes(query);
      }).forEach(function(route) {
        entries.push({kind: 'route', count: route.requestCount, name: route.path, markup: apiStatisticsRouteMarkup(route, category)});
      });
    }
  });
  entries.sort(function(left, right) {
    return right.count - left.count || left.name.localeCompare(right.name);
  });
  return entries.map(function(entry) {
    return entry.kind === 'folder'
      ? apiStatisticsFolderMarkup(entry.node, query, false, category)
      : entry.markup;
  }).join('');
}

function apiStatisticsFolderMarkup(node, query, root, category) {
  const expandedKey = category + ':' + node.path;
  const expanded = Boolean(query) || apiStatisticsExpandedPaths.has(expandedKey);
  const groupId = 'apiStatisticsTreeChildren_' + category + '_' + encodeURIComponent(node.path).replace(/%/g, '_');
  const label = root ? '/' : node.name;
  const folderButton = root
    ? '<div class="api-statistics-endpoint-root-row"><button type="button" class="api-statistics-tree-toggle" data-api-statistics-tree-toggle="' + esc(node.path) + '" data-api-statistics-tree-category="' + category + '" aria-expanded="' + expanded + '" aria-controls="' + groupId + '" aria-label="' + (expanded ? '收起' : '展开') + '根目录"><svg class="subscription-icon" aria-hidden="true"><use href="#subscription-icon-next"></use></svg></button><button type="button" class="api-statistics-tree-root" data-api-statistics-root-category="' + category + '" title="点击查看全部' + (category === 'outbound' ? '出站' : '入站') + '网络日志"><svg class="subscription-icon" aria-hidden="true"><use href="#subscription-icon-all"></use></svg><span>' + label + '</span><span class="api-statistics-endpoint-count">' + node.requestCount.toLocaleString() + '</span></button></div>'
    : '<button type="button" class="api-statistics-tree-folder" data-api-statistics-tree-toggle="' + esc(node.path) + '" data-api-statistics-tree-category="' + category + '" aria-expanded="' + expanded + '" aria-controls="' + groupId + '"><svg class="subscription-icon api-statistics-tree-caret" aria-hidden="true"><use href="#subscription-icon-next"></use></svg><svg class="subscription-icon api-statistics-tree-folder-icon" aria-hidden="true"><use href="#subscription-icon-folder"></use></svg><span class="api-statistics-tree-name" title="' + esc(node.path) + '">' + esc(label) + '</span><span class="api-statistics-endpoint-count">' + node.requestCount.toLocaleString() + '</span></button>';
  const entries = apiStatisticsEntriesMarkup(node, query, category);
  return '<div class="api-statistics-tree-branch' + (root ? ' root' : '') + '">' + folderButton +
    '<div id="' + groupId + '" class="api-statistics-tree-children"' + (expanded ? '' : ' hidden') + '>' + entries + '</div></div>';
}

function apiStatisticsSummaryText(groups) {
  const start = el('apiStatisticsStartDate').value;
  const end = el('apiStatisticsEndDate').value;
  const range = start || end
    ? (start ? start.replace('T', ' ') : '最早') + ' 至 ' + (end ? end.replace('T', ' ') : '现在')
    : '最近 30 天';
  const summaryFor = function(category) {
    const endpoints = groups[category] || [];
    const requests = endpoints.reduce(function(total, endpoint) {
      return total + (Number(endpoint.requestCount) || 0);
    }, 0);
    return endpoints.length + ' 个端点 · ' + requests.toLocaleString() + ' 次请求';
  };
  return range + ' · 入站 ' + summaryFor('request') + ' · 出站 ' + summaryFor('outbound');
}

function renderApiStatistics() {
  const query = String(el('apiStatisticsSearch').value || '').trim().toLowerCase();
  const summary = el('apiStatisticsSummary');
  if (!apiStatisticsData) return;
  const groups = apiStatisticsData.endpointsByCategory || {request: [], outbound: []};
  if (summary) summary.textContent = apiStatisticsSummaryText(groups);
  [
    {category: 'request', listId: 'apiStatisticsInboundList', summaryId: 'apiStatisticsInboundSummary'},
    {category: 'outbound', listId: 'apiStatisticsOutboundList', summaryId: 'apiStatisticsOutboundSummary'},
  ].forEach(function(item) {
    const list = el(item.listId);
    const count = el(item.summaryId);
    const rows = Array.isArray(groups[item.category]) ? groups[item.category] : [];
    const visibleCount = rows.filter(function(route) {
      return !query || (String(route.method || '') + ' ' + String(route.path || '/')).toLowerCase().includes(query);
    }).length;
    if (count) count.textContent = query
      ? visibleCount.toLocaleString() + ' / ' + rows.length.toLocaleString() + ' 个端点'
      : rows.length.toLocaleString() + ' 个端点';
    if (!list) return;
    if (!rows.length) {
      list.innerHTML = '<div class="api-statistics-endpoint-state">当前时间范围暂无' + (item.category === 'outbound' ? '出站' : '入站') + '端点</div>';
    } else if (!visibleCount) {
      list.innerHTML = '<div class="api-statistics-endpoint-state">没有匹配的端点</div>';
    } else {
      list.innerHTML = apiStatisticsFolderMarkup(apiStatisticsBuildTree(rows, item.category), query, true, item.category);
    }
  });
}

async function loadApiStatistics(force) {
  if (apiStatisticsLoading) {
    if (force) apiStatisticsReloadQueued = true;
    return false;
  }
  if (apiStatisticsData && !force) {
    renderApiStatistics();
    return true;
  }
  apiStatisticsLoading = true;
  const requestId = ++apiStatisticsRequestId;
  ['apiStatisticsInboundList', 'apiStatisticsOutboundList'].forEach(function(id) {
    const list = el(id);
    if (list) list.innerHTML = '<div class="loading-state">正在统计端点…</div>';
  });
  try {
    const query = new URLSearchParams({includeEndpoints: 'true', limit: '1', offset: '0'});
    const startDate = el('apiStatisticsStartDate').value;
    const endDate = el('apiStatisticsEndDate').value;
    if (startDate) query.set('startDate', startDate);
    if (endDate) query.set('endDate', endDate);
    const response = await fetch(ADMIN_API_BASE + '/system-logs?' + query.toString(), {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) throw new Error(adminErrorMessage(data.error, 'API统计读取失败（HTTP ' + response.status + '）'));
    if (requestId !== apiStatisticsRequestId) return false;
    const groups = data.endpointsByCategory;
    apiStatisticsData = {
      endpointsByCategory: groups && Array.isArray(groups.request) && Array.isArray(groups.outbound)
        ? groups
        : {request: Array.isArray(data.endpoints) ? data.endpoints : [], outbound: []}
    };
    renderApiStatistics();
    return true;
  } catch (error) {
    if (requestId === apiStatisticsRequestId) {
      const message = esc(error.message || 'API统计读取失败');
      ['apiStatisticsInboundList', 'apiStatisticsOutboundList'].forEach(function(id) {
        const list = el(id);
        if (list) list.innerHTML = '<div class="api-statistics-endpoint-state error">' + message + '</div>';
      });
      if (el('apiStatisticsSummary')) el('apiStatisticsSummary').textContent = 'API 统计读取失败';
    }
    return false;
  } finally {
    apiStatisticsLoading = false;
    if (apiStatisticsReloadQueued) {
      apiStatisticsReloadQueued = false;
      apiStatisticsData = null;
      loadApiStatistics(true);
    }
  }
}

function scheduleApiStatisticsRefresh() {
  if (apiStatisticsFilterTimer) clearTimeout(apiStatisticsFilterTimer);
  apiStatisticsFilterTimer = setTimeout(function() {
    apiStatisticsFilterTimer = null;
    apiStatisticsRequestId += 1;
    apiStatisticsData = null;
    if (apiStatisticsLoading) apiStatisticsReloadQueued = true;
    else loadApiStatistics(true);
  }, 250);
}

function resetApiStatisticsFilters() {
  el('apiStatisticsSearch').value = '';
  el('apiStatisticsStartDate').value = '';
  el('apiStatisticsEndDate').value = '';
  apiStatisticsExpandedPaths = new Set(['request:/', 'request:/api', 'outbound:/']);
  apiStatisticsRequestId += 1;
  apiStatisticsData = null;
  loadApiStatistics(true);
}

el('apiStatisticsSearch').addEventListener('input', renderApiStatistics);
['apiStatisticsStartDate', 'apiStatisticsEndDate'].forEach(function(id) {
  el(id).addEventListener('input', scheduleApiStatisticsRefresh);
  el(id).addEventListener('change', scheduleApiStatisticsRefresh);
});

el('apiStatisticsGrid').addEventListener('click', function(event) {
  const toggle = event.target.closest('[data-api-statistics-tree-toggle]');
  if (toggle) {
    const path = toggle.dataset.apiStatisticsTreeToggle || '/';
    const category = toggle.dataset.apiStatisticsTreeCategory === 'outbound' ? 'outbound' : 'request';
    const key = category + ':' + path;
    const expanded = toggle.getAttribute('aria-expanded') === 'true';
    const nextExpanded = !expanded;
    if (nextExpanded) apiStatisticsExpandedPaths.add(key);
    else apiStatisticsExpandedPaths.delete(key);
    toggle.setAttribute('aria-expanded', String(nextExpanded));
    toggle.setAttribute('aria-label', (nextExpanded ? '收起' : '展开') + (path === '/' ? '根目录' : path));
    const children = el(toggle.getAttribute('aria-controls'));
    if (children) children.hidden = !nextExpanded;
    return;
  }
  const root = event.target.closest('[data-api-statistics-root-category]');
  if (root) {
    showSystemLogsForApiEndpoint(
      root.dataset.apiStatisticsRootCategory,
      '',
      '',
      el('apiStatisticsStartDate').value,
      el('apiStatisticsEndDate').value,
    );
    return;
  }
  const endpoint = event.target.closest('[data-api-statistics-endpoint-path]');
  if (endpoint) {
    showSystemLogsForApiEndpoint(
      endpoint.dataset.apiStatisticsEndpointCategory,
      endpoint.dataset.apiStatisticsEndpointMethod || '',
      endpoint.dataset.apiStatisticsEndpointPath || '',
      el('apiStatisticsStartDate').value,
      el('apiStatisticsEndDate').value,
    );
  }
});
