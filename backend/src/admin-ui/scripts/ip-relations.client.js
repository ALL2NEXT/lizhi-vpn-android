let ipRelationsData = null;
let ipRelationsLoading = false;
let ipRelationsOffset = 0;
let ipRelationsRevision = 0;

function resetIpRelations() {
  ipRelationsRevision += 1;
  ipRelationsData = null;
  ipRelationsLoading = false;
  ipRelationsOffset = 0;
  el('ipRelationsDirection').value = 'ip';
  el('ipRelationsQuery').value = '';
  updateIpRelationsQueryHint();
  el('ipRelationsTable').innerHTML = '';
  el('ipRelationsSummary').textContent = '尚未读取关联记录';
  el('ipRelationsPageStatus').textContent = '';
  el('ipRelationsPrevious').disabled = true;
  el('ipRelationsNext').disabled = true;
  el('ipRelationsRefresh').disabled = false;
}

function updateIpRelationsQueryHint() {
  const byIp = value('ipRelationsDirection') !== 'user';
  const input = el('ipRelationsQuery');
  input.placeholder = byIp ? '输入完整 IP，留空查看全部关联' : '输入完整用户 UID，留空查看全部关联';
  input.setAttribute('aria-label', byIp ? '查询 IP 对应的用户' : '查询用户 UID 对应的 IP');
}

function setIpRelationFilter(direction, query) {
  el('ipRelationsDirection').value = direction === 'user' ? 'user' : 'ip';
  el('ipRelationsQuery').value = query || '';
  updateIpRelationsQueryHint();
  ipRelationsOffset = 0;
  loadIpRelations(true);
  activateTab('ip-relations');
}

function renderIpRelations() {
  const data = ipRelationsData;
  if (!data) return;
  const stats = data.stats || {};
  const byIp = data.direction !== 'user';
  const groups = Array.isArray(data.groups) ? data.groups : buildIpRelationGroups(data.relations, byIp);
  const relationCount = stats.relationCount == null
    ? (Array.isArray(data.relations) ? data.relations.length : 0)
    : Number(stats.relationCount);
  const groupLabel = byIp ? '个 IP' : '个账户';
  el('ipRelationsSummary').textContent = '当前 ' + Number(data.total || 0).toLocaleString() + groupLabel +
    ' · 关联 ' + Number(relationCount || 0).toLocaleString() + ' 组 · IP ' +
    Number(stats.ipCount || 0).toLocaleString() + ' 个 · 用户 ' + Number(stats.userCount || 0).toLocaleString() +
    ' 个 · 成功请求 ' + Number(stats.requestCount || 0).toLocaleString() + ' 次';
  const offset = Number(data.offset) || 0;
  const limit = Number(data.limit) || 50;
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
  const statusLabel = function(status) {
    return status === 'banned' ? '已封禁' : status === 'deleted' ? '已删除' : '正常';
  };
  const groupMarkup = groups.map(function(group) {
    const parent = byIp
      ? '<span class="ip-relation-parent-kind">IP</span><button type="button" class="ip-relation-link ip-relation-parent-link" data-relation-ip="' +
        esc(group.ip || group.key) + '">' + esc(group.ip || group.key) + '</button>'
      : '<span class="ip-relation-parent-kind">账户</span><div class="ip-relation-parent-account"><strong>' +
        esc(group.nickname || group.userUid || group.key) + '</strong><span class="ip-relation-user-status">' +
        esc(statusLabel(group.accountStatus)) + '</span><button type="button" class="ip-relation-link" data-relation-uid="' +
        esc(group.userUid || group.key) + '">' + esc(group.userUid || group.key) + '</button></div>';
    const rows = group.relations.map(function(relation) {
      const identity = byIp
        ? '<div class="ip-relation-identity"><strong>' + esc(relation.nickname || relation.userUid) +
          '</strong><span class="ip-relation-user-status">' + esc(statusLabel(relation.accountStatus)) + '</span>' +
          '<button type="button" class="ip-relation-link" data-relation-uid="' + esc(relation.userUid) + '">' +
          esc(relation.userUid) + '</button></div>'
        : '<button type="button" class="ip-relation-link" data-relation-ip="' + esc(relation.ip) + '">' +
          esc(relation.ip) + '</button>';
      return '<tr><td>' + identity + '</td><td>' + Number(relation.requestCount || 0).toLocaleString() +
        '</td><td>' + Number(relation.subscriptionCount || 0).toLocaleString() +
        '</td><td>' + Number(relation.loginCount || 0).toLocaleString() +
        '</td><td>' + esc(systemLogDateTime(relation.firstSeenAt)) +
        '</td><td>' + esc(systemLogDateTime(relation.lastSeenAt)) + '</td></tr>';
    }).join('');
    const memberLabel = byIp ? '关联账户' : '关联 IP';
    return '<section class="ip-relation-group"><header class="ip-relation-group-heading"><div class="ip-relation-parent">' +
      parent + '</div><div class="ip-relation-group-summary"><span><strong>' +
      Number(group.childCount || 0).toLocaleString() + '</strong> ' + (byIp ? '个账户' : '个 IP') +
      '</span><span><strong>' + Number(group.requestCount || 0).toLocaleString() +
      '</strong> 次成功请求</span><span>最近访问 ' + esc(systemLogDateTime(group.lastSeenAt)) +
      '</span></div></header><div class="ip-relation-members"><table class="traffic-detail-table ip-relations-table" aria-label="' +
      memberLabel + '"><thead><tr><th>' + memberLabel + '</th><th>成功请求</th><th>订阅请求</th>' +
      '<th>密码登录</th><th>首次访问</th><th>最近访问</th></tr></thead><tbody>' + rows +
      '</tbody></table></div></section>';
  }).join('');
  host.innerHTML = '<div class="ip-relation-groups">' + groupMarkup + '</div>';
  host.querySelectorAll('[data-relation-ip]').forEach(function(button) {
    button.onclick = function() { setIpRelationFilter('ip', button.dataset.relationIp); };
  });
  host.querySelectorAll('[data-relation-uid]').forEach(function(button) {
    button.onclick = function() { setIpRelationFilter('user', button.dataset.relationUid); };
  });
}

