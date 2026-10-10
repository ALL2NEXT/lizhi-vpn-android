let subscriptionTrafficRequestId = 0;
let activeSubscriptionGroupId = '__all__';
let activeWebNavigationGroupId = '';
let selectedSubscriptionRow = null;
const selectedSubscriptionRows = new Set();
let subscriptionCurrentPage = 1;
let subscriptionPageSize = 20;
let subscriptionView = 'list';
function subscriptionIcon(name, extraClass) {
  return '<svg class="subscription-icon' + (extraClass ? ' ' + extraClass : '') + '" aria-hidden="true" focusable="false"><use href="#subscription-icon-' + name + '"></use></svg>';
}
function sourceOrder(row) {
  const order = Number(row.dataset.sourceOrder);
  return Number.isFinite(order) ? order : 1;
}

function bindSubscriptionSourceSwitch(row) {
  const input = row.querySelector('.source-enabled-check input[type="checkbox"]');
  if (!input) return;
  input.addEventListener('change', function() {
    updateSourceRowPresentation(row);
  });
}

function getSubscriptionGroupRows() {
  return Array.from(el('subscriptionRows').querySelectorAll(':scope > .subscription-group'));
}

function setSourceTrafficMessage(row, message, error) {
  const host = row.querySelector('[data-source-traffic]');
  const text = row.querySelector('[data-source-traffic-text]');
  const expiry = row.querySelector('[data-source-expiry]');
  const track = row.querySelector('[data-source-traffic-track]');
  const fill = row.querySelector('[data-source-traffic-progress]');
  if (!host || !text || !track || !fill) return;
  host.dataset.trafficError = error ? 'true' : 'false';
  text.textContent = message;
  if (expiry) {
    expiry.textContent = '';
    expiry.classList.remove('expired');
  }
  track.hidden = true;
  track.removeAttribute('aria-valuenow');
  track.title = '';
  fill.style.width = '0%';
  fill.className = 'source-traffic-progress';
}

function updateSourceRowPresentation(row) {
  const sourceType = row.querySelector('[data-field="sourceType"]').value;
  const url = row.querySelector('[data-field="url"]').value.trim();
  const trafficHost = row.querySelector('[data-source-traffic]');
  if (sourceType !== 'url') {
    trafficHost.dataset.sourceUrl = '';
    setSourceTrafficMessage(row, '本地文本订阅没有上游流量信息');
  } else if (!url) {
    trafficHost.dataset.sourceUrl = '';
    setSourceTrafficMessage(row, '填写远程订阅地址以获取流量信息');
  } else if (url !== row.dataset.persistedUrl || !row.dataset.sourceId ||
      String(row.querySelector('[data-field="enabled"]').checked) !== row.dataset.persistedEnabled) {
    trafficHost.dataset.sourceUrl = '';
    setSourceTrafficMessage(row, '保存配置后获取流量信息');
  } else if (url !== trafficHost.dataset.sourceUrl) {
    setSourceTrafficMessage(row, row.querySelector('[data-field="enabled"]').checked
      ? '等待读取流量信息…'
      : '未获取流量信息');
  }
}

function formatSubscriptionTrafficBytes(value) {
  const bytes = Math.max(0, Number(value) || 0);
  if (bytes < 1024) return Math.floor(bytes) + ' B';
  const units = ['KB', 'MB', 'GB', 'TB', 'PB'];
  let amount = bytes;
  let unitIndex = -1;
  while (amount >= 1024 && unitIndex < units.length - 1) {
    amount /= 1024;
    unitIndex += 1;
  }
  const digits = amount >= 100 ? 0 : amount >= 10 ? 1 : 2;
  return amount.toFixed(digits).replace(/(\.\d*?[1-9])0+$/, '$1').replace(/\.0+$/, '') + ' ' + units[unitIndex];
}

function renderSourceTraffic(row, traffic, unavailable) {
  const sourceUrl = row.querySelector('[data-field="url"]').value.trim();
  const host = row.querySelector('[data-source-traffic]');
  const text = row.querySelector('[data-source-traffic-text]');
  const expiry = row.querySelector('[data-source-expiry]');
  const track = row.querySelector('[data-source-traffic-track]');
  const fill = row.querySelector('[data-source-traffic-progress]');
  if (!host || !text || !track || !fill) return;
  row.subscriptionTraffic = traffic || null;
  row.subscriptionTrafficUnavailable = unavailable === true;
  row.dataset.sourceHealth = traffic && unavailable !== true ? 'normal' : 'unknown';
  row.dataset.trafficUpdatedAt = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date());
  host.dataset.sourceUrl = sourceUrl;
  if (unavailable) {
    setSourceTrafficMessage(row, '流量信息获取失败', true);
    host.dataset.sourceUrl = sourceUrl;
    updateSourceRowsMeta();
    if (selectedSubscriptionRow === row) updateSubscriptionEditorTraffic(row);
    return;
  }
  if (!traffic) {
    setSourceTrafficMessage(row, '上游未提供流量信息');
    host.dataset.sourceUrl = sourceUrl;
    row.dataset.sourceHealth = 'unknown';
    updateSourceRowsMeta();
    if (selectedSubscriptionRow === row) updateSubscriptionEditorTraffic(row);
    return;
  }

  const upload = Math.max(0, Number(traffic.uploadBytes) || 0);
  const download = Math.max(0, Number(traffic.downloadBytes) || 0);
  const used = upload + download;
  const total = Math.max(0, Number(traffic.totalBytes) || 0);
  const hasUsage = traffic.uploadBytes != null || traffic.downloadBytes != null;
  text.textContent = total > 0
    ? '总量 ' + formatSubscriptionTrafficBytes(total) + ' · 已用 ' + formatSubscriptionTrafficBytes(used)
    : hasUsage ? '已用 ' + formatSubscriptionTrafficBytes(used) : '已获取订阅信息';
  track.hidden = total <= 0;
  if (total > 0) {
    const ratio = used / total;
    fill.style.width = Math.min(100, Math.max(0, ratio * 100)) + '%';
    fill.className = 'source-traffic-progress ' + (
      ratio >= 1 ? 'exhausted' : ratio > 0.9 ? 'critical' : ratio > 0.5 ? 'warning' : 'safe'
    );
    track.setAttribute('aria-valuenow', String(Math.min(100, Math.max(0, Math.round(ratio * 100)))));
    track.title = '上传 ' + formatSubscriptionTrafficBytes(upload) + ' · 下载 ' + formatSubscriptionTrafficBytes(download);
  } else {
    fill.style.width = '0%';
    fill.className = 'source-traffic-progress';
    track.removeAttribute('aria-valuenow');
    track.title = '';
  }
  if (expiry) {
    const expireAt = Number(traffic.expireAtMillis);
    const expireDate = new Date(expireAt);
    const hasValidExpiry = Number.isFinite(expireAt) && expireAt > 0 && Number.isFinite(expireDate.getTime());
    expiry.textContent = hasValidExpiry
      ? '到期 ' + new Intl.DateTimeFormat('zh-CN', {year: 'numeric', month: '2-digit', day: '2-digit'}).format(expireDate)
      : '';
    expiry.classList.toggle('expired', hasValidExpiry && expireAt <= Date.now());
  }
  host.dataset.trafficError = 'false';
  updateSourceRowsMeta();
  if (selectedSubscriptionRow === row) updateSubscriptionEditorTraffic(row);
}

