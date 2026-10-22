import { invoke } from "@tauri-apps/api/core";
import { save } from "@tauri-apps/plugin-dialog";
import { detectSubscriptionInput, toStandardUris } from "./core/subscription-import/index.ts";

interface FetchedSubscription {
  content: string;
  finalUrl: string;
  contentType: string | null;
  subscriptionUserInfo: string | null;
  bytes: number;
  usedUserAgent: string;
}

interface FetchFailure {
  code?: string;
  message?: string;
  status?: number | null;
}

type SourceState = "pending" | "success" | "error";
type ProcessingState = "idle" | "running" | "done" | "warning" | "error";

interface SourceResult {
  url: string;
  state: SourceState;
  nodes: string[];
  bytes?: number;
  finalUrl?: string;
  usedUserAgent?: string;
  errorCode?: string;
  errorMessage?: string;
}

const MIN_FETCH_CONCURRENCY = 1;
const MAX_FETCH_CONCURRENCY = 20;
const MIN_RESPONSE_MIB = 1;
const MAX_RESPONSE_MIB = 64;
const MAX_REDIRECTS = 50;
const USER_AGENT_PRESETS: Record<string, string> = {
  nekobox: "NekoBox/Android/1.4.2 (Prefer ClashMeta Format)",
  "sing-box": "sing-box/1.15.0",
  clash: "clash-meta/1.19.0",
  v2rayn: "v2rayN/7.8.2",
};

class ConversionError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ConversionError";
  }
}

function getElement<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (!element) throw new Error(`缺少界面元素 #${id}`);
  return element as T;
}

const dom = {
  urls: getElement<HTMLTextAreaElement>("subscription-urls"),
  settingsMenu: getElement<HTMLDetailsElement>("settings-menu"),
  validation: getElement<HTMLDivElement>("validation-message"),
  userAgentSelect: getElement<HTMLSelectElement>("user-agent-select"),
  customUserAgent: getElement<HTMLInputElement>("custom-user-agent"),
  fetchConcurrency: getElement<HTMLInputElement>("fetch-concurrency"),
  maxResponseMib: getElement<HTMLInputElement>("max-response-mib"),
  maxRedirects: getElement<HTMLInputElement>("max-redirects"),
  skipCertificateVerification: getElement<HTMLButtonElement>("skip-certificate-verification"),
  skipCertificateNote: getElement<HTMLParagraphElement>("skip-certificate-note"),
  clearButton: getElement<HTMLButtonElement>("clear-button"),
  convertButton: getElement<HTMLButtonElement>("convert-button"),
  convertLabel: getElement<HTMLSpanElement>("convert-button").querySelector<HTMLSpanElement>(".button-label")!,
  progressCopy: getElement<HTMLDivElement>("progress-copy"),
  progressBar: getElement<HTMLSpanElement>("progress-bar"),
  processingStatus: getElement<HTMLSpanElement>("processing-status"),
  processingEmpty: getElement<HTMLDivElement>("processing-empty"),
  emptyState: getElement<HTMLDivElement>("empty-state"),
  emptyStateTitle: getElement<HTMLElement>("empty-state-title"),
  sourceList: getElement<HTMLDivElement>("source-list"),
  outputArea: getElement<HTMLDivElement>("output-area"),
  output: getElement<HTMLTextAreaElement>("output-uris"),
  outputSize: getElement<HTMLSpanElement>("output-size"),
  nodeCount: getElement<HTMLElement>("node-count"),
  sourceCount: getElement<HTMLElement>("source-count"),
  duplicateCount: getElement<HTMLElement>("duplicate-count"),
  copyButton: getElement<HTMLButtonElement>("copy-button"),
  saveButton: getElement<HTMLButtonElement>("save-button"),
  toast: getElement<HTMLDivElement>("toast"),
  brandHome: getElement<HTMLAnchorElement>("brand-home"),
};

let sourceResults: SourceResult[] = [];
let outputUris: string[] = [];
let duplicateCount = 0;
let running = false;
let completedSources = 0;
let toastTimer: number | undefined;

