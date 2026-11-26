let apiStatisticsData = null;
let apiStatisticsLoading = false;
let apiStatisticsReloadQueued = false;
let apiStatisticsRequestId = 0;
let apiStatisticsExpandedPaths = new Set(['request:/', 'request:/api', 'unconfigured:/']);

const API_ENDPOINT_INVENTORY = [
  ['GET', '/api/transport/key'],
  ['POST', '/api/transport'],
  ['GET', '/api/client'],
  ['GET', '/api/app/update'],
  ['GET', '/api/app/proxy-packages'],
  ['GET', '/api/subscription/:id'],
  ['GET', '/api/client/notifications'],
  ['POST', '/api/client/notifications/read-all'],
  ['POST', '/api/client/notifications/:id/read'],
  ['POST', '/api/account/sync'],
  ['POST', '/api/account/register'],
  ['POST', '/api/account/login'],
  ['POST', '/api/account/activate-vip'],
  ['POST', '/api/account/delete'],
  ['PATCH', '/api/account/profile'],
  ['POST', '/api/account/security-report'],
  ['POST', '/api/account/report'],
  ['GET', '/api/system-log-settings', true],
  ['PUT', '/api/system-log-settings', true],
  ['GET', '/api/system-logs/payload', true],
  ['GET', '/api/system-logs', true],
  ['DELETE', '/api/system-logs', true],
  ['GET', '/api/users/growth', true],
  ['GET', '/api/users/traffic', true],
  ['GET', '/api/users/activity', true],
  ['GET', '/api/dashboard/devices', true],
  ['GET', '/api/registration-codes', true],
  ['POST', '/api/registration-codes', true],
  ['GET', '/api/registration-codes/:id/usages', true],
  ['GET', '/api/vip-codes', true],
  ['POST', '/api/vip-codes', true],
  ['GET', '/api/users', true],
  ['PATCH', '/api/users/batch', true],
  ['DELETE', '/api/users/batch', true],
  ['POST', '/api/users/:uid/ban', true],
  ['DELETE', '/api/users/:uid/ban', true],
  ['DELETE', '/api/users/:uid', true],
  ['PATCH', '/api/users/:uid', true],
  ['GET', '/api/notifications', true],
  ['POST', '/api/notifications', true],
  ['PUT', '/api/notifications/:id', true],
  ['GET', '/api/security/client-reports', true],
  ['POST', '/api/security/client-reports/:id/review', true],
  ['GET', '/api/ip-relations', true],
  ['GET', '/api/device-relations', true],
  ['PATCH', '/api/devices/batch', true],
  ['GET', '/api/devices', true],
  ['GET', '/api/security/accounts', true],
  ['GET', '/api/subscriptions/traffic', true],
  ['POST', '/api/subscriptions/refresh', true],
  ['GET', '/api/state', true],
  ['PUT', '/api/state', true],
];

function apiStatisticsCatalog() {
  const adminPrefix = ADMIN_API_BASE.replace(/\/api$/, '');
  return API_ENDPOINT_INVENTORY.map(function(item) {
    return {method: item[0], path: item[2] ? adminPrefix + item[1] : item[1], requestCount: 0};
  });
}

function apiStatisticsEscapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function apiStatisticsMatchPattern(path) {
  return new RegExp('^' + path.split('/').map(function(segment) {
    return /^:[A-Za-z][A-Za-z0-9_]*$/.test(segment) ? '[^/]+' : apiStatisticsEscapeRegex(segment);
  }).join('/') + '$');
}

function apiStatisticsApplyCounts(loggedEndpoints) {
  const catalog = apiStatisticsCatalog();
  const patterns = catalog.map(function(endpoint) {
    return {endpoint: endpoint, pattern: apiStatisticsMatchPattern(endpoint.path)};
  });
  const unconfigured = new Map();
  (Array.isArray(loggedEndpoints) ? loggedEndpoints : []).forEach(function(logged) {
    const method = String(logged.method || '-').toUpperCase();
    const path = String(logged.path || '/');
    const count = Number(logged.requestCount) || 0;
    const match = patterns.find(function(item) {
      return item.endpoint.method === method && item.pattern.test(path);
    });
    if (match) {
      match.endpoint.requestCount += count;
      return;
    }
    const key = method + '\n' + path;
    const row = unconfigured.get(key) || {method: method, path: path, requestCount: 0};
    row.requestCount += count;
    unconfigured.set(key, row);
  });
  return {catalog: catalog, unconfigured: Array.from(unconfigured.values())};
}

