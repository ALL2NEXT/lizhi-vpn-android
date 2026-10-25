let aiRiskSettingsLoading = false;
let aiRiskSettingsLoaded = false;
let aiRiskOperationInFlight = false;
let aiRiskDefaultPromptTemplate = '';
let aiRiskModels = [];
let aiRiskDashboard = null;
let aiRiskAnalysis = null;
let aiRiskFilter = 'all';
let aiRiskExpandedIp = '';
let aiRiskSearchValue = '';
let aiRiskSortValue = 'risk';
let aiRiskActiveViews = Object.create(null);
let aiRiskReviewedIps = aiRiskReadReviewedIps();

function aiRiskReadReviewedIps() {
  try {
    if (typeof localStorage === 'undefined') return new Set();
    const saved = JSON.parse(localStorage.getItem('chimera.ai-risk-reviewed.v1') || '[]');
    return new Set(Array.isArray(saved) ? saved.filter(function(ip) { return typeof ip === 'string'; }) : []);
  } catch (_) {
    return new Set();
  }
}

function aiRiskSaveReviewedIps() {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('chimera.ai-risk-reviewed.v1', JSON.stringify(Array.from(aiRiskReviewedIps).slice(-500)));
    }
  } catch (_) {}
}

function aiRiskSetStatus(id, message, isError) {
  const node = el(id);
  node.textContent = message || '';
  node.classList.toggle('error', Boolean(isError));
}

function aiRiskErrorText(code) {
  const messages = {
    ai_risk_api_key_required: '请先填写并保存 Google AI API Key。',
    too_many_ai_risk_api_keys: '最多只能保存 20 个 Google AI API Key。',
    ai_risk_disabled: '请先启用 Gemini 风控分析。',
    ai_risk_config_binding_missing: '当前 Worker 未绑定 CONFIG KV。',
    invalid_ai_risk_prompt_template: '提示词模板必须包含且仅包含一次 {{DATASET_JSON}}，且不能超过 12,000 字符。',
    invalid_ai_risk_model: '模型名称格式无效。',
    invalid_ai_risk_thinking_level: '思考强度设置无效。',
    invalid_ai_risk_window: '分析时间窗无效。',
    ai_risk_dashboard_data_read_failed: '读取风控统计失败，请检查网络日志和数据库状态。',
    gemini_auth_failed: 'Gemini 认证失败，请检查 API Key 和模型权限。',
    gemini_rate_limited: 'Gemini 请求频率受限，请稍后再试。',
    gemini_unavailable: '无法连接 Gemini，请检查网络后重试。',
    gemini_request_failed: 'Gemini 请求失败，请检查模型名称和 API 配额。',
    gemini_model_not_found: '所有 Key 均无法访问此模型；请刷新模型列表、选择可用模型或检查各项目的模型权限。',
    gemini_model_configuration_unsupported: '所选模型不支持当前生成配置；请更换模型或将思考强度设为“模型默认”。',
    gemini_empty_response: 'Gemini 没有返回可用内容。',
    gemini_output_truncated: 'Gemini 输出达到 token 上限；请降低思考强度或缩小分析时间窗。',
    gemini_invalid_json: 'Gemini 返回内容格式无效，请重试。',
    gemini_models_list_failed: '读取 Gemini 模型列表失败，请检查 API 配额后重试。',
  };
  return messages[code] || code || '操作失败，请稍后重试。';
}

async function aiRiskRequest(path, options) {
  const response = await fetch(ADMIN_API_BASE + path, Object.assign({headers: headers()}, options || {}));
  const data = parseResponseJson(await response.text());
  if (!response.ok || data.ok === false) throw new Error(data.error || 'ai_risk_request_failed');
  return data;
}

function aiRiskUpdateHeader(settings) {
  const model = settings && settings.model || value('aiRiskModel') || 'Gemini';
  el('aiRiskHeaderModel').textContent = model;
  const configured = Boolean(settings && settings.apiKeyConfigured);
  const indicator = el('aiRiskConnectionIndicator');
  indicator.textContent = configured ? (settings.enabled ? 'Key 已配置' : 'Key 已保存') : '未配置';
  indicator.classList.toggle('connected', configured && settings.enabled === true);
}

