function escapeAiHtml(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
function renderAiMarkdown(markdown) {
  const lines = String(markdown == null ? '' : markdown).replace(/\r\n?/g, '\n').split('\n');
  let html = '';
  let listType = '';
  function closeList() {
    if (!listType) return;
    html += '</' + listType + '>';
    listType = '';
  }
  function inline(value) {
    return escapeAiHtml(value)
      .replace(new RegExp(String.fromCharCode(96) + '([^' + String.fromCharCode(96) + ']*)' + String.fromCharCode(96), 'g'), '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/__([^_]+)__/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>')
      .replace(/_([^_]+)_/g, '<em>$1</em>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  }
  lines.forEach(function(rawLine) {
    const line = rawLine.trim();
    if (!line) {
      closeList();
      return;
    }
    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = heading[1].length + 2;
      html += '<h' + level + '>' + inline(heading[2]) + '</h' + level + '>';
      return;
    }
    const unordered = line.match(/^[-*]\s+(.+)$/);
    if (unordered) {
      if (listType !== 'ul') {
        closeList();
        html += '<ul>';
        listType = 'ul';
      }
      html += '<li>' + inline(unordered[1]) + '</li>';
      return;
    }
    const ordered = line.match(/^\d+[.)]\s+(.+)$/);
    if (ordered) {
      if (listType !== 'ol') {
        closeList();
        html += '<ol>';
        listType = 'ol';
      }
      html += '<li>' + inline(ordered[1]) + '</li>';
      return;
    }
    closeList();
    html += '<p>' + inline(line) + '</p>';
  });
  closeList();
  return html || '<p>暂无分析内容</p>';
}
function updateAiAnalysisContext(resetResult) {
  const context = DASHBOARD_AI_CONTEXT[activeDashboardTab] || DASHBOARD_AI_CONTEXT['user-trends'];
  const title = el('aiAnalysisTitle');
  const result = el('aiAnalysisResult');
  if (title) title.textContent = 'AI 数据分析';
  if (resetResult && result && !aiAnalysisLoading) {
    result.textContent = '当前板块：' + context.title + '。点击“开始分析”生成 Markdown 分析。';
  }
}
function setAiAnalysisSettingsDisabled(disabled) {
  document.querySelectorAll('#aiAnalysisSettings input, #aiAnalysisSettings select, #aiAnalysisSettings textarea')
    .forEach(function(control) {
      control.disabled = disabled;
    });
}
async function readAiAnalysisStream(response, host) {
  const reader = response.body && response.body.getReader();
  if (!reader) throw new Error('浏览器不支持 AI 流式响应');
  const decoder = new TextDecoder();
  let pending = '';
  const state = {
    analysis: '',
    finishReason: '',
    modelVersion: '',
    usage: {},
    meta: {},
  };
  function renderPartial() {
    if (!host) return;
    host.innerHTML = renderAiMarkdown(state.analysis || '正在生成分析…');
    positionAiAnalysisDialog();
  }
  function handleEvent(eventText) {
    const lines = eventText.split('\n');
    const isMetaEvent = lines.some(function(line) { return line.trim() === 'event: ai-meta'; });
    const dataText = lines
      .filter(function(line) { return line.indexOf('data:') === 0; })
      .map(function(line) { return line.slice(5).trim(); })
      .join('');
    if (!dataText || dataText === '[DONE]') return;
    let chunk;
    try {
      chunk = JSON.parse(dataText);
    } catch (_) {
      return;
    }
    if (isMetaEvent) {
      state.meta = chunk;
      return;
    }
    const candidate = chunk.candidates && chunk.candidates[0] || {};
    const delta = (candidate.content && candidate.content.parts || [])
      .map(function(part) {
        return part && part.thought ? '' : String(part && part.text || '');
      })
      .join('');
    if (delta) {
      state.analysis += delta;
      renderPartial();
    }
    if (candidate.finishReason) state.finishReason = candidate.finishReason;
    if (chunk.modelVersion) state.modelVersion = chunk.modelVersion;
    if (chunk.usageMetadata) state.usage = chunk.usageMetadata;
  }
  function consumeEvents(flush) {
    const normalized = pending.replace(/\r\n/g, '\n');
    const events = normalized.split('\n\n');
    pending = events.pop() || '';
    events.forEach(handleEvent);
    if (flush && pending.trim()) {
      handleEvent(pending);
      pending = '';
    }
  }
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    pending += decoder.decode(chunk.value, {stream: true});
    consumeEvents(false);
  }
  pending += decoder.decode();
  consumeEvents(true);
  if (!state.analysis) throw new Error('AI 未返回可展示的分析内容');
  return {
    ok: true,
    panel: state.meta.panel,
    model: state.meta.model,
    providerModel: state.meta.providerModel,
    analysis: state.analysis,
    finishReason: state.finishReason || 'STOP',
    modelVersion: state.modelVersion,
    usage: {
      promptTokens: Number(state.usage.promptTokenCount || 0),
      outputTokens: Number(state.usage.candidatesTokenCount || 0),
      thoughtsTokens: Number(state.usage.thoughtsTokenCount || 0),
    },
    settings: state.meta.settings || {},
    dataWindow: state.meta.dataWindow,
  };
}
async function analyzeDashboardData() {
  if (aiAnalysisLoading) return;
  const host = el('aiAnalysisResult');
  const button = el('aiAnalyzeButton');
  const modelSelect = el('aiAnalysisModel');
  const model = modelSelect ? modelSelect.value : 'gemini-flash-latest';
  const panel = activeDashboardTab;
  const thinkingSelect = el('aiAnalysisThinkingLevel');
  const thinkingLevel = thinkingSelect ? thinkingSelect.value : 'low';
  const maxOutputInput = el('aiAnalysisMaxOutputTokens');
  const rawMaxOutputTokens = maxOutputInput ? Number(maxOutputInput.value) : 4096;
  const maxOutputTokens = Math.min(Math.max(Math.round(rawMaxOutputTokens) || 4096, 256), 65536);
  const styleSelect = el('aiAnalysisStyle');
  const analysisStyle = styleSelect ? styleSelect.value : 'standard';
  const instructionInput = el('aiAnalysisInstruction');
  const customInstruction = instructionInput ? instructionInput.value.trim().slice(0, 1000) : '';
  if (maxOutputInput) maxOutputInput.value = String(maxOutputTokens);
  aiAnalysisLoading = true;
  if (button) {
    button.disabled = true;
    button.textContent = '分析中…';
  }
  setAiAnalysisSettingsDisabled(true);
  if (host) host.textContent = '正在汇总数据并请求 Gemini 分析，请稍候…';
  try {
    const response = await fetch(ADMIN_API_BASE + '/ai/analyze-dashboard', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({
        model: model,
        panel: panel,
        thinkingLevel: thinkingLevel,
        maxOutputTokens: maxOutputTokens,
        analysisStyle: analysisStyle,
        customInstruction: customInstruction,
        stream: true
      })
    });
    if (!response.ok) {
      const responseText = await response.text();
      const data = parseResponseJson(responseText);
      throw new Error(adminErrorMessage(data.error, 'AI 数据分析失败（HTTP ' + response.status + '）'));
    }
    const data = await readAiAnalysisStream(response, host);
    if (host) {
      const context = DASHBOARD_AI_CONTEXT[data.panel || panel] || DASHBOARD_AI_CONTEXT[panel] || DASHBOARD_AI_CONTEXT['user-trends'];
      const usage = data.usage || {};
      const settings = data.settings || {};
      const finishWarning = data.finishReason === 'MAX_TOKENS'
        ? ' · **状态：** ⚠ 已达到输出上限，请提高最大输出 tokens'
        : '';
      const metadata = '\n\n**分析板块：** ' + context.title +
        ' · **模型：** ' + String(data.model || model) +
        ' · **思考：** ' + String(settings.thinkingLevel || thinkingLevel) +
        ' · **输出 tokens：** ' + String(usage.outputTokens || '-') + '/' + String(settings.maxOutputTokens || maxOutputTokens) +
        ' · **数据范围：** ' + String(data.dataWindow && data.dataWindow.startDate || '-') +
        ' 至 ' + String(data.dataWindow && data.dataWindow.endDate || '-') +
        finishWarning;
      host.innerHTML = renderAiMarkdown(String(data.analysis || '') + metadata);
      positionAiAnalysisDialog();
    }
    show('AI 数据分析完成');
  } catch (error) {
    if (host) host.textContent = error.message || 'AI 数据分析失败';
    show(error.message || 'AI 数据分析失败', true);
  } finally {
    aiAnalysisLoading = false;
    if (button) {
      button.disabled = false;
      button.textContent = '开始分析';
    }
    setAiAnalysisSettingsDisabled(false);
  }
}
function openAiAnalysis() {
  const overlay = el('aiAnalysisOverlay');
  if (!overlay) return;
  updateAiAnalysisContext(true);
  overlay.hidden = false;
  positionAiAnalysisDialog();
  window.requestAnimationFrame(positionAiAnalysisDialog);
  const analyzeButton = el('aiAnalyzeButton');
  if (analyzeButton) analyzeButton.focus();
}
function closeAiAnalysis(event) {
  if (event && event.target !== event.currentTarget) return;
  const overlay = el('aiAnalysisOverlay');
  if (overlay) overlay.hidden = true;
}