async function loadSubscriptionTraffic() {
  const rows = getSourceRows();
  const activeRows = rows.filter(function(row) {
    return row.dataset.sourceId &&
      row.querySelector('[data-field="sourceType"]').value === 'url' &&
      row.querySelector('[data-field="enabled"]').checked &&
      row.querySelector('[data-field="url"]').value.trim() === row.dataset.persistedUrl;
  });
  if (!activeRows.length) return;
  const requestId = ++subscriptionTrafficRequestId;
  activeRows.forEach(function(row) {
    setSourceTrafficMessage(row, '正在获取流量信息…');
  });
  try {
    const response = await fetch(ADMIN_API_BASE + '/subscriptions/traffic', {
      method: 'GET',
      headers: headers(),
      cache: 'no-store'
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error('subscription_traffic_unavailable');
    if (requestId !== subscriptionTrafficRequestId) return;
    const items = new Map((Array.isArray(data.items) ? data.items : []).map(function(item) {
      return [String(item.id), item];
    }));
    activeRows.forEach(function(row) {
      const savedSource = (currentState && Array.isArray(currentState.subscriptionGroups)
        ? currentState.subscriptionGroups : []).flatMap(function(group) {
          return Array.isArray(group.subscriptions) ? group.subscriptions : [];
        }).find(function(source) { return String(source.id) === row.dataset.sourceId; });
      if (!row.isConnected || !savedSource ||
          row.querySelector('[data-field="url"]').value.trim() !== savedSource.url) return;
      const item = items.get(row.dataset.sourceId);
      renderSourceTraffic(row, item && item.traffic, !item || item.unavailable === true);
    });
  } catch (_) {
    if (requestId !== subscriptionTrafficRequestId) return;
    activeRows.forEach(function(row) {
      if (row.isConnected && row.querySelector('[data-field="url"]').value.trim() === row.dataset.persistedUrl) {
        renderSourceTraffic(row, null, true);
      }
    });
  }
}

function insertSourceByOrder(row, groupRow) {
  const container = groupRow.querySelector('[data-group-sources]');
  if (!container) return;
  const nextRow = Array.from(container.children).find(function(candidate) {
    return candidate.classList.contains('source-row') && sourceOrder(candidate) > sourceOrder(row);
  });
  container.insertBefore(row, nextRow || null);
}

function renderSubscriptionGroups(groups) {
  const container = el('subscriptionRows');
  selectSubscriptionSource(null);
  container.innerHTML = '';
  selectedSubscriptionRow = null;
  selectedSubscriptionRows.clear();
  activeSubscriptionGroupId = '__all__';
  (Array.isArray(groups) ? groups : []).forEach(function(group) {
    const row = addSubscriptionGroup(group);
    (Array.isArray(group.subscriptions) ? group.subscriptions : []).forEach(function(source, index) {
      addSource(row, source, index + 1);
    });
  });
  renderSubscriptionGroupNavigation();
  updateSourceRowsMeta();
  updateSubscriptionGroupControls();
  const firstGroup = getSubscriptionGroupRows()[0];
  if (firstGroup) selectSubscriptionGroup(firstGroup.dataset.groupId, false);
  const firstSource = firstGroup && firstGroup.querySelector('.source-row');
  if (firstSource) selectSubscriptionSource(firstSource);
}

function addSubscriptionGroup(group) {
  const isNewGroup = !group;
  group = group || {};
  const row = document.createElement('section');
  row.className = 'subscription-group';
  row.dataset.groupId = String(group.id || crypto.randomUUID());
  const groupOrder = getSubscriptionGroupRows().length + 1;
  const allowedStatuses = Array.isArray(group.allowedStatuses) ? group.allowedStatuses : ['normal'];
  const allowedAccountTypes = Array.isArray(group.allowedAccountTypes)
    ? group.allowedAccountTypes
    : ['guest', 'formal'];
  // 保留原有字段作为保存数据源，右侧编辑器只同步到这些字段，不改变接口结构。
  row.innerHTML = '<div class="subscription-group-heading">' +
    '<div class="subscription-group-title">' +
      '<span class="subscription-folder-icon" aria-hidden="true">' + subscriptionIcon('folder') + '</span>' +
      '<label><span class="visually-hidden">分组名称</span><input data-field="groupName" type="text" maxlength="80" placeholder="例如：标准线路"></label>' +
      '<small data-group-count>0 条订阅</small>' +
    '</div>' +
    '<input class="subscription-group-enabled-data" data-field="groupEnabled" type="checkbox" ' + (group.enabled === false ? '' : 'checked') + ' hidden>' +
    '<input class="subscription-group-order-data" data-field="groupOrder" type="number" min="1" step="1" value="' + groupOrder + '" aria-label="分组排序序号">' +
  '</div>' +
  '<dialog class="subscription-permissions-dialog" data-group-permissions-dialog aria-label="分组设置">' +
    '<div class="source-settings-dialog-header"><div><h3>分组设置</h3></div>' +
      '<button class="secondary" type="button" data-action="permissions-close">关闭</button>' +
    '</div>' +
    '<div class="subscription-group-permissions">' +
    '<fieldset class="subscription-group-type-setting"><legend>分组类型</legend>' +
      '<label><span>类型</span><select data-field="groupType"><option value="standard">普通常驻分组</option><option value="load_balanced">负载均衡分组</option></select></label>' +
      '<small>普通常驻分组会向客户端提供全部启用订阅；负载均衡分组为每位用户分配其中一条订阅。</small>' +
    '</fieldset>' +
    '<fieldset class="subscription-load-balance-settings" data-group-type-settings hidden><legend>负载均衡设置</legend>' +
      '<label><span>每个订阅最大分配人数</span><input data-field="maxUsersPerSubscription" type="number" min="1" max="1000000" step="1"></label>' +
      '<label><span>用户分配重置周期（天）</span><input data-field="resetPeriodDays" type="number" min="1" max="3650" step="1"></label>' +
      '<small>每位用户从首次分配时独立计时；周期到期后，下次获取订阅时重新分配。达到订阅人数上限后，该订阅不再分配给新用户。</small>' +
    '</fieldset>' +
    '<fieldset><legend>账号状态</legend>' +
      '<label class="check"><input data-field="allowedStatuses" type="checkbox" value="normal" ' + (allowedStatuses.includes('normal') ? 'checked' : '') + '>正常</label>' +
      '<label class="check"><input data-field="allowedStatuses" type="checkbox" value="banned" ' + (allowedStatuses.includes('banned') ? 'checked' : '') + '>封禁</label>' +
    '</fieldset>' +
    '<fieldset><legend>账户类型</legend>' +
      '<label class="check"><input data-field="allowedAccountTypes" type="checkbox" value="guest" ' + (allowedAccountTypes.includes('guest') ? 'checked' : '') + '>游客</label>' +
      '<label class="check"><input data-field="allowedAccountTypes" type="checkbox" value="formal" ' + (allowedAccountTypes.includes('formal') ? 'checked' : '') + '>正式用户</label>' +
    '</fieldset>' +
    '<fieldset class="subscription-version-range"><legend>App 版本区间（包含边界）</legend>' +
      '<label><span>左侧 · 最低</span><input data-field="minAppVersion" type="text" list="subscriptionAppVersionOptions" placeholder="不限制"></label>' +
      '<span aria-hidden="true">至</span>' +
      '<label><span>右侧 · 最高</span><input data-field="maxAppVersion" type="text" list="subscriptionAppVersionOptions" placeholder="不限制"></label>' +
    '</fieldset>' +
    '<fieldset class="subscription-cache-setting"><legend>更新设置</legend>' +
      '<label class="subscription-switch subscription-update-toggle"><span class="subscription-switch-control"><input data-field="updateEnabled" type="checkbox" aria-label="启用远程订阅更新" ' + (group.updateEnabled === false ? '' : 'checked') + '><span class="subscription-switch-track"><span></span></span></span><span>启用更新</span></label>' +
      '<label><span>接口缓存时间（分钟）</span><input data-field="groupCacheMinutes" type="number" min="0" max="10080" step="1"></label>' +
      '<small>该时间应用于本分组的所有外部接口订阅；启用更新时设为 0 表示每次请求上游。关闭更新后使用最近成功获取的内容；若无快照，保存时会尝试获取一次。</small>' +
    '</fieldset>' +
    '</div>' +
    '<div class="source-settings-dialog-actions subscription-permissions-dialog-actions">' +
      '<button class="danger" type="button" data-action="group-delete">删除分组</button>' +
      '<button class="secondary" type="button" data-action="permissions-close">完成</button>' +
    '</div>' +
  '</dialog>' +
  '<div class="source-list-header" aria-hidden="true"><span></span><span></span><span>#</span><span>订阅名称</span><span>流量使用</span><span>到期时间</span><span>备注</span><span>状态</span><span>启用</span><span>设置</span></div>' +
  '<div class="subscription-group-sources" data-group-sources>' +
    '<div class="subscription-group-empty" data-group-subscription-empty hidden>没有符合条件的订阅</div>' +
  '</div>';
  row.querySelector('[data-field="groupName"]').value = String(group.name || '');
  row.querySelector('[data-field="groupCacheMinutes"]').value = String(group.cacheMinutes == null ? 30 : group.cacheMinutes);
  row.querySelector('[data-field="groupType"]').value = group.groupType === 'load_balanced' ? 'load_balanced' : 'standard';
  row.querySelector('[data-field="maxUsersPerSubscription"]').value = String(group.maxUsersPerSubscription == null ? 10 : group.maxUsersPerSubscription);
  row.querySelector('[data-field="resetPeriodDays"]').value = String(group.resetPeriodDays == null ? 30 : group.resetPeriodDays);
  row.querySelector('[data-field="minAppVersion"]').value = String(group.minAppVersion || '');
  row.querySelector('[data-field="maxAppVersion"]').value = String(group.maxAppVersion || '');
  updateSubscriptionGroupTypeSettings(row);
  el('subscriptionRows').appendChild(row);
  row.querySelector('[data-field="minAppVersion"]').addEventListener('change', updateSubscriptionAppVersionOptions);
  row.querySelector('[data-field="maxAppVersion"]').addEventListener('change', updateSubscriptionAppVersionOptions);
  row.querySelector('[data-field="groupType"]').addEventListener('change', function() {
    updateSubscriptionGroupTypeSettings(row);
    updateSourceRowsMeta();
  });
  ['maxUsersPerSubscription', 'resetPeriodDays'].forEach(function(field) {
    row.querySelector('[data-field="' + field + '"]').addEventListener('input', updateSourceRowsMeta);
  });
  row.querySelector('[data-field="groupOrder"]').addEventListener('change', function() {
    moveSubscriptionGroupToOrder(row, this.value);
  });
  row.querySelector('[data-field="groupName"]').addEventListener('input', updateSubscriptionGroupNavigation);
  const permissionsDialog = row.querySelector('[data-group-permissions-dialog]');
  row.querySelectorAll('[data-action="permissions-close"]').forEach(function(button) {
    button.onclick = function() { permissionsDialog.close(); };
  });
  permissionsDialog.addEventListener('click', function(event) {
    if (event.target === permissionsDialog) permissionsDialog.close();
  });
  row.querySelector('[data-action="group-delete"]').onclick = function() {
    if (permissionsDialog.open) permissionsDialog.close();
    const wasActive = activeSubscriptionGroupId === row.dataset.groupId;
    if (selectedSubscriptionRow && row.contains(selectedSubscriptionRow)) selectSubscriptionSource(null);
    row.querySelectorAll('.source-row').forEach(function(source) { selectedSubscriptionRows.delete(source); });
    row.remove();
    updateSubscriptionGroupControls();
    renderSubscriptionGroupNavigation();
    if (wasActive) {
      const nextGroup = getSubscriptionGroupRows()[0];
      selectSubscriptionGroup(nextGroup ? nextGroup.dataset.groupId : '__all__');
    }
    updateSourceRowsMeta();
    markSettingsDirty('subscriptions');
  };
  updateSubscriptionGroupControls();
  renderSubscriptionGroupNavigation();
  updateSubscriptionAppVersionOptions();
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
  if (isNewGroup) selectSubscriptionGroup(row.dataset.groupId);
  return row;
}

function updateSubscriptionAppVersionOptions() {
  const host = el('subscriptionAppVersionOptions');
  if (!host) return;
  const versions = new Set();
  const versionInput = document.getElementById('appVersion');
  const currentVersion = String(versionInput && versionInput.value || currentState && currentState.appUpdate && currentState.appUpdate.version || '').trim();
  if (currentVersion) versions.add(currentVersion);
  getSubscriptionGroupRows().forEach(function(group) {
    ['minAppVersion', 'maxAppVersion'].forEach(function(field) {
      const value = String(group.querySelector('[data-field="' + field + '"]').value || '').trim();
      if (value) versions.add(value);
    });
  });
  const sortedVersions = Array.from(versions).sort(function(left, right) {
    const leftParts = left.replace(/^v/i, '').split('.').map(Number);
    const rightParts = right.replace(/^v/i, '').split('.').map(Number);
    for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
      const difference = (leftParts[index] || 0) - (rightParts[index] || 0);
      if (difference) return difference;
    }
    return left.localeCompare(right);
  });
  host.innerHTML = sortedVersions.map(function(version) {
    return '<option value="' + esc(version) + '"></option>';
  }).join('');
}

function compareAppVersions(left, right) {
  const normalize = function(value) {
    const version = String(value || '').trim().replace(/^v/i, '');
    if (!/^\d+(?:\.\d+)*$/.test(version)) return null;
    return version.split('.').map(function(part) { return Number(part); });
  };
  const leftParts = normalize(left);
  const rightParts = normalize(right);
  if (!leftParts || !rightParts) return null;
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const difference = (leftParts[index] || 0) - (rightParts[index] || 0);
    if (difference) return difference < 0 ? -1 : 1;
  }
  return 0;
}