async function loadAiRiskSettings(force) {
  if (aiRiskSettingsLoading || (aiRiskSettingsLoaded && !force)) return;
  aiRiskSettingsLoading = true;
  aiRiskSetStatus('aiRiskSettingsStatus', '正在读取配置…', false);
  try {
    const data = await aiRiskRequest('/ai-risk/settings');
    const settings = data.settings || {};
    if (typeof settings.defaultPromptTemplate === 'string') aiRiskDefaultPromptTemplate = settings.defaultPromptTemplate;
    el('aiRiskEnabled').checked = settings.enabled === true;
    el('aiRiskModel').value = settings.model || 'gemini-3.8-flash';
    el('aiRiskThinkingLevel').value = settings.thinkingLevel || 'medium';
    if (typeof settings.promptTemplate === 'string') el('aiRiskPromptTemplate').value = settings.promptTemplate;
    el('aiRiskApiKeys').value = '';
    el('aiRiskApiKeys').placeholder = settings.apiKeyCount ? '已配置 ' + settings.apiKeyCount + ' 个 Key；留空保留' : '请输入 Google AI API Key';
    el('aiRiskKeyStatus').textContent = settings.apiKeyCount ? '已保存 ' + settings.apiKeyCount + ' 个 Key；密钥不会回显。输入新列表将替换现有 Key。' : '支持最多 20 个 Key，用英文逗号或分号分隔；留空保留当前配置。';
    el('aiRiskClearKeys').checked = false;
    el('aiRiskClearKeys').disabled = settings.apiKeyConfigured !== true;
    aiRiskSetStatus('aiRiskSettingsStatus', settings.apiKeyConfigured ? '已配置 ' + settings.apiKeyCount + ' 个 API Key（密钥不会回显）' : '尚未配置 API Key', false);
    aiRiskUpdateHeader(settings);
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
      thinkingLevel: el('aiRiskThinkingLevel').value,
      promptTemplate: el('aiRiskPromptTemplate').value,
      apiKeys: el('aiRiskApiKeys').value,
      clearApiKeys: el('aiRiskClearKeys').checked,
    };
    const data = await aiRiskRequest('/ai-risk/settings', {method: 'PUT', body: JSON.stringify(body)});
    el('aiRiskApiKeys').value = '';
    el('aiRiskClearKeys').checked = false;
    const settings = data.settings || {};
    if (typeof settings.defaultPromptTemplate === 'string') aiRiskDefaultPromptTemplate = settings.defaultPromptTemplate;
    if (typeof settings.promptTemplate === 'string') el('aiRiskPromptTemplate').value = settings.promptTemplate;
    el('aiRiskApiKeys').placeholder = settings.apiKeyCount ? '已配置 ' + settings.apiKeyCount + ' 个 Key；留空保留' : '请输入 Google AI API Key';
    el('aiRiskKeyStatus').textContent = settings.apiKeyCount ? '已保存 ' + settings.apiKeyCount + ' 个 Key；密钥不会回显。输入新列表将替换现有 Key。' : '支持最多 20 个 Key，用英文逗号或分号分隔；留空保留当前配置。';
    el('aiRiskClearKeys').disabled = settings.apiKeyConfigured !== true;
    aiRiskUpdateHeader(settings);
    aiRiskSettingsLoaded = true;
    aiRiskSetStatus('aiRiskSettingsStatus', '配置已保存；' + (settings.apiKeyConfigured ? '已保存 ' + settings.apiKeyCount + ' 个 API Key' : '未配置 API Key'), false);
    return true;
  } catch (error) {
    aiRiskSetStatus('aiRiskSettingsStatus', aiRiskErrorText(error.message), true);
    return false;
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskSave').disabled = false;
  }
}

function aiRiskFormatCount(value) {
  const count = Number(value);
  return Number.isFinite(count) ? Math.max(0, count).toLocaleString() : '0';
}

function aiRiskFormatTime(value) {
  if (!value) return '—';
  return String(value).replace('T', ' ').replace(/([+-]\d{2}:\d{2}|Z)$/, '').slice(0, 16);
}

function aiRiskRiskRank(level) {
  return level === 'high' ? 3 : level === 'medium' ? 2 : level === 'low' ? 1 : 0;
}

