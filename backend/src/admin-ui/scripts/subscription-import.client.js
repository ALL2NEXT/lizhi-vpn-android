let subscriptionImportGroup = null;
let subscriptionImportPreview = null;
let subscriptionImportRequest = null;
let subscriptionImportGeneration = 0;
const SUBSCRIPTION_IMPORT_MAX_BYTES = 2 * 1024 * 1024;

function resetSubscriptionImport() {
  subscriptionImportGeneration++;
  if (subscriptionImportRequest) subscriptionImportRequest.abort();
  subscriptionImportRequest = null;
  subscriptionImportPreview = null;
  el('subscriptionImportPreview').replaceChildren();
  el('subscriptionImportStatus').textContent = '';
  el('subscriptionImportAdd').disabled = true;
  setSubscriptionImportBusy(false);
}

function setSubscriptionImportBusy(busy) {
  ['subscriptionImportRecognize', 'subscriptionImportClipboard', 'subscriptionImportFileButton'].forEach(function(id) {
    el(id).disabled = busy;
  });
  el('subscriptionImportRecognize').textContent = busy ? '正在识别…' : '识别并预览';
}

function openSubscriptionImport(groupRow) {
  resetSubscriptionImport();
  subscriptionImportGroup = groupRow;
  el('subscriptionImportInput').value = '';
  el('subscriptionImportFile').value = '';
  const groupName = groupRow.querySelector('[data-field="groupName"]').value.trim() || '未命名分组';
  el('subscriptionImportTier').textContent = '添加到分组：' + groupName;
  el('subscriptionImportDialog').showModal();
  el('subscriptionImportInput').focus();
}

el('subscriptionImportInput').addEventListener('input', resetSubscriptionImport);
el('subscriptionImportDialog').addEventListener('close', resetSubscriptionImport);
el('subscriptionImportCancel').onclick = function() { el('subscriptionImportDialog').close(); };
el('subscriptionImportClipboard').onclick = async function() {
  const generation = subscriptionImportGeneration;
  try {
    const content = await navigator.clipboard.readText();
    if (generation !== subscriptionImportGeneration || !el('subscriptionImportDialog').open) return;
    resetSubscriptionImport();
    el('subscriptionImportInput').value = content;
    el('subscriptionImportInput').focus();
  } catch (_) { el('subscriptionImportStatus').textContent = '无法读取剪贴板，请直接粘贴到输入框。'; }
};
el('subscriptionImportFileButton').onclick = function() { el('subscriptionImportFile').click(); };
el('subscriptionImportFile').onchange = async function() {
  const file = this.files && this.files[0];
  if (!file) return;
  resetSubscriptionImport();
  const generation = subscriptionImportGeneration;
  try {
    if (file.size > SUBSCRIPTION_IMPORT_MAX_BYTES) throw new Error('managed_subscription_too_large');
    const content = await file.text();
    if (generation !== subscriptionImportGeneration || !el('subscriptionImportDialog').open) return;
    el('subscriptionImportInput').value = content;
    el('subscriptionImportInput').focus();
  } catch (error) { el('subscriptionImportStatus').textContent = adminErrorMessage(error.message, '文件读取失败'); }
  finally { this.value = ''; }
};

el('subscriptionImportForm').onsubmit = async function(event) {
  event.preventDefault();
  resetSubscriptionImport();
  const input = el('subscriptionImportInput').value;
  if (new TextEncoder().encode(input).length > SUBSCRIPTION_IMPORT_MAX_BYTES) {
    el('subscriptionImportStatus').textContent = adminErrorMessage('managed_subscription_too_large');
    return;
  }
  const generation = subscriptionImportGeneration;
  subscriptionImportRequest = new AbortController();
  setSubscriptionImportBusy(true);
  el('subscriptionImportStatus').textContent = '正在识别并获取远程订阅…';
  try {
    const response = await fetch(adminApiUrl('/subscriptions/import'), {
      method: 'POST', headers: headers(), signal: subscriptionImportRequest.signal,
      body: JSON.stringify({ input: input })
    });
    const result = await response.json();
    if (generation !== subscriptionImportGeneration || !el('subscriptionImportDialog').open) return;
    if (!response.ok || !result.ok) {
      const failures = Array.isArray(result.failedSources) ? result.failedSources : [];
      throw new Error(result.error || (failures[0] && failures[0].error) || 'subscription_import_no_nodes');
    }
    subscriptionImportPreview = result;
    result.sources.forEach(function(source) {
      const item = document.createElement('div');
      item.className = 'subscription-import-item';
      const title = document.createElement('strong');
      title.textContent = source.name;
      const detail = document.createElement('small');
      detail.textContent = (source.url ? '远程订阅' : source.nodeCount ? '本地节点' : '空白线路') + ' · ' + source.nodeCount + ' 个节点';
      item.append(title, detail);
      el('subscriptionImportPreview').appendChild(item);
    });
    const failures = result.failedSources || [];
    let message = '识别到 ' + result.sources.length + ' 条线路，共 ' + result.nodeCount + ' 个节点。';
    if (failures.length) {
      message += '\n' + failures.length + ' 个远程来源失败，成功的线路仍可添加。';
      failures.forEach(function(failure, index) {
        message += '\n失败来源 ' + (index + 1) + '（' + new URL(failure.url).hostname + '）：' + adminErrorMessage(failure.error, '获取失败');
      });
    }
    if (!input.trim()) message += '\n空白线路需填写订阅地址或节点后再推送。';
    el('subscriptionImportStatus').textContent = message;
    el('subscriptionImportAdd').disabled = false;
  } catch (error) {
    if (generation !== subscriptionImportGeneration || error.name === 'AbortError') return;
    el('subscriptionImportStatus').textContent = adminErrorMessage(error.message, '导入失败');
  } finally {
    if (generation === subscriptionImportGeneration) {
      subscriptionImportRequest = null;
      setSubscriptionImportBusy(false);
    }
  }
};

el('subscriptionImportAdd').onclick = function() {
  if (!subscriptionImportPreview) return;
  const result = subscriptionImportPreview;
  if (!subscriptionImportGroup || !subscriptionImportGroup.isConnected) return;
  const rows = result.sources.map(function(source) { return addSource(subscriptionImportGroup, source); });
  el('subscriptionImportDialog').close();
  if (rows.length === 1) toggleSourceRow(rows[0]);
  rows[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  show('已添加 ' + rows.length + ' 条线路、' + result.nodeCount + ' 个节点，请检查后点击「推送」保存。');
};