function apiStatisticsCreateNode(name, path) {
  return {name: name, path: path, requestCount: 0, routes: [], children: new Map()};
}

function apiStatisticsBuildTree(endpoints) {
  const root = apiStatisticsCreateNode('/', '/');
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
      if (!node.children.has(segment)) node.children.set(segment, apiStatisticsCreateNode(segment, childPath));
      node = node.children.get(segment);
      node.requestCount += route.requestCount;
    });
    node.routes.push(route);
  });
  return root;
}

function apiStatisticsRouteMarkup(route) {
  const displayPath = route.displayPath || route.path;
  return '<div class="api-statistics-endpoint-item"><span class="api-statistics-endpoint-icon" aria-hidden="true">&#x25c9;</span><span class="api-statistics-endpoint-path">' + esc(displayPath) + '</span><span class="api-statistics-endpoint-count">' +
    route.requestCount.toLocaleString() + '</span></div>';
}

function apiStatisticsEntriesMarkup(node, category) {
  const entries = [];
  Array.from(node.children.values()).forEach(function(child) {
    if (child.children.size) {
      entries.push({kind: 'folder', count: child.requestCount, name: child.name, node: child});
    }
    child.routes.forEach(function(route) {
      const parentPath = node.path === '/' ? '' : node.path;
      const displayPath = route.path.slice(parentPath.length) || '/';
      const displayRoute = Object.assign({}, route, {displayPath: displayPath});
      entries.push({kind: 'route', count: route.requestCount, name: displayPath, markup: apiStatisticsRouteMarkup(displayRoute)});
    });
  });
  entries.sort(function(left, right) {
    return right.count - left.count || left.name.localeCompare(right.name);
  });
  return entries.map(function(entry) {
    return entry.kind === 'folder'
      ? apiStatisticsFolderMarkup(entry.node, false, category)
      : entry.markup;
  }).join('');
}

function apiStatisticsFolderMarkup(node, root, category) {
  const expandedKey = category + ':' + node.path;
  const expanded = apiStatisticsExpandedPaths.has(expandedKey);
  const groupId = 'apiStatisticsTreeChildren_' + category + '_' + encodeURIComponent(node.path).replace(/%/g, '_');
  const label = root ? '/' : node.name;
  const folderButton = root
    ? '<div class="api-statistics-endpoint-root-row"><button type="button" class="api-statistics-tree-toggle" data-api-statistics-tree-toggle="' + esc(node.path) + '" data-api-statistics-tree-category="' + category + '" aria-expanded="' + expanded + '" aria-controls="' + groupId + '" aria-label="' + (expanded ? '\u6536\u8d77' : '\u5c55\u5f00') + '\u6839\u76ee\u5f55"><svg class="subscription-icon" aria-hidden="true"><use href="#subscription-icon-next"></use></svg></button><div class="api-statistics-tree-root"><svg class="subscription-icon" aria-hidden="true"><use href="#subscription-icon-all"></use></svg><span>' + label + '</span><span class="api-statistics-endpoint-count">' + node.requestCount.toLocaleString() + '</span></div></div>'
    : '<button type="button" class="api-statistics-tree-folder" data-api-statistics-tree-toggle="' + esc(node.path) + '" data-api-statistics-tree-category="' + category + '" aria-expanded="' + expanded + '" aria-controls="' + groupId + '"><svg class="subscription-icon api-statistics-tree-caret" aria-hidden="true"><use href="#subscription-icon-next"></use></svg><svg class="subscription-icon api-statistics-tree-folder-icon" aria-hidden="true"><use href="#subscription-icon-folder"></use></svg><span class="api-statistics-tree-name" title="' + esc(node.path) + '">' + esc(label) + '</span><span class="api-statistics-endpoint-count">' + node.requestCount.toLocaleString() + '</span></button>';
  const entries = apiStatisticsEntriesMarkup(node, category);
  return '<div class="api-statistics-tree-branch' + (root ? ' root' : '') + '">' + folderButton +
    '<div id="' + groupId + '" class="api-statistics-tree-children"' + (expanded ? '' : ' hidden') + '>' + entries + '</div></div>';
}