function aiRiskBuildRows() {
  const dashboard = aiRiskDashboard || {};
  const rows = new Map();
  function getRow(ip) {
    if (!ip) return null;
    if (!rows.has(ip)) {
      rows.set(ip, {
        ip: ip,
        accounts: new Map(),
        requestCount: 0,
        subscriptionRequests: 0,
        successfulSubscriptions: 0,
        clientErrors: 0,
        serverErrors: 0,
        firstSeenAt: '',
        lastSeenAt: '',
        hourCounts: new Map(),
        endpoints: new Map(),
        finding: null,
      });
    }
    return rows.get(ip);
  }
  (dashboard.subjects || []).forEach(function(subject) {
    const row = getRow(String(subject.ip || ''));
    if (!row) return;
    const uid = String(subject.uid || '');
    if (uid) row.accounts.set(uid, row.accounts.get(uid) || {uid: uid, requestCount: 0, subscriptionCount: 0, loginCount: 0, accountStatus: 'unknown', lastSeenAt: ''});
    const account = uid ? row.accounts.get(uid) : null;
    const requests = Number(subject.requestCount) || 0;
    const subscriptions = Number(subject.subscriptionRequests) || 0;
    if (account) {
      account.requestCount += requests;
      account.subscriptionCount += subscriptions;
    }
    row.requestCount += requests;
    row.subscriptionRequests += subscriptions;
    row.successfulSubscriptions += Number(subject.successfulSubscriptions) || 0;
    row.clientErrors += Number(subject.clientErrors) || 0;
    row.serverErrors += Number(subject.serverErrors) || 0;
    if (subject.firstSeenAt && (!row.firstSeenAt || subject.firstSeenAt < row.firstSeenAt)) row.firstSeenAt = subject.firstSeenAt;
    if (subject.lastSeenAt && subject.lastSeenAt > row.lastSeenAt) row.lastSeenAt = subject.lastSeenAt;
  });
  (dashboard.ipRelations || []).forEach(function(relation) {
    const row = rows.get(String(relation.ip || ''));
    if (!row) return;
    (relation.users || []).forEach(function(user) {
      const uid = String(user.uid || '');
      if (!uid) return;
      const account = row.accounts.get(uid) || {uid: uid, requestCount: 0, subscriptionCount: 0, loginCount: 0, accountStatus: 'unknown', lastSeenAt: ''};
      account.requestCount = Math.max(account.requestCount, Number(user.requestCount) || 0);
      account.subscriptionCount = Math.max(account.subscriptionCount, Number(user.subscriptionCount) || 0);
      account.loginCount = Number(user.loginCount) || 0;
      account.accountStatus = String(user.accountStatus || 'unknown');
      account.lastSeenAt = String(user.lastSeenAt || account.lastSeenAt || '');
      row.accounts.set(uid, account);
      if (user.lastSeenAt && user.lastSeenAt > row.lastSeenAt) row.lastSeenAt = user.lastSeenAt;
    });
  });
  (dashboard.hourlyActivity || []).forEach(function(activity) {
    const row = getRow(String(activity.ip || ''));
    if (!row) return;
    (activity.hours || []).forEach(function(point) {
      const offset = Number(point[0]);
      if (!Number.isFinite(offset)) return;
      const current = row.hourCounts.get(offset) || {requests: 0, subscriptions: 0, clientErrors: 0};
      current.requests += Number(point[1]) || 0;
      current.subscriptions += Number(point[2]) || 0;
      current.clientErrors += Number(point[3]) || 0;
      row.hourCounts.set(offset, current);
    });
  });
  (dashboard.endpointActivity || []).forEach(function(endpoint) {
    const row = getRow(String(endpoint.ip || ''));
    if (!row) return;
    const key = String(endpoint.method || '') + '\u0000' + String(endpoint.path || '');
    const current = row.endpoints.get(key) || {method: String(endpoint.method || ''), path: String(endpoint.path || ''), requests: 0, successfulRequests: 0, clientErrors: 0, serverErrors: 0};
    current.requests += Number(endpoint.requests) || 0;
    current.successfulRequests += Number(endpoint.successfulRequests) || 0;
    current.clientErrors += Number(endpoint.clientErrors) || 0;
    current.serverErrors += Number(endpoint.serverErrors) || 0;
    row.endpoints.set(key, current);
  });
  (aiRiskAnalysis && aiRiskAnalysis.findings || []).forEach(function(finding) {
    const row = getRow(String(finding.ip || ''));
    if (!row) return;
    const incoming = Object.assign({}, finding, {uids: Array.isArray(finding.uids) ? finding.uids : [], evidence: Array.isArray(finding.evidence) ? finding.evidence : []});
    if (!row.finding || aiRiskRiskRank(incoming.riskLevel) > aiRiskRiskRank(row.finding.riskLevel)) row.finding = incoming;
    else {
      row.finding.uids = Array.from(new Set(row.finding.uids.concat(incoming.uids)));
      row.finding.evidence = Array.from(new Set(row.finding.evidence.concat(incoming.evidence)));
    }
    incoming.uids.forEach(function(uid) {
      if (!row.accounts.has(uid)) row.accounts.set(uid, {uid: uid, requestCount: 0, subscriptionCount: 0, loginCount: 0, accountStatus: 'unknown', lastSeenAt: ''});
    });
  });
  return Array.from(rows.values()).map(function(row) {
    row.activeHourCount = row.hourCounts.size;
    row.accounts = Array.from(row.accounts.values()).sort(function(left, right) { return right.requestCount - left.requestCount; });
    row.endpoints = Array.from(row.endpoints.values()).sort(function(left, right) { return right.requests - left.requests; });
    row.processed = aiRiskReviewedIps.has(row.ip);
    return row;
  });
}

function aiRiskFilteredRows(rows) {
  const query = aiRiskSearchValue.trim().toLowerCase();
  return rows.filter(function(row) {
    if (aiRiskFilter === 'pending' && (!row.finding || row.processed)) return false;
    if (['high', 'medium', 'low'].includes(aiRiskFilter) && (!row.finding || row.finding.riskLevel !== aiRiskFilter)) return false;
    if (!query) return true;
    return row.ip.toLowerCase().includes(query) || row.accounts.some(function(account) { return account.uid.toLowerCase().includes(query); });
  }).sort(function(left, right) {
    if (aiRiskSortValue === 'requests') return right.requestCount - left.requestCount || right.lastSeenAt.localeCompare(left.lastSeenAt);
    if (aiRiskSortValue === 'recent') return right.lastSeenAt.localeCompare(left.lastSeenAt);
    return aiRiskRiskRank(right.finding && right.finding.riskLevel) - aiRiskRiskRank(left.finding && left.finding.riskLevel) || right.requestCount - left.requestCount;
  });
}

