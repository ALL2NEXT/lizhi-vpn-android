import type { Env } from "../types/env.ts";
import type { SubscriptionGroup, SubscriptionSource } from "../types/models.ts";

import {
  DEFAULT_SUBSCRIPTION_CACHE_MINUTES,
  MAX_MANAGED_SUBSCRIPTION_BYTES,
  MAX_SUBSCRIPTION_CACHE_MINUTES,
  PLAINTEXT_NODE_PATTERN,
  SUBSCRIPTION_CACHE_KEY_PREFIX,
  TEXT_ENCODER,
} from "../config.ts";
import { cleanText } from "../lib/values.ts";
import { normalizeRemoteUrl, toStandardUris } from "../domain/subscription-import/index.ts";
import { decodeBase64, encodeBase64 } from "../domain/subscription-import/text.ts";
import {
  loadSubscriptionCacheMetadata,
  saveSubscriptionCacheMetadata,
} from "./subscription-cache-store.ts";
import { observedFetch } from "./system-logs.ts";

export function normalizeManagedContent(value: unknown) {
  if (typeof value !== "string") return "";
  const content = value.replace(/\r\n?/g, "\n").trim();
  if (TEXT_ENCODER.encode(content).length > MAX_MANAGED_SUBSCRIPTION_BYTES) {
    throw new Error("managed_subscription_too_large");
  }
  return content;
}

export function normalizeSubscriptionCacheMinutes(value: unknown) {
  if (value === undefined || value === null || value === "") {
    return DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
  }
  const minutes = Number(value);
  return Number.isFinite(minutes)
    ? Math.min(Math.max(Math.round(minutes), 0), MAX_SUBSCRIPTION_CACHE_MINUTES)
    : DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
}

export function normalizeMinimumAppVersion(value: unknown) {
  const version = cleanText(value).replace(/^v/i, "").slice(0, 40);
  if (!version) return "";
  if (!/^\d+(?:\.\d+)*$/.test(version)) {
    throw new Error("invalid_min_app_version");
  }
  return version;
}

export function appVersionParts(value: unknown) {
  const version = cleanText(value).replace(/^v/i, "");
  if (!/^\d+(?:\.\d+)*$/.test(version)) return null;
  return version.split(".").map((part) => Number(part));
}

export function isAppVersionAtLeast(currentVersion: unknown, minimumVersion: unknown) {
  const current = appVersionParts(currentVersion);
  const minimum = appVersionParts(minimumVersion);
  if (!current || !minimum) return false;
  const length = Math.max(current.length, minimum.length);
  for (let index = 0; index < length; index += 1) {
    const currentPart = current[index] || 0;
    const minimumPart = minimum[index] || 0;
    if (currentPart !== minimumPart) return currentPart > minimumPart;
  }
  return true;
}

export function assertPlainSubscriptionContent(value: unknown) {
  const content = normalizeManagedContent(value);
  if (!content || !PLAINTEXT_NODE_PATTERN.test(content)) {
    throw new Error("subscription_must_be_plaintext_nodes");
  }
  return content;
}

/** 本地导入与远程更新使用相同的 URI / Base64 / Clash / sing-box / Xray 转换。 */
export function decodeExternalSubscriptionContent(value: unknown, options: { deduplicate?: boolean } = {}) {
  const nodes = toStandardUris(normalizeManagedContent(value), options);
  if (!nodes.length) throw new Error("subscription_must_be_plaintext_nodes");
  return normalizeManagedContent(nodes.join("\n"));
}

