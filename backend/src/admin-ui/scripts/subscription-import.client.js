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
  ['subscriptionImportRecognize', 'subscriptionImportClipboard', 'subscriptionImportFileButton', 'subscriptionImportFetchMode'].forEach(function(id) {
    el(id).disabled = busy;
  });
  el('subscriptionImportRecognize').textContent = busy ? '正在识别…' : '识别并预览';
}

function updateSubscriptionImportFetchHint() {
  const local = el('subscriptionImportFetchMode').value === 'local';
  el('subscriptionImportFetchHint').textContent = local
    ? '本地获取由当前浏览器访问订阅地址，正文会发送到后台进行格式转换；可能受跨域、HTTPS 混合内容或网络策略限制，失败时可切换服务器获取。'
    : '服务器获取由 Worker 访问订阅地址，适用于不允许浏览器跨域访问的来源。';
}

async function requestSubscriptionImport(payload, signal) {
  const response = await fetch(adminApiUrl('/subscriptions/import'), {
    method: 'POST',
    headers: headers(),
    signal: signal,
    body: JSON.stringify(payload)
  });
  const result = await response.json();
  if (!response.ok || !result.ok) {
    const failures = Array.isArray(result.failedSources) ? result.failedSources : [];
    throw new Error(result.error || (failures[0] && failures[0].error) || 'subscription_import_no_nodes');
  }
  return result;
}

async function readLocalSubscriptionContent(response) {
  const declaredLength = Number(response.headers.get('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > SUBSCRIPTION_IMPORT_MAX_BYTES) {
    throw new Error('managed_subscription_too_large');
  }
  if (!response.body) {
    const content = await response.text();
    if (new TextEncoder().encode(content).length > SUBSCRIPTION_IMPORT_MAX_BYTES) {
      throw new Error('managed_subscription_too_large');
    }
    return content;
  }

  const reader = response.body.getReader();
  const chunks = [];
  let receivedBytes = 0;
  while (true) {
    const part = await reader.read();
    if (part.done) break;
    receivedBytes += part.value.byteLength;
    if (receivedBytes > SUBSCRIPTION_IMPORT_MAX_BYTES) {
      await reader.cancel();
      throw new Error('managed_subscription_too_large');
    }
    chunks.push(part.value);
  }
  const bytes = new Uint8Array(receivedBytes);
  let offset = 0;
  chunks.forEach(function(chunk) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  });
  return new TextDecoder().decode(bytes);
}

function localSubscriptionFetchError(error) {
  if (error && error.name === 'AbortError') throw error;
  if (error && (error.message === 'managed_subscription_too_large' ||
      /^local_subscription_http_\d{3}$/.test(error.message) ||
      error.message === 'subscription_must_be_plaintext_nodes')) return error.message;
  return 'local_subscription_cors_or_network_error';
}

async function importSubscriptionsLocally(input, signal, generation) {
  const plan = await requestSubscriptionImport({mode: 'prepare-local', input: input}, signal);
  const sources = Array.isArray(plan.sources) ? plan.sources.slice() : [];
  const failedSources = [];
  const urls = Array.isArray(plan.urls) ? plan.urls : [];

  for (let start = 0; start < urls.length; start += 3) {
    if (generation !== subscriptionImportGeneration) return null;
    const batch = urls.slice(start, start + 3);
    el('subscriptionImportStatus').textContent = '本地获取中（' + Math.min(start + batch.length, urls.length) + '/' + urls.length + '）…';
    const results = await Promise.all(batch.map(async function(url) {
      try {
        const response = await fetch(url, {
          method: 'GET',
          headers: {accept: 'text/plain, application/yaml, application/json, */*;q=0.1'},
          credentials: 'omit',
          redirect: 'follow',
          signal: signal
        });
        if (!response.ok) throw new Error('local_subscription_http_' + response.status);
        const content = await readLocalSubscriptionContent(response);
        const parsed = await requestSubscriptionImport({
          mode: 'parse-local-response',
          url: url,
          content: content
        }, signal);
        return {source: parsed.source};
      } catch (error) {
        return {failure: {url: url, error: localSubscriptionFetchError(error)}};
      }
    }));
    results.forEach(function(result) {
      if (result.source) sources.push(result.source);
      if (result.failure) failedSources.push(result.failure);
    });
  }

  return {
    ok: sources.length > 0,
    sources: sources,
    failedSources: failedSources,
    nodeCount: sources.reduce(function(total, source) { return total + (Number(source.nodeCount) || 0); }, 0)
  };
}