function aiRiskUpdateFilterCounts(rows) {
  const high = rows.filter(function(row) { return row.finding && row.finding.riskLevel === 'high'; }).length;
  const medium = rows.filter(function(row) { return row.finding && row.finding.riskLevel === 'medium'; }).length;
  const low = rows.filter(function(row) { return row.finding && row.finding.riskLevel === 'low'; }).length;
  const pending = rows.filter(function(row) { return row.finding && !row.processed; }).length;
  el('aiRiskCountAll').textContent = aiRiskFormatCount(rows.length);
  el('aiRiskCountHigh').textContent = aiRiskFormatCount(high);
  el('aiRiskCountMedium').textContent = aiRiskFormatCount(medium);
  el('aiRiskCountLow').textContent = aiRiskFormatCount(low);
  el('aiRiskCountPending').textContent = aiRiskFormatCount(pending);
}

function aiRiskRenderHourChart(row) {
  const points = Array.from(row.hourCounts.entries()).sort(function(left, right) { return left[0] - right[0]; });
  if (!points.length) return '<div class="ai-risk-empty-note">当前时间窗内没有可绘制的逐小时数据。</div>';
  const max = Math.max.apply(null, points.map(function(entry) { return entry[1].requests; }).concat([1]));
  const bars = points.map(function(entry) {
    const offset = entry[0];
    const counts = entry[1];
    const height = Math.max(3, Math.round(counts.requests / max * 100));
    const title = '时间窗第 ' + offset + ' 小时 · ' + counts.requests + ' 次请求 · ' + counts.subscriptions + ' 次订阅';
    return '<li><span class="ai-risk-hour-bar" style="--bar-height:' + height + '%" title="' + esc(title) + '"></span><small>' + esc(String(offset).padStart(2, '0')) + '</small></li>';
  }).join('');
  return '<div class="ai-risk-chart-heading"><strong>请求时间分布</strong><span>按小时汇总</span></div><ol class="ai-risk-hour-chart" aria-label="逐小时请求量图表">' + bars + '</ol>';
}

function aiRiskRenderOverview(row) {
  const errorCount = row.clientErrors + row.serverErrors;
  const successRate = row.subscriptionRequests ? Math.round(row.successfulSubscriptions / row.subscriptionRequests * 100) : 0;
  return '<div class="ai-risk-overview-grid"><section class="ai-risk-activity-card"><h4>异常统计</h4><div class="ai-risk-activity-metrics">' +
    '<div><span>总请求数</span><strong>' + aiRiskFormatCount(row.requestCount) + '</strong></div>' +
    '<div><span>订阅请求</span><strong>' + aiRiskFormatCount(row.subscriptionRequests) + '</strong><small>' + successRate + '% 成功</small></div>' +
    '<div><span>服务端错误</span><strong>' + aiRiskFormatCount(errorCount) + '</strong><small>4xx ' + aiRiskFormatCount(row.clientErrors) + ' · 5xx ' + aiRiskFormatCount(row.serverErrors) + '</small></div>' +
    '<div><span>活跃小时</span><strong>' + aiRiskFormatCount(row.activeHourCount) + '</strong></div></div></section>' +
    '<section class="ai-risk-chart-card"><h4>请求趋势（最近 ' + esc(String(Number(el('aiRiskWindowHours').value) || 24)) + ' 小时）</h4>' + aiRiskRenderHourChart(row) + '</section></div>' +
    aiRiskRenderOverviewFinding(row);
}

function aiRiskRenderOverviewFinding(row) {
  const finding = row.finding;
  if (!finding) return '<div class="ai-risk-overview-analysis"><div class="ai-risk-empty-note">运行 AI 风控分析后，可查看该 IP 的研判理由和人工复核建议。</div></div>';
  const evidence = finding.evidence || [];
  const score = Number.isSafeInteger(finding.riskScore) && finding.riskScore >= 0 && finding.riskScore <= 100
    ? finding.riskScore : null;
  return '<div class="ai-risk-overview-analysis"><section class="ai-risk-overview-conclusion"><h4>AI 分析结论</h4>' +
    '<strong>' + esc(finding.title || '风控线索') + '</strong><p>' + esc(finding.rationale || 'Gemini 未提供详细理由。') + '</p>' +
    (evidence.length ? '<ul>' + evidence.slice(0, 4).map(function(item) { return '<li>' + esc(item) + '</li>'; }).join('') + '</ul>' : '') +
    '</section><aside class="ai-risk-overview-advice"><h4>建议人工复核</h4><p>' + esc(finding.recommendation || '由管理员进一步核验。') + '</p>' +
    (score !== null ? '<span>风控排查优先级 ' + score + '/100</span>' : '') +
    '<small>AI 结论仅供辅助判断，系统不会自动封禁。</small></aside></div>';
}