function setValidation(message = "", warning = false) {
  dom.validation.textContent = message;
  dom.validation.hidden = !message;
  dom.validation.classList.toggle("warning", warning);
}

function showToast(message: string, error = false) {
  window.clearTimeout(toastTimer);
  dom.toast.textContent = message;
  dom.toast.classList.toggle("error", error);
  dom.toast.classList.add("visible");
  toastTimer = window.setTimeout(() => dom.toast.classList.remove("visible"), 3200);
}

function formatBytes(bytes = 0): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function displayUrl(rawUrl: string): string {
  try {
    const url = new URL(rawUrl);
    return `${url.protocol}//${url.host}${url.pathname}${url.search ? "?…" : ""}`;
  } catch {
    return "无效地址";
  }
}

function resolveUserAgent(): string | null {
  const preset = dom.userAgentSelect.value;
  if (preset === "auto") return null;
  if (preset === "custom") {
    const custom = dom.customUserAgent.value.trim();
    if (!custom) {
      setValidation("已选择自定义 User-Agent，但输入内容为空。");
      dom.customUserAgent.focus();
      throw new ConversionError("invalid_user_agent", "自定义 User-Agent 不能为空。");
    }
    if (custom.length > 512 || /[\r\n]/.test(custom)) {
      setValidation("User-Agent 不能包含换行，且长度不能超过 512 个字符。");
      dom.customUserAgent.focus();
      throw new ConversionError("invalid_user_agent", "User-Agent 格式无效。");
    }
    return custom;
  }
  return USER_AGENT_PRESETS[preset] ?? null;
}

function readInputUrls(): string[] {
  const value = dom.urls.value.trim();
  if (!value) {
    setValidation("请先输入至少一个订阅 URL。");
    dom.urls.focus();
    return [];
  }

  const detected = detectSubscriptionInput(value);
  if (detected.urls.length === 0) {
    setValidation(
      detected.local.length > 0
        ? "当前版本只处理远程订阅 URL；检测到的是节点或本地配置。"
        : "未检测到有效的 HTTP / HTTPS 订阅地址。",
    );
    dom.urls.focus();
    return [];
  }

  setValidation();
  return detected.urls;
}

function resolveRequestLimits(): { maxResponseBytes: number; maxRedirects: number } {
  const maxResponseMib = dom.maxResponseMib.valueAsNumber;
  if (!Number.isInteger(maxResponseMib) || maxResponseMib < MIN_RESPONSE_MIB || maxResponseMib > MAX_RESPONSE_MIB) {
    dom.settingsMenu.open = true;
    setValidation(`响应上限必须是 ${MIN_RESPONSE_MIB} 到 ${MAX_RESPONSE_MIB} MiB 之间的整数。`);
    dom.maxResponseMib.focus();
    throw new ConversionError("invalid_response_limit", "响应上限设置无效。");
  }

  const maxRedirects = dom.maxRedirects.valueAsNumber;
  if (!Number.isInteger(maxRedirects) || maxRedirects < 0 || maxRedirects > MAX_REDIRECTS) {
    dom.settingsMenu.open = true;
    setValidation(`最大重定向必须是 0 到 ${MAX_REDIRECTS} 之间的整数。`);
    dom.maxRedirects.focus();
    throw new ConversionError("invalid_redirect_limit", "重定向设置无效。");
  }

  return { maxResponseBytes: maxResponseMib * 1024 * 1024, maxRedirects };
}

function resolveFetchConcurrency(): number {
  const concurrency = dom.fetchConcurrency.valueAsNumber;
  if (!Number.isInteger(concurrency) || concurrency < MIN_FETCH_CONCURRENCY || concurrency > MAX_FETCH_CONCURRENCY) {
    dom.settingsMenu.open = true;
    setValidation(`并发数量必须是 ${MIN_FETCH_CONCURRENCY} 到 ${MAX_FETCH_CONCURRENCY} 之间的整数。`);
    dom.fetchConcurrency.focus();
    throw new ConversionError("invalid_concurrency", "并发数量设置无效。");
  }
  return concurrency;
}

