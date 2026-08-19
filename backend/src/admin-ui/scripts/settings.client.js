function addSource(source) {
  source = source || {};
  const row = document.createElement('div');
  row.className = 'source-row';
  const sourceType = source.content ? 'content' : 'url';
  row.innerHTML = '<div class="source-row-header">' +
    '<div class="source-row-heading"><span class="source-row-title" data-source-title>线路</span></div>' +
    '<div class="source-row-controls" role="group" aria-label="线路排序和折叠操作">' +
      '<span class="source-drag-handle" data-drag-handle draggable="true" role="button" aria-label="拖动线路排序" title="拖动线路排序">☷</span>' +
      '<button class="secondary" type="button" data-action="collapse" aria-expanded="true">折叠</button>' +
    '</div>' +
  '</div>' +
  '<div class="source-row-body" data-source-body>' +
  '<section class="source-section source-identity-section">' +
    '<div class="grid source-identity-grid">' +
      '<div class="field"><label>线路名称<input data-field="name" value="' + esc(source.name) + '"></label></div>' +
      '<div class="field"><label>后台备注（仅后台保存）<input data-field="remark" type="text" value="' + esc(source.remark) + '" placeholder="例如：备用线路、长期稳定"></label></div>' +
    '</div>' +
    '</section>' +
    '<section class="source-section source-permissions-section">' +
      '<h3 class="source-section-title">线路权限控制</h3>' +
      '<div class="grid source-permissions-grid">' +
        '<div class="field"><label>最低账户等级<select data-field="minLevel">' + levelOptions(source.minLevel == null ? 1 : source.minLevel) + '</select></label></div>' +
        '<div class="field"><label>最低应用版本<input data-field="minAppVersion" type="text" value="' + esc(source.minAppVersion) + '" placeholder="例如 2.40（留空不限制）"></label></div>' +
        '<label class="check"><input data-field="enabled" type="checkbox" ' + (source.enabled === false ? '' : 'checked') + '>启用这条线路</label>' +
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
      '</div><small>接口必须返回可识别的明文节点，由 Worker 中转并加密；缓存时间设为 0 会每次请求上游。</small></div>' +
      '<div class="source-mode" data-source-mode="content"><div class="field"><label>纯文本节点（每行一个）<textarea data-field="content" placeholder="vless://...&#10;hy2://...&#10;ss://...">' + esc(source.content) + '</textarea></label></div><small>保存后由 Worker 直接加密下发，不经过外部接口缓存。</small></div>' +
    '</section>' +
    '<div class="row-actions"><button class="danger" type="button">删除</button></div>' +
  '</div>';
  row.querySelector('.danger').onclick = function() {
    row.remove();
    updateSourceRowsMeta();
  };
  row.querySelector('[data-field="sourceType"]').onchange = function() { updateSourceMode(row); };
  row.querySelector('[data-field="name"]').oninput = function() { updateSourceRowsMeta(); };
  row.querySelector('[data-action="collapse"]').onclick = function() {
    toggleSourceRow(row);
  };
  const dragHandle = row.querySelector('[data-drag-handle]');
  dragHandle.ondragstart = function(event) {
    draggedSourceRow = row;
    row.classList.add('dragging');
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', 'subscription-row');
    }
  };
  dragHandle.ondragend = function() {
    clearSourceDragState();
  };
  row.ondragover = function(event) {
    if (!draggedSourceRow || draggedSourceRow === row) return;
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
    row.classList.add('drag-over');
  };
  row.ondragleave = function(event) {
    if (!row.contains(event.relatedTarget)) row.classList.remove('drag-over');
  };
  row.ondrop = function(event) {
    if (!draggedSourceRow || draggedSourceRow === row) return;
    event.preventDefault();
    const draggedRow = draggedSourceRow;
    const container = el('subscriptionRows');
    const rect = row.getBoundingClientRect();
    const insertAfter = event.clientY > rect.top + rect.height / 2;
    if (insertAfter) {
      const nextRow = row.nextElementSibling;
      if (nextRow === draggedRow) {
        // The dragged row is already immediately after the target row.
      } else if (nextRow) container.insertBefore(draggedRow, nextRow);
      else container.appendChild(draggedRow);
    } else if (row.previousElementSibling !== draggedRow) {
      container.insertBefore(draggedRow, row);
    }
    clearSourceDragState();
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
    const title = row.querySelector('[data-source-title]');
    const name = nameInput ? nameInput.value.trim() : '';
    if (title) title.textContent = name || '线路' + (index + 1);
  });
}
function toggleSourceRow(row) {
  const body = row.querySelector('[data-source-body]');
  const button = row.querySelector('[data-action="collapse"]');
  if (!body || !button) return;
  const collapsed = !body.hidden;
  body.hidden = collapsed;
  row.classList.toggle('collapsed', collapsed);
  button.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
  button.textContent = collapsed ? '展开' : '折叠';
}
function clearSourceDragState() {
  getSourceRows().forEach(function(row) {
    row.classList.remove('dragging', 'drag-over');
  });
  draggedSourceRow = null;
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
   setValue('nodeRequestTimeoutMs', settings.nodeRequestTimeoutMs || 25000);
   checked('formalRegistrationEnabled', settings.formalRegistrationEnabled !== false);
   setValue('accountBindingExperience', settings.accountBindingExperience == null ? 100 : settings.accountBindingExperience);
   setValue('invitationExperience', settings.invitationExperience == null ? 50 : settings.invitationExperience);
   setValue('telegramGroupUrl', settings.telegramGroupUrl);
   setValue('telegramJoinCode', settings.telegramJoinCode);
   setValue('telegramTaskExperience', settings.telegramTaskExperience == null ? 50 : settings.telegramTaskExperience);
   setValue('speedShareSmartExperience', settings.speedShareSmartExperience == null ? 5 : settings.speedShareSmartExperience);
  const aiAnalysis = settings.aiAnalysis || {};
  setValue('aiAnalysisModel', aiAnalysis.model || 'gemini-flash-latest');
  setValue('aiAnalysisThinkingLevel', aiAnalysis.thinkingLevel || 'low');
  setValue('aiAnalysisMaxOutputTokens', aiAnalysis.maxOutputTokens || 4096);
  setValue('aiAnalysisStyle', aiAnalysis.analysisStyle || 'standard');
  setValue('aiAnalysisInstruction', aiAnalysis.customInstruction || '');
  const experienceThresholds = Array.isArray(settings.experienceThresholds)
    ? settings.experienceThresholds
    : [100, 200, 400, 800, 1600];
  ['experienceThresholdLv2', 'experienceThresholdLv3', 'experienceThresholdLv4',
    'experienceThresholdLv5', 'experienceThresholdLv6'].forEach(function(id, index) {
    setValue(id, experienceThresholds[index]);
  });
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
      minLevel: Number(row.querySelector('[data-field="minLevel"]').value || 1),
      minAppVersion: row.querySelector('[data-field="minAppVersion"]').value.trim(),
      cacheMinutes: sourceType === 'url'
        ? Number(row.querySelector('[data-field="cacheMinutes"]').value || 0)
        : 0,
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
       nodeRequestTimeoutMs: Number(value('nodeRequestTimeoutMs') || 25000),
       formalRegistrationEnabled: el('formalRegistrationEnabled').checked,
       accountBindingExperience: Number(value('accountBindingExperience') || 0),
       invitationExperience: Number(value('invitationExperience') || 0),
       telegramGroupUrl: value('telegramGroupUrl'),
       telegramJoinCode: value('telegramJoinCode'),
       telegramTaskExperience: Number(value('telegramTaskExperience') || 0),
       speedShareSmartExperience: Number(value('speedShareSmartExperience') || 0),
       aiAnalysis: {
        model: value('aiAnalysisModel') || 'gemini-flash-latest',
        thinkingLevel: value('aiAnalysisThinkingLevel') || 'low',
        maxOutputTokens: Number(value('aiAnalysisMaxOutputTokens') || 4096),
        analysisStyle: value('aiAnalysisStyle') || 'standard',
        customInstruction: value('aiAnalysisInstruction').slice(0, 1000)
       },
       experienceThresholds: [
        Number(value('experienceThresholdLv2') || 100),
        Number(value('experienceThresholdLv3') || 200),
        Number(value('experienceThresholdLv4') || 400),
        Number(value('experienceThresholdLv5') || 800),
        Number(value('experienceThresholdLv6') || 1600)
      ]
    },
    notice: notice,
    appUpdate: appUpdate
  };
}
async function pushSection(section) {
  if (section === 'users') {
    if (await loadUsers()) show('用户表单已刷新');
    return;
  }

  const form = formState();
  const state = {
    subscriptions: section === 'subscriptions' ? form.subscriptions : currentState.subscriptions,
    settings: section === 'app-settings' || section === 'ai-config'
      ? form.settings
      : currentState.settings,
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