// 常见节点命名中的国家别名，用于把外部订阅节点统一整理成“国家+编号”。
const COUNTRY_DEFINITIONS: [string, string, string[]][] = [
  ["CN", "中国", ["中国大陆", "中国", "china", "chn", "cn"]],
  ["US", "美国", ["美国", "usa", "united states", "united states of america", "us"]],
  ["JP", "日本", ["日本", "japan", "jpn", "jp"]],
  ["KR", "韩国", ["韩国", "南韩", "korea", "south korea", "kor", "kr"]],
  ["KP", "朝鲜", ["朝鲜", "north korea", "kp"]],
  ["TW", "台湾", ["台湾", "台灣", "taiwan", "twn", "tw"]],
  ["HK", "香港", ["香港", "hong kong", "hkg", "hk"]],
  ["MO", "澳门", ["澳门", "澳門", "macao", "macau", "mo"]],
  ["SG", "新加坡", ["新加坡", "singapore", "sgp", "sg"]],
  ["GB", "英国", ["英国", "英國", "uk", "united kingdom", "great britain", "gb"]],
  ["DE", "德国", ["德国", "德國", "germany", "deu", "de"]],
  ["FR", "法国", ["法国", "法國", "france", "fra", "fr"]],
  ["RU", "俄罗斯", ["俄罗斯", "俄羅斯", "russia", "rus", "ru"]],
  ["CA", "加拿大", ["加拿大", "canada", "can"]],
  ["AU", "澳大利亚", ["澳大利亚", "澳洲", "australia", "aus", "au"]],
  ["NL", "荷兰", ["荷兰", "荷蘭", "netherlands", "holland", "nld", "nl"]],
  ["IN", "印度", ["印度", "india", "ind"]],
  ["TH", "泰国", ["泰国", "泰國", "thailand", "tha", "th"]],
  ["VN", "越南", ["越南", "vietnam", "vnm", "vn"]],
  ["PH", "菲律宾", ["菲律宾", "菲律賓", "philippines", "phl", "ph"]],
  ["MY", "马来西亚", ["马来西亚", "馬來西亞", "malaysia", "mys", "my"]],
  ["ID", "印度尼西亚", ["印度尼西亚", "印度尼西亞", "indonesia", "idn", "id"]],
  ["TR", "土耳其", ["土耳其", "土耳其", "turkey", "turkiye", "tur", "tr"]],
  ["CH", "瑞士", ["瑞士", "switzerland", "che", "ch"]],
  ["SE", "瑞典", ["瑞典", "sweden", "swe", "se"]],
  ["NO", "挪威", ["挪威", "norway", "nor", "no"]],
  ["FI", "芬兰", ["芬兰", "芬蘭", "finland", "fin", "fi"]],
  ["DK", "丹麦", ["丹麦", "丹麥", "denmark", "dnk", "dk"]],
  ["PL", "波兰", ["波兰", "波蘭", "poland", "pol", "pl"]],
  ["UA", "乌克兰", ["乌克兰", "烏克蘭", "ukraine", "ukr", "ua"]],
  ["IL", "以色列", ["以色列", "israel", "isr", "il"]],
  ["ZA", "南非", ["南非", "south africa", "zaf", "za"]],
  ["MX", "墨西哥", ["墨西哥", "mexico", "mex", "mx"]],
  ["BR", "巴西", ["巴西", "brazil", "bra", "br"]],
  ["CL", "智利", ["智利", "chile", "chl", "cl"]],
  ["AR", "阿根廷", ["阿根廷", "argentina", "arg", "ar"]],
  ["NZ", "新西兰", ["新西兰", "紐西蘭", "new zealand", "nzl", "nz"]],
  ["IE", "爱尔兰", ["爱尔兰", "愛爾蘭", "ireland", "irl", "ie"]],
  ["ES", "西班牙", ["西班牙", "spain", "esp", "es"]],
  ["IT", "意大利", ["意大利", "意大利", "italy", "ita", "it"]],
  ["PT", "葡萄牙", ["葡萄牙", "portugal", "prt", "pt"]],
  ["AT", "奥地利", ["奥地利", "奧地利", "austria", "aut", "at"]],
  ["BE", "比利时", ["比利时", "比利時", "belgium", "bel", "be"]],
  ["CZ", "捷克", ["捷克", "czech", "czechia", "cze", "cz"]],
  ["RO", "罗马尼亚", ["罗马尼亚", "羅馬尼亞", "romania", "rou", "ro"]],
  ["HU", "匈牙利", ["匈牙利", "hungary", "hun", "hu"]],
  ["IS", "冰岛", ["冰岛", "冰島", "iceland", "isl", "is"]],
  ["AE", "阿联酋", ["阿联酋", "阿聯酋", "uae", "united arab emirates", "are", "ae"]],
  ["SA", "沙特阿拉伯", ["沙特阿拉伯", "沙特", "saudi arabia", "sau", "sa"]],
  ["PK", "巴基斯坦", ["巴基斯坦", "pakistan", "pak", "pk"]],
  ["BD", "孟加拉国", ["孟加拉国", "孟加拉國", "bangladesh", "bgd", "bd"]],
  ["KH", "柬埔寨", ["柬埔寨", "cambodia", "khm", "kh"]],
  ["LA", "老挝", ["老挝", "老撾", "laos", "lao", "la"]],
  ["MM", "缅甸", ["缅甸", "緬甸", "myanmar", "mmr", "mm"]],
];

