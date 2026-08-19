function getTrafficDetailSortValue(user, key) {
  const today = user.today || {};
  const month = user.month || {};
  switch (key) {
    case 'todayUploadMb': return Number(today.uploadMb || 0);
    case 'todayDownloadMb': return Number(today.downloadMb || 0);
    case 'todayTotalMb': return Number(today.totalMb || 0);
    case 'monthUploadMb': return Number(month.uploadMb || 0);
    case 'monthDownloadMb': return Number(month.downloadMb || 0);
    case 'monthTotalMb': return Number(month.totalMb || 0);
    case 'totalUploadMb': return Number(user.totalUploadMb || 0);
    case 'totalDownloadMb': return Number(user.totalDownloadMb || 0);
    case 'totalMb': return Number(user.totalMb || 0);
    default: return 0;
  }
}
function trafficDetailSortHeader(key, label) {
  const active = trafficDetailsSortKey === key;
  const indicator = active ? (trafficDetailsSortDirection === 'asc' ? '↑' : '↓') : '↕';
  const direction = active ? (trafficDetailsSortDirection === 'asc' ? '正序' : '倒序') : '排序';
  return '<th class="traffic-sort-header" scope="col"><button class="traffic-sort-button" type="button" ' +
    'data-traffic-sort="' + key + '" aria-label="按' + esc(label) + direction + '">' +
    esc(label) + '<span class="traffic-sort-indicator" aria-hidden="true">' + indicator + '</span></button></th>';
}
function toggleTrafficSort(key) {
  if (!Object.prototype.hasOwnProperty.call(TRAFFIC_DETAIL_SORT_FIELDS, key)) return;
  if (trafficDetailsSortKey === key) {
    trafficDetailsSortDirection = trafficDetailsSortDirection === 'asc' ? 'desc' : 'asc';
  } else {
    trafficDetailsSortKey = key;
    trafficDetailsSortDirection = 'asc';
  }
  renderTrafficDetails();
}
const TRAFFIC_ZERO_FILTER_FIELDS = {
  today: {key: 'todayTotalMb', label: '日流量'},
  month: {key: 'monthTotalMb', label: '月流量'},
  total: {key: 'totalMb', label: '总流量'}
};
function updateTrafficZeroFilterButtons() {
  document.querySelectorAll('[data-traffic-zero-filter]').forEach(function(button) {
    const active = trafficZeroFilters[button.dataset.trafficZeroFilter] === true;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', active ? 'true' : 'false');
  });
}
function toggleTrafficZeroFilter(key) {
  if (!Object.prototype.hasOwnProperty.call(TRAFFIC_ZERO_FILTER_FIELDS, key)) return;
  trafficZeroFilters[key] = !trafficZeroFilters[key];
  renderTrafficDetails();
}
function renderTrafficDetails() {
  const host = el('trafficDetailsTable');
  const summary = el('trafficDetailSummary');
  if (!host || !summary) return;
  updateTrafficZeroFilterButtons();
  const users = trafficDetailsData && Array.isArray(trafficDetailsData.users)
    ? trafficDetailsData.users : [];
  const search = String(value('trafficDetailSearch') || '').toLowerCase();
  const filtered = users.filter(function(user) {
    if (search && [user.uid, user.nickname, user.email, user.status].join(' ')
      .toLowerCase().indexOf(search) < 0) return false;
    return Object.keys(trafficZeroFilters).every(function(filterKey) {
      if (!trafficZeroFilters[filterKey]) return true;
      return getTrafficDetailSortValue(user, TRAFFIC_ZERO_FILTER_FIELDS[filterKey].key) !== 0;
    });
  });
  const sorted = filtered.map(function(user, index) {
    return {user: user, index: index};
  });
  if (trafficDetailsSortKey) {
    const direction = trafficDetailsSortDirection === 'asc' ? 1 : -1;
    sorted.sort(function(left, right) {
      const leftValue = getTrafficDetailSortValue(left.user, trafficDetailsSortKey);
      const rightValue = getTrafficDetailSortValue(right.user, trafficDetailsSortKey);
      if (leftValue === rightValue) return left.index - right.index;
      return (leftValue < rightValue ? -1 : 1) * direction;
    });
  }
  const totalMb = filtered.reduce(function(total, user) {
    return total + Number(user.totalMb || 0);
  }, 0);
  const sortSummary = trafficDetailsSortKey
    ? ' · 已按' + TRAFFIC_DETAIL_SORT_FIELDS[trafficDetailsSortKey] +
      (trafficDetailsSortDirection === 'asc' ? '正序' : '倒序') : '';
  const filterSummary = Object.keys(trafficZeroFilters).filter(function(filterKey) {
    return trafficZeroFilters[filterKey];
  }).map(function(filterKey) {
    return TRAFFIC_ZERO_FILTER_FIELDS[filterKey].label;
  });
  summary.textContent = '共 ' + users.length + ' 个账户 · 当前匹配 ' + filtered.length +
    ' 个 · 当前累计总流量 ' + formatMegabytes(totalMb) +
    (trafficDetailsData && trafficDetailsData.date ? ' · 统计日期 ' + trafficDetailsData.date : '') + sortSummary +
    (filterSummary.length ? ' · 已过滤零' + filterSummary.join('、') : '');
  if (sorted.length === 0) {
    host.innerHTML = '<div class="user-table-empty">没有匹配的流量汇总表单</div>';
    return;
  }
  const rows = sorted.map(function(item) {
    const user = item.user;
    const banned = user.status === 'banned';
    const today = user.today || {};
    const month = user.month || {};
    return '<tr class="' + (banned ? 'banned' : '') + '">' +
      '<td><code>' + esc(user.uid) + '</code></td>' +
      '<td>' + esc(user.nickname || user.uid) + '</td>' +
      '<td>' + esc(user.email || '游客账户') + '</td>' +
      '<td>' + (banned ? '已封禁' : '正常') + '</td>' +
      '<td>Lv' + esc(user.level == null ? '-' : user.level) + '</td>' +
      '<td>' + formatMegabytes(today.uploadMb) + '</td>' +
      '<td>' + formatMegabytes(today.downloadMb) + '</td>' +
      '<td>' + formatMegabytes(today.totalMb) + '</td>' +
      '<td>' + formatMegabytes(month.uploadMb) + '</td>' +
      '<td>' + formatMegabytes(month.downloadMb) + '</td>' +
      '<td>' + formatMegabytes(month.totalMb) + '</td>' +
      '<td>' + formatMegabytes(user.totalUploadMb) + '</td>' +
      '<td>' + formatMegabytes(user.totalDownloadMb) + '</td>' +
      '<td>' + formatMegabytes(user.totalMb) + '</td>' +
      '<td>' + esc(user.lastReportAt || '暂无') + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table" aria-label="账户流量汇总表单">' +
    '<thead><tr><th scope="col">UID</th><th scope="col">昵称</th><th scope="col">邮箱</th><th scope="col">状态</th><th scope="col">等级</th>' +
    trafficDetailSortHeader('todayUploadMb', '今日上传') +
    trafficDetailSortHeader('todayDownloadMb', '今日下载') +
    trafficDetailSortHeader('todayTotalMb', '今日总流量') +
    trafficDetailSortHeader('monthUploadMb', '本月上传') +
    trafficDetailSortHeader('monthDownloadMb', '本月下载') +
    trafficDetailSortHeader('monthTotalMb', '本月总流量') +
    trafficDetailSortHeader('totalUploadMb', '累计上传') +
    trafficDetailSortHeader('totalDownloadMb', '累计下载') +
    trafficDetailSortHeader('totalMb', '累计总流量') +
    '<th scope="col">最后上报</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
  host.querySelectorAll('[data-traffic-sort]').forEach(function(button) {
    button.addEventListener('click', function() {
      toggleTrafficSort(button.dataset.trafficSort);
    });
  });
}
async function loadTrafficDetails(force) {
  if (trafficDetailsLoading) return false;
  if (trafficDetailsData && !force) {
    renderTrafficDetails();
    return true;
  }
  const host = el('trafficDetailsTable');
  trafficDetailsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取流量汇总表单…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users', {headers: headers()});
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    trafficDetailsData = data;
    renderTrafficDetails();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">流量汇总表单读取失败</div>';
    show('流量汇总表单读取失败：' + (error.message || error), true);
    return false;
  } finally {
    trafficDetailsLoading = false;
  }
}
function renderInviteRelations() {
  const host = el('inviteRelationsTable');
  const summary = el('inviteRelationSummary');
  if (!host || !summary) return;
  const relations = inviteRelationsData && Array.isArray(inviteRelationsData.relations)
    ? inviteRelationsData.relations : [];
  const search = String(value('inviteRelationSearch') || '').toLowerCase();
  const filtered = relations.filter(function(relation) {
    if (!search) return true;
    return [
      relation.inviterUid,
      relation.inviterNickname,
      relation.inviterInviteCode,
      relation.inviteeUid,
      relation.inviteeNickname,
      relation.inviteeEmail
    ].join(' ').toLowerCase().indexOf(search) >= 0;
  });
  summary.textContent = '共 ' + relations.length + ' 条关系 · 当前匹配 ' + filtered.length + ' 条';
  if (filtered.length === 0) {
    host.innerHTML = '<div class="user-table-empty">暂无匹配的邀请关系</div>';
    return;
  }
  const rows = filtered.map(function(relation) {
    const inviterStatus = relation.inviterStatus === 'banned' ? '已封禁' : '正常';
    const inviteeStatus = relation.inviteeStatus === 'banned' ? '已封禁' : '正常';
    return '<tr>' +
      '<td><code>' + esc(relation.inviterUid || '-') + '</code></td>' +
      '<td>' + esc(relation.inviterNickname || '-') + '</td>' +
      '<td><code>' + esc(relation.inviterInviteCode || '-') + '</code></td>' +
      '<td>' + esc(inviterStatus) + '</td>' +
      '<td><code>' + esc(relation.inviteeUid || '-') + '</code></td>' +
      '<td>' + esc(relation.inviteeNickname || '-') + '</td>' +
      '<td>' + esc(relation.inviteeEmail || '游客账户') + '</td>' +
      '<td>' + esc(inviteeStatus) + '</td>' +
      '<td>' + esc(relation.invitedAt || '-') + '</td>' +
      '<td>' + esc(relation.inviteeCreatedAt || '-') + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="traffic-detail-table" aria-label="邀请关系表单">' +
    '<thead><tr><th scope="col">邀请人 UID</th><th scope="col">邀请人昵称</th>' +
    '<th scope="col">邀请码</th><th scope="col">邀请人状态</th>' +
    '<th scope="col">被邀请人 UID</th><th scope="col">被邀请人昵称</th>' +
    '<th scope="col">被邀请人邮箱</th><th scope="col">被邀请人状态</th>' +
    '<th scope="col">绑定时间</th><th scope="col">注册时间</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
}

async function loadInviteRelations(force) {
  if (inviteRelationsLoading) return false;
  if (inviteRelationsData && !force) {
    renderInviteRelations();
    return true;
  }
  const host = el('inviteRelationsTable');
  inviteRelationsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取邀请关系…</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/invite-relations?limit=1000', {
      headers: headers()
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    inviteRelationsData = data;
    renderInviteRelations();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">邀请关系读取失败</div>';
    show('邀请关系读取失败：' + (error.message || error), true);
    return false;
  } finally {
    inviteRelationsLoading = false;
  }
}