function openSubscriptionImport(groupRow) {
  resetSubscriptionImport();
  subscriptionImportGroup = groupRow;
  el('subscriptionImportInput').value = '';
  el('subscriptionImportFile').value = '';
  const groupName = groupRow.querySelector('[data-field="groupName"]').value.trim() || '未命名分组';
  el('subscriptionImportTier').textContent = '添加到分组：' + groupName;
  updateSubscriptionImportFetchHint();
  el('subscriptionImportDialog').showModal();
  el('subscriptionImportInput').focus();
}

el('subscriptionImportInput').addEventListener('input', resetSubscriptionImport);
el('subscriptionImportDialog').addEventListener('close', resetSubscriptionImport);
el('subscriptionImportFetchMode').addEventListener('change', function() {
  resetSubscriptionImport();
  updateSubscriptionImportFetchHint();
});
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
  const fetchMode = el('subscriptionImportFetchMode').value;
  const signal = subscriptionImportRequest.signal;
  el('subscriptionImportStatus').textContent = fetchMode === 'local'
    ? '正在通过当前浏览器获取远程订阅…'
    : '正在通过服务器获取远程订阅…';
  try {
    const result = fetchMode === 'local'
      ? await importSubscriptionsLocally(input, signal, generation)
      : await requestSubscriptionImport({input: input}, signal);
    if (generation !== subscriptionImportGeneration || !el('subscriptionImportDialog').open) return;
    if (!result) return;
    const sources = Array.isArray(result.sources) ? result.sources : [];
    const failures = Array.isArray(result.failedSources) ? result.failedSources : [];
    el('subscriptionImportPreview').replaceChildren();
    subscriptionImportPreview = sources.length ? result : null;
    sources.forEach(function(source) {
      const item = document.createElement('div');
      item.className = 'subscription-import-item';
      const title = document.createElement('strong');
      title.textContent = source.name;
      const detail = document.createElement('small');
      detail.textContent = (source.url ? '远程订阅' : source.nodeCount ? '本地节点' : '空白线路') + ' · ' + source.nodeCount + ' 个节点';
      item.append(title, detail);
      el('subscriptionImportPreview').appendChild(item);
    });
    let message = (fetchMode === 'local' ? '本地获取' : '服务器获取') +
      '完成：识别到 ' + sources.length + ' 条线路，共 ' + (Number(result.nodeCount) || 0) + ' 个节点。';
    if (failures.length) {
      message += '\n' + failures.length + ' 个远程来源失败' + (sources.length ? '，成功的线路仍可添加。' : '。');
      failures.forEach(function(failure, index) {
        let host = failure.url;
        try { host = new URL(failure.url).hostname; } catch (_) {}
        message += '\n失败来源 ' + (index + 1) + '（' + host + '）：' + adminErrorMessage(failure.error, '获取失败');
      });
    }
    if (!input.trim()) message += '\n空白线路需填写订阅地址或节点后再保存。';
    el('subscriptionImportStatus').textContent = message;
    el('subscriptionImportAdd').disabled = sources.length === 0;
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
  if (!subscriptionImportPreview || !subscriptionImportPreview.sources.length) return;
  const result = subscriptionImportPreview;
  if (!subscriptionImportGroup || !subscriptionImportGroup.isConnected) return;
  const rows = result.sources.map(function(source) { return addSource(subscriptionImportGroup, source); });
  el('subscriptionImportDialog').close();
  if (rows.length === 1) toggleSourceRow(rows[0]);
  rows[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  show('已加入草稿：' + rows.length + ' 条线路、' + result.nodeCount + ' 个节点，检查后点击「保存配置」生效。');
};

updateSubscriptionImportFetchHint();