const COUNTRY_BY_CODE = new Map(
  COUNTRY_DEFINITIONS.map(([code, name]) => [code, { code, name }]),
);
const COUNTRY_ALIASES = COUNTRY_DEFINITIONS
  .flatMap(([code, name, aliases]) => aliases.map((alias) => ({ code, name, alias })));
const UNKNOWN_COUNTRY = { code: "UN", name: "未知" };
const NODE_LINK_PATTERN = /^(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|masque|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+$/i;

function normalizeNodeName(value: unknown) {
  return String(value || "").trim().replace(/\s+/g, " ").toLowerCase();
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function matchesCountryAlias(name: string, alias: string) {
  if (/^[a-z]+$/i.test(alias)) {
    const pattern = new RegExp(
      `(^|[^a-z])${escapeRegExp(alias)}(?=$|[^a-z]|\\d)`,
      "i",
    );
    return pattern.test(name);
  }
  return name.includes(alias);
}

function countryFromFlag(name: string) {
  const match = String(name || "").match(/([\u{1F1E6}-\u{1F1FF}]){2}/u);
  if (!match) return null;
  const code = Array.from(match[0])
    .map((character) => String.fromCharCode(character.codePointAt(0)! - 0x1f1e6 + 65))
    .join("");
  return COUNTRY_BY_CODE.get(code) || null;
}

function findCountry(name: string) {
  const flagCountry = countryFromFlag(name);
  if (flagCountry) return flagCountry;

  const normalized = normalizeNodeName(name);
  if (!normalized) return UNKNOWN_COUNTRY;
  const match = COUNTRY_ALIASES
    .filter(({ alias }) => matchesCountryAlias(normalized, alias))
    .sort((left, right) => right.alias.length - left.alias.length)[0];
  return match ? { code: match.code, name: match.name } : UNKNOWN_COUNTRY;
}

function decodeBase64Utf8(value: string) {
  const normalized = String(value || "")
    .trim()
    .replace(/-/g, "+")
    .replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function encodeBase64Utf8(value: string) {
  const bytes = TEXT_ENCODER.encode(value);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}

function decodeUriFragment(value: string) {
  try {
    return decodeURIComponent(value.replace(/\+/g, " "));
  } catch (_) {
    return value;
  }
}

function extractNodeName(link: string) {
  const trimmed = link.trim();
  if (/^vmess:\/\//i.test(trimmed)) {
    try {
      const payload = trimmed.slice(trimmed.indexOf("://") + 3).split("#", 1)[0];
      const value = JSON.parse(decodeBase64Utf8(payload));
      return String(value.ps || value.name || "");
    } catch (_) {
      // 兼容损坏或非标准 VMess 节点，继续尝试读取 URL 片段。
    }
  }
  const hashIndex = trimmed.indexOf("#");
  return hashIndex >= 0 ? decodeUriFragment(trimmed.slice(hashIndex + 1)) : "";
}

function renameNodeLink(link: string, name: string) {
  const trimmed = link.trim();
  if (/^vmess:\/\//i.test(trimmed)) {
    try {
      const prefixEnd = trimmed.indexOf("://") + 3;
      const payload = trimmed.slice(prefixEnd).split("#", 1)[0];
      const value = JSON.parse(decodeBase64Utf8(payload));
      value.ps = name;
      return `${trimmed.slice(0, prefixEnd)}${encodeBase64Utf8(JSON.stringify(value))}`;
    } catch (_) {
      // 无法解码时退回到 URL 片段改名，不能让单个异常节点阻断整条订阅。
    }
  }

  const hashIndex = trimmed.indexOf("#");
  const base = hashIndex >= 0 ? trimmed.slice(0, hashIndex) : trimmed;
  return `${base}#${encodeURIComponent(name)}`;
}

export function autoRenameSubscriptionContent(content: string) {
  const counters = new Map<string, number>();
  return String(content || "").split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!NODE_LINK_PATTERN.test(trimmed)) return line;

    const country = findCountry(extractNodeName(trimmed));
    const nextNumber = (counters.get(country.code) || 0) + 1;
    counters.set(country.code, nextNumber);
    return renameNodeLink(trimmed, `${country.name}${nextNumber}`);
  }).join("\n");
}