function validateSubscriptionGroupVersions(groups) {
  for (let index = 0; index < groups.length; index += 1) {
    const group = groups[index];
    if (!Number.isInteger(group.cacheMinutes) || group.cacheMinutes < 0 || group.cacheMinutes > 10080) {
      return '分组「' + group.name + '」的接口缓存时间需为 0 至 10080 的整数分钟';
    }
    if (group.groupType === 'load_balanced' &&
        (!Number.isInteger(group.maxUsersPerSubscription) || group.maxUsersPerSubscription < 1 || group.maxUsersPerSubscription > 1000000)) {
      return '分组「' + group.name + '」的每订阅最大分配人数需为 1 至 1000000 的整数';
    }
    if (group.groupType === 'load_balanced' &&
        (!Number.isInteger(group.resetPeriodDays) || group.resetPeriodDays < 1 || group.resetPeriodDays > 3650)) {
      return '分组「' + group.name + '」的重置周期需为 1 至 3650 天的整数';
    }
    if (group.minAppVersion && compareAppVersions(group.minAppVersion, group.minAppVersion) === null) {
      return '分组「' + group.name + '」的左侧版本格式无效';
    }
    if (group.maxAppVersion && compareAppVersions(group.maxAppVersion, group.maxAppVersion) === null) {
      return '分组「' + group.name + '」的右侧版本格式无效';
    }
    if (group.minAppVersion && group.maxAppVersion &&
        compareAppVersions(group.minAppVersion, group.maxAppVersion) > 0) {
      return '分组「' + group.name + '」的左侧版本不能高于右侧版本';
    }
  }
  return '';
}

function moveSubscriptionGroupToOrder(row, requestedOrder) {
  const groups = getSubscriptionGroupRows();
  const index = groups.indexOf(row);
  if (index < 0) return;
  const order = Number(requestedOrder);
  const targetIndex = Number.isInteger(order) && order > 0
    ? Math.min(order - 1, groups.length - 1)
    : index;
  const reordered = groups.filter(function(group) { return group !== row; });
  reordered.splice(targetIndex, 0, row);
  reordered.forEach(function(group) { row.parentElement.appendChild(group); });
  updateSubscriptionGroupControls();
  renderSubscriptionGroupNavigation();
  updateSourceRowsMeta();
}

function updateSubscriptionGroupControls() {
  const groups = getSubscriptionGroupRows();
  groups.forEach(function(group, index) {
    const order = group.querySelector('[data-field="groupOrder"]');
    if (order) order.value = String(index + 1);
    const proxy = Array.from(document.querySelectorAll('[data-group-order-for]')).find(function(input) {
      return input.dataset.groupOrderFor === group.dataset.groupId;
    });
    if (proxy && document.activeElement !== proxy) proxy.value = String(index + 1);
  });
}

function addSource(groupRow, source, sourceOrderValue) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  row.dataset.sourceId = String(source.id || '');
  row.dataset.contentKey = String(source.contentKey || '');
  row.dataset.persistedUrl = String(source.url || '');
  row.dataset.persistedEnabled = String(source.enabled !== false);
  const existingOrders = Array.from(groupRow.querySelectorAll('.source-row')).map(sourceOrder);
  row.dataset.sourceOrder = Number.isFinite(Number(sourceOrderValue)) && Number(sourceOrderValue) > 0
    ? String(Number(sourceOrderValue))
    : String(existingOrders.length ? Math.max.apply(null, existingOrders) + 1 : 1);
  const sourceType = source.sourceType === 'content' || source.content ? 'content' : 'url';
  row.innerHTML = subscriptionIcon('grip', 'source-row-drag') +
    '<input class="source-row-select" type="checkbox" data-source-select aria-label="选择订阅：' + esc(source.name || '未命名订阅') + '">' +
    '<div class="source-row-sort-controls"><input data-field="sourceOrder" type="number" min="1" step="1" value="' + row.dataset.sourceOrder + '" aria-label="订阅排序序号"></div>' +
    '<button class="source-name-cell" type="button" data-action="details" aria-haspopup="dialog" aria-controls="subscriptionEditor" aria-label="编辑订阅：' + esc(source.name || '未命名订阅') + '"><span class="source-name-line"><strong data-source-display-name>' + esc(source.name || '未命名订阅') + '</strong><span class="source-type-badge" data-source-type-label>' + (sourceType === 'content' ? '本地' : 'URL') + '</span></span></button>' +
    '<div class="source-traffic" data-source-traffic aria-live="polite"><div class="source-traffic-line"><span data-source-traffic-text>等待读取流量信息…</span></div><div class="source-traffic-track" data-source-traffic-track role="progressbar" aria-label="订阅流量使用比例" aria-valuemin="0" aria-valuemax="100" hidden><span class="source-traffic-progress" data-source-traffic-progress></span></div></div>' +
    '<div class="source-expiry-cell"><span class="source-expiry" data-source-expiry></span></div>' +
    '<span class="source-row-remark" data-source-display-remark>' + esc(source.remark || '—') + '</span>' +
    '<span class="subscription-state-badge source-row-status" data-source-status>' + (sourceType === 'content' ? '本地' : '未知') + '</span>' +
    '<label class="subscription-switch source-enabled-check"><span class="subscription-switch-control"><input data-field="enabled" type="checkbox" aria-label="启用订阅" ' + (source.enabled === false ? '' : 'checked') + '><span class="subscription-switch-track"><span></span></span></span></label>' +
    '<button class="source-settings-button" type="button" data-action="source-settings" aria-haspopup="dialog" aria-controls="subscriptionEditor" aria-label="设置订阅：' + esc(source.name || '未命名订阅') + '" title="设置订阅：' + esc(source.name || '未命名订阅') + '">' + subscriptionIcon('wrench') + '<span>设置</span></button>' +
    '<div class="source-row-fields" hidden>' +
      '<input data-field="name" type="text" value="' + esc(source.name) + '">' +
      '<input data-field="remark" type="text" value="' + esc(source.remark) + '">' +
      '<select data-field="sourceType"><option value="url" ' + (sourceType === 'url' ? 'selected' : '') + '>远程订阅</option><option value="content" ' + (sourceType === 'content' ? 'selected' : '') + '>本地文本</option></select>' +
      '<div class="source-mode" data-source-mode="url"><input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/sub"><label class="check"><input data-field="autoRename" type="checkbox" ' + (source.autoRename === true ? 'checked' : '') + '>自动重命名节点</label></div>' +
      '<input data-field="deduplicate" type="checkbox" ' + (source.deduplicate !== false ? 'checked' : '') + '>' +
      '<div class="source-mode" data-source-mode="content"><textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></div>' +
    '</div>';
  row.querySelector('[data-field="sourceType"]').onchange = function() {
    updateSourceMode(row);
    updateSourceRowPresentation(row);
  };
  row.querySelector('[data-field="url"]').addEventListener('input', function() {
    updateSourceRowPresentation(row);
  });
  row.querySelector('[data-field="name"]').addEventListener('input', updateSourceRowsMeta);
  row.querySelector('[data-action="details"]').onclick = function() {
    openSubscriptionEditor(row);
  };
  row.querySelector('[data-action="source-settings"]').onclick = function() {
    openSubscriptionEditor(row);
  };
  row.querySelector('[data-field="sourceOrder"]').addEventListener('change', function() {
    moveSourceRowToOrder(row, this.value);
  });
  bindSubscriptionSourceSwitch(row);
  updateSourceMode(row);
  updateSourceRowPresentation(row);
  insertSourceByOrder(row, groupRow);
  updateSubscriptionSourceOrder(groupRow);
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
  return row;
}
function getSourceRows() {
  return Array.from(el('subscriptionRows').querySelectorAll('.source-row'));
}
function updateSourceRowsMeta() {
  getSubscriptionGroupRows().forEach(function(group) {
    const container = group.querySelector('[data-group-sources]');
    const rows = Array.from(container.children).filter(function(row) {
      return row.classList.contains('source-row');
    });
    const count = group.querySelector('[data-group-count]');
    const enabledCount = rows.filter(function(row) { return row.querySelector('[data-field="enabled"]').checked; }).length;
    if (count) count.textContent = rows.length + ' 条订阅 · ' + enabledCount + ' 条启用';
    rows.forEach(function(row, index) {
      const nameInput = row.querySelector('[data-field="name"]');
      if (nameInput) nameInput.placeholder = '订阅' + sourceOrder(row);
      const displayName = row.querySelector('[data-source-display-name]');
      const displayRemark = row.querySelector('[data-source-display-remark]');
      const type = row.querySelector('[data-field="sourceType"]').value;
      const sourceName = nameInput.value.trim() || nameInput.placeholder;
      if (displayName) displayName.textContent = sourceName;
      const detailsButton = row.querySelector('[data-action="details"]');
      if (detailsButton) detailsButton.setAttribute('aria-label', '编辑订阅：' + sourceName);
      const settingsButton = row.querySelector('[data-action="source-settings"]');
      if (settingsButton) {
        settingsButton.setAttribute('aria-label', '设置订阅：' + sourceName);
        settingsButton.title = '设置订阅：' + sourceName;
      }
      if (displayRemark) displayRemark.textContent = row.querySelector('[data-field="remark"]').value.trim() || '—';
      const typeBadge = row.querySelector('[data-source-type-label]');
      if (typeBadge) typeBadge.textContent = type === 'content' ? '本地' : 'URL';
      const status = row.querySelector('[data-source-status]');
      if (status) {
        const health = type === 'content' ? 'local' : row.dataset.sourceHealth || 'unknown';
        status.textContent = health === 'normal' ? '正常' : health === 'local' ? '本地' : '未知';
        status.className = 'subscription-state-badge source-row-status ' + health;
      }
      const orderInput = row.querySelector('[data-field="sourceOrder"]');
      if (orderInput) orderInput.value = String(sourceOrder(row));
    });
  });
  const groups = getSubscriptionGroupRows();
  const allRows = getSourceRows();
  const enabledCount = allRows.filter(function(row) { return row.querySelector('[data-field="enabled"]').checked; }).length;
  const summary = el('subscriptionSummary');
  if (summary) summary.textContent = groups.length + ' 个分组 · ' + allRows.length + ' 条订阅 · ' + enabledCount + ' 条启用';
  updateSubscriptionGroupNavigation();
  applySubscriptionFilters();
}

