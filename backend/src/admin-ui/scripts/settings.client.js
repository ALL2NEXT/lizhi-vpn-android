function sourceOrder(row) {
  const order = Number(row.dataset.sourceOrder);
  return Number.isFinite(order) ? order : 0;
}

function bindSubscriptionSwitchStates(row) {
  row.querySelectorAll('.subscription-switch').forEach(function(label) {
    const input = label.querySelector('input[type="checkbox"]');
    const state = label.querySelector('[data-switch-state]');
    if (!input || !state) return;
    const update = function() { state.textContent = input.checked ? '已启用' : '已停用'; };
    input.addEventListener('change', update);
    update();
  });
}

function getSubscriptionGroupRows() {
  return Array.from(el('subscriptionRows').querySelectorAll(':scope > .subscription-group'));
}

function insertSourceByOrder(row, groupRow) {
  const container = groupRow.querySelector('[data-group-sources]');
  if (!container) return;
  const nextRow = Array.from(container.children).find(function(candidate) {
    return candidate.classList.contains('source-row') && sourceOrder(candidate) > sourceOrder(row);
  });
  container.insertBefore(row, nextRow || null);
}

function setSubscriptionGroupExpanded(row, expanded) {
  const body = row.querySelector('[data-group-body]');
  const button = row.querySelector('[data-action="group-collapse"]');
  body.hidden = !expanded;
  button.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  button.textContent = expanded ? '收起' : '展开';
}

function renderSubscriptionGroups(groups) {
  const container = el('subscriptionRows');
  container.innerHTML = '';
  (Array.isArray(groups) ? groups : []).forEach(function(group, groupIndex) {
    const row = addSubscriptionGroup(group);
    (Array.isArray(group.subscriptions) ? group.subscriptions : []).forEach(function(source, index) {
      addSource(row, source, index);
    });
    if (groupIndex > 0) setSubscriptionGroupExpanded(row, false);
  });
  updateSourceRowsMeta();
  updateSubscriptionGroupControls();
}