export function deduplicateSubscriptionContent(content: string) {
  const seen = new Set<string>();
  return String(content || "").split(/\r?\n/).filter((line) => {
    const trimmed = line.trim();
    if (!NODE_LINK_PATTERN.test(trimmed)) return true;
    if (seen.has(trimmed)) return false;
    seen.add(trimmed);
    return true;
  }).join("\n");
}

export function subscriptionCacheKey(source: SubscriptionSource) {
  return `${SUBSCRIPTION_CACHE_KEY_PREFIX}${source.id}`;
}

function subscriptionTrafficCacheKey(source: SubscriptionSource) {
  return `${subscriptionCacheKey(source)}:traffic`;
}

export interface SubscriptionTrafficMetadata {
  uploadBytes: number | null;
  downloadBytes: number | null;
  totalBytes: number | null;
  expireAtMillis: number | null;
  remainingBytes?: number | null;
  resetAtMillis?: number | null;
}

// 兼容客户端读取的 Subscription-Userinfo 头，数字字段统一转换为字节和毫秒。
function parseSubscriptionUserInfo(value: string | null): SubscriptionTrafficMetadata | null {
  if (!value) return null;
  const fields = new Map<string, number>();
  for (const part of value.split(/[;,]/)) {
    const separator = part.indexOf("=");
    if (separator <= 0) continue;
    const key = part.slice(0, separator).trim().toLowerCase();
    if (!["upload", "download", "total", "expire"].includes(key)) continue;
    const raw = part.slice(separator + 1).trim();
    if (!/^\d+$/.test(raw)) continue;
    const number = Number(raw);
    if (!Number.isSafeInteger(number) || number < 0) continue;
    fields.set(key, number);
  }
  if (!fields.size) return null;
  const expireSeconds = fields.get("expire") || 0;
  const expireAtMillis = expireSeconds > 0 && expireSeconds <= Math.floor(Number.MAX_SAFE_INTEGER / 1000)
    ? expireSeconds * 1000
    : null;
  return {
    uploadBytes: fields.get("upload") ?? null,
    downloadBytes: fields.get("download") ?? null,
    totalBytes: fields.get("total") ?? null,
    expireAtMillis,
  };
}

function cachedSubscriptionTraffic(value: unknown): SubscriptionTrafficMetadata | null {
  if (!value || typeof value !== "object") return null;
  const metadata = value as Partial<SubscriptionTrafficMetadata>;
  const normalize = (item: unknown) => {
    if (item === null || item === undefined) return null;
    const number = Number(item);
    return Number.isSafeInteger(number) && number >= 0 ? number : null;
  };
  const traffic = {
    uploadBytes: normalize(metadata.uploadBytes),
    downloadBytes: normalize(metadata.downloadBytes),
    totalBytes: normalize(metadata.totalBytes),
    expireAtMillis: normalize(metadata.expireAtMillis),
    remainingBytes: normalize(metadata.remainingBytes),
    resetAtMillis: normalize(metadata.resetAtMillis),
  };
  return traffic.uploadBytes !== null || traffic.downloadBytes !== null ||
      traffic.totalBytes !== null || traffic.expireAtMillis !== null ||
      traffic.remainingBytes !== null || traffic.resetAtMillis !== null
    ? traffic
    : null;
}