function buildIpRelationGroups(relations, byIp) {
  const groups = new Map();
  (Array.isArray(relations) ? relations : []).forEach(function(relation) {
    const key = byIp ? relation.ip : relation.userUid;
    if (!key) return;
    let group = groups.get(key);
    if (!group) {
      group = {
        key: key,
        ip: byIp ? key : '',
        userUid: byIp ? '' : key,
        nickname: byIp ? '' : relation.nickname || '',
        accountStatus: byIp ? '' : relation.accountStatus || 'deleted',
        childCount: 0,
        requestCount: 0,
        firstSeenAt: relation.firstSeenAt || '',
        lastSeenAt: relation.lastSeenAt || '',
        relations: [],
      };
      groups.set(key, group);
    }
    group.childCount += 1;
    group.requestCount += Number(relation.requestCount || 0);
    if (relation.firstSeenAt && (!group.firstSeenAt || relation.firstSeenAt < group.firstSeenAt)) {
      group.firstSeenAt = relation.firstSeenAt;
    }
    if (relation.lastSeenAt > group.lastSeenAt) group.lastSeenAt = relation.lastSeenAt;
    group.relations.push(relation);
  });
  return Array.from(groups.values());
}

async function loadIpRelations(force) {
  if (!force && (ipRelationsLoading || ipRelationsData)) {
    if (ipRelationsData) renderIpRelations();
    return;
  }
  const revision = ++ipRelationsRevision;
  const direction = value('ipRelationsDirection') === 'user' ? 'user' : 'ip';
  const query = value('ipRelationsQuery');
  if (ipRelationsData && (direction !== ipRelationsData.direction || query !== ipRelationsData.query)) {
    ipRelationsOffset = 0;
  }
  const params = new URLSearchParams({ direction: direction, limit: '50', offset: String(ipRelationsOffset) });
  if (query) params.set(direction === 'user' ? 'uid' : 'ip', query);
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
      throw new Error(({ invalid_ip: '请输入有效的完整 IP 地址', invalid_uid: '请输入有效的完整用户 UID' })[data.error] ||
        data.error || ('HTTP ' + response.status));
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

el('ipRelationsForm').addEventListener('submit', function(event) {
  event.preventDefault();
  ipRelationsOffset = 0;
  loadIpRelations(true);
});
el('ipRelationsDirection').addEventListener('change', function() {
  setIpRelationFilter(value('ipRelationsDirection'), '');
});
