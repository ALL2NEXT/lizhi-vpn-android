function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row collapsed';
  const sourceType = source.content ? 'content' : 'url';
  const minAccountType = ['banned', 'guest', 'formal'].includes(source.minAccountType)
    ? source.minAccountType
    : 'guest';
  row.innerHTML = '<div class="source-row-header">' +
    '<div class="source-row-heading">' +
      '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
      '<div class="field"><label>备注<input data-field="remark" type="text" value="' + esc(source.remark) + '" placeholder="仅后台保存"></label></div>' +
    '</div>' +
    '<div class="source-row-controls" role="group" aria-label="线路启用、排序和折叠操作">' +
      '<label class="check source-enabled-check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用</label>' +
      '<button class="secondary source-move-button" type="button" data-action="move-up" aria-label="上移线路" title="上移线路">↑</button>' +
      '<button class="secondary source-move-button" type="button" data-action="move-down" aria-label="下移线路" title="下移线路">↓</button>' +
      '<button class="secondary source-edit-button" type="button" data-action="collapse" aria-expanded="false" aria-label="编辑订阅线路" title="编辑订阅线路">🖊</button>' +
    '</div>' +
  '</div>' +
  '<div class="source-row-body" data-source-body hidden>' +
    '<section class="source-section source-permissions-section">' +
      '<h3 class="source-section-title">线路权限控制</h3>' +
      '<div class="grid source-permissions-grid">' +
        '<div class="field"><label>最低应用版本<input data-field="minAppVersion" type="text" value="' + esc(source.minAppVersion) + '" placeholder="例如 2.40（留空不限制）"></label></div>' +
        '<div class="field"><label>最低账户等级<select data-field="minAccountType">' +
          '<option value="banned" ' + (minAccountType === 'banned' ? 'selected' : '') + '>被封禁账户</option>' +
          '<option value="guest" ' + (minAccountType === 'guest' ? 'selected' : '') + '>游客</option>' +
          '<option value="formal" ' + (minAccountType === 'formal' ? 'selected' : '') + '>正式用户</option>' +
        '</select></label></div>' +
      '</div>' +
    '</section>' +
    '<section class="source-section source-details-section">' +
      '<h3 class="source-section-title">订阅详细设定</h3>' +
      '<div class="grid source-details-grid">' +
        '<div class="field source-type-field"><label>订阅来源<select data-field="sourceType">' +
          '<option value="url" ' + (sourceType === 'url' ? 'selected' : '') + '>外部接口</option>' +
          '<option value="content" ' + (sourceType === 'content' ? 'selected' : '') + '>纯文本节点</option>' +
        '</select></label></div>' +
        '<label class="check"><input data-field="refreshOnAppOpen" type="checkbox" ' + (source.refreshOnAppOpen === false ? '' : 'checked') + '>打开app立即更新</label>' +
      '</div>' +
      '<div class="source-mode" data-source-mode="url"><div class="grid">' +
        '<div class="field"><label>外部明文订阅地址<input data-field="url" type="url" value="' + esc(source.url) + '" placeholder="https://example.com/plain-sub"></label></div>' +
        '<div class="field"><label>接口缓存时间（分钟）<input data-field="cacheMinutes" type="number" min="0" max="10080" step="1" value="' + esc(source.cacheMinutes == null ? 30 : source.cacheMinutes) + '"></label></div>' +
      '</div><label class="check"><input data-field="autoRename" type="checkbox" ' + (source.autoRename === true ? 'checked' : '') + '>自动重命名节点</label><small>按节点名称识别国家，并批量改为“国家+编号”，例如：日本1、日本2。</small><small>接口必须返回可识别的明文节点，由 Worker 中转并加密；缓存时间设为 0 会每次请求上游。</small></div>' +
      '<div class="source-mode" data-source-mode="content"><div class="field"><label>纯文本节点（每行一个）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div><small>保存后由 Worker 直接加密下发，不经过外部接口缓存。</small></div>' +
    '</section>' +
    '<div class="row-actions"><button class="danger" type="button">删除</button></div>' +
  '</div>';
  row.querySelector('.danger').onclick = function() {
    row.remove();
    updateSourceRowsMeta();
  };
  row.querySelector('[data-field="sourceType"]').onchange = function() { updateSourceMode(row); };
  row.querySelector('[data-action="collapse"]').onclick = function() {
    toggleSourceRow(row);
  };
  row.querySelector('[data-action="move-up"]').onclick = function() {
    moveSourceRow(row, -1);
  };
  row.querySelector('[data-action="move-down"]').onclick = function() {
    moveSourceRow(row, 1);
  };
  updateSourceMode(row);
  el('subscriptionRows').appendChild(row);
  updateSourceRowsMeta();
}
function getSourceRows() {
  return Array.from(el('subscriptionRows').children).filter(function(row) {
    return row.classList.contains('source-row');
  });
}
function updateSourceRowsMeta() {
  const rows = getSourceRows();
  rows.forEach(function(row, index) {
    const nameInput = row.querySelector('[data-field="name"]');
    const moveUp = row.querySelector('[data-action="move-up"]');
    const moveDown = row.querySelector('[data-action="move-down"]');
    if (nameInput) nameInput.placeholder = '线路' + (index + 1);
    if (moveUp) moveUp.disabled = index === 0;
    if (moveDown) moveDown.disabled = index === rows.length - 1;
  });
}
function moveSourceRow(row, direction) {
  const rows = getSourceRows();
  const index = rows.indexOf(row);
  const targetIndex = index + direction;
  if (index < 0 || targetIndex < 0 || targetIndex >= rows.length) return;
  const container = el('subscriptionRows');
  if (direction < 0) container.insertBefore(row, rows[targetIndex]);
  else container.insertBefore(rows[targetIndex], row);
  updateSourceRowsMeta();
}
function toggleSourceRow(row) {
  const body = row.querySelector('[data-source-body]');
  const button = row.querySelector('[data-action="collapse"]');
  if (!body || !button) return;
  const collapsed = !body.hidden;
  body.hidden = collapsed;
  row.classList.toggle('collapsed', collapsed);
  button.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
  button.textContent = '🖊';
  button.setAttribute('aria-label', collapsed ? '编辑订阅线路' : '收起订阅线路');
  button.title = collapsed ? '编辑订阅线路' : '收起订阅线路';
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
function fillState(state) {
  currentState = state || {};
  el('subscriptionRows').innerHTML = '';
  (currentState.subscriptions || []).forEach(addSource);
  if (!currentState.subscriptions || currentState.subscriptions.length === 0) addSource();
  const settings = currentState.settings || {};
  setValue('websiteUrl', settings.websiteUrl);
  setValue('feedbackEmail', settings.feedbackEmail);
  setValue('feedbackUrl', settings.feedbackUrl);
   setValue('githubUrl', settings.githubUrl);
  setValue('telegramUrl', settings.telegramUrl);
  setValue('nodeRequestTimeoutMs', settings.nodeRequestTimeoutMs || 25000);
  checked('registrationEnabled', settings.registrationEnabled !== false);
  checked('requireAdminRegistrationCode', settings.requireAdminRegistrationCode === true);
  const notice = currentState.notice || {};
  checked('noticeEnabled', Boolean(currentState.notice && notice.hasNotice !== false));
  setValue('noticeTitle', notice.title);
  setValue('noticeId', notice.noticeId);
  setValue('noticeStartAt', noticeDateTimeValue(notice.startAt));
  setValue('noticeEndAt', noticeDateTimeValue(notice.endAt));
  setValue('noticeContent', notice.content);
  checked('noticeShowOnce', notice.showOnce !== false);
  const update = currentState.appUpdate || {};
  checked('appUpdateEnabled', Boolean(currentState.appUpdate));
  setValue('appVersion', update.version);
  setValue('appVersionCode', update.versionCode);
  setValue('appDownloadUrl', update.downloadUrl);
  setValue('appChangelog', update.changelog);
  checked('appForce', update.is_force === 1 || update.isForce === 1);
  loadRegistrationCodes();
}
function noticeDateTimeValue(data) {
  const text = String(data == null ? '' : data).trim();
  const match = text.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return match ? match[1] : '';
}
function formState() {
  const subscriptions = Array.from(document.querySelectorAll('.source-row')).map(function(row, index) {
    const sourceType = row.querySelector('[data-field="sourceType"]').value;
    const url = row.querySelector('[data-field="url"]').value.trim();
    const content = row.querySelector('[data-field="content"]').value.trim();
    return {
      name: row.querySelector('[data-field="name"]').value.trim() || '线路' + (index + 1),
      url: sourceType === 'url' ? url : '',
      enabled: row.querySelector('[data-field="enabled"]').checked,
      minAccountType: row.querySelector('[data-field="minAccountType"]').value,
      minAppVersion: row.querySelector('[data-field="minAppVersion"]').value.trim(),
      cacheMinutes: sourceType === 'url'
        ? Number(row.querySelector('[data-field="cacheMinutes"]').value || 0)
        : 0,
      autoRename: sourceType === 'url' && row.querySelector('[data-field="autoRename"]').checked,
      content: sourceType === 'content' ? content : '',
      refreshOnAppOpen: row.querySelector('[data-field="refreshOnAppOpen"]').checked,
      remark: row.querySelector('[data-field="remark"]').value.trim()
    };
  }).filter(function(item) { return item.url || item.content; });
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
    subscriptions: subscriptions,
    settings: {
      websiteUrl: value('websiteUrl'),
      feedbackEmail: value('feedbackEmail'),
       feedbackUrl: value('feedbackUrl'),
       githubUrl: value('githubUrl'),
       telegramUrl: value('telegramUrl'),
       nodeRequestTimeoutMs: Number(value('nodeRequestTimeoutMs') || 25000),
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

  const form = formState();
  const settings = section === 'app-settings'
    ? Object.assign({}, form.settings, {
      registrationEnabled: currentState.settings.registrationEnabled,
      requireAdminRegistrationCode: currentState.settings.requireAdminRegistrationCode
    })
    : section === 'registration-control'
      ? Object.assign({}, currentState.settings, {
        registrationEnabled: form.settings.registrationEnabled,
        requireAdminRegistrationCode: form.settings.requireAdminRegistrationCode
      })
      : currentState.settings;
  const state = {
    subscriptions: section === 'subscriptions' ? form.subscriptions : currentState.subscriptions,
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
  if (!response.ok) return show(adminErrorMessage(data.error, '推送失败'), true);
  currentState = data.state;
  fillState(data.state);
  show('当前模块已推送');
}
initializeGrowthRange();
initializeActivityRange();
initializeTrafficRange();
addSource();