interface LegacySubscriptionCache {
  url: string;
  content: string;
  cachedAt: number;
  trafficFetched: boolean;
  traffic: unknown;
}

function legacySubscriptionCache(value: string): LegacySubscriptionCache | null {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object") return null;
    const record = parsed as Record<string, unknown>;
    if (typeof record.url !== "string" || typeof record.content !== "string") return null;
    const cachedAt = Number(record.cachedAt);
    return {
      url: record.url,
      content: record.content,
      cachedAt: Number.isSafeInteger(cachedAt) && cachedAt >= 0 ? cachedAt : Date.now(),
      trafficFetched: record.trafficFetched === true,
      traffic: record.traffic,
    };
  } catch {
    return null;
  }
}

async function readSubscriptionCache(env: Env, source: SubscriptionSource) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  let metadata = await loadSubscriptionCacheMetadata(env, source.id);
  let contentValue = await env.CONFIG.get(subscriptionCacheKey(source));
  let legacyUrl = "";
  const legacy = typeof contentValue === "string" ? legacySubscriptionCache(contentValue) : null;
  if (legacy) {
    const sameUrl = !metadata || metadata.sourceUrl === legacy.url;
    const legacyIsCurrent = sameUrl && (!metadata?.contentCachedAt ||
      legacy.cachedAt >= metadata.contentCachedAt);
    if (legacyIsCurrent) {
      await saveSubscriptionCacheMetadata(env, source.id, legacy.url, {
        contentCachedAt: legacy.cachedAt,
      });
      if (legacy.trafficFetched) {
        const storedTrafficAt = metadata?.sourceUrl === legacy.url
          ? metadata.trafficFetchedAt || 0
          : 0;
        if (legacy.cachedAt >= storedTrafficAt) {
          await saveSubscriptionCacheMetadata(env, source.id, legacy.url, {
            trafficFetchedAt: legacy.cachedAt,
            traffic: legacy.traffic,
          });
        }
      }
      await env.CONFIG.put(subscriptionCacheKey(source), legacy.content);
      contentValue = legacy.content;
      legacyUrl = legacy.url;
    } else {
      await env.CONFIG.delete(subscriptionCacheKey(source));
      contentValue = null;
    }
    metadata = await loadSubscriptionCacheMetadata(env, source.id);
  }

  const legacyTrafficValue = await env.CONFIG.get(subscriptionTrafficCacheKey(source));
  if (typeof legacyTrafficValue === "string") {
    try {
      const parsed: unknown = JSON.parse(legacyTrafficValue);
      if (parsed && typeof parsed === "object") {
        const record = parsed as Record<string, unknown>;
        const fetchedAt = Number(record.trafficFetchedAt);
        if (record.url === source.url && (!metadata || metadata.sourceUrl === source.url) &&
            Number.isSafeInteger(fetchedAt) && fetchedAt >= 0 &&
            fetchedAt > (metadata?.trafficFetchedAt || 0)) {
          await saveSubscriptionCacheMetadata(env, source.id, source.url, {
            trafficFetchedAt: fetchedAt,
            traffic: record.traffic,
          });
        }
      }
    } catch {
      // Invalid legacy metadata is discarded below.
    }
    await env.CONFIG.delete(subscriptionTrafficCacheKey(source));
  }

  metadata = await loadSubscriptionCacheMetadata(env, source.id);
  const metadataMatches = metadata?.sourceUrl === source.url;
  const legacyMatches = legacyUrl === source.url;
  return {
    content: (metadataMatches || legacyMatches) && typeof contentValue === "string"
      ? contentValue
      : null,
    metadata,
    contentCachedAt: metadataMatches
      ? metadata?.contentCachedAt ?? null
      : legacyMatches
        ? legacy?.cachedAt ?? null
        : null,
  };
}

async function cacheExternalSubscription(
  env: Env,
  source: SubscriptionSource,
  result: { content: string; traffic: SubscriptionTrafficMetadata | null },
  cachedAt: number,
) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  await env.CONFIG.put(subscriptionCacheKey(source), result.content);
  await saveSubscriptionCacheMetadata(env, source.id, source.url, {
    contentCachedAt: cachedAt,
    trafficFetchedAt: cachedAt,
    traffic: result.traffic,
  });
}