function shouldSkipCertificateVerification(): boolean {
  return dom.skipCertificateVerification.getAttribute("aria-pressed") === "true";
}

function toggleCertificateVerification() {
  const skip = !shouldSkipCertificateVerification();
  dom.skipCertificateVerification.setAttribute("aria-pressed", String(skip));
  dom.skipCertificateNote.hidden = !skip;
}

function setProcessingStatus(label: string, state: ProcessingState) {
  dom.processingStatus.textContent = label;
  dom.processingStatus.dataset.state = state;
}

function setBusy(busy: boolean) {
  running = busy;
  dom.convertButton.disabled = busy;
  dom.userAgentSelect.disabled = busy;
  dom.customUserAgent.disabled = busy;
  dom.fetchConcurrency.disabled = busy;
  dom.maxResponseMib.disabled = busy;
  dom.maxRedirects.disabled = busy;
  dom.skipCertificateVerification.disabled = busy;
  dom.convertButton.classList.toggle("busy", busy);
  dom.convertLabel.textContent = busy ? "正在获取与转换…" : "获取并转换";
  setProcessingStatus(busy ? "处理中" : "已完成", busy ? "running" : "done");
}

function updateProgress(done: number, total: number) {
  const percent = total === 0 ? 0 : Math.round((done / total) * 100);
  dom.progressBar.style.width = `${percent}%`;
  dom.progressCopy.textContent = running
    ? `正在处理 ${done} / ${total} 个来源 · ${percent}%`
    : `已处理 ${done} / ${total} 个来源`;
}

function createSourceCard(source: SourceResult): HTMLElement {
  const card = document.createElement("article");
  card.className = `source-card ${source.state}`;

  const main = document.createElement("div");
  main.className = "source-main";

  const url = document.createElement("div");
  url.className = "source-url";
  url.textContent = displayUrl(source.url);
  url.title = displayUrl(source.url);

  const meta = document.createElement("div");
  meta.className = "source-meta";
  if (source.state === "pending") {
    meta.textContent = "正在通过本机原生 HTTP 客户端拉取…";
  } else if (source.state === "success") {
    const usedUserAgent = source.usedUserAgent || "未知 User-Agent";
    const ua = usedUserAgent.length > 34 ? `${usedUserAgent.slice(0, 34)}…` : usedUserAgent;
    meta.textContent = `${source.nodes.length} 个节点 · ${formatBytes(source.bytes)} · ${ua}`;
    meta.title = `${usedUserAgent}${source.finalUrl ? `\n最终地址：${displayUrl(source.finalUrl)}` : ""}`;
  } else {
    meta.textContent = source.errorMessage || "订阅获取失败";
  }

  main.append(url, meta);

  const status = document.createElement("span");
  status.className = "source-status";
  status.textContent = source.state === "pending" ? "请求中" : source.state === "success" ? "完成" : "失败";

  card.append(main, status);
  return card;
}

function renderSources() {
  const hasSources = sourceResults.length > 0;
  dom.sourceList.replaceChildren(...sourceResults.map(createSourceCard));
  dom.sourceList.hidden = !hasSources;
  dom.processingEmpty.hidden = hasSources;
}

