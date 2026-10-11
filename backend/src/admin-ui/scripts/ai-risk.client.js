let aiRiskSettingsLoading = false;
let aiRiskSettingsLoaded = false;
let aiRiskOperationInFlight = false;

function aiRiskSetStatus(id, message, isError) {
  const node = el(id);
  node.textContent = message || '';
  node.classList.toggle('error', Boolean(isError));
}

function aiRiskErrorText(code) {
  const messages = {
    ai_risk_api_key_required: '请先填写并保存 Google AI API Key。',
    ai_risk_disabled: '请先启用 Gemini 风控分析。',
    ai_risk_config_binding_missing: '当前 Worker 未绑定 CONFIG KV。',
    invalid_ai_risk_model: '模型名称格式无效。',
    invalid_ai_risk_window: '分析时间窗无效。',
    gemini_auth_failed: 'Gemini 认证失败，请检查 API Key 和模型权限。',
    gemini_rate_limited: 'Gemini 请求频率受限，请稍后再试。',
    gemini_unavailable: '无法连接 Gemini，请检查网络后重试。',
    gemini_request_failed: 'Gemini 请求失败，请检查模型名称和 API 配额。',
    gemini_empty_response: 'Gemini 没有返回可用内容。',
    gemini_invalid_json: 'Gemini 返回内容格式无效，请重试。',
  };
  return messages[code] || code || '操作失败，请稍后重试。';
}

async function aiRiskRequest(path, options) {
  const response = await fetch(ADMIN_API_BASE + path, Object.assign({headers: headers()}, options || {}));
  const data = parseResponseJson(await response.text());
  if (!response.ok || data.ok === false) throw new Error(data.error || 'ai_risk_request_failed');
  return data;
}

async function loadAiRiskSettings(force) {
  if (aiRiskSettingsLoading || (aiRiskSettingsLoaded && !force)) return;
  aiRiskSettingsLoading = true;
  aiRiskSetStatus('aiRiskSettingsStatus', '正在读取配置…', false);
  try {
    const data = await aiRiskRequest('/ai-risk/settings');
    const settings = data.settings || {};
    el('aiRiskEnabled').checked = settings.enabled === true;
    el('aiRiskModel').value = settings.model || 'gemini-3.8-flash';
    el('aiRiskApiKey').value = '';
    el('aiRiskApiKey').placeholder = settings.apiKeyConfigured ? '已设置；留空表示保留当前密钥' : '请输入 Google AI API Key';
    el('aiRiskClearKey').checked = false;
    el('aiRiskClearKey').disabled = settings.apiKeyConfigured !== true;
    aiRiskSetStatus('aiRiskSettingsStatus', settings.apiKeyConfigured ? 'API Key 已配置（密钥不会回显）' : '尚未配置 API Key', false);
    aiRiskSettingsLoaded = true;
  } catch (error) {
    aiRiskSetStatus('aiRiskSettingsStatus', aiRiskErrorText(error.message), true);
  } finally {
    aiRiskSettingsLoading = false;
  }
}

async function saveAiRiskSettings() {
  if (aiRiskOperationInFlight) return false;
  aiRiskOperationInFlight = true;
  el('aiRiskSave').disabled = true;
  aiRiskSetStatus('aiRiskSettingsStatus', '正在保存配置…', false);
  try {
    const body = {
      enabled: el('aiRiskEnabled').checked,
      model: value('aiRiskModel'),
      apiKey: el('aiRiskApiKey').value,
      clearApiKey: el('aiRiskClearKey').checked,
    };
    const data = await aiRiskRequest('/ai-risk/settings', {method: 'PUT', body: JSON.stringify(body)});
    el('aiRiskApiKey').value = '';
    el('aiRiskClearKey').checked = false;
    const settings = data.settings || {};
    el('aiRiskApiKey').placeholder = settings.apiKeyConfigured ? '已设置；留空表示保留当前密钥' : '请输入 Google AI API Key';
    el('aiRiskClearKey').disabled = settings.apiKeyConfigured !== true;
    aiRiskSettingsLoaded = true;
    aiRiskSetStatus('aiRiskSettingsStatus', '配置已保存；API Key ' + (settings.apiKeyConfigured ? '已配置' : '未配置'), false);
    return true;
  } catch (error) {
    aiRiskSetStatus('aiRiskSettingsStatus', aiRiskErrorText(error.message), true);
    return false;
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskSave').disabled = false;
  }
}