function renderSubscriptionGroupNavigation() {
  const nav = el('subscriptionGroupNavigation');
  if (!nav) return;
  const groups = getSubscriptionGroupRows();
  const allCount = getSourceRows().length;
  const rows = ['<button class="subscription-group-nav-item" type="button" data-group-nav="__all__"><span class="subscription-nav-icon" aria-hidden="true">' + subscriptionIcon('all') + '</span><span class="subscription-nav-name">全部订阅</span><span class="subscription-nav-count">' + allCount + '</span></button>'];
  groups.forEach(function(group, index) {
    const groupName = group.querySelector('[data-field="groupName"]').value.trim() || '分组' + (index + 1);
    const count = group.querySelectorAll('[data-group-sources] > .source-row').length;
    const order = group.querySelector('[data-field="groupOrder"]').value || String(index + 1);
    const enabled = group.querySelector('[data-field="groupEnabled"]').checked;
    const groupId = group.dataset.groupId;
    rows.push('<div class="subscription-group-nav-entry" data-group-nav-entry="' + esc(group.dataset.groupId) + '">' +
      '<input class="subscription-group-order-proxy" type="number" min="1" step="1" value="' + esc(order) + '" data-group-order-for="' + esc(groupId) + '" aria-label="' + esc(groupName) + '排序序号">' +
      '<div class="subscription-group-nav-row">' +
      '<button class="subscription-group-nav-item" type="button" data-group-nav="' + esc(groupId) + '"><span class="subscription-nav-icon" aria-hidden="true">' + subscriptionIcon('folder') + '</span><span class="subscription-nav-name">' + esc(groupName) + '</span><span class="subscription-nav-count">' + count + '</span></button>' +
      '<div class="subscription-group-nav-actions" role="group" aria-label="' + esc(groupName) + '操作">' +
        '<label class="subscription-switch subscription-group-nav-toggle" title="启用分组：' + esc(groupName) + '"><span class="subscription-switch-control"><input type="checkbox" data-group-enabled-for="' + esc(groupId) + '" aria-label="启用分组：' + esc(groupName) + '" ' + (enabled ? 'checked' : '') + '><span class="subscription-switch-track" aria-hidden="true"><span></span></span></span></label>' +
        '<button class="secondary subscription-group-nav-settings" type="button" data-group-settings-for="' + esc(groupId) + '" aria-haspopup="dialog" title="分组设置：' + esc(groupName) + '" aria-label="分组设置：' + esc(groupName) + '">' + subscriptionIcon('wrench') + '</button>' +
      '</div>' +
      '</div>' +
      '</div>');
  });
  nav.innerHTML = rows.join('');
  nav.querySelectorAll('[data-group-nav]').forEach(function(button) {
    button.addEventListener('click', function() {
      selectSubscriptionGroup(button.dataset.groupNav);
    });
  });
  nav.querySelectorAll('[data-group-enabled-for]').forEach(function(input) {
    input.addEventListener('change', function() {
      const group = groups.find(function(candidate) { return candidate.dataset.groupId === input.dataset.groupEnabledFor; });
      const enabled = group && group.querySelector('[data-field="groupEnabled"]');
      if (!enabled) return;
      enabled.checked = input.checked;
      markSettingsDirty('subscriptions');
      updateSubscriptionGroupNavigation();
    });
  });
  nav.querySelectorAll('[data-group-settings-for]').forEach(function(button) {
    button.addEventListener('click', function() {
      const group = groups.find(function(candidate) { return candidate.dataset.groupId === button.dataset.groupSettingsFor; });
      const dialog = group && group.querySelector('[data-group-permissions-dialog]');
      if (!group || !dialog) return;
      selectSubscriptionGroup(group.dataset.groupId);
      if (!dialog.open) dialog.showModal();
    });
  });
  nav.querySelectorAll('[data-group-order-for]').forEach(function(input) {
    input.addEventListener('change', function() {
      const group = groups.find(function(candidate) { return candidate.dataset.groupId === input.dataset.groupOrderFor; });
      const order = group && group.querySelector('[data-field="groupOrder"]');
      if (!group || !order) return;
      order.value = input.value;
      moveSubscriptionGroupToOrder(group, input.value);
    });
  });
  updateSubscriptionGroupNavigation();
}

function updateSubscriptionGroupNavigation() {
  const nav = el('subscriptionGroupNavigation');
  if (!nav) return;
  const groups = getSubscriptionGroupRows();
  const total = getSourceRows().length;
  nav.querySelectorAll('[data-group-nav]').forEach(function(button) {
    const id = button.dataset.groupNav;
    button.classList.toggle('active', id === activeSubscriptionGroupId);
    const entry = button.closest('.subscription-group-nav-entry');
    const row = entry && entry.querySelector('.subscription-group-nav-row');
    if (row) row.classList.toggle('active', id === activeSubscriptionGroupId);
    if (id === '__all__') button.querySelector('.subscription-nav-count').textContent = String(total);
    const group = groups.find(function(candidate) { return candidate.dataset.groupId === id; });
    if (!group) return;
    const index = groups.indexOf(group);
    const groupName = group.querySelector('[data-field="groupName"]').value.trim() || '分组' + (index + 1);
    button.querySelector('.subscription-nav-name').textContent = groupName;
    button.querySelector('.subscription-nav-count').textContent = String(group.querySelectorAll('[data-group-sources] > .source-row').length);
    const enabledInput = Array.from(nav.querySelectorAll('[data-group-enabled-for]')).find(function(input) {
      return input.dataset.groupEnabledFor === id;
    });
    if (enabledInput) {
      if (document.activeElement !== enabledInput) enabledInput.checked = group.querySelector('[data-field="groupEnabled"]').checked;
      enabledInput.setAttribute('aria-label', '启用分组：' + groupName);
      enabledInput.closest('label').title = '启用分组：' + groupName;
    }
    const settingsButton = Array.from(nav.querySelectorAll('[data-group-settings-for]')).find(function(control) {
      return control.dataset.groupSettingsFor === id;
    });
    if (settingsButton) {
      settingsButton.setAttribute('aria-label', '分组设置：' + groupName);
      settingsButton.title = '分组设置：' + groupName;
    }
    const order = group.querySelector('[data-field="groupOrder"]');
    const proxy = Array.from(nav.querySelectorAll('[data-group-order-for]')).find(function(input) {
      return input.dataset.groupOrderFor === id;
    });
    if (order && proxy && document.activeElement !== proxy) proxy.value = order.value;
  });
}

function selectSubscriptionGroup(groupId, resetPage) {
  activeSubscriptionGroupId = groupId || '__all__';
  if (resetPage !== false) subscriptionCurrentPage = 1;
  const groups = getSubscriptionGroupRows();
  groups.forEach(function(group) {
    const active = activeSubscriptionGroupId === '__all__' || group.dataset.groupId === activeSubscriptionGroupId;
    group.hidden = !active;
    group.classList.toggle('active', group.dataset.groupId === activeSubscriptionGroupId);
  });
  updateSubscriptionGroupNavigation();
  applySubscriptionFilters();
  if (selectedSubscriptionRow && activeSubscriptionGroupId !== '__all__' &&
      (!selectedSubscriptionRow.closest('.subscription-group') ||
       selectedSubscriptionRow.closest('.subscription-group').dataset.groupId !== activeSubscriptionGroupId)) {
    const group = groups.find(function(candidate) { return candidate.dataset.groupId === activeSubscriptionGroupId; });
    selectSubscriptionSource(group && group.querySelector('.source-row'));
  }
}

function selectSubscriptionSource(row) {
  if (selectedSubscriptionRow) selectedSubscriptionRow.classList.remove('selected');
  selectedSubscriptionRow = row || null;
  const editor = el('subscriptionEditor');
  const empty = editor.querySelector('[data-subscription-editor-empty]');
  const content = editor.querySelector('[data-subscription-editor-content]');
  editor.classList.toggle('is-empty', !row);
  if (empty) empty.hidden = Boolean(row);
  if (content) content.hidden = !row;
  if (!row) {
    if (editor.open) editor.close();
    return;
  }
  row.classList.add('selected');
  ['name', 'url', 'remark', 'sourceType', 'content', 'autoRename', 'deduplicate'].forEach(function(field) {
    const sourceControl = row.querySelector('[data-field="' + field + '"]');
    const editorControl = editor.querySelector('[data-editor-field="' + field + '"]');
    if (!sourceControl || !editorControl) return;
    if (sourceControl.type === 'checkbox') editorControl.checked = sourceControl.checked;
    else editorControl.value = sourceControl.value;
  });
  const group = row.closest('.subscription-group');
  const groupSelect = editor.querySelector('[data-editor-field="group"]');
  groupSelect.innerHTML = groupsForEditorOptions();
  groupSelect.value = group ? group.dataset.groupId : '';
  updateSubscriptionEditorType(row.querySelector('[data-field="sourceType"]').value);
  updateSubscriptionEditorTraffic(row);
}

function openSubscriptionEditor(row) {
  if (!row) return;
  selectSubscriptionSource(row);
  const editor = el('subscriptionEditor');
  if (!editor.open) editor.showModal();
}

function groupsForEditorOptions() {
  return getSubscriptionGroupRows().map(function(group, index) {
    const name = group.querySelector('[data-field="groupName"]').value.trim() || '分组' + (index + 1);
    return '<option value="' + esc(group.dataset.groupId) + '">' + esc(name) + '</option>';
  }).join('');
}

function moveSubscriptionToGroup(row, targetGroupId) {
  if (!row || !row.isConnected) return;
  const currentGroup = row.closest('.subscription-group');
  const targetGroup = getSubscriptionGroupRows().find(function(group) {
    return group.dataset.groupId === targetGroupId;
  });
  if (!currentGroup || !targetGroup || currentGroup === targetGroup) return;

  const targetSources = targetGroup.querySelector('[data-group-sources]');
  if (!targetSources) return;
  targetSources.appendChild(row);
  updateSubscriptionSourceOrder(currentGroup);
  updateSubscriptionSourceOrder(targetGroup);

  if (activeSubscriptionGroupId === currentGroup.dataset.groupId) {
    selectSubscriptionGroup(targetGroup.dataset.groupId, false);
  } else {
    updateSourceRowsMeta();
  }

  // 移动后将列表翻到订阅所在页，避免弹窗仍开着但列表里找不到当前项。
  const rowPosition = Number(row.style.order);
  if (Number.isFinite(rowPosition) && rowPosition >= 0) {
    subscriptionCurrentPage = Math.floor(rowPosition / subscriptionPageSize) + 1;
    applySubscriptionFilters();
  }
  markSettingsDirty('subscriptions');
}

function updateSubscriptionEditorType(type) {
  const infoField = el('subscriptionEditor').querySelector('[data-editor-source-info]');
  const fieldName = type === 'content' ? 'content' : 'url';
  const sourceField = selectedSubscriptionRow && selectedSubscriptionRow.querySelector('[data-field="' + fieldName + '"]');
  infoField.dataset.editorField = fieldName;
  infoField.value = sourceField ? sourceField.value : '';
  infoField.rows = type === 'content' ? 8 : 3;
  infoField.placeholder = type === 'content'
    ? 'vless://...\nhy2://...\nss://...'
    : 'https://example.com/sub';
}