function aggregateResults() {
  const uniqueNodes = new Set<string>();
  duplicateCount = 0;

  for (const source of sourceResults) {
    if (source.state !== "success") continue;
    for (const node of source.nodes) {
      if (uniqueNodes.has(node)) duplicateCount++;
      else uniqueNodes.add(node);
    }
  }

  outputUris = [...uniqueNodes];
  const successfulSources = sourceResults.filter(source => source.state === "success").length;
  dom.nodeCount.textContent = String(outputUris.length);
  dom.sourceCount.textContent = String(successfulSources);
  dom.duplicateCount.textContent = String(duplicateCount);

  const hasOutput = outputUris.length > 0;
  dom.output.value = outputUris.join("\n");
  dom.outputSize.textContent = `${outputUris.length} 条 · ${formatBytes(new Blob([dom.output.value]).size)}`;
  dom.outputArea.hidden = !hasOutput;
  dom.emptyState.hidden = hasOutput;
  if (!hasOutput) {
    dom.emptyStateTitle.textContent = sourceResults.length > 0 ? "没有可用节点" : "等待输出";
  }
}
function failureFrom(error: unknown): { code: string; message: string } {
  if (error instanceof ConversionError) return { code: error.code, message: error.message };
  if (typeof error === "object" && error !== null) {
    const failure = error as FetchFailure;
    return {
      code: failure.code || "unknown_error",
      message: failure.message || "请求失败，请检查订阅地址和网络连接。",
    };
  }
  if (typeof error === "string" && error.trim()) {
    return { code: "unknown_error", message: error };
  }
  return { code: "unknown_error", message: "请求失败，请稍后重试。" };
}

async function runWithConcurrency<T>(
  items: T[],
  concurrency: number,
  worker: (item: T, index: number) => Promise<void>,
) {
  let cursor = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++;
      await worker(items[index], index);
    }
  });
  await Promise.all(workers);
}

async function startConversion() {
  if (running) return;

  const urls = readInputUrls();
  if (urls.length === 0) return;

  let userAgent: string | null;
  let fetchConcurrency: number;
  let requestLimits: { maxResponseBytes: number; maxRedirects: number };
  try {
    userAgent = resolveUserAgent();
    fetchConcurrency = resolveFetchConcurrency();
    requestLimits = resolveRequestLimits();
  } catch (error) {
    showToast(failureFrom(error).message, true);
    return;
  }

  dom.settingsMenu.open = false;

  sourceResults = urls.map(url => ({ url, state: "pending", nodes: [] }));
  outputUris = [];
  duplicateCount = 0;
  completedSources = 0;

  dom.nodeCount.textContent = "0";
  dom.sourceCount.textContent = "0";
  dom.duplicateCount.textContent = "0";
  dom.outputArea.hidden = true;
  dom.emptyState.hidden = false;
  dom.emptyStateTitle.textContent = "正在转换";
  renderSources();
  setBusy(true);
  updateProgress(0, urls.length);

  await runWithConcurrency(urls, fetchConcurrency, async (url, index) => {
    try {
      const fetched = await invoke<FetchedSubscription>("fetch_subscription", {
        url,
        userAgent,
        skipCertificateVerification: shouldSkipCertificateVerification(),
        maxResponseBytes: requestLimits.maxResponseBytes,
        maxRedirects: requestLimits.maxRedirects,
      });
      const nodes = toStandardUris(fetched.content).filter(node => !/^https?:\/\//i.test(node));
      if (nodes.length === 0) {
        throw new ConversionError(
          "no_nodes",
          "订阅内容已获取，但未找到可转换的节点；请检查订阅格式或更换 User-Agent。",
        );
      }

      sourceResults[index] = {
        url,
        state: "success",
        nodes,
        bytes: fetched.bytes,
        finalUrl: fetched.finalUrl,
        usedUserAgent: fetched.usedUserAgent,
      };
    } catch (error) {
      const failure = failureFrom(error);
      sourceResults[index] = {
        url,
        state: "error",
        nodes: [],
        errorCode: failure.code,
        errorMessage: failure.message,
      };
    } finally {
      completedSources++;
      renderSources();
      updateProgress(completedSources, urls.length);
    }
  });

  aggregateResults();
  setBusy(false);
  updateProgress(urls.length, urls.length);

  const successful = sourceResults.filter(source => source.state === "success").length;
  if (successful === urls.length) setProcessingStatus("全部完成", "done");
  else if (successful > 0) setProcessingStatus("部分完成", "warning");
  else setProcessingStatus("处理失败", "error");

  if (outputUris.length > 0) {
    dom.progressCopy.textContent = `转换完成 · ${successful} 个来源 · ${outputUris.length} 个唯一节点`;
    showToast(`已生成 ${outputUris.length} 个节点 URI`);
  } else {
    dom.progressCopy.textContent = "转换结束，但没有获得可用节点";
    showToast("没有获得可用节点，请查看各来源的错误信息", true);
  }
}
async function copyText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    return;
  } catch {
    const fallback = document.createElement("textarea");
    fallback.value = value;
    fallback.style.position = "fixed";
    fallback.style.opacity = "0";
    document.body.append(fallback);
    fallback.select();
    const copied = document.execCommand("copy");
    fallback.remove();
    if (!copied) throw new Error("clipboard_unavailable");
  }
}

