let ipRelationsData = null;
let ipRelationsLoading = false;
let ipRelationsOffset = 0;
let ipRelationsRevision = 0;
let ipRelationsDirection = 'user';
let ipRelationsSearchTimer = null;
let ipRelationsExpanded = new Set();

function resetIpRelations() {
  ipRelationsRevision += 1;
  ipRelationsData = null;
  ipRelationsLoading = false;
  ipRelationsOffset = 0;
  ipRelationsDirection = 'user';
  ipRelationsExpanded.clear();
  if (ipRelationsSearchTimer) clearTimeout(ipRelationsSearchTimer);
  ipRelationsSearchTimer = null;
  el('ipRelationsQuery').value = '';
  updateIpRelationsViewButtons();
  el('ipRelationsTable').innerHTML = '';
  el('ipRelationsSummary').textContent = '尚未读取关联记录';
  el('ipRelationsPageStatus').textContent = '';
  el('ipRelationsPrevious').disabled = true;
  el('ipRelationsNext').disabled = true;
  el('ipRelationsRefresh').disabled = false;
}

function updateIpRelationsViewButtons() {
  const byUser = ipRelationsDirection === 'user';
  const userButton = el('ipRelationsViewUser');
  const ipButton = el('ipRelationsViewIp');
  userButton.classList.toggle('active', byUser);
  userButton.setAttribute('aria-pressed', String(byUser));
  ipButton.classList.toggle('active', !byUser);
  ipButton.setAttribute('aria-pressed', String(!byUser));
}

function setIpRelationView(direction) {
  if (ipRelationsSearchTimer) clearTimeout(ipRelationsSearchTimer);
  ipRelationsSearchTimer = null;
  ipRelationsDirection = direction === 'ip' ? 'ip' : 'user';
  updateIpRelationsViewButtons();
  ipRelationsOffset = 0;
  ipRelationsExpanded.clear();
  loadIpRelations(true);
}

function setIpRelationFilter(direction, query) {
  ipRelationsDirection = direction === 'ip' ? 'ip' : 'user';
  el('ipRelationsQuery').value = query || '';
  updateIpRelationsViewButtons();
  ipRelationsOffset = 0;
  ipRelationsExpanded.clear();
  loadIpRelations(true);
  activateTab('ip-relations');
}

function toggleIpRelationGroup(key) {
  if (ipRelationsExpanded.has(key)) ipRelationsExpanded.delete(key);
  else ipRelationsExpanded.add(key);
  renderIpRelations();
}

async function copyIpRelationValue(value, label) {
  try {
    if (typeof navigator === 'undefined' || !navigator.clipboard || !navigator.clipboard.writeText) {
      throw new Error('clipboard_unavailable');
    }
    await navigator.clipboard.writeText(value);
    show(label + '已复制');
  } catch (_) {
    show('复制失败，请手动选择内容', true);
  }
}

function ipRelationStatusLabel(status) {
  return status === 'banned' ? '已封禁' : status === 'deleted' ? '已删除' : '正常';
}

function ipRelationAccountCell(relation) {
  return '<div class="ip-relation-account"><strong>' + esc(relation.nickname || relation.userUid) +
    '</strong><small>' + esc(relation.userUid) + '</small><span class="ip-relation-status status-' +
    esc(relation.accountStatus || 'deleted') + '">' + esc(ipRelationStatusLabel(relation.accountStatus)) + '</span></div>';
}

function ipRelationCopyButton(value, label) {
  return '<button class="ip-relation-copy" type="button" data-copy-value="' + esc(value) +
    '" data-copy-label="' + esc(label) + '" aria-label="复制' + esc(label) + '"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="13" rx="2"></rect><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"></path></svg>复制 ' + esc(label) + '</button>';
}