function updateSubscriptionEditorTraffic(row) {
  const traffic = row && row.subscriptionTraffic;
  const unavailable = row && row.subscriptionTrafficUnavailable;
  const state = el('subscriptionEditor').querySelector('[data-editor-traffic-state]');
  const set = function(name, value) {
    const target = el('subscriptionEditor').querySelector('[data-editor-traffic-' + name + ']');
    if (target) target.textContent = value;
  };
  const bar = el('subscriptionEditor').querySelector('[data-editor-traffic-progress]');
  const percent = el('subscriptionEditor').querySelector('[data-editor-traffic-percent]');
  if (!traffic) {
    set('used', '—');
    set('total', '—');
    set('expiry', '—');
    set('updated', unavailable ? '读取失败' : '—');
    if (bar) bar.style.width = '0%';
    if (percent) percent.textContent = '—';
    if (state) {
      state.textContent = row && row.querySelector('[data-field="sourceType"]').value === 'content' ? '本地' : '未知';
      state.className = 'subscription-state-badge ' + (row && row.querySelector('[data-field="sourceType"]').value === 'content' ? 'local' : 'unknown');
    }
    return;
  }
  const upload = Math.max(0, Number(traffic.uploadBytes) || 0);
  const download = Math.max(0, Number(traffic.downloadBytes) || 0);
  const used = upload + download;
  const total = Math.max(0, Number(traffic.totalBytes) || 0);
  const ratio = total > 0 ? Math.min(100, Math.max(0, used / total * 100)) : 0;
  set('used', formatSubscriptionTrafficBytes(used));
  set('total', total > 0 ? formatSubscriptionTrafficBytes(total) : '未提供');
  set('expiry', row.querySelector('[data-source-expiry]').textContent || '—');
  set('updated', row.dataset.trafficUpdatedAt || '—');
  if (bar) bar.style.width = ratio + '%';
  if (percent) percent.textContent = total > 0 ? Math.round(ratio) + '%' : '—';
  if (state) {
    state.textContent = '正常';
    state.className = 'subscription-state-badge normal';
  }
}

function applySubscriptionFilters() {
  const groups = getSubscriptionGroupRows();
  const rows = getSourceRows();
  const localSearch = (document.querySelector('[data-source-search]') || {}).value || '';
  const localQuery = localSearch.trim().toLocaleLowerCase();
  const healthFilter = (document.querySelector('[data-source-health-filter]') || {}).value || 'all';
  const typeFilter = (document.querySelector('[data-source-type-filter]') || {}).value || 'all';
  const sort = (document.querySelector('[data-source-sort]') || {}).value || 'custom';
  const matches = rows.filter(function(row) {
    const group = row.closest('.subscription-group');
    if (activeSubscriptionGroupId !== '__all__' && group.dataset.groupId !== activeSubscriptionGroupId) return false;
    const type = row.querySelector('[data-field="sourceType"]').value;
    const health = type === 'content' ? 'local' : row.dataset.sourceHealth || 'unknown';
    const text = [row.querySelector('[data-field="name"]').value, row.querySelector('[data-field="remark"]').value,
      row.querySelector('[data-field="url"]').value].join(' ').toLocaleLowerCase();
    return (!localQuery || text.includes(localQuery)) &&
      (typeFilter === 'all' || type === typeFilter) &&
      (healthFilter === 'all' || health === healthFilter);
  });
  matches.sort(function(left, right) {
    const groupOrder = groups.indexOf(left.closest('.subscription-group')) -
      groups.indexOf(right.closest('.subscription-group'));
    if (groupOrder !== 0) return groupOrder;
    if (sort === 'name' || sort === 'name-desc') {
      const compared = left.querySelector('[data-field="name"]').value.localeCompare(right.querySelector('[data-field="name"]').value, 'zh-CN');
      return sort === 'name-desc' ? -compared : compared;
    }
    return sourceOrder(left) - sourceOrder(right);
  });
  const pageCount = Math.max(1, Math.ceil(matches.length / subscriptionPageSize));
  subscriptionCurrentPage = Math.min(Math.max(1, subscriptionCurrentPage), pageCount);
  const start = (subscriptionCurrentPage - 1) * subscriptionPageSize;
  const pageRows = matches.slice(start, start + subscriptionPageSize);
  const pageSet = new Set(pageRows);
  const matchSet = new Set(matches);
  rows.forEach(function(row) {
    row.hidden = !pageSet.has(row);
    row.style.order = String(matches.indexOf(row));
  });
  groups.forEach(function(group) {
    const active = activeSubscriptionGroupId === '__all__' || group.dataset.groupId === activeSubscriptionGroupId;
    const hasMatches = Array.from(group.querySelectorAll('.source-row')).some(function(row) { return matchSet.has(row); });
    group.hidden = !active || (activeSubscriptionGroupId === '__all__' && !hasMatches);
    const groupEmpty = group.querySelector('[data-group-subscription-empty]');
    const showGroupEmpty = active && activeSubscriptionGroupId !== '__all__' && !hasMatches;
    if (groupEmpty) groupEmpty.hidden = !showGroupEmpty;
    const groupSources = group.querySelector('[data-group-sources]');
    if (groupSources) groupSources.classList.toggle('is-empty', showGroupEmpty);
  });
  const empty = document.querySelector('[data-subscription-empty]');
  if (empty) empty.hidden = matches.length > 0 || activeSubscriptionGroupId !== '__all__';
  const count = document.querySelector('[data-subscription-filtered-count]');
  if (count) count.textContent = '共 ' + matches.length + ' 条';
  const pageNumber = document.querySelector('[data-subscription-page-number]');
  if (pageNumber) pageNumber.textContent = String(subscriptionCurrentPage);
  const previous = document.querySelector('[data-subscription-page-prev]');
  const next = document.querySelector('[data-subscription-page-next]');
  if (previous) previous.disabled = subscriptionCurrentPage <= 1;
  if (next) next.disabled = subscriptionCurrentPage >= pageCount;
  const selectPage = document.querySelector('.subscription-select-page');
  if (selectPage) {
    const selected = pageRows.filter(function(row) { return selectedSubscriptionRows.has(row); }).length;
    selectPage.checked = pageRows.length > 0 && selected === pageRows.length;
    selectPage.indeterminate = selected > 0 && selected < pageRows.length;
  }
  const selectedMeta = document.querySelector('[data-subscription-selection-meta]');
  if (selectedMeta) selectedMeta.textContent = '已选择 ' + selectedSubscriptionRows.size + ' 项';
  const deleteSelectedButton = document.querySelector('[data-action="delete-selected-subscriptions"]');
  if (deleteSelectedButton) deleteSelectedButton.disabled = selectedSubscriptionRows.size === 0;
  const workspace = document.querySelector('.subscription-group-workspace');
  if (workspace) workspace.classList.toggle('grid-view', subscriptionView === 'grid');
}

function deleteSelectedSubscriptions() {
  Array.from(selectedSubscriptionRows).forEach(function(row) {
    if (!row.isConnected || !row.classList.contains('source-row')) selectedSubscriptionRows.delete(row);
  });
  const rows = Array.from(selectedSubscriptionRows);
  if (!rows.length) {
    applySubscriptionFilters();
    return;
  }
  if (!window.confirm('确定删除已选中的 ' + rows.length + ' 条订阅吗？此操作无法撤销。')) return;
  if (selectedSubscriptionRow && rows.indexOf(selectedSubscriptionRow) >= 0) selectSubscriptionSource(null);
  const groups = new Set();
  rows.forEach(function(row) {
    const group = row.closest('.subscription-group');
    if (group) groups.add(group);
    selectedSubscriptionRows.delete(row);
    row.remove();
  });
  groups.forEach(updateSubscriptionSourceOrder);
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
}

function initializeSubscriptionManager() {
  const panel = document.querySelector('[data-panel="subscriptions"]');
  const editor = el('subscriptionEditor');
  editor.addEventListener('close', function() {
    if (selectedSubscriptionRow) selectSubscriptionSource(null);
  });
  ['input', 'change'].forEach(function(type) {
    editor.querySelectorAll('[data-editor-field]').forEach(function(control) {
      control.addEventListener(type, function() {
        if (!selectedSubscriptionRow) return;
        const field = control.dataset.editorField;
        if (field === 'group') {
          if (type === 'change') moveSubscriptionToGroup(selectedSubscriptionRow, control.value);
          return;
        }
        const sourceControl = selectedSubscriptionRow.querySelector('[data-field="' + field + '"]');
        if (!sourceControl) return;
        if (sourceControl.type === 'checkbox') sourceControl.checked = control.checked;
        else sourceControl.value = control.value;
        if (field === 'sourceType') {
          updateSourceMode(selectedSubscriptionRow);
          updateSourceRowPresentation(selectedSubscriptionRow);
          updateSubscriptionEditorType(control.value);
        }
        if (field === 'url') updateSourceRowPresentation(selectedSubscriptionRow);
        updateSourceRowsMeta();
      });
    });
  });
  panel.querySelectorAll('[data-action="add-group"]').forEach(function(button) {
    button.addEventListener('click', function() { addSubscriptionGroup(); });
  });
  panel.querySelector('[data-action="add-subscription"]').addEventListener('click', function() {
    let group = selectedSubscriptionRow && selectedSubscriptionRow.closest('.subscription-group');
    if (!group && activeSubscriptionGroupId !== '__all__') {
      group = getSubscriptionGroupRows().find(function(candidate) { return candidate.dataset.groupId === activeSubscriptionGroupId; });
    }
    if (!group) group = getSubscriptionGroupRows()[0];
    if (!group) group = addSubscriptionGroup();
    openSubscriptionImport(group);
  });
  panel.querySelector('[data-action="delete-selected-subscriptions"]').addEventListener('click', deleteSelectedSubscriptions);
  panel.querySelectorAll('[data-action="close-subscription-editor"]').forEach(function(button) {
    button.addEventListener('click', function() { selectSubscriptionSource(null); });
  });
  panel.querySelector('[data-action="copy-subscription-info"]').addEventListener('click', async function() {
    const input = editor.querySelector('[data-editor-source-info]');
    if (!input.value) return show('没有可复制的订阅信息', true);
    try { await navigator.clipboard.writeText(input.value); show('订阅信息已复制'); }
    catch (_) { input.focus(); input.select(); show('无法自动复制，请手动复制订阅信息', true); }
  });
  panel.querySelector('[data-source-search]').addEventListener('input', function() { subscriptionCurrentPage = 1; applySubscriptionFilters(); });
  ['[data-source-health-filter]', '[data-source-type-filter]', '[data-source-sort]'].forEach(function(selector) {
    panel.querySelector(selector).addEventListener('change', function() { subscriptionCurrentPage = 1; applySubscriptionFilters(); });
  });
  panel.querySelector('[data-subscription-page-size]').addEventListener('change', function() {
    subscriptionPageSize = Number(this.value) || 20;
    subscriptionCurrentPage = 1;
    applySubscriptionFilters();
  });
  panel.querySelector('[data-subscription-page-prev]').addEventListener('click', function() { subscriptionCurrentPage -= 1; applySubscriptionFilters(); });
  panel.querySelector('[data-subscription-page-next]').addEventListener('click', function() { subscriptionCurrentPage += 1; applySubscriptionFilters(); });
  panel.querySelector('.subscription-select-page').addEventListener('change', function() {
    const pageSize = subscriptionPageSize;
    const rows = getSourceRows().filter(function(row) { return !row.hidden; }).slice(0, pageSize);
    rows.forEach(function(row) {
      if (this.checked) selectedSubscriptionRows.add(row);
      else selectedSubscriptionRows.delete(row);
      row.querySelector('[data-source-select]').checked = this.checked;
    }, this);
    applySubscriptionFilters();
  });
  panel.querySelectorAll('[data-subscription-view]').forEach(function(button) {
    button.addEventListener('click', function() {
      subscriptionView = button.dataset.subscriptionView;
      panel.querySelectorAll('[data-subscription-view]').forEach(function(viewButton) {
        const active = viewButton === button;
        viewButton.classList.toggle('active', active);
        viewButton.setAttribute('aria-pressed', active ? 'true' : 'false');
      });
      applySubscriptionFilters();
    });
  });
  el('subscriptionRows').addEventListener('change', function(event) {
    const row = event.target.closest('.source-row');
    if (!row || !event.target.matches('[data-source-select]')) return;
    if (event.target.checked) selectedSubscriptionRows.add(row);
    else selectedSubscriptionRows.delete(row);
    applySubscriptionFilters();
  });
}
function updateSubscriptionSourceOrder(group) {
  const rows = Array.from(group.querySelectorAll('[data-group-sources] > .source-row'));
  rows.forEach(function(row, index) {
    row.dataset.sourceOrder = String(index + 1);
    row.querySelector('[data-field="sourceOrder"]').value = String(index + 1);
  });
}
function updateSubscriptionGroupTypeSettings(row) {
  const balanced = row.querySelector('[data-field="groupType"]').value === 'load_balanced';
  row.querySelector('[data-group-type-settings]').hidden = !balanced;
}
function moveSourceRowToOrder(row, requestedOrder) {
  const container = row.parentElement;
  const rows = Array.from(container.children).filter(function(candidate) {
    return candidate.classList.contains('source-row');
  });
  const index = rows.indexOf(row);
  if (index < 0) return;
  const order = Number(requestedOrder);
  const targetIndex = Number.isInteger(order) && order > 0
    ? Math.min(order - 1, rows.length - 1)
    : index;
  rows.splice(index, 1);
  rows.splice(targetIndex, 0, row);
  rows.forEach(function(sourceRow) { container.appendChild(sourceRow); });
  updateSubscriptionSourceOrder(row.closest('.subscription-group'));
  updateSourceRowsMeta();
}
function updateSourceMode(row) {
  const type = row.querySelector('[data-field="sourceType"]').value;
  row.querySelectorAll('[data-source-mode]').forEach(function(panel) {
    const active = panel.dataset.sourceMode === type;
    panel.hidden = !active;
    panel.querySelectorAll('input, textarea, select').forEach(function(control) {
      control.disabled = !active;
    });
  });
}

