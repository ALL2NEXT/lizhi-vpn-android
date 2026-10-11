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
  el('ipRelationsSummary').textContent = '关联 ' + Number(data.total || 0).toLocaleString() +
    ' 组 · IP ' + Number(stats.ipCount || 0).toLocaleString() +
    ' 个 · 用户 ' + Number(stats.userCount || 0).toLocaleString() +
    ' 个 · 累计成功请求 ' + Number(stats.requestCount || 0).toLocaleString() + ' 次';
  const relations = Array.isArray(data.relations) ? data.relations : [];
  const offset = Number(data.offset) || 0;
  const limit = Number(data.limit) || 50;
  el('ipRelationsPageStatus').textContent = data.total
    ? '显示 ' + (offset + 1) + '–' + (offset + relations.length) + ' 条，共 ' + data.total + ' 条'
    : '暂无关联记录';
  el('ipRelationsPrevious').disabled = offset <= 0;
  el('ipRelationsNext').disabled = offset + limit >= Number(data.total || 0);
  const host = el('ipRelationsTable');
  if (!relations.length) {
    host.innerHTML = '<div class="security-device-empty">没有匹配的 IP 与用户关联</div>';
    return;
  }
  const byIp = data.direction !== 'user';
  const rows = relations.map(function(relation) {
    const status = relation.accountStatus === 'banned' ? '已封禁' :
      relation.accountStatus === 'deleted' ? '已删除' : '正常';
    const ipCell = '<td><button type="button" class="ip-relation-link" data-relation-ip="' +
      esc(relation.ip) + '">' + esc(relation.ip) + '</button></td>';
    const userCell = '<td><strong>' + esc(relation.nickname || relation.userUid) + '</strong>' +
      '<small class="ip-relation-user-status">' + esc(status) + '</small><br>' +
      '<button type="button" class="ip-relation-link" data-relation-uid="' +
      esc(relation.userUid) + '">' + esc(relation.userUid) + '</button></td>';
    return '<tr>' + (byIp ? ipCell + userCell : userCell + ipCell) +
      '<td>' + Number(relation.requestCount || 0).toLocaleString() + '</td>' +
      '<td>' + Number(relation.subscriptionCount || 0).toLocaleString() + '</td>' +
      '<td>' + Number(relation.loginCount || 0).toLocaleString() + '</td>' +
      '<td>' + esc(systemLogDateTime(relation.firstSeenAt)) + '</td>' +
      '<td>' + esc(systemLogDateTime(relation.lastSeenAt)) + '</td></tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table ip-relations-table" aria-label="IP 与用户关联">' +
    '<thead><tr>' + (byIp ? '<th>IP</th><th>用户</th>' : '<th>用户</th><th>IP</th>') +
    '<th>累计成功请求</th><th>订阅下载</th><th>密码登录</th><th>首次访问</th><th>最近访问</th>' +
    '</tr></thead><tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-relation-ip]').forEach(function(button) {
    button.onclick = function() { setIpRelationFilter('ip', button.dataset.relationIp); };
  });
  host.querySelectorAll('[data-relation-uid]').forEach(function(button) {
    button.onclick = function() { setIpRelationFilter('user', button.dataset.relationUid); };
  });
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
  const params = new URLSearchParams({ limit: '50', offset: String(ipRelationsOffset) });
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