function ipRelationExpandedMarkup(group, byIp) {
  const rows = group.relations.map(function(relation) {
    if (byIp) {
      return '<tr><td>' + ipRelationAccountCell(relation) + '</td><td>' +
        Number(relation.requestCount || 0).toLocaleString() + '</td><td>' +
        Number(relation.subscriptionCount || 0).toLocaleString() + '</td><td>' +
        Number(relation.loginCount || 0).toLocaleString() + '</td><td>' +
        esc(systemLogDateTime(relation.firstSeenAt)) + '</td><td>' +
        esc(systemLogDateTime(relation.lastSeenAt)) + '</td><td>' +
        ipRelationCopyButton(relation.userUid, 'UID') + '</td></tr>';
    }
    return '<tr><td><code>' + esc(relation.ip) + '</code></td><td>' +
      Number(relation.requestCount || 0).toLocaleString() + '</td><td>' +
      Number(relation.subscriptionCount || 0).toLocaleString() + '</td><td>' +
      esc(systemLogDateTime(relation.firstSeenAt)) + '</td><td>' +
      esc(systemLogDateTime(relation.lastSeenAt)) + '</td><td>' +
      ipRelationCopyButton(relation.ip, 'IP') + '</td></tr>';
  }).join('');
  const headers = byIp
    ? '<th>关联账户</th><th>成功请求</th><th>订阅请求</th><th>密码登录</th><th>首次访问</th><th>最近访问</th><th>操作</th>'
    : '<th>IP 地址</th><th>成功请求</th><th>订阅请求</th><th>首次访问</th><th>最近访问</th><th>操作</th>';
  return '<tr class="ip-relation-expanded-row"><td colspan="6"><div class="ip-relation-expanded-content"><h3>' +
    (byIp ? '关联账户（' + group.relations.length + ' 个）' : '关联 IP（' + group.relations.length + ' 个）') +
    '</h3><div class="ip-relation-expanded-table-wrap"><table class="ip-relation-detail-table"><thead><tr>' +
    headers + '</tr></thead><tbody>' + rows + '</tbody></table></div></div></td></tr>';
}

function renderIpRelations() {
  const data = ipRelationsData;
  if (!data) return;
  const stats = data.stats || {};
  const byIp = data.direction === 'ip';
  const groups = Array.isArray(data.groups) ? data.groups : [];
  el('ipRelationsSummary').textContent = Number(stats.userCount || 0).toLocaleString() + ' 个用户 · ' +
    Number(stats.ipCount || 0).toLocaleString() + ' 个 IP · ' +
    Number(stats.requestCount || 0).toLocaleString() + ' 次成功请求';
  const offset = Number(data.offset) || 0;
  const limit = Number(data.limit) || 50;
  const groupLabel = byIp ? '个 IP' : '个用户';
  el('ipRelationsPageStatus').textContent = data.total
    ? '显示 ' + (offset + 1) + '–' + (offset + groups.length) + ' ' + groupLabel +
      '，共 ' + Number(data.total).toLocaleString() + ' ' + groupLabel
    : '暂无关联记录';
  el('ipRelationsPrevious').disabled = offset <= 0;
  el('ipRelationsNext').disabled = offset + limit >= Number(data.total || 0);
  const host = el('ipRelationsTable');
  if (!groups.length) {
    host.innerHTML = '<div class="security-device-empty">没有匹配的 IP 与用户关联</div>';
    return;
  }

  const rows = groups.map(function(group) {
    const groupKey = data.direction + ':' + group.key;
    const expanded = ipRelationsExpanded.has(groupKey);
    const expandButton = '<button class="ip-relation-expand" type="button" data-expand-group="' +
      esc(groupKey) + '" aria-expanded="' + expanded + '" aria-label="' + (expanded ? '收起' : '展开') +
      '关联详情"><span aria-hidden="true">' + (expanded ? '⌄' : '›') + '</span></button>';
    let identity;
    let secondary;
    let copyValue;
    let copyLabel;
    if (byIp) {
      const previews = group.relations.slice(0, 2).map(function(relation) {
        return '<span class="ip-relation-account-preview"><strong>' + esc(relation.nickname || relation.userUid) +
          '</strong><small>' + esc(relation.userUid) + '</small></span>';
      }).join('');
      identity = '<code>' + esc(group.ip || group.key) + '</code>';
      secondary = '<div class="ip-relation-account-list">' + previews +
        (group.childCount > 2 ? '<span class="ip-relation-more">+' + (group.childCount - 2) + ' 个账户</span>' : '') + '</div>';
      copyValue = group.ip || group.key;
      copyLabel = 'IP';
    } else {
      identity = '<div class="ip-relation-main-account"><strong>' + esc(group.nickname || group.userUid || group.key) +
        '</strong><small>' + esc(group.userUid || group.key) + '</small></div>';
      secondary = Number(group.childCount || 0).toLocaleString();
      copyValue = group.userUid || group.key;
      copyLabel = 'UID';
    }
    const parentRow = '<tr class="ip-relation-parent-row' + (expanded ? ' expanded' : '') + '"><td><div class="ip-relation-row-identity">' +
      expandButton + '<div>' + identity + '</div></div></td><td>' +
      (byIp ? secondary : secondary + ' 个') + '</td><td>' + Number(group.requestCount || 0).toLocaleString() +
      '</td><td>' + Number(group.subscriptionCount || 0).toLocaleString() + '</td><td>' +
      esc(systemLogDateTime(group.lastSeenAt)) + '</td><td>' + ipRelationCopyButton(copyValue, copyLabel) + '</td></tr>';
    return parentRow + (expanded ? ipRelationExpandedMarkup(group, byIp) : '');
  }).join('');
  const firstHeader = byIp ? 'IP 地址' : '账户';
  const secondHeader = byIp ? '关联账户' : 'IP 数';
  host.innerHTML = '<div class="ip-relation-table-wrap"><table class="ip-relation-table" aria-label="IP 与账户关联">' +
    '<thead><tr><th>' + firstHeader + '</th><th>' + secondHeader + '</th><th>成功请求</th><th>订阅请求</th><th>最近访问</th><th>操作</th></tr></thead><tbody>' +
    rows + '</tbody></table></div>';
  host.querySelectorAll('[data-expand-group]').forEach(function(button) {
    button.onclick = function() { toggleIpRelationGroup(button.dataset.expandGroup); };
  });
  host.querySelectorAll('[data-copy-value]').forEach(function(button) {
    button.onclick = function() { copyIpRelationValue(button.dataset.copyValue, button.dataset.copyLabel); };
  });
}