function aiRiskRenderEndpoints(row) {
  if (!row.endpoints.length) return '<div class="ai-risk-empty-note">当前时间窗内没有接口明细。</div>';
  return '<div class="ai-risk-endpoints"><div class="ai-risk-endpoint-row ai-risk-endpoint-heading"><span>方法与路径</span><span>请求</span><span>4xx / 5xx</span></div>' + row.endpoints.map(function(endpoint) {
    return '<div class="ai-risk-endpoint-row"><span><b>' + esc(endpoint.method || '—') + '</b><code>' + esc(endpoint.path || '—') + '</code></span><span>' + aiRiskFormatCount(endpoint.requests) + '</span><span>' + aiRiskFormatCount(endpoint.clientErrors) + ' / ' + aiRiskFormatCount(endpoint.serverErrors) + '</span></div>';
  }).join('') + '</div>';
}

function aiRiskRenderReasoning(row) {
  const finding = row.finding;
  if (!finding) return '<div class="ai-risk-empty-note">尚无 AI 研判。运行分析后会在这里显示理由、证据和建议。</div>';
  const evidence = finding.evidence || [];
  return '<div class="ai-risk-reasoning"><h4>' + esc(finding.title || '风控线索') + '</h4><p>' + esc(finding.rationale || 'Gemini 未提供详细理由。') + '</p>' +
    (evidence.length ? '<h5>支持证据</h5><ul>' + evidence.map(function(item) { return '<li>' + esc(item) + '</li>'; }).join('') + '</ul>' : '') +
    '<div class="ai-risk-recommendation"><strong>建议</strong><p>' + esc(finding.recommendation || '由管理员进一步核验。') + '</p></div><small>AI 输出是辅助线索，仍需结合日志人工复核；系统没有执行封禁。</small></div>';
}

function aiRiskRenderAccounts(row) {
  if (!row.accounts.length) return '<div class="ai-risk-empty-note">当前时间窗没有识别到关联账户。</div>';
  return '<div class="ai-risk-accounts">' + row.accounts.map(function(account) {
    return '<div class="ai-risk-account-row"><code>' + esc(account.uid) + '</code><span>' + aiRiskFormatCount(account.requestCount) + ' 次请求</span><span>' + esc(account.accountStatus || 'unknown') + '</span><small>最近 ' + esc(aiRiskFormatTime(account.lastSeenAt)) + '</small></div>';
  }).join('') + '</div>' + (row.finding ? '<p class="ai-risk-review-note">AI 分析结论需人工复核；系统不执行封禁。</p>' : '');
}

function aiRiskRenderDetail(row) {
  const active = aiRiskActiveViews[row.ip] || 'overview';
  const tabs = [['overview', '概览'], ['trend', '请求趋势'], ['endpoints', '接口分布'], ['reasoning', 'AI 研判'], ['accounts', '关联账户']];
  let content = '';
  if (active === 'overview') content = aiRiskRenderOverview(row);
  else if (active === 'trend') content = '<section class="ai-risk-expanded-chart"><h4>逐小时请求分布</h4>' + aiRiskRenderHourChart(row) + '<p>活跃小时表示该小时内至少有一条日志，不代表持续在线。</p></section>';
  else if (active === 'endpoints') content = aiRiskRenderEndpoints(row);
  else if (active === 'reasoning') content = aiRiskRenderReasoning(row);
  else content = aiRiskRenderAccounts(row);
  return '<div class="ai-risk-detail-tabs" role="tablist" aria-label="IP 风险详情">' + tabs.map(function(tab) {
    return '<button type="button" role="tab" aria-selected="' + (active === tab[0]) + '" class="' + (active === tab[0] ? 'active' : '') + '" data-ai-risk-view="' + tab[0] + '">' + tab[1] + '</button>';
  }).join('') + '</div><div class="ai-risk-detail-content">' + content + '</div>';
}