// 后台用可视化表单维护导航配置，分组与网站顺序对应客户端显示顺序。
function addWebNavigationGroup(group) {
  const isNewGroup = !group;
  group = group || {};
  const row = document.createElement('section');
  row.className = 'web-nav-group';
  row.dataset.webNavGroupId = crypto.randomUUID();
  row.innerHTML = '<div class="web-nav-group-heading">' +
    '<label class="web-nav-group-name">分组名称<input data-field="title" type="text" placeholder="例如：国内测速"></label>' +
    '<div class="web-nav-controls" role="group" aria-label="分组排序和删除操作">' +
      '<span class="web-nav-group-count" data-group-count>0 个网站</span>' +
      '<button class="secondary" type="button" data-action="group-up" aria-label="上移分组" title="上移分组">↑</button>' +
      '<button class="secondary" type="button" data-action="group-down" aria-label="下移分组" title="下移分组">↓</button>' +
      '<button class="danger" type="button" data-action="group-delete">删除分组</button>' +
    '</div>' +
  '</div>' +
  '<div class="web-nav-items"></div>' +
  '<div class="web-nav-group-empty" data-group-empty hidden>该分组还没有网站入口。</div>' +
  '<button class="secondary web-nav-add-site" type="button" data-action="add-site">添加网站</button>';
  row.querySelector('[data-field="title"]').value = String(group.title || '');
  const container = el('webNavigationGroups');
  container.appendChild(row);
  if (isNewGroup) activeWebNavigationGroupId = row.dataset.webNavGroupId;
  const items = Array.isArray(group.items) ? group.items : [{}];
  items.forEach(function(item) { addWebNavigationItem(row, item); });
  row.querySelector('[data-field="title"]').addEventListener('input', renderWebNavigationGroupNavigation);
  row.querySelector('[data-action="group-up"]').onclick = function() {
    moveWebNavigationRow(row, -1);
  };
  row.querySelector('[data-action="group-down"]').onclick = function() {
    moveWebNavigationRow(row, 1);
  };
  row.querySelector('[data-action="group-delete"]').onclick = function() {
    const groupRows = getWebNavigationGroupRows();
    const groupIndex = groupRows.indexOf(row);
    const wasActive = row.dataset.webNavGroupId === activeWebNavigationGroupId;
    row.remove();
    if (wasActive) {
      const remainingGroups = getWebNavigationGroupRows();
      const nextGroup = remainingGroups[Math.min(groupIndex, remainingGroups.length - 1)];
      activeWebNavigationGroupId = nextGroup ? nextGroup.dataset.webNavGroupId : '';
    }
    updateWebNavigationControls();
    markSettingsDirty('navigation-page');
  };
  row.querySelector('[data-action="add-site"]').onclick = function() {
    addWebNavigationItem(row, {});
  };
  updateWebNavigationControls();
  markSettingsDirty('navigation-page');
}

function addWebNavigationItem(groupRow, item) {
  item = item || {};
  const isBlank = !String(item.title || '').trim() && !String(item.url || '').trim();
  const row = document.createElement('div');
  row.className = 'web-nav-item';
  const editorId = 'web-nav-item-editor-' + crypto.randomUUID();
  row.innerHTML = '<div class="web-nav-item-summary">' +
      '<div class="web-nav-item-identity">' +
        '<span class="web-nav-item-icon-fallback" data-role="icon-fallback" aria-hidden="true">站</span>' +
        '<div class="web-nav-icon-preview" data-role="icon-preview" hidden><img alt=""></div>' +
        '<div class="web-nav-item-copy"><strong data-role="summary-title">未命名网站</strong><span data-role="summary-url">请填写网站地址</span></div>' +
      '</div>' +
      '<div class="web-nav-item-controls" role="group" aria-label="网站操作">' +
        '<button class="secondary" type="button" data-action="edit-site" aria-expanded="' + (isBlank ? 'true' : 'false') + '" aria-controls="' + editorId + '">' + (isBlank ? '收起' : '编辑') + '</button>' +
        '<details class="web-nav-item-actions"><summary aria-label="网站排序和删除操作">更多</summary>' +
          '<div class="web-nav-item-action-menu" role="group" aria-label="网站排序和删除操作">' +
            '<button class="secondary" type="button" data-action="item-up" aria-label="上移网站">上移</button>' +
            '<button class="secondary" type="button" data-action="item-down" aria-label="下移网站">下移</button>' +
            '<button class="danger" type="button" data-action="item-delete">删除</button>' +
          '</div>' +
        '</details>' +
      '</div>' +
    '</div>' +
    '<div class="web-nav-item-editor" id="' + editorId + '" data-role="editor"' + (isBlank ? '' : ' hidden') + '>' +
      '<div class="web-nav-item-fields">' +
        '<label>网站名称<input data-field="title" type="text" placeholder="例如：中科大测速"></label>' +
        '<label>网站网址<input data-field="url" type="url" placeholder="https://example.com"></label>' +
      '</div>' +
      '<div class="web-nav-icon-editor">' +
      '<div class="web-nav-icon-actions">' +
        '<input data-field="icon-file" type="file" accept="image/png,image/jpeg,image/webp,image/gif,image/x-icon,image/vnd.microsoft.icon" hidden>' +
        '<button class="secondary" type="button" data-action="icon-upload">上传图标</button>' +
        '<button class="secondary" type="button" data-action="icon-fetch">从网站获取</button>' +
        '<button class="secondary" type="button" data-action="icon-clear">清除图标</button>' +
      '</div>' +
      '</div>' +
    '</div>';
  row.querySelector('[data-field="title"]').value = String(item.title || '');
  row.querySelector('[data-field="url"]').value = String(item.url || '');
  setWebNavigationIcon(row, item.iconBase64 || '', item.iconMimeType || '');
  updateWebNavigationItemSummary(row);
  groupRow.querySelector('.web-nav-items').appendChild(row);
  row.querySelector('[data-action="edit-site"]').onclick = function() {
    const editor = row.querySelector('[data-role="editor"]');
    const expanded = editor.hidden;
    editor.hidden = !expanded;
    this.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    this.textContent = expanded ? '收起' : '编辑';
  };
  ['title', 'url'].forEach(function(field) {
    row.querySelector('[data-field="' + field + '"]').addEventListener('input', function() {
      updateWebNavigationItemSummary(row);
    });
  });
  const fileInput = row.querySelector('[data-field="icon-file"]');
  row.querySelector('[data-action="icon-upload"]').onclick = function() { fileInput.click(); };
  fileInput.onchange = function() { readWebNavigationIconFile(row, fileInput); };
  row.querySelector('[data-action="icon-fetch"]').onclick = function() { fetchWebNavigationIcon(row); };
  row.querySelector('[data-action="icon-clear"]').onclick = function() {
    setWebNavigationIcon(row, '', '');
    fileInput.value = '';
    show('已清除网站图标，推送应用设置后生效');
  };
  row.querySelector('[data-action="item-up"]').onclick = function() {
    moveWebNavigationRow(row, -1);
  };
  row.querySelector('[data-action="item-down"]').onclick = function() {
    moveWebNavigationRow(row, 1);
  };
  row.querySelector('[data-action="item-delete"]').onclick = function() {
    row.remove();
    updateWebNavigationControls();
    markSettingsDirty('navigation-page');
  };
  updateWebNavigationControls();
  markSettingsDirty('navigation-page');
}

// 后台上传和抓取的图标统一保存为 MIME 类型加原始 Base64 数据。
function setWebNavigationIcon(row, iconBase64, iconMimeType) {
  markSettingsDirty('navigation-page');
  row.dataset.iconBase64 = String(iconBase64 || '');
  row.dataset.iconMimeType = String(iconMimeType || '');
  const preview = row.querySelector('[data-role="icon-preview"]');
  const fallback = row.querySelector('[data-role="icon-fallback"]');
  const image = preview.querySelector('img');
  const hasIcon = Boolean(row.dataset.iconBase64 && row.dataset.iconMimeType);
  preview.hidden = !hasIcon;
  fallback.hidden = hasIcon;
  image.src = hasIcon
    ? 'data:' + row.dataset.iconMimeType + ';base64,' + row.dataset.iconBase64
    : '';
}

