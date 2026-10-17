let notificationAdminItems = [];
let notificationAdminLoading = false;
let notificationAdminLoadVersion = 0;

const notificationStatusLabels = {
  draft: '草稿',
  published: '已发布',
  archived: '已撤回'
};

function notificationFormatDate(value) {
  if (!value) return '不限';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间无效' : date.toLocaleString('zh-CN');
}

function notificationDateTimeInput(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function notificationDateTimePayload(id) {
  const value = el(id).value;
  if (!value) return null;
  const timestamp = new Date(value).getTime();
  if (!Number.isFinite(timestamp)) throw new Error('通知时间格式无效');
  return new Date(timestamp).toISOString();
}

function notificationStatusMessage(message, isError) {
  const status = el('notificationAdminStatus');
  if (!status) return;
  status.textContent = message || '';
  status.classList.toggle('error', Boolean(isError));
}

function notificationErrorText(error) {
  const messages = {
    invalid_notification_title: '标题不能为空且不能超过 100 个字符。',
    invalid_notification_body: '正文不能为空且不能超过 10000 个字符。',
    invalid_notification_level: '通知重要程度无效。',
    invalid_notification_status: '通知状态无效。',
    invalid_notification_start_time: '开始显示时间格式无效。',
    invalid_notification_expiry_time: '失效时间格式无效。',
    invalid_notification_time_range: '失效时间必须晚于开始显示时间。'
  };
  return messages[error] || error || '通知操作失败，请重试。';
}

function renderAdminNotifications() {
  const host = el('notificationAdminList');
  if (!host) return;
  if (!notificationAdminItems.length) {
    host.innerHTML = '<div class="user-table-empty">还没有通知，点击“新建通知”开始发布。</div>';
    return;
  }
  host.innerHTML = notificationAdminItems.map(function(item) {
    const body = item.body.length > 260 ? item.body.slice(0, 260) + '…' : item.body;
    const stateLabel = notificationStatusLabels[item.status] || '未知状态';
    const levelLabel = item.level === 'important' ? '重要' : '普通';
    const actionLabel = item.status === 'published'
      ? '撤回'
      : item.status === 'archived' ? '恢复发布' : '发布';
    const nextStatus = item.status === 'published' ? 'archived' : 'published';
    return '<article class="notification-admin-card">' +
      '<div><div class="notification-admin-meta">' +
      '<span class="notification-chip ' + (item.level === 'important' ? 'important' : '') + '">' + esc(levelLabel) + '</span>' +
      '<span class="notification-chip">' + esc(stateLabel) + '</span>' +
      '<span>创建于 ' + esc(notificationFormatDate(item.createdAt)) + '</span>' +
      (item.startsAt ? '<span>开始 ' + esc(notificationFormatDate(item.startsAt)) + '</span>' : '') +
      (item.expiresAt ? '<span>截止 ' + esc(notificationFormatDate(item.expiresAt)) + '</span>' : '') +
      '</div><h3>' + esc(item.title) + '</h3><p>' + esc(body) + '</p></div>' +
      '<div class="notification-admin-actions">' +
      '<button class="secondary" type="button" data-notification-edit="' + item.id + '">编辑</button>' +
      '<button type="button" data-notification-status="' + item.id + '" data-next-status="' + nextStatus + '">' + actionLabel + '</button>' +
      '</div></article>';
  }).join('');
}

async function loadAdminNotifications() {
  if (notificationAdminLoading) return;
  notificationAdminLoading = true;
  const version = ++notificationAdminLoadVersion;
  notificationStatusMessage('正在加载通知…', false);
  try {
    const response = await fetch(adminApiUrl('/notifications'), {
      headers: headers(),
      cache: 'no-store'
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.error || '通知读取失败');
    if (version !== notificationAdminLoadVersion) return;
    notificationAdminItems = Array.isArray(data.items) ? data.items : [];
    renderAdminNotifications();
    notificationStatusMessage('共 ' + notificationAdminItems.length + ' 条通知', false);
  } catch (error) {
    if (version !== notificationAdminLoadVersion) return;
    const host = el('notificationAdminList');
    if (host) host.innerHTML = '<div class="error-state">' + esc(error.message || '通知读取失败') + '</div>';
    notificationStatusMessage('通知加载失败，请检查网络后重试。', true);
  } finally {
    if (version === notificationAdminLoadVersion) notificationAdminLoading = false;
  }
}

function openAdminNotificationEditor(notificationId) {
  const item = notificationAdminItems.find(function(candidate) {
    return candidate.id === Number(notificationId);
  });
  el('notificationId').value = item ? String(item.id) : '';
  el('notificationTitle').value = item ? item.title : '';
  el('notificationBody').value = item ? item.body : '';
  el('notificationLevel').value = item ? item.level : 'info';
  el('notificationStatus').value = item ? item.status : 'draft';
  el('notificationStartsAt').value = notificationDateTimeInput(item && item.startsAt);
  el('notificationExpiresAt').value = notificationDateTimeInput(item && item.expiresAt);
  el('notificationEditorTitle').textContent = item ? '编辑通知' : '新建通知';
  el('notificationEditor').hidden = false;
  el('notificationTitle').focus();
}

function closeAdminNotificationEditor() {
  el('notificationEditor').hidden = true;
}

async function saveAdminNotification() {
  const title = el('notificationTitle').value.trim();
  const body = el('notificationBody').value.trim();
  if (!title || title.length > 100) return notificationStatusMessage('标题不能为空且不能超过 100 个字符。', true);
  if (!body || body.length > 10000) return notificationStatusMessage('正文不能为空且不能超过 10000 个字符。', true);

  let startsAt;
  let expiresAt;
  try {
    startsAt = notificationDateTimePayload('notificationStartsAt');
    expiresAt = notificationDateTimePayload('notificationExpiresAt');
  } catch (error) {
    return notificationStatusMessage(error.message || '通知时间格式无效。', true);
  }
  if (startsAt && expiresAt && new Date(expiresAt) <= new Date(startsAt)) {
    return notificationStatusMessage('失效时间必须晚于开始显示时间。', true);
  }

  const id = el('notificationId').value;
  const payload = {
    title: title,
    body: body,
    level: el('notificationLevel').value,
    status: el('notificationStatus').value,
    startsAt: startsAt,
    expiresAt: expiresAt
  };
  const saveButton = el('notificationSave');
  saveButton.disabled = true;
  notificationStatusMessage('正在保存通知…', false);
  try {
    const response = await fetch(adminApiUrl('/notifications' + (id ? '/' + encodeURIComponent(id) : '')), {
      method: id ? 'PUT' : 'POST',
      headers: headers(),
      body: JSON.stringify(payload)
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.error || '通知保存失败');
    closeAdminNotificationEditor();
    notificationStatusMessage('通知已保存。', false);
    await loadAdminNotifications();
  } catch (error) {
    notificationStatusMessage(notificationErrorText(error.message), true);
  } finally {
    saveButton.disabled = false;
  }
}

async function changeAdminNotificationStatus(notificationId, status) {
  const item = notificationAdminItems.find(function(candidate) {
    return candidate.id === Number(notificationId);
  });
  if (!item) return;
  if (status === 'archived' && !confirm('撤回这条通知？用户之后将无法查看。')) return;
  const payload = {
    title: item.title,
    body: item.body,
    level: item.level,
    status: status,
    startsAt: item.startsAt,
    expiresAt: item.expiresAt
  };
  notificationStatusMessage('正在更新通知状态…', false);
  try {
    const response = await fetch(adminApiUrl('/notifications/' + encodeURIComponent(item.id)), {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify(payload)
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.error || '通知状态更新失败');
    await loadAdminNotifications();
  } catch (error) {
    notificationStatusMessage(notificationErrorText(error.message), true);
  }
}

function resetAdminNotifications() {
  notificationAdminLoadVersion++;
  notificationAdminLoading = false;
  notificationAdminItems = [];
  renderAdminNotifications();
}

el('notificationCreate').addEventListener('click', function() {
  openAdminNotificationEditor(null);
});
el('notificationSave').addEventListener('click', saveAdminNotification);
el('notificationAdminList').addEventListener('click', function(event) {
  const editButton = event.target.closest('[data-notification-edit]');
  if (editButton) return openAdminNotificationEditor(editButton.dataset.notificationEdit);
  const statusButton = event.target.closest('[data-notification-status]');
  if (statusButton) changeAdminNotificationStatus(
    statusButton.dataset.notificationStatus,
    statusButton.dataset.nextStatus
  );
});
document.querySelectorAll('[data-notification-close]').forEach(function(button) {
  button.addEventListener('click', closeAdminNotificationEditor);
});
document.addEventListener('keydown', function(event) {
  if (event.key === 'Escape' && !el('notificationEditor').hidden) closeAdminNotificationEditor();
});
const notificationAdminTab = document.querySelector('[data-tab="notifications"]');
if (notificationAdminTab) notificationAdminTab.addEventListener('click', loadAdminNotifications);