async function loadIpRelations(force) {
  if (force && ipRelationsSearchTimer) clearTimeout(ipRelationsSearchTimer);
  if (force) ipRelationsSearchTimer = null;
  if (!force && (ipRelationsLoading || ipRelationsData)) {
    if (ipRelationsData) renderIpRelations();
    return;
  }
  const revision = ++ipRelationsRevision;
  const direction = ipRelationsDirection;
  const query = value('ipRelationsQuery');
  if (ipRelationsData && (direction !== ipRelationsData.direction || query !== ipRelationsData.query)) {
    ipRelationsOffset = 0;
    ipRelationsExpanded.clear();
  }
  const params = new URLSearchParams({ direction: direction, limit: '50', offset: String(ipRelationsOffset) });
  if (query) params.set('q', query);
  ipRelationsLoading = true;
  el('ipRelationsRefresh').disabled = true;
  el('ipRelationsPrevious').disabled = true;
  el('ipRelationsNext').disabled = true;
  el('ipRelationsTable').innerHTML = '<div class="loading-state">正在读取 IP 与用户关联…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/ip-relations?' + params, { headers: headers() });
    const data = parseResponseJson(await response.text());
    if (revision !== ipRelationsRevision) return;
    if (!response.ok) {
      throw new Error(data.error || ('HTTP ' + response.status));
    }
    ipRelationsOffset = Number(data.offset) || 0;
    ipRelationsData = Object.assign({}, data, { direction: direction, query: query });
    renderIpRelations();
  } catch (error) {
    if (revision !== ipRelationsRevision) return;
    ipRelationsData = null;
    el('ipRelationsSummary').textContent = '关联记录读取失败';
    el('ipRelationsPageStatus').textContent = '';
    el('ipRelationsTable').innerHTML = '<div class="error-state">' + esc(error.message || error) + '</div>';
    show('IP 与用户关联读取失败：' + (error.message || error), true);
  } finally {
    if (revision === ipRelationsRevision) {
      ipRelationsLoading = false;
      el('ipRelationsRefresh').disabled = false;
    }
  }
}

function changeIpRelationsPage(delta) {
  if (ipRelationsLoading || !ipRelationsData) return;
  const next = ipRelationsOffset + delta * 50;
  if (next < 0 || next >= ipRelationsData.total) return;
  ipRelationsOffset = next;
  loadIpRelations(true);
}

el('ipRelationsViewUser').addEventListener('click', function() { setIpRelationView('user'); });
el('ipRelationsViewIp').addEventListener('click', function() { setIpRelationView('ip'); });
el('ipRelationsForm').addEventListener('submit', function(event) {
  event.preventDefault();
  if (ipRelationsSearchTimer) clearTimeout(ipRelationsSearchTimer);
  ipRelationsSearchTimer = null;
  ipRelationsOffset = 0;
  ipRelationsExpanded.clear();
  loadIpRelations(true);
});
el('ipRelationsQuery').addEventListener('input', function() {
  if (ipRelationsSearchTimer) clearTimeout(ipRelationsSearchTimer);
  ipRelationsSearchTimer = setTimeout(function() {
    ipRelationsSearchTimer = null;
    ipRelationsOffset = 0;
    ipRelationsExpanded.clear();
    loadIpRelations(true);
  }, 300);
});