function updateWebNavigationItemSummary(row) {
  const title = row.querySelector('[data-field="title"]').value.trim();
  const url = row.querySelector('[data-field="url"]').value.trim();
  const titleSummary = row.querySelector('[data-role="summary-title"]');
  const urlSummary = row.querySelector('[data-role="summary-url"]');
  titleSummary.textContent = title || '未命名网站';
  titleSummary.title = title;
  urlSummary.textContent = url || '请填写网站地址';
  urlSummary.title = url;
}

function readWebNavigationIconFile(row, input) {
  const file = input.files && input.files[0];
  if (!file) return;
  const supportedTypes = ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/x-icon', 'image/vnd.microsoft.icon'];
  if (!supportedTypes.includes(file.type)) {
    input.value = '';
    return show('图标格式需为 PNG、JPEG、WebP、GIF 或 ICO', true);
  }
  if (file.size > 128 * 1024) {
    input.value = '';
    return show('单张网站图标不能超过 128 KB', true);
  }
  const reader = new FileReader();
  reader.onload = function() {
    const dataUrl = String(reader.result || '');
    const encoded = dataUrl.match(/^data:(image\/(?:png|jpeg|webp|gif|x-icon|vnd\.microsoft\.icon));base64,([A-Za-z0-9+/=]+)$/);
    if (!encoded) return show('无法读取该图标文件', true);
    setWebNavigationIcon(row, encoded[2], encoded[1]);
    show('图标已载入，推送应用设置后生效');
  };
  reader.onerror = function() { show('读取图标文件失败', true); };
  reader.readAsDataURL(file);
}

