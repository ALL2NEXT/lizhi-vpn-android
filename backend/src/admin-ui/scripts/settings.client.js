let subscriptionTrafficRequestId = 0;
let subscriptionRefreshInFlight = false;
let activeSubscriptionGroupId = '__all__';
let selectedSubscriptionRow = null;
const selectedSubscriptionRows = new Set();
let subscriptionCurrentPage = 1;
let subscriptionPageSize = 20;
function subscriptionIcon(name, extraClass) {
  return '<svg class="subscription-icon' + (extraClass ? ' ' + extraClass : '') + '" aria-hidden="true" focusable="false"><use href="#subscription-icon-' + name + '"></use></svg>';
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

function formatSubscriptionResetRemaining(value) {
  const resetAt = Number(value);
  if (!Number.isFinite(resetAt) || resetAt <= 0) return '';
  const remainingMs = resetAt - Date.now();
  if (remainingMs <= 0) return '重置时间已过';
  const days = Math.ceil(remainingMs / (24 * 60 * 60 * 1000));
  if (days >= 1) return '重置还有 ' + days + ' 天';
  const hours = Math.ceil(remainingMs / (60 * 60 * 1000));
  if (hours >= 1) return '重置还有 ' + hours + ' 小时';
  return '重置还有 ' + Math.ceil(remainingMs / (60 * 1000)) + ' 分钟';
}

function renderSourceTraffic(row, traffic, unavailable) {
  const sourceUrl = row.querySelector('[data-field="url"]').value.trim();
  const host = row.querySelector('[data-source-traffic]');
  const text = row.querySelector('[data-source-traffic-text]');
  const expiry = row.querySelector('[data-source-expiry]');
  const track = row.querySelector('[data-source-traffic-track]');
  const fill = row.querySelector('[data-source-traffic-progress]');
  if (!host || !text || !track || !fill) return;
  host.dataset.sourceUrl = sourceUrl;
  if (unavailable) {
    setSourceTrafficMessage(row, '流量信息获取失败', true);
    host.dataset.sourceUrl = sourceUrl;
    updateSourceRowsMeta();
    return;
  }
  if (!traffic) {
    setSourceTrafficMessage(row, '上游未提供流量信息');
    host.dataset.sourceUrl = sourceUrl;
    updateSourceRowsMeta();
    return;
  }

  const upload = Math.max(0, Number(traffic.uploadBytes) || 0);
  const download = Math.max(0, Number(traffic.downloadBytes) || 0);
  const hasUsage = traffic.uploadBytes != null || traffic.downloadBytes != null;
  const remaining = traffic.remainingBytes == null
    ? null : Math.max(0, Number(traffic.remainingBytes) || 0);
  const total = Math.max(0, Number(traffic.totalBytes) || 0);
  const used = hasUsage ? upload + download
    : total > 0 && remaining !== null ? Math.max(0, total - remaining) : 0;
  const resetLabel = formatSubscriptionResetRemaining(traffic.resetAtMillis);
  const usageLabel = remaining !== null
    ? '剩余流量 ' + formatSubscriptionTrafficBytes(remaining) + (resetLabel ? ' · ' + resetLabel : '')
    : total > 0
      ? '总量 ' + formatSubscriptionTrafficBytes(total) + ' · 已用 ' + formatSubscriptionTrafficBytes(used)
      : hasUsage ? '已用 ' + formatSubscriptionTrafficBytes(used) : '';
  text.textContent = [usageLabel, remaining === null ? resetLabel : ''].filter(Boolean).join(' · ') ||
    '已获取订阅信息';
  track.hidden = total <= 0 || !hasUsage && remaining === null;
  if (total > 0) {
    const ratio = used / total;
    fill.style.width = Math.min(100, Math.max(0, ratio * 100)) + '%';
    fill.className = 'source-traffic-progress ' + (
      ratio >= 1 ? 'exhausted' : ratio > 0.9 ? 'critical' : ratio > 0.5 ? 'warning' : 'safe'
    );
    track.setAttribute('aria-valuenow', String(Math.min(100, Math.max(0, Math.round(ratio * 100)))));
    track.title = hasUsage
      ? '上传 ' + formatSubscriptionTrafficBytes(upload) + ' · 下载 ' + formatSubscriptionTrafficBytes(download)
      : remaining !== null ? '剩余 ' + formatSubscriptionTrafficBytes(remaining) : '';
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
}

async function loadSubscriptionTraffic(sourceIds) {
  const requestedIds = Array.isArray(sourceIds) ? new Set(sourceIds.map(String)) : null;
  const rows = getSourceRows();
  const activeRows = rows.filter(function(row) {
    return row.dataset.sourceId &&
      (!requestedIds || requestedIds.has(row.dataset.sourceId)) &&
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
    const query = requestedIds
      ? '?' + Array.from(requestedIds).map(function(id) { return 'id=' + encodeURIComponent(id); }).join('&')
      : '';
    const response = await fetch(ADMIN_API_BASE + '/subscriptions/traffic' + query, {
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

function renderSubscriptionGroups(groups) {
  const container = el('subscriptionRows');
  selectSubscriptionSource(null);
  container.innerHTML = '';
  selectedSubscriptionRow = null;
  selectedSubscriptionRows.clear();
  activeSubscriptionGroupId = '__all__';
  (Array.isArray(groups) ? groups : []).forEach(function(group) {
    const row = addSubscriptionGroup(group);
    (Array.isArray(group.subscriptions) ? group.subscriptions : []).forEach(function(source) {
      addSource(row, source);
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
  const allowedIdentityTypes = Array.isArray(group.allowedIdentityTypes)
    ? group.allowedIdentityTypes
    : ['human', 'bot'];
  const allowedMembershipTypes = Array.isArray(group.allowedMembershipTypes)
    ? group.allowedMembershipTypes
    : ['member', 'non_member'];
  const allowedStatusValues = ['normal', 'banned'];
  const allowedIdentityValues = ['human', 'bot'];
  const allowedMembershipValues = ['member', 'non_member'];
  const statusFilterEnabled = allowedStatusValues.some(function(value) { return !allowedStatuses.includes(value); });
  const identityFilterEnabled = allowedIdentityValues.some(function(value) { return !allowedIdentityTypes.includes(value); });
  const membershipFilterEnabled = allowedMembershipValues.some(function(value) { return !allowedMembershipTypes.includes(value); });
  const versionFilterEnabled = Boolean(group.minAppVersion || group.maxAppVersion);
  const selectedStatuses = statusFilterEnabled ? allowedStatuses : ['normal'];
  const selectedIdentityTypes = identityFilterEnabled ? allowedIdentityTypes : ['human'];
  const selectedMembershipTypes = membershipFilterEnabled ? allowedMembershipTypes : ['member'];
  const membershipRadioGroup = 'subscription-membership-' + crypto.randomUUID();
  // 保留原有字段作为保存数据源，右侧编辑器只同步到这些字段，不改变接口结构。
  row.innerHTML = '<input class="subscription-group-enabled-data" data-field="groupEnabled" type="checkbox" ' + (group.enabled === false ? '' : 'checked') + ' hidden>' +
  '<input class="subscription-group-order-data" data-field="groupOrder" type="number" min="1" step="1" value="' + groupOrder + '" aria-label="分组排序序号">' +
  '<dialog class="subscription-permissions-dialog" data-group-permissions-dialog aria-label="分组设置">' +
    '<div class="source-settings-dialog-header"><div><h3>分组设置</h3></div>' +
      '<button class="secondary" type="button" data-action="permissions-close">关闭</button>' +
    '</div>' +
    '<div class="subscription-group-permissions">' +
    '<fieldset class="subscription-group-name-setting"><legend>分组名称</legend>' +
      '<label><span>名称</span><input data-field="groupName" type="text" maxlength="80" placeholder="例如：标准线路"></label>' +
    '</fieldset>' +
    '<fieldset class="subscription-access-control"><legend>权限控制</legend>' +
      '<div class="subscription-access-control-fields">' +
    '<fieldset data-filter-section><legend>账号状态</legend>' +
      '<label class="permission-filter-toggle subscription-switch"><span>启用筛选</span><span class="subscription-switch-control"><input data-field="allowedStatusesFilterEnabled" data-filter-toggle type="checkbox" aria-label="启用账号状态筛选" ' + (statusFilterEnabled ? 'checked' : '') + '><span class="subscription-switch-track"><span></span></span></span></label>' +
      '<div class="permission-filter-options">' +
      '<label class="check"><input data-field="allowedStatuses" data-filter-option type="checkbox" value="normal" ' + (selectedStatuses.includes('normal') ? 'checked' : '') + '>正常</label>' +
      '<label class="check"><input data-field="allowedStatuses" data-filter-option type="checkbox" value="banned" ' + (selectedStatuses.includes('banned') ? 'checked' : '') + '>封禁</label>' +
      '</div>' +
    '</fieldset>' +
    '<fieldset data-filter-section><legend>账号身份</legend>' +
      '<label class="permission-filter-toggle subscription-switch"><span>启用筛选</span><span class="subscription-switch-control"><input data-field="allowedIdentityTypesFilterEnabled" data-filter-toggle type="checkbox" aria-label="启用账号身份筛选" ' + (identityFilterEnabled ? 'checked' : '') + '><span class="subscription-switch-track"><span></span></span></span></label>' +
      '<div class="permission-filter-options">' +
      '<label class="check"><input data-field="allowedIdentityTypes" data-filter-option type="checkbox" value="human" ' + (selectedIdentityTypes.includes('human') ? 'checked' : '') + '>真人</label>' +
      '<label class="check"><input data-field="allowedIdentityTypes" data-filter-option type="checkbox" value="bot" ' + (selectedIdentityTypes.includes('bot') ? 'checked' : '') + '>机器人</label>' +
      '</div>' +
    '</fieldset>' +
    '<fieldset data-filter-section><legend>会员状态</legend>' +
      '<label class="permission-filter-toggle subscription-switch"><span>启用筛选</span><span class="subscription-switch-control"><input data-field="allowedMembershipTypesFilterEnabled" data-filter-toggle type="checkbox" aria-label="启用会员状态筛选" ' + (membershipFilterEnabled ? 'checked' : '') + '><span class="subscription-switch-track"><span></span></span></span></label>' +
      '<div class="permission-filter-options">' +
      '<label class="check"><input data-field="allowedMembershipTypes" data-filter-option type="radio" name="' + membershipRadioGroup + '" value="member" ' + (selectedMembershipTypes.includes('member') ? 'checked' : '') + '>会员</label>' +
      '<label class="check"><input data-field="allowedMembershipTypes" data-filter-option type="radio" name="' + membershipRadioGroup + '" value="non_member" ' + (selectedMembershipTypes.includes('non_member') ? 'checked' : '') + '>非会员</label>' +
      '</div>' +
    '</fieldset>' +
    '<fieldset class="subscription-version-range" data-filter-section><legend>App 版本区间（包含边界）</legend>' +
      '<label class="permission-filter-toggle subscription-switch"><span>启用筛选</span><span class="subscription-switch-control"><input data-field="versionFilterEnabled" data-filter-toggle type="checkbox" aria-label="启用 App 版本筛选" ' + (versionFilterEnabled ? 'checked' : '') + '><span class="subscription-switch-track"><span></span></span></span></label>' +
      '<div class="permission-filter-options subscription-version-range-options">' +
      '<label><span>左侧 · 最低</span><input data-field="minAppVersion" data-filter-option type="text" list="subscriptionAppVersionOptions" placeholder="不限制"></label>' +
      '<span aria-hidden="true">至</span>' +
      '<label><span>右侧 · 最高</span><input data-field="maxAppVersion" data-filter-option type="text" list="subscriptionAppVersionOptions" placeholder="不限制"></label>' +
      '</div>' +
    '</fieldset>' +
      '</div>' +
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
  '<div class="source-list-header" aria-hidden="true"><span></span><span>订阅名称</span><span class="source-list-header-remark">备注</span><span class="source-list-header-type">订阅类型</span><span class="source-list-header-traffic">流量使用</span><span class="source-list-header-expiry">到期时间</span><span class="source-list-header-enabled">启用</span><span>设置</span></div>' +
  '<div class="subscription-group-sources" data-group-sources>' +
    '<div class="subscription-group-empty" data-group-subscription-empty hidden>此分组暂无订阅</div>' +
  '</div>';
  row.querySelector('[data-field="groupName"]').value = String(group.name || '');
  row.querySelector('[data-field="groupCacheMinutes"]').value = String(group.cacheMinutes == null ? 30 : group.cacheMinutes);
  row.querySelector('[data-field="minAppVersion"]').value = String(group.minAppVersion || '');
  row.querySelector('[data-field="maxAppVersion"]').value = String(group.maxAppVersion || '');
  el('subscriptionRows').appendChild(row);
  row.querySelector('[data-field="minAppVersion"]').addEventListener('change', updateSubscriptionAppVersionOptions);
  row.querySelector('[data-field="maxAppVersion"]').addEventListener('change', updateSubscriptionAppVersionOptions);
  row.querySelectorAll('[data-filter-toggle]').forEach(function(toggle) {
    toggle.addEventListener('change', function() {
      syncSubscriptionFilterControls(row);
    });
  });
  syncSubscriptionFilterControls(row);
  row.querySelector('[data-field="groupOrder"]').addEventListener('change', function() {
    moveSubscriptionGroupToOrder(row, this.value);
  });
  row.querySelector('[data-field="groupName"]').addEventListener('input', function() {
    updateSubscriptionGroupNavigation();
    markSettingsDirty('subscriptions');
  });
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
    if (!group.allowedStatuses.length) {
      return '分组「' + group.name + '」启用账号状态筛选时至少选择一个状态';
    }
    if (!group.allowedIdentityTypes.length) {
      return '分组「' + group.name + '」启用账号身份筛选时至少选择一种身份';
    }
    if (!group.allowedMembershipTypes.length) {
      return '分组「' + group.name + '」启用会员状态筛选时至少选择一种状态';
    }
    if (group.versionFilterEnabled && !group.minAppVersion && !group.maxAppVersion) {
      return '分组「' + group.name + '」启用 App 版本筛选时至少填写一个版本边界';
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

function addSource(groupRow, source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  row.dataset.sourceId = String(source.id || '');
  row.dataset.contentKey = String(source.contentKey || '');
  row.dataset.persistedUrl = String(source.url || '');
  row.dataset.persistedEnabled = String(source.enabled !== false);
  const sourceType = source.sourceType === 'content' || source.content ? 'content' : 'url';
  row.innerHTML = '<input class="source-row-select" type="checkbox" data-source-select aria-label="选择订阅：' + esc(source.name || '未命名订阅') + '">' +
    '<span class="source-name-cell"><span class="source-name-line"><strong data-source-display-name>' + esc(source.name || '未命名订阅') + '</strong></span></span>' +
    '<span class="source-row-remark" data-source-display-remark>' + esc(source.remark || '—') + '</span>' +
    '<span class="source-type-cell" data-source-display-type>' + (sourceType === 'content' ? '本地订阅' : '远程订阅') + '</span>' +
    '<div class="source-traffic" data-source-traffic aria-live="polite"><div class="source-traffic-line"><span data-source-traffic-text>等待读取流量信息…</span></div><div class="source-traffic-track" data-source-traffic-track role="progressbar" aria-label="订阅流量使用比例" aria-valuemin="0" aria-valuemax="100" hidden><span class="source-traffic-progress" data-source-traffic-progress></span></div></div>' +
    '<div class="source-expiry-cell"><span class="source-expiry" data-source-expiry></span></div>' +
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
  row.querySelector('[data-action="source-settings"]').onclick = function() {
    openSubscriptionEditor(row);
  };
  bindSubscriptionSourceSwitch(row);
  updateSourceMode(row);
  updateSourceRowPresentation(row);
  groupRow.querySelector('[data-group-sources]').appendChild(row);
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
    rows.forEach(function(row, index) {
      const nameInput = row.querySelector('[data-field="name"]');
      if (nameInput) nameInput.placeholder = '订阅' + (index + 1);
      const displayName = row.querySelector('[data-source-display-name]');
      const displayType = row.querySelector('[data-source-display-type]');
      const displayRemark = row.querySelector('[data-source-display-remark]');
      const type = row.querySelector('[data-field="sourceType"]').value;
      const sourceName = nameInput.value.trim() || nameInput.placeholder;
      if (displayName) displayName.textContent = sourceName;
      if (displayType) displayType.textContent = type === 'content' ? '本地订阅' : '远程订阅';
      const settingsButton = row.querySelector('[data-action="source-settings"]');
      if (settingsButton) {
        settingsButton.setAttribute('aria-label', '设置订阅：' + sourceName);
        settingsButton.title = '设置订阅：' + sourceName;
      }
      if (displayRemark) displayRemark.textContent = row.querySelector('[data-field="remark"]').value.trim() || '—';
    });
  });
  updateSubscriptionGroupNavigation();
  updateSubscriptionList();
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
  updateSubscriptionList();
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
  updateSubscriptionEditorType(row.querySelector('[data-field="sourceType"]').value);
}

function openSubscriptionEditor(row) {
  if (!row) return;
  selectSubscriptionSource(row);
  const editor = el('subscriptionEditor');
  if (!editor.open) editor.showModal();
}

function openSubscriptionMoveDialog() {
  // 打开移动弹窗前清理已删除或失效的勾选项。
  Array.from(selectedSubscriptionRows).forEach(function(row) {
    if (!row.isConnected || !row.classList.contains('source-row')) selectedSubscriptionRows.delete(row);
  });
  const rows = Array.from(selectedSubscriptionRows);
  const dialog = el('subscriptionMoveDialog');
  const groupSelect = dialog.querySelector('[data-move-target-group]');
  const groups = getSubscriptionGroupRows();
  groupSelect.innerHTML = '<option value="">请选择目标分组</option>' + groups.map(function(group, index) {
    const name = group.querySelector('[data-field="groupName"]').value.trim() || '分组' + (index + 1);
    return '<option value="' + esc(group.dataset.groupId) + '">' + esc(name) + '</option>';
  }).join('');
  groupSelect.value = '';
  dialog.querySelector('[data-move-selection-count]').textContent = rows.length
    ? '已选择 ' + rows.length + ' 条订阅，请选择目标分组。'
    : '请先勾选要移动的订阅。';
  dialog.querySelector('[data-action="confirm-move-subscriptions"]').disabled = true;
  if (!dialog.open) dialog.showModal();
}

function updateSubscriptionMoveConfirmState() {
  const dialog = el('subscriptionMoveDialog');
  const targetGroupId = dialog.querySelector('[data-move-target-group]').value;
  const rows = Array.from(selectedSubscriptionRows).filter(function(row) {
    return row.isConnected && row.classList.contains('source-row');
  });
  const targetGroup = getSubscriptionGroupRows().find(function(group) {
    return group.dataset.groupId === targetGroupId;
  });
  const alreadyInTarget = targetGroup && rows.every(function(row) {
    return row.closest('.subscription-group') === targetGroup;
  });
  dialog.querySelector('[data-action="confirm-move-subscriptions"]').disabled =
    !targetGroup || rows.length === 0 || alreadyInTarget;
}

function moveSelectedSubscriptions(targetGroupId) {
  Array.from(selectedSubscriptionRows).forEach(function(row) {
    if (!row.isConnected || !row.classList.contains('source-row')) selectedSubscriptionRows.delete(row);
  });
  const rows = Array.from(selectedSubscriptionRows);
  const targetGroup = getSubscriptionGroupRows().find(function(group) {
    return group.dataset.groupId === targetGroupId;
  });
  if (!targetGroup || rows.length === 0) return;
  const targetSources = targetGroup.querySelector('[data-group-sources]');
  if (!targetSources) return;

  const movedRows = rows.filter(function(row) {
    return row.closest('.subscription-group') !== targetGroup;
  });
  if (movedRows.length === 0) return;
  // 将选中订阅追加到目标分组末尾。
  movedRows.forEach(function(row) {
    targetSources.appendChild(row);
  });
  selectedSubscriptionRows.clear();
  rows.forEach(function(row) {
    const checkbox = row.querySelector('[data-source-select]');
    if (checkbox) checkbox.checked = false;
  });
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
  el('subscriptionMoveDialog').close();
  show('已将 ' + movedRows.length + ' 条订阅移动到目标分组');
}

function updateSubscriptionEditorType(type) {
  const editor = el('subscriptionEditor');
  const infoField = editor.querySelector('[data-editor-source-info]');
  const autoRenameField = editor.querySelector('[data-editor-auto-rename]');
  const autoRenameControl = editor.querySelector('[data-editor-field="autoRename"]');
  const isRemoteSource = type === 'url';
  if (autoRenameField) autoRenameField.hidden = !isRemoteSource;
  if (autoRenameControl) autoRenameControl.disabled = !isRemoteSource;
  const fieldName = type === 'content' ? 'content' : 'url';
  const sourceField = selectedSubscriptionRow && selectedSubscriptionRow.querySelector('[data-field="' + fieldName + '"]');
  infoField.dataset.editorField = fieldName;
  infoField.value = sourceField ? sourceField.value : '';
  infoField.rows = type === 'content' ? 8 : 3;
  infoField.placeholder = type === 'content'
    ? 'vless://...\nhy2://...\nss://...'
    : 'https://example.com/sub';
}

function updateSubscriptionList() {
  const groups = getSubscriptionGroupRows();
  const rows = getSourceRows();
  const visibleRows = rows.filter(function(row) {
    const group = row.closest('.subscription-group');
    return activeSubscriptionGroupId === '__all__' || group.dataset.groupId === activeSubscriptionGroupId;
  });
  const pageCount = Math.max(1, Math.ceil(visibleRows.length / subscriptionPageSize));
  subscriptionCurrentPage = Math.min(Math.max(1, subscriptionCurrentPage), pageCount);
  const start = (subscriptionCurrentPage - 1) * subscriptionPageSize;
  const pageRows = visibleRows.slice(start, start + subscriptionPageSize);
  const pageSet = new Set(pageRows);
  rows.forEach(function(row) {
    row.hidden = !pageSet.has(row);
    row.style.removeProperty('order');
  });
  groups.forEach(function(group) {
    const active = activeSubscriptionGroupId === '__all__' || group.dataset.groupId === activeSubscriptionGroupId;
    const hasRows = group.querySelectorAll('.source-row').length > 0;
    group.hidden = !active || (activeSubscriptionGroupId === '__all__' && !hasRows);
    const groupEmpty = group.querySelector('[data-group-subscription-empty]');
    const showGroupEmpty = active && activeSubscriptionGroupId !== '__all__' && !hasRows;
    if (groupEmpty) groupEmpty.hidden = !showGroupEmpty;
    const groupSources = group.querySelector('[data-group-sources]');
    if (groupSources) groupSources.classList.toggle('is-empty', showGroupEmpty);
  });
  const empty = document.querySelector('[data-subscription-empty]');
  if (empty) empty.hidden = visibleRows.length > 0 || activeSubscriptionGroupId !== '__all__';
  const count = document.querySelector('[data-subscription-count]');
  if (count) count.textContent = '共 ' + visibleRows.length + ' 条';
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
  const moveSelectedButton = document.querySelector('[data-action="move-selected-subscriptions"]');
  if (moveSelectedButton) moveSelectedButton.disabled = selectedSubscriptionRows.size === 0;
  document.querySelectorAll('[data-action="refresh-subscriptions"]').forEach(function(button) {
    button.disabled = subscriptionRefreshInFlight || selectedSubscriptionRows.size === 0;
  });
}

function deleteSelectedSubscriptions() {
  Array.from(selectedSubscriptionRows).forEach(function(row) {
    if (!row.isConnected || !row.classList.contains('source-row')) selectedSubscriptionRows.delete(row);
  });
  const rows = Array.from(selectedSubscriptionRows);
  if (!rows.length) {
    updateSubscriptionList();
    return;
  }
  if (!window.confirm('确定删除已选中的 ' + rows.length + ' 条订阅吗？此操作无法撤销。')) return;
  if (selectedSubscriptionRow && rows.indexOf(selectedSubscriptionRow) >= 0) selectSubscriptionSource(null);
  rows.forEach(function(row) {
    selectedSubscriptionRows.delete(row);
    row.remove();
  });
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
}

function getSelectedPersistedRemoteSources(rows) {
  const sources = new Map((currentState && Array.isArray(currentState.subscriptionGroups)
    ? currentState.subscriptionGroups : []).flatMap(function(group) {
    return Array.isArray(group.subscriptions) ? group.subscriptions : [];
  }).map(function(source) { return [String(source.id), source]; }));
  return rows.map(function(row) {
    const source = sources.get(row.dataset.sourceId);
    if (!source || !source.url || source.content || source.enabled === false) return null;
    const sourceType = row.querySelector('[data-field="sourceType"]').value;
    const url = row.querySelector('[data-field="url"]').value.trim();
    const enabled = row.querySelector('[data-field="enabled"]').checked;
    if (sourceType !== 'url' || !enabled || url !== source.url ||
        url !== row.dataset.persistedUrl || String(enabled) !== row.dataset.persistedEnabled) return null;
    return source;
  }).filter(Boolean);
}

async function refreshSubscriptions(button) {
  Array.from(selectedSubscriptionRows).forEach(function(row) {
    if (!row.isConnected || !row.classList.contains('source-row')) selectedSubscriptionRows.delete(row);
  });
  const selectedRows = Array.from(selectedSubscriptionRows);
  const remoteSources = getSelectedPersistedRemoteSources(selectedRows);
  const sourceIds = Array.from(new Set(remoteSources.map(function(source) { return String(source.id); })));
  if (!sourceIds.length) {
    show('选中的订阅中没有可更新的已保存远程订阅', true);
    updateSubscriptionList();
    return;
  }
  subscriptionRefreshInFlight = true;
  const label = button.querySelector('[data-refresh-label]');
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  if (label) label.textContent = 'Worker更新中…';
  try {
    let skipped = selectedRows.length - sourceIds.length;
    const response = await fetch(ADMIN_API_BASE + '/subscriptions/refresh', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ids: sourceIds}),
      cache: 'no-store'
    });
    const data = await response.json();
    if (!response.ok || typeof data.updated !== 'number' || typeof data.failed !== 'number' ||
        typeof data.skipped !== 'number' || !Array.isArray(data.updatedIds)) {
      throw new Error(adminErrorMessage(data.error, '订阅更新失败'));
    }
    skipped += data.skipped;
    const results = data.updatedIds.map(function(id) { return {id: String(id), ok: true}; });
    for (let index = 0; index < data.failed; index++) results.push({ok: false});
    const updatedIds = results.filter(function(result) { return result.ok; }).map(function(result) { return result.id; });
    const updated = updatedIds.length;
    const failed = results.length - updated;
    if (updatedIds.length > 0) await loadSubscriptionTraffic(updatedIds);
    if (updated === 0 && failed === 0) {
      show('选中的订阅中没有可更新的已保存远程订阅');
    } else {
      const summary = '更新完成：成功 ' + updated + ' 个，失败 ' + failed + ' 个，跳过 ' + skipped + ' 个';
      const firstFailure = results.find(function(result) { return !result.ok && result.error; });
      const detail = firstFailure ? '；' + adminErrorMessage(firstFailure.error, '') : '';
      show(summary + detail, failed > 0);
    }
  } catch (error) {
    show(error.message || '订阅更新失败，请检查网络后重试', true);
  } finally {
    subscriptionRefreshInFlight = false;
    button.removeAttribute('aria-busy');
    if (label) label.textContent = '更新订阅';
    updateSubscriptionList();
  }
}

function initializeSubscriptionManager() {
  const panel = document.querySelector('[data-panel="subscriptions"]');
  const editor = el('subscriptionEditor');
  const moveDialog = el('subscriptionMoveDialog');
  editor.addEventListener('close', function() {
    if (selectedSubscriptionRow) selectSubscriptionSource(null);
  });
  ['input', 'change'].forEach(function(type) {
    editor.querySelectorAll('[data-editor-field]').forEach(function(control) {
      control.addEventListener(type, function() {
        if (!selectedSubscriptionRow) return;
        const field = control.dataset.editorField;
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
  panel.querySelector('[data-action="move-selected-subscriptions"]').addEventListener('click', openSubscriptionMoveDialog);
  panel.querySelector('[data-action="delete-selected-subscriptions"]').addEventListener('click', deleteSelectedSubscriptions);
  moveDialog.querySelectorAll('[data-action="close-subscription-move"]').forEach(function(button) {
    button.addEventListener('click', function() { moveDialog.close(); });
  });
  moveDialog.querySelector('[data-move-target-group]').addEventListener('change', updateSubscriptionMoveConfirmState);
  moveDialog.querySelector('[data-action="confirm-move-subscriptions"]').addEventListener('click', function() {
    moveSelectedSubscriptions(moveDialog.querySelector('[data-move-target-group]').value);
  });
  panel.querySelectorAll('[data-action="refresh-subscriptions"]').forEach(function(button) {
    button.addEventListener('click', function(event) {
      refreshSubscriptions(event.currentTarget);
    });
  });
  panel.querySelectorAll('[data-action="close-subscription-editor"]').forEach(function(button) {
    button.addEventListener('click', function() { selectSubscriptionSource(null); });
  });
  panel.querySelector('[data-action="copy-subscription-info"]').addEventListener('click', async function() {
    const input = editor.querySelector('[data-editor-source-info]');
    if (!input.value) return show('没有可复制的订阅信息', true);
    try { await navigator.clipboard.writeText(input.value); show('订阅信息已复制'); }
    catch (_) { input.focus(); input.select(); show('无法自动复制，请手动复制订阅信息', true); }
  });
  panel.querySelector('[data-subscription-page-size]').addEventListener('change', function() {
    subscriptionPageSize = Number(this.value) || 20;
    subscriptionCurrentPage = 1;
    updateSubscriptionList();
  });
  panel.querySelector('[data-subscription-page-prev]').addEventListener('click', function() { subscriptionCurrentPage -= 1; updateSubscriptionList(); });
  panel.querySelector('[data-subscription-page-next]').addEventListener('click', function() { subscriptionCurrentPage += 1; updateSubscriptionList(); });
  panel.querySelector('.subscription-select-page').addEventListener('change', function() {
    const pageSize = subscriptionPageSize;
    const rows = getSourceRows().filter(function(row) { return !row.hidden; }).slice(0, pageSize);
    rows.forEach(function(row) {
      if (this.checked) selectedSubscriptionRows.add(row);
      else selectedSubscriptionRows.delete(row);
      row.querySelector('[data-source-select]').checked = this.checked;
    }, this);
    updateSubscriptionList();
  });
  el('subscriptionRows').addEventListener('change', function(event) {
    const row = event.target.closest('.source-row');
    if (!row || !event.target.matches('[data-source-select]')) return;
    if (event.target.checked) selectedSubscriptionRows.add(row);
    else selectedSubscriptionRows.delete(row);
    updateSubscriptionList();
  });
}
function syncSubscriptionFilterControls(row) {
  row.querySelectorAll('[data-filter-toggle]').forEach(function(toggle) {
    const section = toggle.closest('[data-filter-section]');
    if (!section) return;
    const enabled = toggle.checked;
    section.querySelectorAll('[data-filter-option]').forEach(function(control) {
      control.disabled = !enabled;
    });
    const options = section.querySelector('.permission-filter-options');
    if (options) options.classList.toggle('is-disabled', !enabled);
  });
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
function fillState(state, section) {
  currentState = state || {};
  settingsFilling = true;
  try {
    const settings = currentState.settings || {};
    if (!section || section === 'subscriptions') {
      renderSubscriptionGroups(Array.isArray(currentState.subscriptionGroups) ? currentState.subscriptionGroups : []);
    }
    if (!section || section === 'about-page') {
      setValue('websiteUrl', settings.websiteUrl);
      setValue('feedbackEmail', settings.feedbackEmail);
      setValue('feedbackUrl', settings.feedbackUrl);
      setValue('githubUrl', settings.githubUrl);
      setValue('telegramUrl', settings.telegramUrl);
    }
    if (!section || section === 'update-management') {
      setValue('latestAppVersion', settings.latestAppVersion);
      setValue('appDownloadUrl', settings.appDownloadUrl);
    }
    if (!section || section === 'registration-control') {
      const registrationMode = settings.registrationEnabled === false
        ? 'closed'
        : settings.requireAdminRegistrationCode === true
          ? 'code'
          : 'normal';
      setRegistrationMode(registrationMode);
      loadRegistrationCodes();
      loadVipCodes();
    }
  } finally {
    settingsFilling = false;
  }
  if (!section) resetSettingsDrafts();
  updateSettingsSaveControls();
  if (!section || section === 'subscriptions') loadSubscriptionTraffic();
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
function readEnabledSubscriptionFilter(group, toggleField, valuesField, allowedValues) {
  const toggle = group.querySelector('[data-field="' + toggleField + '"]');
  if (!toggle.checked) return allowedValues.slice();
  return Array.from(group.querySelectorAll('[data-field="' + valuesField + '"]:checked'))
    .map(function(input) { return input.value; });
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
      allowedStatuses: readEnabledSubscriptionFilter(group, 'allowedStatusesFilterEnabled', 'allowedStatuses', ['normal', 'banned']),
      allowedIdentityTypes: readEnabledSubscriptionFilter(group, 'allowedIdentityTypesFilterEnabled', 'allowedIdentityTypes', ['human', 'bot']),
      allowedMembershipTypes: readEnabledSubscriptionFilter(group, 'allowedMembershipTypesFilterEnabled', 'allowedMembershipTypes', ['member', 'non_member']),
      updateEnabled: group.querySelector('[data-field="updateEnabled"]').checked,
      cacheMinutes: Number(group.querySelector('[data-field="groupCacheMinutes"]').value || 0),
      versionFilterEnabled: group.querySelector('[data-field="versionFilterEnabled"]').checked,
      minAppVersion: group.querySelector('[data-field="versionFilterEnabled"]').checked
        ? group.querySelector('[data-field="minAppVersion"]').value.trim() : '',
      maxAppVersion: group.querySelector('[data-field="versionFilterEnabled"]').checked
        ? group.querySelector('[data-field="maxAppVersion"]').value.trim() : '',
      subscriptions: sources
    };
  });
}
function formState() {
  const subscriptionGroups = readSubscriptionGroups();
  const registrationMode = selectedRegistrationMode();
  return {
    subscriptionGroups: subscriptionGroups,
    settings: {
      websiteUrl: value('websiteUrl'),
      feedbackEmail: value('feedbackEmail'),
      feedbackUrl: value('feedbackUrl'),
      githubUrl: value('githubUrl'),
      telegramUrl: value('telegramUrl'),
      latestAppVersion: value('latestAppVersion'),
      appDownloadUrl: value('appDownloadUrl'),
      registrationEnabled: registrationMode !== 'closed',
      requireAdminRegistrationCode: registrationMode === 'code'
    }
  };
}
function selectedRegistrationMode() {
  const selected = el('registrationModeSwitch')
    .querySelector('input[name="registrationMode"]:checked');
  return selected ? selected.value : 'normal';
}
function setRegistrationMode(mode) {
  const control = el('registrationModeSwitch');
  const selectedMode = ['normal', 'code', 'closed'].includes(mode) ? mode : 'normal';
  control.dataset.mode = selectedMode;
  const selected = control.querySelector('input[name="registrationMode"][value="' + selectedMode + '"]');
  if (selected) selected.checked = true;
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
    '<thead><tr><th>注册码</th><th>状态</th><th>使用次数</th><th>发放时间</th><th>使用时间</th><th>使用账户 UID</th></tr></thead>' +
    '<tbody>' + rows.map(function(row) {
      const useCount = Math.max(0, Number(row.useCount) || 0);
      const maxUses = Math.max(1, Number(row.maxUses) || 1);
      const status = useCount >= maxUses ? '已用完' : useCount > 0 ? '生效中' : '未使用';
      const previewUids = String(row.previewUids || row.usedByUid || '').trim();
      const uidPreview = previewUids
        ? esc(previewUids) + (useCount > 2 ? '…' : '')
        : '-';
      const history = useCount > 0
        ? '<button class="registration-code-history-link" type="button" onclick="viewRegistrationCodeUsages(' + Number(row.id) + ')">查看记录 (' + useCount + '人)</button>'
        : '';
      return '<tr><td><span class="registration-code-cell"><code>' + esc(row.code) + '</code>' +
        '<button class="registration-code-copy" type="button" title="复制注册码" aria-label="复制注册码" data-code="' + esc(row.code) + '" onclick="copyRegistrationCode(this.dataset.code)"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"></path></svg></button></span></td>' +
        '<td>' + status + '</td>' +
        '<td>' + useCount + ' / ' + maxUses + '</td>' +
        '<td>' + esc(row.createdAt || '-') + '</td>' +
        '<td>' + esc(row.usedAt || '-') + '</td>' +
        '<td><span class="registration-code-preview"><code>' + uidPreview + '</code>' + history + '</span></td></tr>';
    }).join('') + '</tbody></table>';
}

async function copyRegistrationCode(code) {
  try {
    await navigator.clipboard.writeText(String(code || ''));
    show('注册码已复制');
  } catch (error) {
    show('复制失败，请手动复制注册码', true);
  }
}

async function viewRegistrationCodeUsages(codeId) {
  const dialog = el('registrationCodeUsagesDialog');
  const host = el('registrationCodeUsagesTable');
  const summary = el('registrationCodeUsagesSummary');
  if (!dialog || !host) return;
  host.innerHTML = '<div class="loading-state">正在读取使用记录</div>';
  if (summary) summary.textContent = '';
  dialog.showModal();
  try {
    const response = await fetch(ADMIN_API_BASE + '/registration-codes/' + encodeURIComponent(codeId) + '/usages', {
      headers: headers(),
      cache: 'no-store'
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    const usages = Array.isArray(data.usages) ? data.usages : [];
    if (summary) summary.textContent = '注册码 ' + String(data.code || '') + ' · 共 ' + usages.length + ' 次使用';
    if (!usages.length) {
      host.innerHTML = '<div class="loading-state">暂无使用记录</div>';
      return;
    }
    host.innerHTML = '<table class="traffic-detail-table" aria-label="注册码使用记录"><thead><tr><th>使用次数</th><th>使用账户 UID</th><th>使用时间</th></tr></thead><tbody>' +
      usages.map(function(row) {
        return '<tr><td>' + Number(row.useNumber || 0) + '</td><td><code>' + esc(row.uid || '-') + '</code></td><td>' + esc(row.usedAt || '-') + '</td></tr>';
      }).join('') + '</tbody></table>';
  } catch (error) {
    host.innerHTML = '<div class="error-state">注册码使用记录读取失败</div>';
  }
}

function closeRegistrationCodeUsages() {
  const dialog = el('registrationCodeUsagesDialog');
  if (dialog && dialog.open) dialog.close();
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

function renderVipCodes(codes) {
  const host = el('vipCodesTable');
  if (!host) return;
  const rows = Array.isArray(codes) ? codes : [];
  if (!rows.length) {
    host.innerHTML = '<div class="loading-state">暂无 VIP 卡密</div>';
    return;
  }
  host.innerHTML = '<table class="security-account-table" aria-label="VIP 卡密">' +
    '<thead><tr><th>卡密</th><th>状态</th><th>有效时长</th><th>发放时间</th><th>兑换时间</th><th>使用账户 UID</th></tr></thead>' +
    '<tbody>' + rows.map(function(row) {
      return '<tr><td><span class="registration-code-cell"><code>' + esc(row.code || '') + '</code>' +
        '<button class="registration-code-copy" type="button" title="复制 VIP 卡密" aria-label="复制 VIP 卡密" data-vip-code="' + esc(row.code || '') + '" onclick="copyVipCode(this.dataset.vipCode)"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"></path></svg></button></span></td>' +
        '<td>' + (row.used ? '已兑换' : '未使用') + '</td>' +
        '<td>' + Number(row.durationDays || 0) + ' 天</td>' +
        '<td>' + esc(row.createdAt || '-') + '</td>' +
        '<td>' + esc(row.usedAt || '-') + '</td>' +
        '<td><code>' + esc(row.usedByUid || '-') + '</code></td></tr>';
    }).join('') + '</tbody></table>';
}

async function loadVipCodes() {
  const host = el('vipCodesTable');
  if (!host || !adminToken) return;
  try {
    const response = await fetch(ADMIN_API_BASE + '/vip-codes?limit=200', {
      headers: headers(),
      cache: 'no-store'
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    renderVipCodes(data.codes);
  } catch (error) {
    host.innerHTML = '<div class="error-state">VIP 卡密读取失败</div>';
  }
}

async function issueVipCodes() {
  const count = Number(value('vipCodeCount') || 1);
  const durationDays = Number(value('vipCodeDurationDays') || 30);
  if (!Number.isInteger(count) || count < 1 || count > 100) {
    return show('一次最多发放 100 个 VIP 卡密', true);
  }
  if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 36500) {
    return show('VIP 有效天数必须是 1 到 36500 之间的整数', true);
  }
  const response = await fetch(ADMIN_API_BASE + '/vip-codes', {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({count: count, durationDays: durationDays})
  });
  const data = await response.json();
  if (!response.ok) return show(adminErrorMessage(data.error, 'VIP 卡密发放失败'), true);
  show('已发放 ' + (data.codes || []).length + ' 张 VIP 卡密，每张有效 ' + durationDays + ' 天');
  await loadVipCodes();
}

async function copyVipCode(code) {
  try {
    await navigator.clipboard.writeText(String(code || ''));
    show('VIP 卡密已复制');
  } catch (error) {
    show('复制失败，请手动复制 VIP 卡密', true);
  }
}

async function issueRegistrationCodes() {
  const count = Number(value('registrationCodeCount') || 1);
  const maxUses = Number(value('registrationCodeMaxUses') || 1);
  if (!Number.isInteger(count) || count < 1 || count > 100) {
    return show('一次最多发放 100 个注册码', true);
  }
  if (!Number.isInteger(maxUses) || maxUses < 1 || maxUses > 1000) {
    return show('单个注册码可用次数必须是 1 到 1000 之间的整数', true);
  }
  const response = await fetch(ADMIN_API_BASE + '/registration-codes', {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({count: count, maxUses: maxUses})
  });
  const data = await response.json();
  if (!response.ok) return show(adminErrorMessage(data.error, '注册码发放失败'), true);
  show('已发放 ' + (data.codes || []).length + ' 个注册码，每码可用 ' + maxUses + ' 次');
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
  let saveSucceeded = false;
  settingsSaveInFlight = section;
  settingsSaveMessages.delete(section);
  updateSettingsSaveControls();
  try {
    const form = formState();
    if (section === 'subscriptions') {
      const versionError = validateSubscriptionGroupVersions(form.subscriptionGroups);
      if (versionError) throw new Error(versionError);
    }
    const settings = section === 'about-page'
        ? Object.assign({}, currentState.settings, {
          websiteUrl: form.settings.websiteUrl,
          feedbackEmail: form.settings.feedbackEmail,
          feedbackUrl: form.settings.feedbackUrl,
          githubUrl: form.settings.githubUrl,
          telegramUrl: form.settings.telegramUrl
        })
      : section === 'update-management'
        ? Object.assign({}, currentState.settings, {
          latestAppVersion: form.settings.latestAppVersion,
          appDownloadUrl: form.settings.appDownloadUrl
        })
      : section === 'registration-control'
        ? Object.assign({}, currentState.settings, {
          registrationEnabled: form.settings.registrationEnabled,
          requireAdminRegistrationCode: form.settings.requireAdminRegistrationCode
        })
        : currentState.settings;
    const state = {
      subscriptionGroups: section === 'subscriptions' ? form.subscriptionGroups : currentState.subscriptionGroups,
      settings: settings
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
      settingsSaveMessages.set(section, {text: '正在保存最新修改…', error: false});
    }
    saveSucceeded = true;
    return true;
  } catch (error) {
    if (session !== settingsSession) return false;
    const message = error.message || '保存失败，请检查网络后重试';
    settingsSaveMessages.set(section, {text: message, error: true});
    show(message, true);
    return false;
  } finally {
    if (session === settingsSession) {
      settingsSaveInFlight = '';
      updateSettingsSaveControls();
      resumePendingSettingsSaves(section, version, saveSucceeded);
    }
  }
}
initializeGrowthRange();
initializeActivityRange();
initializeTrafficRange();
initializeSubscriptionManager();
initializeSettingsDrafts();
const subscriptionTabButton = document.querySelector('[data-tab="subscriptions"]');
if (subscriptionTabButton) subscriptionTabButton.addEventListener('click', loadSubscriptionTraffic);