async function readManagedSubscriptionBody(response: Response) {
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_MANAGED_SUBSCRIPTION_BYTES) {
    await response.body?.cancel();
    throw new Error("managed_subscription_too_large");
  }
  if (!response.body) return "";

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let receivedBytes = 0;
  let content = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    receivedBytes += value.byteLength;
    if (receivedBytes > MAX_MANAGED_SUBSCRIPTION_BYTES) {
      await reader.cancel("managed_subscription_too_large");
      throw new Error("managed_subscription_too_large");
    }
    content += decoder.decode(value, { stream: true });
  }
  return content + decoder.decode();
}

// 与 Android 私人源 AUTO 模式相同，兼容按 User-Agent 返回不同格式的机场。
const SUBSCRIPTION_USER_AGENTS = [
  "NekoBox/Android/1.4.2 (Prefer ClashMeta Format)",
  "sing-box/1.15.0", "Singbox/1.14", "clash-meta", "v2rayN/7.8.2",
  "Throne/1.0.0", "sing-box/1.14.0",
];

function subscriptionName(headers: Headers, url: string) {
  const decodeTitle = (value: string) => {
    const title = value.trim().replace(/^"|"$/g, "");
    return /^base64:/i.test(title) ? decodeBase64(title.slice(7))?.trim() || "" : title;
  };
  for (const key of ["profile-title", "subscription-name"]) {
    const value = headers.get(key)?.trim();
    if (value && decodeTitle(value)) return decodeTitle(value).slice(0, 120);
  }
  const disposition = headers.get("content-disposition") || "";
  const extended = disposition.match(/(?:^|;)\s*filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1];
  const filename = extended || disposition.match(/(?:^|;)\s*filename\s*=\s*("[^"]*"|[^;]+)/i)?.[1];
  if (filename) {
    let title = filename.trim().replace(/^"|"$/g, "");
    if (extended) { try { title = decodeURIComponent(title); } catch { title = ""; } }
    title = title.split(/[\\/]/).pop()!.replace(/\.(?:yaml|yml|txt|conf)$/i, "").trim();
    if (title) return title.slice(0, 120);
  }
  return new URL(url).hostname.replace(/^www\./, "");
}

export async function fetchExternalSubscription(url: string, options: { deduplicate?: boolean; env?: Env } = {}) {
  const normalized = normalizeRemoteUrl(url);
  if (!normalized) throw new Error("invalid_subscription_url");
  const target = new URL(normalized);
  const authorization = target.username || target.password ? "Basic " + encodeBase64(`${decodeURIComponent(target.username)}:${decodeURIComponent(target.password)}`) : "";
  target.username = ""; target.password = "";
  let failure = new Error("external_subscription_unreachable");
  // 一条来源的自动尝试共享 25 秒，避免失败来源长时间占据整个导入请求。
  const deadline = Date.now() + 25000;
  for (const userAgent of SUBSCRIPTION_USER_AGENTS) {
    if (Date.now() >= deadline) break;
    try {
      const response = await observedFetch(options.env, target.href, {
        method: "GET", redirect: "follow", signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())),
        headers: { accept: "text/plain, application/yaml, application/json, */*;q=0.1", "user-agent": userAgent, ...(authorization ? { authorization } : {}) },
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw new Error(`external_subscription_http_${response.status}`);
      }
      const traffic = parseSubscriptionUserInfo(response.headers.get("subscription-userinfo"));
      const content = decodeExternalSubscriptionContent(await readManagedSubscriptionBody(response), options);
      return {
        content,
        name: subscriptionName(response.headers, normalized),
        nodeCount: content.split("\n").length,
        traffic,
      };
    } catch (error) {
      if (error instanceof Error && error.message === "managed_subscription_too_large") throw error;
      failure = error instanceof Error && /^(?:external_subscription_http_|subscription_must_be_plaintext_nodes)/.test(error.message)
        ? error : new Error("external_subscription_unreachable");
    }
  }
  throw failure;
}