async function fetchWebNavigationIcon(row) {
  const url = row.querySelector('[data-field="url"]').value.trim();
  if (!url) return show('请先填写网站网址', true);
  let parsedUrl;
  try { parsedUrl = new URL(url); } catch (_) { return show('网站网址格式无效', true); }
  if (!['http:', 'https:'].includes(parsedUrl.protocol)) return show('网站网址需使用 http:// 或 https://', true);

  const button = row.querySelector('[data-action="icon-fetch"]');
  button.disabled = true;
  const originalText = button.textContent;
  button.textContent = '获取中…';
  try {
    const response = await fetch(ADMIN_API_BASE + '/navigation-icon', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({url: url})
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || '网站图标获取失败');
    setWebNavigationIcon(row, data.iconBase64, data.iconMimeType);
    show('网站图标已获取，推送应用设置后生效');
  } catch (error) {
    const errorCode = error && error.message ? error.message : '';
    const errorMessages = {
      invalid_navigation_icon_url: '只支持获取公网网站的图标，请检查网址',
      navigation_icon_redirect_blocked: '网站跳转到了受限制的地址，后台已停止获取',
      navigation_icon_not_found: '目标网站没有提供图标，请尝试上传图片',
      navigation_icon_site_challenge: '目标网站启用了机器人验证，后台无法自动读取，请上传图标',
      navigation_icon_unsupported_format: '目标网站返回了暂不支持的图标格式，请上传 PNG、JPEG、WebP、GIF 或 ICO 图片'
    };
    show(errorMessages[errorCode] || '网站图标获取失败，请检查网址后重试', true);
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
}

function moveWebNavigationRow(row, direction) {
  const sibling = direction < 0 ? row.previousElementSibling : row.nextElementSibling;
  if (!sibling) return;
  if (direction < 0) row.parentElement.insertBefore(row, sibling);
  else row.parentElement.insertBefore(sibling, row);
  updateWebNavigationControls();
  markSettingsDirty('navigation-page');
}

function updateWebNavigationControls() {
  const groups = getWebNavigationGroupRows();
  groups.forEach(function(group, groupIndex) {
    const groupUp = group.querySelector('[data-action="group-up"]');
    const groupDown = group.querySelector('[data-action="group-down"]');
    if (groupUp) groupUp.disabled = groupIndex === 0;
    if (groupDown) groupDown.disabled = groupIndex === groups.length - 1;
    const items = Array.from(group.querySelector('.web-nav-items').children);
    const count = group.querySelector('[data-group-count]');
    if (count) count.textContent = items.length + ' 个网站';
    const empty = group.querySelector('[data-group-empty]');
    if (empty) empty.hidden = items.length > 0;
    items.forEach(function(item, itemIndex) {
      const itemUp = item.querySelector('[data-action="item-up"]');
      const itemDown = item.querySelector('[data-action="item-down"]');
      if (itemUp) itemUp.disabled = itemIndex === 0;
      if (itemDown) itemDown.disabled = itemIndex === items.length - 1;
    });
  });
  renderWebNavigationGroupNavigation();
}

function getWebNavigationGroupRows() {
  const host = el('webNavigationGroups');
  return host ? Array.from(host.children).filter(function(group) {
    return group.classList.contains('web-nav-group');
  }) : [];
}

function renderWebNavigationGroupNavigation() {
  const nav = el('webNavigationGroupNavigation');
  const host = el('webNavigationGroups');
  const empty = el('webNavigationEmpty');
  const groups = getWebNavigationGroupRows();
  if (!nav || !host) return;
  if (!groups.some(function(group) { return group.dataset.webNavGroupId === activeWebNavigationGroupId; })) {
    activeWebNavigationGroupId = groups.length ? groups[0].dataset.webNavGroupId : '';
  }
  nav.innerHTML = groups.map(function(group, index) {
    const title = group.querySelector('[data-field="title"]').value.trim() || '未命名分组';
    const count = group.querySelectorAll('.web-nav-item').length;
    return '<button class="subscription-group-nav-item web-nav-group-nav-item" type="button" data-web-nav-group-id="' +
      esc(group.dataset.webNavGroupId) + '" aria-label="' + esc(title) + '，' + count + ' 个网站，第 ' + (index + 1) + ' 组">' +
      '<span class="subscription-nav-icon" aria-hidden="true">' + subscriptionIcon('folder') + '</span>' +
      '<span class="subscription-nav-name">' + esc(title) + '</span>' +
      '<span class="subscription-nav-count">' + count + '</span>' +
    '</button>';
  }).join('');
  host.hidden = groups.length === 0;
  if (empty) empty.hidden = groups.length > 0;
  selectWebNavigationGroup(activeWebNavigationGroupId);
}

function selectWebNavigationGroup(groupId) {
  const groups = getWebNavigationGroupRows();
  const activeGroup = groups.find(function(group) { return group.dataset.webNavGroupId === groupId; }) || null;
  activeWebNavigationGroupId = activeGroup ? activeGroup.dataset.webNavGroupId : '';
  groups.forEach(function(group) {
    const active = group === activeGroup;
    group.hidden = !active;
    group.classList.toggle('active', active);
  });
  const nav = el('webNavigationGroupNavigation');
  if (nav) nav.querySelectorAll('[data-web-nav-group-id]').forEach(function(button) {
    const active = button.dataset.webNavGroupId === activeWebNavigationGroupId;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
}

function initializeWebNavigationManager() {
  const panel = document.querySelector('[data-panel="navigation-page"]');
  if (!panel) return;
  panel.querySelectorAll('[data-action="add-web-navigation-group"]').forEach(function(button) {
    button.addEventListener('click', function() { addWebNavigationGroup(); });
  });
  const nav = el('webNavigationGroupNavigation');
  nav.addEventListener('click', function(event) {
    const button = event.target.closest('[data-web-nav-group-id]');
    if (!button || !nav.contains(button)) return;
    selectWebNavigationGroup(button.dataset.webNavGroupId);
  });
}

function readWebNavigationGroups() {
  return Array.from(el('webNavigationGroups').children).map(function(group) {
    return {
      title: group.querySelector('[data-field="title"]').value.trim(),
      items: Array.from(group.querySelector('.web-nav-items').children).map(function(item) {
        return {
          title: item.querySelector('[data-field="title"]').value.trim(),
          url: item.querySelector('[data-field="url"]').value.trim(),
          iconBase64: item.dataset.iconBase64 || '',
          iconMimeType: item.dataset.iconMimeType || ''
        };
      })
    };
  });
}

// 推送前校验必填项和网址格式，避免错误配置下发到客户端。
function validateWebNavigationGroups(groups) {
  if (groups.length > 50) return '导航网站分组最多 50 个';
  let itemCount = 0;
  let iconTotalSize = 0;
  for (let groupIndex = 0; groupIndex < groups.length; groupIndex += 1) {
    const group = groups[groupIndex];
    if (!group.title || group.title.length > 60) {
      return '第 ' + (groupIndex + 1) + ' 个分组请填写名称（最多 60 个字符）';
    }
    for (let itemIndex = 0; itemIndex < group.items.length; itemIndex += 1) {
      const item = group.items[itemIndex];
      if (!item.title || item.title.length > 80) {
        return '“' + group.title + '”中的第 ' + (itemIndex + 1) + ' 个网站请填写名称（最多 80 个字符）';
      }
      if (!item.url || item.url.length > 2048) {
        return '请填写“' + item.title + '”的网址（最多 2048 个字符）';
      }
      let parsedUrl;
      try {
        parsedUrl = new URL(item.url);
      } catch (_) {
        return '“' + item.title + '”的网址格式无效';
      }
      if (!['http:', 'https:'].includes(parsedUrl.protocol) || parsedUrl.username || parsedUrl.password) {
        return '“' + item.title + '”的网址需使用 http:// 或 https://，且不能包含账号密码';
      }
      const iconBase64 = item.iconBase64 || '';
      const iconMimeType = item.iconMimeType || '';
      if (iconBase64 && (iconBase64.length > 180000 || !['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/x-icon', 'image/vnd.microsoft.icon'].includes(iconMimeType))) {
        return '“' + item.title + '”的网站图标格式无效或超过 128 KB';
      }
      if (!iconBase64 && iconMimeType) return '“' + item.title + '”的网站图标数据不完整';
      iconTotalSize += iconBase64.length;
      if (iconTotalSize > 4 * 1024 * 1024) return '导航网站图标数据总量不能超过 4 MB';
      itemCount += 1;
      if (itemCount > 250) return '导航网站最多 250 个';
    }
  }
  return '';
}

function fillState(state, section) {
  currentState = state || {};
  settingsFilling = true;
  try {
    const settings = currentState.settings || {};
    if (!section || section === 'subscriptions') {
      renderSubscriptionGroups(Array.isArray(currentState.subscriptionGroups) ? currentState.subscriptionGroups : []);
    }
    if (!section || section === 'navigation-page') {
      el('webNavigationGroups').innerHTML = '';
      el('webNavigationGroupNavigation').innerHTML = '';
      activeWebNavigationGroupId = '';
      (Array.isArray(settings.webNavigationGroups) ? settings.webNavigationGroups : []).forEach(addWebNavigationGroup);
      updateWebNavigationControls();
    }
    if (!section || section === 'about-page') {
      setValue('websiteUrl', settings.websiteUrl);
      setValue('feedbackEmail', settings.feedbackEmail);
      setValue('feedbackUrl', settings.feedbackUrl);
      setValue('githubUrl', settings.githubUrl);
      setValue('telegramUrl', settings.telegramUrl);
    }
    if (!section || section === 'app-update') {
      const update = currentState.appUpdate || {};
      checked('appUpdateEnabled', Boolean(currentState.appUpdate));
      setValue('appVersion', update.version);
      setValue('appVersionCode', update.versionCode);
      setValue('appDownloadUrl', update.downloadUrl);
      setValue('appChangelog', update.changelog);
      checked('appForce', update.is_force === 1 || update.isForce === 1);
    }
    if (!section || section === 'registration-control') {
      checked('registrationEnabled', settings.registrationEnabled !== false);
      checked('requireAdminRegistrationCode', settings.requireAdminRegistrationCode === true);
      loadRegistrationCodes();
    }
    if (!section || section === 'notice') {
      const notice = currentState.notice || {};
      checked('noticeEnabled', Boolean(currentState.notice && notice.hasNotice !== false));
      setValue('noticeTitle', notice.title);
      setValue('noticeId', notice.noticeId);
      setValue('noticeStartAt', noticeDateTimeValue(notice.startAt));
      setValue('noticeEndAt', noticeDateTimeValue(notice.endAt));
      setValue('noticeContent', notice.content);
      checked('noticeShowOnce', notice.showOnce !== false);
    }
  } finally {
    settingsFilling = false;
  }
  if (!section) resetSettingsDrafts();
  updateSettingsSaveControls();
  if (section === 'subscriptions') loadSubscriptionTraffic();
}
function noticeDateTimeValue(data) {
  const text = String(data == null ? '' : data).trim();
  const match = text.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return match ? match[1] : '';
}
function readSubscriptionSource(row, index) {
    const sourceType = row.querySelector('[data-field="sourceType"]').value;
    const url = row.querySelector('[data-field="url"]').value.trim();
    const content = row.querySelector('[data-field="content"]').value.trim();
    return {
      id: row.dataset.sourceId || undefined,
      contentKey: row.dataset.contentKey || undefined,
      name: row.querySelector('[data-field="name"]').value.trim() || '订阅' + (index + 1),
      url: sourceType === 'url' ? url : '',
      enabled: row.querySelector('[data-field="enabled"]').checked,
      autoRename: sourceType === 'url' && row.querySelector('[data-field="autoRename"]').checked,
      deduplicate: row.querySelector('[data-field="deduplicate"]').checked,
      content: sourceType === 'content' ? content : '',
      remark: row.querySelector('[data-field="remark"]').value.trim()
    };
}
function readSubscriptionGroups() {
  return getSubscriptionGroupRows().map(function(group, groupIndex) {
    const sources = Array.from(group.querySelectorAll('[data-group-sources] > .source-row'))
      .map(readSubscriptionSource)
      .filter(function(source) { return source.url || source.content; });
    return {
      id: group.dataset.groupId || String(groupIndex + 1),
      name: group.querySelector('[data-field="groupName"]').value.trim() || '分组' + (groupIndex + 1),
      enabled: group.querySelector('[data-field="groupEnabled"]').checked,
      allowedStatuses: Array.from(group.querySelectorAll('[data-field="allowedStatuses"]:checked'))
        .map(function(input) { return input.value; }),
      allowedAccountTypes: Array.from(group.querySelectorAll('[data-field="allowedAccountTypes"]:checked'))
        .map(function(input) { return input.value; }),
      updateEnabled: group.querySelector('[data-field="updateEnabled"]').checked,
      cacheMinutes: Number(group.querySelector('[data-field="groupCacheMinutes"]').value || 0),
      groupType: group.querySelector('[data-field="groupType"]').value,
      maxUsersPerSubscription: Number(group.querySelector('[data-field="maxUsersPerSubscription"]').value || 0),
      resetPeriodDays: Number(group.querySelector('[data-field="resetPeriodDays"]').value || 0),
      minAppVersion: group.querySelector('[data-field="minAppVersion"]').value.trim(),
      maxAppVersion: group.querySelector('[data-field="maxAppVersion"]').value.trim(),
      subscriptions: sources
    };
  });
}
function formState() {
  const subscriptionGroups = readSubscriptionGroups();
  const notice = el('noticeEnabled').checked ? {
    hasNotice: true,
    title: value('noticeTitle'),
    content: value('noticeContent'),
    noticeId: value('noticeId'),
    showOnce: el('noticeShowOnce').checked,
    startAt: value('noticeStartAt'),
    endAt: value('noticeEndAt')
  } : null;
  const appUpdate = el('appUpdateEnabled').checked ? {
    version: value('appVersion'),
    versionCode: Number(value('appVersionCode') || 0),
    downloadUrl: value('appDownloadUrl'),
    changelog: value('appChangelog'),
    is_force: el('appForce').checked ? 1 : 0
  } : null;
  return {
    subscriptionGroups: subscriptionGroups,
    settings: {
      websiteUrl: value('websiteUrl'),
      webNavigationGroups: readWebNavigationGroups(),
      feedbackEmail: value('feedbackEmail'),
       feedbackUrl: value('feedbackUrl'),
       githubUrl: value('githubUrl'),
       telegramUrl: value('telegramUrl'),
       registrationEnabled: el('registrationEnabled').checked,
       requireAdminRegistrationCode: el('requireAdminRegistrationCode').checked,
      },
    notice: notice,
    appUpdate: appUpdate
  };
}
function renderRegistrationCodes(codes) {
  const host = el('registrationCodesTable');
  if (!host) return;
  const rows = Array.isArray(codes) ? codes : [];
  if (!rows.length) {
    host.innerHTML = '<div class="loading-state">暂无注册码</div>';
    return;
  }
  host.innerHTML = '<table class="security-account-table" aria-label="注册码">' +
    '<thead><tr><th>注册码</th><th>状态</th><th>发放时间</th><th>使用时间</th><th>使用账户 UID</th></tr></thead>' +
    '<tbody>' + rows.map(function(row) {
      return '<tr><td><code>' + esc(row.code) + '</code></td>' +
        '<td>' + (row.used ? '已使用' : '未使用') + '</td>' +
        '<td>' + esc(row.createdAt || '-') + '</td>' +
        '<td>' + esc(row.usedAt || '-') + '</td>' +
        '<td><code>' + esc(row.usedByUid || '-') + '</code></td></tr>';
    }).join('') + '</tbody></table>';
}

async function loadRegistrationCodes() {
  const host = el('registrationCodesTable');
  if (!host || !adminToken) return;
  try {
    const response = await fetch(ADMIN_API_BASE + '/registration-codes?limit=200', {
      headers: headers(),
      cache: 'no-store'
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    renderRegistrationCodes(data.codes);
  } catch (error) {
    host.innerHTML = '<div class="error-state">注册码读取失败</div>';
  }
}

async function issueRegistrationCodes() {
  const count = Number(value('registrationCodeCount') || 1);
  if (!Number.isInteger(count) || count < 1 || count > 100) {
    return show('一次最多发放 100 个注册码', true);
  }
  const response = await fetch(ADMIN_API_BASE + '/registration-codes', {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({count: count})
  });
  const data = await response.json();
  if (!response.ok) return show(adminErrorMessage(data.error, '注册码发放失败'), true);
  show('已发放 ' + (data.codes || []).length + ' 个注册码');
  await loadRegistrationCodes();
}
async function pushSection(section) {
  if (section === 'users') {
    if (await loadUsers()) show('用户表单已刷新');
    return;
  }

  if (!currentState || settingsSaveInFlight || !SETTINGS_SECTIONS.includes(section)) return false;
  const session = settingsSession;
  const version = settingsDraftVersions.get(section) || 0;
  settingsSaveInFlight = section;
  settingsSaveMessages.delete(section);
  updateSettingsSaveControls();
  try {
    const form = formState();
    if (section === 'navigation-page') {
      const validationError = validateWebNavigationGroups(form.settings.webNavigationGroups);
      if (validationError) throw new Error(validationError);
    }
    if (section === 'subscriptions') {
      const versionError = validateSubscriptionGroupVersions(form.subscriptionGroups);
      if (versionError) throw new Error(versionError);
    }
    const settings = section === 'navigation-page'
        ? Object.assign({}, currentState.settings, {
          webNavigationGroups: form.settings.webNavigationGroups
        })
      : section === 'about-page'
        ? Object.assign({}, currentState.settings, {
          websiteUrl: form.settings.websiteUrl,
          feedbackEmail: form.settings.feedbackEmail,
          feedbackUrl: form.settings.feedbackUrl,
          githubUrl: form.settings.githubUrl,
          telegramUrl: form.settings.telegramUrl
        })
      : section === 'app-update'
        ? currentState.settings
      : section === 'registration-control'
        ? Object.assign({}, currentState.settings, {
          registrationEnabled: form.settings.registrationEnabled,
          requireAdminRegistrationCode: form.settings.requireAdminRegistrationCode
        })
        : currentState.settings;
    const state = {
      subscriptionGroups: section === 'subscriptions' ? form.subscriptionGroups : currentState.subscriptionGroups,
      settings: settings,
      notice: section === 'notice' ? form.notice : currentState.notice,
      appUpdate: section === 'app-update' ? form.appUpdate : currentState.appUpdate
    };
    const response = await fetch(ADMIN_API_BASE + '/state', {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify(state)
    });
    const data = await response.json();
    if (session !== settingsSession) return false;
    if (!response.ok) throw new Error(adminErrorMessage(data.error, '保存失败'));
    currentState = data.state;
    if ((settingsDraftVersions.get(section) || 0) === version) {
      fillState(data.state, section);
      settingsDirtySections.delete(section);
      settingsSaveMessages.set(section, {text: '已保存' + (data.state.updatedAt ? ' · ' + data.state.updatedAt.replace('T', ' ') : ''), error: false});
    } else {
      settingsSaveMessages.set(section, {text: '已保存提交时的内容，后续修改仍未保存', error: false});
    }
    show('当前模块已保存，其他模块的草稿已保留');
    return true;
  } catch (error) {
    if (session !== settingsSession) return false;
    const message = error.message || '保存失败，请检查网络后重试';
    settingsSaveMessages.set(section, {text: message + '，草稿已保留', error: true});
    show(message, true);
    return false;
  } finally {
    if (session === settingsSession) {
      settingsSaveInFlight = '';
      updateSettingsSaveControls();
    }
  }
}
initializeGrowthRange();
initializeActivityRange();
initializeTrafficRange();
initializeSubscriptionManager();
initializeWebNavigationManager();
initializeSettingsDrafts();
const subscriptionTabButton = document.querySelector('[data-tab="subscriptions"]');
if (subscriptionTabButton) subscriptionTabButton.addEventListener('click', loadSubscriptionTraffic);