function aiRiskRenderCard(row, index) {
  const expanded = row.ip === aiRiskExpandedIp || (!aiRiskExpandedIp && index === 0);
  const finding = row.finding;
  const level = finding && ['high', 'medium', 'low'].includes(finding.riskLevel) ? finding.riskLevel : 'pending';
  const labels = {high: '高风险', medium: '中风险', low: '低风险', pending: finding ? '待复核' : '待分析'};
  const riskScore = finding && Number.isSafeInteger(finding.riskScore) && finding.riskScore >= 0 && finding.riskScore <= 100
    ? finding.riskScore : null;
  const accountLabel = row.accounts.length ? '关联 ' + row.accounts.length + ' 个账户' : '未识别账户';
  const title = finding && finding.title || (row.accounts.length > 1 ? '单 IP 关联多个账户' : '等待 AI 风险研判');
  const actionLabel = row.processed ? '撤销已处理' : '标记为已处理';
  return '<article class="ai-risk-ip-card risk-border-' + level + (expanded ? ' expanded' : '') + '" data-ip="' + esc(row.ip) + '">' +
    '<div class="ai-risk-ip-summary">' +
      '<button class="ai-risk-expand" type="button" data-ai-risk-action="expand" aria-expanded="' + expanded + '" aria-label="' + (expanded ? '收起' : '展开') + ' IP 详情">' + (expanded ? '−' : '+') + '</button>' +
      '<span class="ai-risk-badge risk-' + level + '">' + labels[level] + '</span>' +
      '<div class="ai-risk-ip-identity"><div><strong><code>' + esc(row.ip) + '</code></strong><button class="ai-risk-copy" type="button" data-ai-risk-action="copy" aria-label="复制 IP">复制</button></div><p>' + accountLabel + ' <span>首次 ' + esc(aiRiskFormatTime(row.firstSeenAt)) + '</span><span>最近 ' + esc(aiRiskFormatTime(row.lastSeenAt)) + '</span></p></div>' +
      '<div class="ai-risk-confidence"><span>' + (riskScore !== null ? '风险分' : finding ? '风险级别' : '请求量') + '</span><strong>' + (riskScore !== null ? riskScore + '/100' : finding ? labels[level] : aiRiskFormatCount(row.requestCount)) + '</strong>' + (riskScore !== null ? '<div class="ai-risk-confidence-track" role="img" aria-label="风控排查优先级 ' + riskScore + ' 分"><i class="' + level + '" style="--risk-width:' + riskScore + '%"></i></div>' : '<div class="ai-risk-confidence-track" aria-hidden="true"><i class="' + level + '" style="--risk-width:0%"></i></div>') + '</div>' +
      (finding ? '<button class="ai-risk-review-button secondary" type="button" data-ai-risk-action="review">' + actionLabel + '</button>' : '<span class="ai-risk-awaiting">运行分析后复核</span>') +
    '</div>' +
    (expanded ? '<div class="ai-risk-detail" id="ai-risk-detail-' + index + '">' + aiRiskRenderDetail(row) + '</div>' : '') +
  '</article>';
}

function renderAiRiskFindings(result) {
  aiRiskAnalysis = result || null;
  aiRiskRenderDashboard();
}

function aiRiskRenderDashboard() {
  const host = el('aiRiskResults');
  const rows = aiRiskBuildRows();
  aiRiskUpdateFilterCounts(rows);
  if (aiRiskAnalysis && aiRiskAnalysis.analyzedAt) {
    el('aiRiskLastAnalysis').textContent = aiRiskFormatTime(aiRiskAnalysis.analyzedAt);
    el('aiRiskAnalysisDuration').textContent = Number.isFinite(aiRiskAnalysis.durationMs) ? Math.max(1, Math.round(aiRiskAnalysis.durationMs / 1000)) + ' 秒' : '—';
    el('aiRiskHeaderModel').textContent = aiRiskAnalysis.model || value('aiRiskModel') || 'Gemini';
  } else if (aiRiskDashboard) {
    el('aiRiskLastAnalysis').textContent = '尚未分析';
    el('aiRiskAnalysisDuration').textContent = '—';
  }
  if (aiRiskAnalysis && aiRiskAnalysis.summary) {
    host.innerHTML = '<section class="ai-risk-analysis-summary"><strong>AI 分析结论</strong><p>' + esc(aiRiskAnalysis.summary) + '</p></section>';
  } else {
    host.innerHTML = '';
  }
  if (!aiRiskDashboard && !rows.length) {
    host.innerHTML += '<div class="loading-state">正在读取风险数据…</div>';
    return;
  }
  const visible = aiRiskFilteredRows(rows);
  if (!visible.length) {
    const message = rows.length ? '没有符合当前筛选条件的 IP。' : '当前时间窗内没有可展示的请求记录。请确认网络日志记录已启用。';
    host.innerHTML += '<div class="ai-risk-empty"><strong>' + message + '</strong>' + (aiRiskAnalysis ? '<p>分析结果仅覆盖当前日志保留范围。</p>' : '<p>运行 AI 风控分析后，可查看风险级别、证据和处置建议。</p>') + '</div>';
    return;
  }
  host.innerHTML += '<div class="ai-risk-list-summary">显示 ' + aiRiskFormatCount(visible.length) + ' / ' + aiRiskFormatCount(rows.length) + ' 个 IP · AI 结果只作人工复核参考</div>' +
    '<div class="ai-risk-ip-list">' + visible.map(aiRiskRenderCard).join('') + '</div>';
}