export async function readExternalSubscription(
  env: Env,
  source: SubscriptionSource,
  cacheMinutesValue: unknown = DEFAULT_SUBSCRIPTION_CACHE_MINUTES,
  updateEnabled = true,
) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  if (!source || !source.url) throw new Error("external_subscription_url_required");

  const cacheMinutes = normalizeSubscriptionCacheMinutes(cacheMinutesValue);
  const now = Date.now();
  const cached = await readSubscriptionCache(env, source);
  if (!updateEnabled) {
    if (typeof cached.content === "string") {
      return decodeExternalSubscriptionContent(cached.content, { deduplicate: false });
    }
    throw new Error("subscription_update_disabled_cache_missing");
  }
  if (cacheMinutes > 0) {
    const cachedAt = Number(cached.metadata?.contentCachedAt);
    const freshUntil = cachedAt + cacheMinutes * 60 * 1000;
    if (
      typeof cached.content === "string" &&
      cached.metadata?.sourceUrl === source.url &&
      Number.isFinite(cachedAt) &&
      freshUntil > now
    ) {
      return decodeExternalSubscriptionContent(cached.content, { deduplicate: false });
    }
  }

  const fetched = await fetchExternalSubscription(source.url, { deduplicate: false, env });
  await cacheExternalSubscription(env, source, fetched, Date.now());
  return fetched.content;
}

export async function isExternalSubscriptionRefreshDue(
  env: Env,
  source: SubscriptionSource,
  cacheMinutesValue: unknown,
  updateEnabled: boolean,
) {
  if (!updateEnabled || !source?.url) return false;
  const cacheMinutes = normalizeSubscriptionCacheMinutes(cacheMinutesValue);
  if (cacheMinutes <= 0) return true;

  const cached = await readSubscriptionCache(env, source);
  const cachedAt = Number(cached.contentCachedAt);
  return typeof cached.content !== "string" || !Number.isFinite(cachedAt) ||
    cachedAt + cacheMinutes * 60 * 1000 <= Date.now();
}

export async function readExternalSubscriptionTraffic(
  env: Env,
  source: SubscriptionSource,
  cacheMinutesValue: unknown = DEFAULT_SUBSCRIPTION_CACHE_MINUTES,
  updateEnabled = true,
): Promise<SubscriptionTrafficMetadata | null> {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  if (!source?.url) return null;

  const cacheMinutes = normalizeSubscriptionCacheMinutes(cacheMinutesValue);
  const now = Date.now();
  const cached = await readSubscriptionCache(env, source);
  const metadata = cached.metadata?.sourceUrl === source.url ? cached.metadata : null;
  const trafficFetchedAt = Number(metadata?.trafficFetchedAt);
  const trafficCacheMinutes = cacheMinutes > 0 ? cacheMinutes : 5;
  if (metadata && Number.isFinite(trafficFetchedAt) && trafficFetchedAt > 0 &&
      (!updateEnabled || trafficFetchedAt + trafficCacheMinutes * 60 * 1000 > now)) {
    return cachedSubscriptionTraffic(metadata.traffic);
  }
  if (!updateEnabled) return null;

  const fetched = await fetchExternalSubscription(source.url, { deduplicate: false, env });
  await cacheExternalSubscription(env, source, fetched, Date.now());
  return fetched.traffic;
}

export async function validateAndWarmSubscriptionSources(env: Env, groups: SubscriptionGroup[]) {
  for (const group of groups) {
    for (const source of group.subscriptions) {
      if (source.enabled === false) continue;
      // 后台直接维护的文本订阅原样保存，不要求内容匹配特定节点协议。
      if (source.url && !source.content) {
        if (!group.updateEnabled) {
          if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
          const cached = await readSubscriptionCache(env, source);
          if (typeof cached.content !== "string") {
            // 尚无可用快照时尝试预热一次；失败不阻止关闭后续更新。
            try {
              await readExternalSubscription(env, source, group.cacheMinutes, true);
            } catch {
              // 无快照时客户端会收到明确的缓存缺失错误。
            }
          }
          continue;
        }
        await readExternalSubscription(env, source, group.cacheMinutes, group.updateEnabled);
      }
    }
  }
}