async function copyAll() {
  if (outputUris.length === 0) return;
  try {
    await copyText(dom.output.value);
    showToast(`已复制 ${outputUris.length} 个节点 URI`);
  } catch {
    showToast("复制失败，请手动选择文本框内容", true);
  }
}

function defaultSaveName(): string {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `chimera-subscriptions-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}.txt`;
}

async function saveAll() {
  if (outputUris.length === 0) return;
  try {
    const path = await save({
      defaultPath: defaultSaveName(),
      filters: [{ name: "文本文件", extensions: ["txt"] }],
    });
    if (!path) return;
    await invoke<string>("save_text_file", { path, content: dom.output.value });
    showToast(`已保存 ${outputUris.length} 个节点 URI`);
  } catch (error) {
    showToast(failureFrom(error).message || "保存文件失败", true);
  }
}

function resetView(clearInput: boolean) {
  if (running) {
    showToast("请等待当前转换任务完成", true);
    return;
  }
  sourceResults = [];
  outputUris = [];
  duplicateCount = 0;
  completedSources = 0;
  if (clearInput) dom.urls.value = "";
  setValidation();
  dom.nodeCount.textContent = "0";
  dom.sourceCount.textContent = "0";
  dom.duplicateCount.textContent = "0";
  dom.progressBar.style.width = "0%";
  dom.progressCopy.textContent = "准备就绪";
  dom.sourceList.hidden = true;
  dom.sourceList.replaceChildren();
  dom.processingEmpty.hidden = false;
  setProcessingStatus("待开始", "idle");
  dom.outputArea.hidden = true;
  dom.emptyState.hidden = false;
  dom.emptyStateTitle.textContent = "等待输出";
  dom.output.value = "";
  dom.outputSize.textContent = "";
}

function bindEvents() {
  dom.convertButton.addEventListener("click", () => void startConversion());
  dom.copyButton.addEventListener("click", () => void copyAll());
  dom.saveButton.addEventListener("click", () => void saveAll());
  dom.clearButton.addEventListener("click", () => resetView(true));
  dom.brandHome.addEventListener("click", event => {
    event.preventDefault();
    dom.urls.focus();
  });

  document.addEventListener("click", event => {
    const target = event.target;
    if (dom.settingsMenu.open && target instanceof Node && !dom.settingsMenu.contains(target)) {
      dom.settingsMenu.open = false;
    }
  });

  document.addEventListener("keydown", event => {
    if (event.key !== "Escape" || !dom.settingsMenu.open) return;
    dom.settingsMenu.open = false;
    dom.settingsMenu.querySelector<HTMLElement>("summary")?.focus();
  });

  dom.urls.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
      event.preventDefault();
      void startConversion();
    }
  });

  dom.userAgentSelect.addEventListener("change", () => {
    dom.customUserAgent.hidden = dom.userAgentSelect.value !== "custom";
    if (!dom.customUserAgent.hidden) dom.customUserAgent.focus();
  });

  dom.skipCertificateVerification.addEventListener("click", toggleCertificateVerification);

  for (const input of [dom.fetchConcurrency, dom.maxResponseMib, dom.maxRedirects]) {
    input.addEventListener("input", () => {
      if (!dom.validation.hidden) setValidation();
    });
  }

  dom.urls.addEventListener("input", () => {
    if (!dom.validation.hidden) setValidation();
  });
}

bindEvents();