function addSubscriptionGroup(group) {
  group = group || {};
  const row = document.createElement('section');
  row.className = 'subscription-group';
  row.dataset.groupId = String(group.id || crypto.randomUUID());
  const allowedStatuses = Array.isArray(group.allowedStatuses) ? group.allowedStatuses : ['normal'];
  const allowedAccountTypes = Array.isArray(group.allowedAccountTypes)
    ? group.allowedAccountTypes
    : ['guest', 'formal'];
  row.innerHTML = '<div class="subscription-group-heading">' +
    '<div class="subscription-group-leading-controls" role="group" aria-label="启用和分组排序">' +
      '<label class="subscription-switch subscription-group-enabled"><span class="subscription-switch-control"><input data-field="groupEnabled" type="checkbox" aria-label="启用分组" ' + (group.enabled === false ? '' : 'checked') + '><span class="subscription-switch-track"><span></span></span></span><span class="subscription-switch-state" data-switch-state></span></label>' +
      '<button class="secondary" type="button" data-action="group-up" aria-label="上移分组" title="上移分组">↑</button>' +
      '<button class="secondary" type="button" data-action="group-down" aria-label="下移分组" title="下移分组">↓</button>' +
    '</div>' +
    '<div class="subscription-group-title"><span>分组</span>' +
      '<label>分组名称<input data-field="groupName" type="text" maxlength="80" placeholder="例如：标准线路"></label>' +
      '<small class="subscription-group-summary" data-group-summary></small>' +
    '</div>' +
    '<div class="subscription-group-controls" role="group" aria-label="订阅数量和分组设置">' +
      '<span data-group-count>0 条订阅</span>' +
      '<button class="secondary" type="button" data-action="group-collapse" aria-expanded="true">收起</button>' +
      '<button class="secondary" type="button" data-action="permissions" aria-haspopup="dialog">设置 &#9881;&#65038;</button>' +
    '</div>' +
  '</div>' +
  '<dialog class="subscription-permissions-dialog" data-group-permissions-dialog aria-label="分组权限设置">' +
    '<div class="source-settings-dialog-header"><div><h3>分组权限设置</h3><p>设置允许访问此分组的账号状态、账户类型和 App 版本区间。</p></div>' +
      '<button class="secondary" type="button" data-action="permissions-close">关闭</button>' +
    '</div>' +
    '<div class="subscription-group-permissions">' +
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
    '</div>' +
    '<div class="source-settings-dialog-actions subscription-permissions-dialog-actions">' +
      '<button class="danger" type="button" data-action="group-delete">删除分组</button>' +
      '<button class="secondary" type="button" data-action="permissions-close">完成</button>' +
    '</div>' +
  '</dialog>' +
  '<div data-group-body>' +
  '<div class="source-list-header" aria-hidden="true"><span>排序</span><span>启用</span><span>订阅名称</span><span>备注</span><span>操作</span></div>' +
  '<div class="subscription-group-sources" data-group-sources></div>' +
  '<button class="secondary subscription-group-add" type="button" data-action="add-source">添加订阅</button></div>';
  row.querySelector('[data-field="groupName"]').value = String(group.name || '');
  row.querySelector('[data-field="minAppVersion"]').value = String(group.minAppVersion || '');
  row.querySelector('[data-field="maxAppVersion"]').value = String(group.maxAppVersion || '');
  bindSubscriptionSwitchStates(row);
  el('subscriptionRows').appendChild(row);
  row.querySelector('[data-field="minAppVersion"]').addEventListener('change', updateSubscriptionAppVersionOptions);
  row.querySelector('[data-field="maxAppVersion"]').addEventListener('change', updateSubscriptionAppVersionOptions);
  row.querySelector('[data-action="add-source"]').onclick = function() {
    openSubscriptionImport(row);
  };
  row.querySelector('[data-action="group-up"]').onclick = function() {
    moveSubscriptionGroup(row, -1);
  };
  row.querySelector('[data-action="group-down"]').onclick = function() {
    moveSubscriptionGroup(row, 1);
  };
  const permissionsDialog = row.querySelector('[data-group-permissions-dialog]');
  row.querySelector('[data-action="permissions"]').onclick = function() {
    if (permissionsDialog && !permissionsDialog.open) permissionsDialog.showModal();
  };
  row.querySelectorAll('[data-action="permissions-close"]').forEach(function(button) {
    button.onclick = function() { permissionsDialog.close(); };
  });
  permissionsDialog.addEventListener('click', function(event) {
    if (event.target === permissionsDialog) permissionsDialog.close();
  });
  row.querySelector('[data-action="group-collapse"]').onclick = function() {
    const body = row.querySelector('[data-group-body]');
    setSubscriptionGroupExpanded(row, body.hidden);
  };
  row.querySelector('[data-action="group-delete"]').onclick = function() {
    if (permissionsDialog.open) permissionsDialog.close();
    row.remove();
    updateSubscriptionGroupControls();
    markSettingsDirty('subscriptions');
  };
  updateSubscriptionGroupControls();
  updateSubscriptionAppVersionOptions();
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
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

function moveSubscriptionGroup(row, direction) {
  const groups = getSubscriptionGroupRows();
  const index = groups.indexOf(row);
  const target = groups[index + direction];
  if (!target) return;
  if (direction < 0) row.parentElement.insertBefore(row, target);
  else row.parentElement.insertBefore(target, row);
  updateSubscriptionGroupControls();
  markSettingsDirty('subscriptions');
}

function updateSubscriptionGroupControls() {
  const groups = getSubscriptionGroupRows();
  groups.forEach(function(group, index) {
    const up = group.querySelector('[data-action="group-up"]');
    const down = group.querySelector('[data-action="group-down"]');
    if (up) up.disabled = index === 0;
    if (down) down.disabled = index === groups.length - 1;
  });
}

function addSource(groupRow, source, sourceOrderValue) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  const existingOrders = Array.from(groupRow.querySelectorAll('.source-row')).map(sourceOrder);
  row.dataset.sourceOrder = Number.isFinite(Number(sourceOrderValue))
    ? String(Number(sourceOrderValue))
    : String(existingOrders.length ? Math.max.apply(null, existingOrders) + 1 : 0);
  const sourceType = source.content ? 'content' : 'url';
  // 每条订阅的设定弹窗保留在行内，关闭后输入仍作为当前页面草稿保留。
  row.innerHTML = '<div class="source-row-header">' +
    '<div class="source-row-sort-controls" role="group" aria-label="订阅排序">' +
      '<button class="secondary source-move-button" type="button" data-action="move-up" aria-label="上移订阅" title="上移订阅">↑</button>' +
      '<button class="secondary source-move-button" type="button" data-action="move-down" aria-label="下移订阅" title="下移订阅">↓</button>' +
    '</div>' +
    '<label class="subscription-switch source-enabled-check"><span class="subscription-switch-control"><input data-field="enabled" type="checkbox" aria-label="启用订阅" ' + (source.enabled === false ? '' : 'checked') + '><span class="subscription-switch-track"><span></span></span></span></label>' +
    '<div class="source-row-heading">' +
      '<div class="field"><input data-field="name" aria-label="订阅名称" value="' + esc(source.name) + '"></div>' +
      '<div class="field"><input data-field="remark" type="text" aria-label="备注" value="' + esc(source.remark) + '" placeholder="仅后台保存"></div>' +
    '</div>' +
    '<div class="source-row-controls" role="group" aria-label="订阅详细设置">' +
      '<button class="secondary source-edit-button" type="button" data-action="details" aria-haspopup="dialog" aria-label="订阅详细设定" title="订阅详细设定">&#9881;&#65038;</button>' +
      '<button class="danger source-delete-button" type="button" data-action="source-delete-row" aria-label="删除订阅" title="删除订阅">&#128465;&#65038;</button>' +
    '</div>' +
  '</div>' +
  '<dialog class="source-settings-dialog" data-source-dialog aria-label="订阅详细设定">' +
    '<div class="source-settings-dialog-header"><div><h3>订阅详细设定</h3><p data-source-dialog-name></p></div>' +
      '<button class="secondary" type="button" data-action="source-close">关闭</button>' +
    '</div>' +
    '<section class="source-section source-details-section">' +
      '<div class="grid source-details-grid">' +
        '<div class="field source-type-field"><label>订阅来源<select data-field="sourceType">' +
          '<option value="url" ' + (sourceType === 'url' ? 'selected' : '') + '>外部接口</option>' +
          '<option value="content" ' + (sourceType === 'content' ? 'selected' : '') + '>纯文本节点</option>' +
        '</select></label></div>' +
      '</div>' +
      '<div class="source-mode" data-source-mode="url"><div class="grid">' +
        '<div class="field"><label>外部订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/sub"></label></div>' +
        '<div class="field"><label>接口缓存时间（分钟）<input data-field="cacheMinutes" type="number" min="0" max="10080" step="1" value="' + esc(source.cacheMinutes == null ? 30 : source.cacheMinutes) + '"></label></div>' +
      '</div><label class="check"><input data-field="autoRename" type="checkbox" ' + (source.autoRename === true ? 'checked' : '') + '>自动重命名节点</label><small>按节点名称识别国家，并批量改为“国家+编号”，例如：日本1、日本2。</small><small>支持节点 URI、Base64、Clash、sing-box 和 Xray 订阅，由 Worker 转换后加密下发；缓存时间设为 0 会每次请求上游。</small></div>' +
      '<div class="source-mode" data-source-mode="content"><div class="field"><label>纯文本节点（每行一个）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div><small>保存后由 Worker 直接加密下发，不经过外部接口缓存。</small></div>' +
    '</section>' +
    '<div class="source-settings-dialog-actions"><button class="danger" type="button" data-action="source-delete">删除订阅</button></div>' +
  '</dialog>';
  const removeSource = function() {
    const dialog = row.querySelector('[data-source-dialog]');
    if (dialog.open) dialog.close();
    row.remove();
    updateSourceRowsMeta();
    markSettingsDirty('subscriptions');
  };
  row.querySelector('[data-action="source-delete"]').onclick = removeSource;
  row.querySelector('[data-action="source-delete-row"]').onclick = removeSource;
  row.querySelector('[data-field="sourceType"]').onchange = function() { updateSourceMode(row); };
  row.querySelector('[data-field="name"]').addEventListener('input', updateSourceRowsMeta);
  // 使用原生弹窗，让浏览器处理焦点约束、Esc 关闭和背景遮罩。
  row.querySelector('[data-action="details"]').onclick = function() {
    const dialog = row.querySelector('[data-source-dialog]');
    if (dialog && !dialog.open) dialog.showModal();
  };
  row.querySelector('[data-action="source-close"]').onclick = function() {
    row.querySelector('[data-source-dialog]').close();
  };
  row.querySelector('[data-action="move-up"]').onclick = function() {
    moveSourceRow(row, -1);
  };
  row.querySelector('[data-action="move-down"]').onclick = function() {
    moveSourceRow(row, 1);
  };
  bindSubscriptionSwitchStates(row);
  updateSourceMode(row);
  insertSourceByOrder(row, groupRow);
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
    if (count) count.textContent = rows.length + '条订阅 · ' + enabledCount + '条启用';
    const summary = group.querySelector('[data-group-summary]');
    if (summary) {
      const statuses = Array.from(group.querySelectorAll('[data-field="allowedStatuses"]:checked')).map(function(input) { return input.value === 'banned' ? '封禁' : '正常'; });
      const types = Array.from(group.querySelectorAll('[data-field="allowedAccountTypes"]:checked')).map(function(input) { return input.value === 'formal' ? '正式用户' : '游客'; });
      const minimum = group.querySelector('[data-field="minAppVersion"]').value.trim();
      const maximum = group.querySelector('[data-field="maxAppVersion"]').value.trim();
      const items = [
        statuses.join('、') || '未允许任何状态',
        types.join('、') || '未允许任何类型',
        '版本：' + (minimum || '不限') + '至' + (maximum || '不限')
      ];
      summary.replaceChildren();
      items.forEach(function(text) {
        const chip = document.createElement('span');
        chip.textContent = text;
        summary.appendChild(chip);
      });
    }
    rows.forEach(function(row, index) {
      const nameInput = row.querySelector('[data-field="name"]');
      if (nameInput) nameInput.placeholder = '订阅' + (sourceOrder(row) + 1);
      const dialogName = row.querySelector('[data-source-dialog-name]');
      if (dialogName) dialogName.textContent = nameInput.value.trim() || nameInput.placeholder;
      const moveUp = row.querySelector('[data-action="move-up"]');
      const moveDown = row.querySelector('[data-action="move-down"]');
      if (moveUp) moveUp.disabled = index === 0;
      if (moveDown) moveDown.disabled = index === rows.length - 1;
    });
  });
}
function moveSourceRow(row, direction) {
  const rows = Array.from(row.parentElement.children).filter(function(candidate) {
    return candidate.classList.contains('source-row');
  });
  const index = rows.indexOf(row);
  const targetIndex = index + direction;
  if (index < 0 || targetIndex < 0 || targetIndex >= rows.length) return;
  const target = rows[targetIndex];
  const rowOrder = row.dataset.sourceOrder;
  row.dataset.sourceOrder = target.dataset.sourceOrder;
  target.dataset.sourceOrder = rowOrder;
  const container = row.parentElement;
  if (direction < 0) container.insertBefore(row, target);
  else container.insertBefore(target, row);
  updateSourceRowsMeta();
  markSettingsDirty('subscriptions');
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
  group = group || {};
  const row = document.createElement('section');
  row.className = 'web-nav-group';
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
  '<button class="secondary web-nav-add-site" type="button" data-action="add-site">添加网站</button>';
  row.querySelector('[data-field="title"]').value = String(group.title || '');
  const container = el('webNavigationGroups');
  container.appendChild(row);
  const items = Array.isArray(group.items) ? group.items : [{}];
  items.forEach(function(item) { addWebNavigationItem(row, item); });
  row.querySelector('[data-action="group-up"]').onclick = function() {
    moveWebNavigationRow(row, -1);
  };
  row.querySelector('[data-action="group-down"]').onclick = function() {
    moveWebNavigationRow(row, 1);
  };
  row.querySelector('[data-action="group-delete"]').onclick = function() {
    row.remove();
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
  const groups = Array.from(el('webNavigationGroups').children);
  groups.forEach(function(group, groupIndex) {
    const groupUp = group.querySelector('[data-action="group-up"]');
    const groupDown = group.querySelector('[data-action="group-down"]');
    if (groupUp) groupUp.disabled = groupIndex === 0;
    if (groupDown) groupDown.disabled = groupIndex === groups.length - 1;
    const items = Array.from(group.querySelector('.web-nav-items').children);
    const count = group.querySelector('[data-group-count]');
    if (count) count.textContent = items.length + ' 个网站';
    items.forEach(function(item, itemIndex) {
      const itemUp = item.querySelector('[data-action="item-up"]');
      const itemDown = item.querySelector('[data-action="item-down"]');
      if (itemUp) itemUp.disabled = itemIndex === 0;
      if (itemDown) itemDown.disabled = itemIndex === items.length - 1;
    });
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
      (Array.isArray(settings.webNavigationGroups) ? settings.webNavigationGroups : []).forEach(addWebNavigationGroup);
    }
    if (!section || section === 'about-page') {
      setValue('websiteUrl', settings.websiteUrl);
      setValue('feedbackEmail', settings.feedbackEmail);
      setValue('feedbackUrl', settings.feedbackUrl);
      setValue('githubUrl', settings.githubUrl);
      setValue('telegramUrl', settings.telegramUrl);
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
    if (!section || section === 'debug-interface') {
      debugInterfaceTokenDraft = null;
      renderDebugInterface();
    } else if (section === 'subscriptions' && !settingsDirtySections.has('debug-interface')) {
      renderDebugInterface();
    }
  } finally {
    settingsFilling = false;
  }
  if (!section) resetSettingsDrafts();
  updateSettingsSaveControls();
}
function noticeDateTimeValue(data) {
  const text = String(data == null ? '' : data).trim();
  const match = text.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return match ? match[1] : '';
}
function getDebugInterfaceConfig() {
  const settings = currentState && currentState.settings ? currentState.settings : {};
  const config = settings.debugInterface || {};
  return {
    token: debugInterfaceTokenDraft === null ? String(config.token || '') : debugInterfaceTokenDraft,
    subscriptionIds: Array.isArray(config.subscriptionIds)
      ? config.subscriptionIds.map(function(id) { return String(id); })
      : []
  };
}
function debugInterfaceUrl(token) {
  return token
    ? new URL('/api/subscription/debug/' + encodeURIComponent(token), window.location.origin).toString()
    : '';
}
function updateDebugInterfaceUrl() {
  const input = el('debugInterfaceUrl');
  if (!input) return;
  const config = getDebugInterfaceConfig();
  const selected = Array.from(document.querySelectorAll('[data-debug-source-id]:checked'));
  input.value = selected.length ? debugInterfaceUrl(config.token) : '';
}
function renderDebugInterface(override) {
  const host = el('debugInterfaceSources');
  if (!host) return;
  const groups = Array.isArray(currentState && currentState.subscriptionGroups)
    ? currentState.subscriptionGroups
    : [];
  const sources = groups.flatMap(function(group) {
    return Array.isArray(group.subscriptions) ? group.subscriptions : [];
  });
  const config = override || getDebugInterfaceConfig();
  const selected = new Set(config.subscriptionIds);
  if (!sources.length) {
    host.innerHTML = '<div class="loading-state">暂无可用订阅线路</div>';
    updateDebugInterfaceUrl();
    return;
  }
  host.innerHTML = sources.map(function(source) {
    const id = String(source.id || '');
    const disabled = source.enabled === false;
    return '<label class="debug-interface-source' + (disabled ? ' disabled' : '') + '">' +
      '<input type="checkbox" data-debug-source-id="' + esc(id) + '" ' +
      (selected.has(id) ? 'checked ' : '') + '>' +
      '<span>' + esc(source.name || ('订阅' + id)) + '</span>' +
      (disabled ? '<small>已停用</small>' : '') +
      '</label>';
  }).join('');
  host.querySelectorAll('[data-debug-source-id]').forEach(function(control) {
    control.addEventListener('change', updateDebugInterfaceUrl);
  });
  updateDebugInterfaceUrl();
}
function regenerateDebugInterfaceToken() {
  if (!currentState) return;
  const config = getDebugInterfaceConfig();
  config.subscriptionIds = Array.from(document.querySelectorAll('[data-debug-source-id]:checked')).map(function(control) { return control.dataset.debugSourceId; });
  debugInterfaceTokenDraft = crypto.randomUUID();
  config.token = debugInterfaceTokenDraft;
  renderDebugInterface(config);
  markSettingsDirty('debug-interface');
  show('UUID 已重新生成，请保存调试接口后生效');
}
async function copyDebugInterfaceUrl() {
  const input = el('debugInterfaceUrl');
  const url = input && input.value;
  if (!url) return show('请先选择线路并保存调试接口', true);
  try {
    await navigator.clipboard.writeText(url);
    show('调试接口地址已复制');
  } catch (_) {
    input.focus();
    input.select();
    show('无法自动复制，请手动复制地址', true);
  }
}
function readSubscriptionSource(row, index) {
    const sourceType = row.querySelector('[data-field="sourceType"]').value;
    const url = row.querySelector('[data-field="url"]').value.trim();
    const content = row.querySelector('[data-field="content"]').value.trim();
    return {
      name: row.querySelector('[data-field="name"]').value.trim() || '订阅' + (index + 1),
      url: sourceType === 'url' ? url : '',
      enabled: row.querySelector('[data-field="enabled"]').checked,
      cacheMinutes: sourceType === 'url'
        ? Number(row.querySelector('[data-field="cacheMinutes"]').value || 0)
        : 0,
      autoRename: sourceType === 'url' && row.querySelector('[data-field="autoRename"]').checked,
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
  const debugConfig = getDebugInterfaceConfig();
  debugConfig.subscriptionIds = Array.from(document.querySelectorAll('[data-debug-source-id]:checked'))
    .map(function(control) { return control.dataset.debugSourceId; });
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
      debugInterface: debugConfig,
    notice: notice,
    appUpdate: appUpdate
  };
}
function renderRegistrationCodes(codes) {
  const host = el('registrationCodesTable');
  if (!host) return;
  const rows = Array.isArray(codes) ? codes : [];
  if (!rows.length) {
    host.innerHTML = '<div class="loading-state">暂无管理员注册码</div>';
    return;
  }
  host.innerHTML = '<table class="security-account-table" aria-label="一次性注册码">' +
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
  show('已发放 ' + (data.codes || []).length + ' 个一次性注册码');
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
      : section === 'registration-control'
        ? Object.assign({}, currentState.settings, {
          registrationEnabled: form.settings.registrationEnabled,
          requireAdminRegistrationCode: form.settings.requireAdminRegistrationCode
        })
        : section === 'debug-interface'
          ? Object.assign({}, currentState.settings, {
            debugInterface: form.debugInterface
          })
        : currentState.settings;
    const state = {
      subscriptionGroups: section === 'subscriptions' ? form.subscriptionGroups : currentState.subscriptionGroups,
      settings: settings,
      notice: section === 'notice' ? form.notice : currentState.notice,
      appUpdate: section === 'about-page' || section === 'app-update' ? form.appUpdate : currentState.appUpdate
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
initializeSettingsDrafts();