function renderApiStatistics() {
  if (!apiStatisticsData) return;
  const groups = apiStatisticsData;
  [
    {category: 'request', key: 'catalog', listId: 'apiStatisticsConfiguredList', summaryId: 'apiStatisticsConfiguredSummary'},
    {category: 'unconfigured', key: 'unconfigured', listId: 'apiStatisticsUnconfiguredList', summaryId: 'apiStatisticsUnconfiguredSummary'},
  ].forEach(function(item) {
    const list = el(item.listId);
    const count = el(item.summaryId);
    const rows = Array.isArray(groups[item.key]) ? groups[item.key] : [];
    const logRecordCount = rows.reduce(function(total, row) {
      return total + (Number(row.requestCount) || 0);
    }, 0);
    if (count) count.textContent = logRecordCount.toLocaleString() + ' \u6761\u65e5\u5fd7\u8bb0\u5f55';
    if (!list) return;
    if (!rows.length) {
      list.innerHTML = '<div class="api-statistics-endpoint-state">\u6682\u65e0\u6570\u636e</div>';
    } else {
      list.innerHTML = apiStatisticsFolderMarkup(apiStatisticsBuildTree(rows), true, item.category);
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
  ['apiStatisticsConfiguredList', 'apiStatisticsUnconfiguredList'].forEach(function(id) {
    const list = el(id);
    if (list) list.innerHTML = '<div class="loading-state">\u6b63\u5728\u7edf\u8ba1\u7aef\u70b9\u2026</div>';
  });
  try {
    const query = new URLSearchParams({includeEndpoints: 'true', limit: '1', offset: '0'});
    const response = await fetch(ADMIN_API_BASE + '/system-logs?' + query.toString(), {
      method: 'GET', headers: headers(), cache: 'no-store'
    });
    const data = parseResponseJson(await response.text());
    if (!response.ok) throw new Error(adminErrorMessage(data.error, 'API\u7edf\u8ba1\u8bfb\u53d6\u5931\u8d25\uff08HTTP ' + response.status + '\uff09'));
    if (requestId !== apiStatisticsRequestId) return false;
    const groups = data.endpointsByCategory;
    const loggedEndpoints = groups && Array.isArray(groups.request)
      ? groups.request
      : Array.isArray(data.endpoints) ? data.endpoints : [];
    apiStatisticsData = apiStatisticsApplyCounts(loggedEndpoints);
    renderApiStatistics();
    return true;
  } catch (error) {
    if (requestId === apiStatisticsRequestId) {
      const message = esc(error.message || 'API\u7edf\u8ba1\u8bfb\u53d6\u5931\u8d25');
      ['apiStatisticsConfiguredList', 'apiStatisticsUnconfiguredList'].forEach(function(id) {
        const list = el(id);
        if (list) list.innerHTML = '<div class="api-statistics-endpoint-state error">' + message + '</div>';
      });
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

document.addEventListener('click', function(event) {
  const toggle = event.target.closest('[data-api-statistics-tree-toggle]');
  if (!toggle) return;
  const path = toggle.dataset.apiStatisticsTreeToggle || '/';
  const category = toggle.dataset.apiStatisticsTreeCategory || 'request';
  const key = category + ':' + path;
  const expanded = toggle.getAttribute('aria-expanded') === 'true';
  const nextExpanded = !expanded;
  if (nextExpanded) apiStatisticsExpandedPaths.add(key);
  else apiStatisticsExpandedPaths.delete(key);
  toggle.setAttribute('aria-expanded', String(nextExpanded));
  toggle.setAttribute('aria-label', (nextExpanded ? '\u6536\u8d77' : '\u5c55\u5f00') + (path === '/' ? '\u6839\u76ee\u5f55' : path));
  const children = el(toggle.getAttribute('aria-controls'));
  if (children) children.hidden = !nextExpanded;
});