function renderAiRiskFindings(result) {
  const host = el('aiRiskResults');
  const findings = Array.isArray(result.findings) ? result.findings : [];
  const summary = '<p class="ai-risk-summary">' + esc(result.summary || 'Gemini 未提供摘要。') + '</p>';
  if (!findings.length) {
    host.innerHTML = summary + '<div class="security-device-empty">当前时间窗未发现需要单独复核的 IP。</div>';
    return;
  }
  const labels = {high: '高风险', medium: '中风险', low: '低风险'};
  const cards = findings.map(function(finding) {
    const uids = Array.isArray(finding.uids) ? finding.uids : [];
    const evidence = Array.isArray(finding.evidence) ? finding.evidence : [];
    return '<article class="ai-risk-finding">' +
      '<div class="ai-risk-finding-heading"><span class="ai-risk-badge risk-' + esc(finding.riskLevel) + '">' + esc(labels[finding.riskLevel] || '待复核') + '</span><h4>' + esc(finding.title || '风控线索') + '</h4></div>' +
      '<p><strong>IP：</strong><code>' + esc(finding.ip) + '</code></p>' +
      (uids.length ? '<p><strong>关联 UID：</strong>' + uids.map(function(uid) { return '<code>' + esc(uid) + '</code>'; }).join(' ') + '</p>' : '') +
      '<p>' + esc(finding.rationale || '') + '</p>' +
      (evidence.length ? '<ul>' + evidence.map(function(item) { return '<li>' + esc(item) + '</li>'; }).join('') + '</ul>' : '') +
      '<p><strong>建议：</strong>' + esc(finding.recommendation || '由管理员进一步核验。') + '</p>' +
      '<small>此结果是辅助判断，需人工复核；系统没有执行封禁。</small>' +
    '</article>';
  }).join('');
  host.innerHTML = summary + cards;
}

async function runAiRiskAnalysis() {
  if (aiRiskOperationInFlight) return;
  if (!aiRiskSettingsLoaded) await loadAiRiskSettings(true);
  if (!el('aiRiskEnabled').checked) {
    aiRiskSetStatus('aiRiskAnalysisStatus', '请先启用并保存 Gemini 风控分析。', true);
    return;
  }
  aiRiskOperationInFlight = true;
  el('aiRiskAnalyze').disabled = true;
  el('aiRiskPreview').disabled = true;
  aiRiskSetStatus('aiRiskAnalysisStatus', '正在汇总数据并请求 Gemini…', false);
  el('aiRiskResults').innerHTML = '<div class="loading-state">AI 正在分析请求行为，请稍候…</div>';
  try {
    const data = await aiRiskRequest('/ai-risk/analyze', {
      method: 'POST',
      body: JSON.stringify({windowHours: Number(el('aiRiskWindowHours').value)}),
    });
    renderAiRiskFindings(data);
    aiRiskSetStatus('aiRiskAnalysisStatus', '分析完成 · ' + data.model + ' · ' + data.analyzedAt, false);
  } catch (error) {
    aiRiskSetStatus('aiRiskAnalysisStatus', aiRiskErrorText(error.message), true);
    el('aiRiskResults').innerHTML = '<div class="security-device-empty">分析失败；请检查配置、日志覆盖和 Gemini 配额。</div>';
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskAnalyze').disabled = false;
    el('aiRiskPreview').disabled = false;
  }
}

async function previewAiRiskData() {
  if (aiRiskOperationInFlight) return;
  aiRiskOperationInFlight = true;
  el('aiRiskPreview').disabled = true;
  aiRiskSetStatus('aiRiskAnalysisStatus', '正在生成脱敏数据预览…', false);
  try {
    const hours = Number(el('aiRiskWindowHours').value);
    const data = await aiRiskRequest('/ai-risk/data?windowHours=' + encodeURIComponent(hours));
    const preview = el('aiRiskDataPreview');
    preview.textContent = JSON.stringify(data.dataset, null, 2);
    preview.hidden = false;
    aiRiskSetStatus('aiRiskAnalysisStatus', '数据预览已生成；IP 和 UID 使用临时引用标记。', false);
  } catch (error) {
    aiRiskSetStatus('aiRiskAnalysisStatus', aiRiskErrorText(error.message), true);
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskPreview').disabled = false;
  }
}

async function testAiRiskGemini() {
  if (aiRiskOperationInFlight) return;
  const saved = await saveAiRiskSettings();
  if (!saved) return;
  aiRiskOperationInFlight = true;
  el('aiRiskTest').disabled = true;
  aiRiskSetStatus('aiRiskSettingsStatus', '正在测试 Gemini 连接…', false);
  try {
    const data = await aiRiskRequest('/ai-risk/test', {method: 'POST', body: '{}'});
    aiRiskSetStatus('aiRiskSettingsStatus', 'Gemini 连接成功 · ' + data.model, false);
  } catch (error) {
    aiRiskSetStatus('aiRiskSettingsStatus', aiRiskErrorText(error.message), true);
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskTest').disabled = false;
  }
}

el('aiRiskSave').addEventListener('click', saveAiRiskSettings);
el('aiRiskTest').addEventListener('click', testAiRiskGemini);
el('aiRiskAnalyze').addEventListener('click', runAiRiskAnalysis);
el('aiRiskPreview').addEventListener('click', previewAiRiskData);
el('aiRiskClearKey').addEventListener('change', function() {
  if (el('aiRiskClearKey').checked) el('aiRiskApiKey').value = '';
});