async function loadAiRiskDashboard(clearAnalysis) {
  if (aiRiskOperationInFlight) return;
  aiRiskOperationInFlight = true;
  el('aiRiskRefresh').disabled = true;
  if (clearAnalysis) aiRiskAnalysis = null;
  aiRiskSetStatus('aiRiskAnalysisStatus', '正在读取风险数据…', false);
  try {
    const hours = Number(el('aiRiskWindowHours').value) || 24;
    const data = await aiRiskRequest('/ai-risk/dashboard-data?windowHours=' + encodeURIComponent(hours));
    aiRiskDashboard = data.dataset || null;
    aiRiskExpandedIp = '';
    aiRiskRenderDashboard();
    aiRiskSetStatus('aiRiskAnalysisStatus', aiRiskDashboard ? '数据已更新 · ' + aiRiskFormatTime(aiRiskDashboard.generatedAt) : '没有可用的风险数据。', false);
  } catch (error) {
    aiRiskDashboard = null;
    aiRiskRenderDashboard();
    aiRiskSetStatus('aiRiskAnalysisStatus', aiRiskErrorText(error.message), true);
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskRefresh').disabled = false;
  }
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
  aiRiskSetStatus('aiRiskAnalysisStatus', '正在汇总脱敏数据并请求 Gemini…', false);
  el('aiRiskResults').innerHTML = '<div class="loading-state">AI 正在分析请求行为，请稍候…</div>';
  const startedAt = Date.now();
  try {
    const data = await aiRiskRequest('/ai-risk/analyze', {
      method: 'POST',
      body: JSON.stringify({windowHours: Number(el('aiRiskWindowHours').value)}),
    });
    data.durationMs = Date.now() - startedAt;
    renderAiRiskFindings(data);
    aiRiskSetStatus('aiRiskAnalysisStatus', '分析完成 · ' + data.model + ' · ' + data.analyzedAt + aiRiskUsageText(data.usage), false);
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

async function loadAiRiskModels() {
  if (aiRiskOperationInFlight) return;
  const saved = await saveAiRiskSettings();
  if (!saved) return;
  aiRiskOperationInFlight = true;
  el('aiRiskLoadModels').disabled = true;
  el('aiRiskModelStatus').textContent = '正在从 Google AI 获取模型列表…';
  try {
    const data = await aiRiskRequest('/ai-risk/models');
    const models = Array.isArray(data.models) ? data.models : [];
    aiRiskModels = models;
    const keyCount = Number(data.keyCount) || 1;
    const successfulKeyCount = Number(data.successfulKeyCount) || 1;
    el('aiRiskModelOptions').innerHTML = models.map(function(model) {
      const details = [
        Number(model.availableKeyCount) + '/' + keyCount + ' keys',
        Number.isSafeInteger(model.inputTokenLimit) ? '输入 ' + Number(model.inputTokenLimit).toLocaleString() : '',
        Number.isSafeInteger(model.outputTokenLimit) ? '输出 ' + Number(model.outputTokenLimit).toLocaleString() : '',
        model.supportsThinking ? '支持思考' : '',
      ].filter(Boolean).join(' · ');
      return '<option value="' + esc(model.id) + '" label="' + esc((model.displayName || model.id) + ' · ' + details) + '"></option>';
    }).join('');
    const selectedModelAvailable = models.some(function(model) { return model.id === value('aiRiskModel'); });
    el('aiRiskModelStatus').textContent = models.length
      ? '已合并 ' + models.length + ' 个 Gemini 对话模型；' + successfulKeyCount + '/' + keyCount + ' 个 Key 成功读取。' +
        (selectedModelAvailable ? '' : ' 当前模型不在可用列表中，已保留设置；请检查模型是否下线或 Key 权限。')
      : '配置的 Key 没有返回支持 generateContent 的模型。';
    updateAiRiskModelDetails();
    aiRiskSetStatus('aiRiskSettingsStatus', '模型列表已更新；可在模型名称中选择。', false);
  } catch (error) {
    el('aiRiskModelStatus').textContent = aiRiskErrorText(error.message);
    aiRiskSetStatus('aiRiskSettingsStatus', aiRiskErrorText(error.message), true);
  } finally {
    aiRiskOperationInFlight = false;
    el('aiRiskLoadModels').disabled = false;
  }
}

function updateAiRiskModelDetails() {
  const model = aiRiskModels.find(function(item) { return item.id === value('aiRiskModel'); });
  const details = el('aiRiskModelDetails');
  if (!model) {
    details.textContent = aiRiskModels.length ? '当前模型没有出现在最近一次可用列表中；可以保留手动配置，或选择列表中的模型。' : '';
    el('aiRiskHeaderModel').textContent = value('aiRiskModel') || 'Gemini';
    return;
  }
  const facts = [
    model.description || model.displayName || model.id,
    Number.isSafeInteger(model.inputTokenLimit) ? '输入上限 ' + Number(model.inputTokenLimit).toLocaleString() : '',
    Number.isSafeInteger(model.outputTokenLimit) ? '输出上限 ' + Number(model.outputTokenLimit).toLocaleString() : '',
    model.supportsThinking ? '支持思考控制' : '使用模型默认推理能力',
    '可被 ' + Number(model.availableKeyCount || 0) + ' 个 Key 访问',
  ].filter(Boolean);
  details.textContent = facts.join(' · ');
  el('aiRiskHeaderModel').textContent = model.displayName || model.id;
}

function aiRiskUsageText(usage) {
  if (!usage || typeof usage !== 'object') return '';
  const parts = [
    Number.isSafeInteger(usage.promptTokenCount) ? '输入 ' + Number(usage.promptTokenCount).toLocaleString() : '',
    Number.isSafeInteger(usage.candidatesTokenCount) ? '输出 ' + Number(usage.candidatesTokenCount).toLocaleString() : '',
    Number.isSafeInteger(usage.thoughtsTokenCount) ? '思考 ' + Number(usage.thoughtsTokenCount).toLocaleString() : '',
  ].filter(Boolean);
  return parts.length ? ' · ' + parts.join(' / ') + ' tokens' : '';
}

function aiRiskSetSettingsOpen(open) {
  const panel = el('aiRiskSettingsPanel');
  const toggle = el('aiRiskSettingsToggle');
  if (open && !panel.open && typeof panel.showModal === 'function') panel.showModal();
  if (!open && panel.open && typeof panel.close === 'function') panel.close();
  if (toggle.setAttribute) toggle.setAttribute('aria-expanded', String(open));
}

function aiRiskHandlePageClick(event) {
  const button = event.target && event.target.closest ? event.target.closest('button') : null;
  if (!button) return;
  if (button.id === 'aiRiskSettingsToggle') {
    aiRiskSetSettingsOpen(!el('aiRiskSettingsPanel').open);
    return;
  }
  if (button.id === 'aiRiskSettingsClose') {
    aiRiskSetSettingsOpen(false);
    return;
  }
  const filter = button.getAttribute && button.getAttribute('data-ai-risk-filter');
  if (filter) {
    aiRiskFilter = filter;
    el('aiRiskPage').querySelectorAll('[data-ai-risk-filter]').forEach(function(tab) {
      const selected = tab === button;
      tab.classList.toggle('active', selected);
      tab.setAttribute('aria-selected', String(selected));
    });
    aiRiskRenderDashboard();
    return;
  }
  const action = button.getAttribute && button.getAttribute('data-ai-risk-action');
  if (action) {
    const card = button.closest('.ai-risk-ip-card');
    const ip = card && card.getAttribute('data-ip');
    if (!ip) return;
    if (action === 'expand') {
      aiRiskExpandedIp = button.getAttribute('aria-expanded') === 'true' ? '__none__' : ip;
      aiRiskRenderDashboard();
    } else if (action === 'review') {
      if (aiRiskReviewedIps.has(ip)) aiRiskReviewedIps.delete(ip);
      else aiRiskReviewedIps.add(ip);
      aiRiskSaveReviewedIps();
      aiRiskRenderDashboard();
    } else if (action === 'copy' && typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(ip).then(function() {
        aiRiskSetStatus('aiRiskAnalysisStatus', 'IP 已复制。', false);
      }).catch(function() {
        aiRiskSetStatus('aiRiskAnalysisStatus', '浏览器未允许剪贴板访问，请手动选择 IP。', true);
      });
    }
    return;
  }
  const view = button.getAttribute && button.getAttribute('data-ai-risk-view');
  if (view) {
    const card = button.closest('.ai-risk-ip-card');
    const ip = card && card.getAttribute('data-ip');
    if (ip) {
      aiRiskActiveViews[ip] = view;
      aiRiskExpandedIp = ip;
      aiRiskRenderDashboard();
    }
  }
}

el('aiRiskSave').addEventListener('click', saveAiRiskSettings);
el('aiRiskPromptReset').addEventListener('click', function() {
  if (!aiRiskDefaultPromptTemplate) {
    aiRiskSetStatus('aiRiskSettingsStatus', '默认提示词尚未加载，请先重新读取配置。', true);
    return;
  }
  el('aiRiskPromptTemplate').value = aiRiskDefaultPromptTemplate;
  aiRiskSetStatus('aiRiskSettingsStatus', '已恢复推荐提示词，点击“保存配置”后生效。', false);
});
el('aiRiskTest').addEventListener('click', testAiRiskGemini);
el('aiRiskLoadModels').addEventListener('click', loadAiRiskModels);
el('aiRiskAnalyze').addEventListener('click', runAiRiskAnalysis);
el('aiRiskRefresh').addEventListener('click', function() { loadAiRiskDashboard(true); });
el('aiRiskPreview').addEventListener('click', previewAiRiskData);
el('aiRiskWindowHours').addEventListener('change', function() { loadAiRiskDashboard(true); });
el('aiRiskSearch').addEventListener('input', function() {
  aiRiskSearchValue = el('aiRiskSearch').value || '';
  aiRiskRenderDashboard();
});
el('aiRiskSort').addEventListener('change', function() {
  aiRiskSortValue = el('aiRiskSort').value || 'risk';
  aiRiskRenderDashboard();
});
el('aiRiskPage').addEventListener('click', aiRiskHandlePageClick);
el('aiRiskSettingsPanel').addEventListener('cancel', function(event) {
  event.preventDefault();
  aiRiskSetSettingsOpen(false);
});
el('aiRiskSettingsPanel').addEventListener('click', function(event) {
  if (event.target === el('aiRiskSettingsPanel')) aiRiskSetSettingsOpen(false);
});
el('aiRiskClearKeys').addEventListener('change', function() {
  if (el('aiRiskClearKeys').checked) el('aiRiskApiKeys').value = '';
});
el('aiRiskModel').addEventListener('input', updateAiRiskModelDetails);
el('aiRiskModel').addEventListener('change', updateAiRiskModelDetails);
