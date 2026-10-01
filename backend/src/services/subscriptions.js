import {
  DEFAULT_SUBSCRIPTION_CACHE_MINUTES,
  MAX_MANAGED_SUBSCRIPTION_BYTES,
  MAX_SUBSCRIPTION_CACHE_MINUTES,
  PLAINTEXT_NODE_PATTERN,
  PRIVATE_B64_ALPHABET,
  SUBSCRIPTION_CACHE_KEY_PREFIX,
  TEXT_ENCODER,
} from "../config.js";
import { cleanText } from "../lib/values.js";

export function normalizeManagedContent(value) {
  if (typeof value !== "string") return "";
  const content = value.replace(/\r\n?/g, "\n").trim();
  if (TEXT_ENCODER.encode(content).length > MAX_MANAGED_SUBSCRIPTION_BYTES) {
    throw new Error("managed_subscription_too_large");
  }
  return content;
}

export function normalizeSubscriptionCacheMinutes(value) {
  if (value === undefined || value === null || value === "") {
    return DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
  }
  const minutes = Number(value);
  return Number.isFinite(minutes)
    ? Math.min(Math.max(Math.round(minutes), 0), MAX_SUBSCRIPTION_CACHE_MINUTES)
    : DEFAULT_SUBSCRIPTION_CACHE_MINUTES;
}

export function normalizeMinimumAppVersion(value) {
  const version = cleanText(value).replace(/^v/i, "").slice(0, 40);
  if (!version) return "";
  if (!/^\d+(?:\.\d+)*$/.test(version)) {
    throw new Error("invalid_min_app_version");
  }
  return version;
}

export function appVersionParts(value) {
  const version = cleanText(value).replace(/^v/i, "");
  if (!/^\d+(?:\.\d+)*$/.test(version)) return null;
  return version.split(".").map((part) => Number(part));
}

export function isAppVersionAtLeast(currentVersion, minimumVersion) {
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

export function assertPlainSubscriptionContent(value) {
  const content = normalizeManagedContent(value);
  if (!content || !PLAINTEXT_NODE_PATTERN.test(content)) {
    throw new Error("subscription_must_be_plaintext_nodes");
  }
  return content;
}

/** 外部订阅同时兼容明文、标准 Base64 和 URL-safe Base64。 */
export function decodeExternalSubscriptionContent(value) {
  const content = normalizeManagedContent(value).replace(/^\uFEFF/, "");
  if (PLAINTEXT_NODE_PATTERN.test(content)) return content;

  const compact = content.replace(/\s+/g, "");
  if (!compact || !/^[A-Za-z0-9+/_-]+={0,2}$/.test(compact)) {
    throw new Error("subscription_must_be_plaintext_nodes");
  }

  try {
    const normalized = compact.replace(/-/g, "+").replace(/_/g, "/");
    if (normalized.length % 4 === 1) {
      throw new Error("invalid_base64_length");
    }
    const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    const decoded = normalizeManagedContent(
      new TextDecoder().decode(bytes).replace(/^\uFEFF/, ""),
    );
    return assertPlainSubscriptionContent(decoded);
  } catch (_) {
    throw new Error("subscription_must_be_plaintext_nodes");
  }
}

// 常见节点命名中的国家别名，用于把外部订阅节点统一整理成“国家+编号”。
const COUNTRY_DEFINITIONS = [
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
const NODE_LINK_PATTERN = /^(?:vless|vmess|trojan|hysteria2|hy2|anytls|tuic|naive|naive\+https|wireguard|ss|socks|socks5|socks4|socks4a|https?):\/\/\S+$/i;

function normalizeNodeName(value) {
  return String(value || "").trim().replace(/\s+/g, " ").toLowerCase();
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function matchesCountryAlias(name, alias) {
  if (/^[a-z]+$/i.test(alias)) {
    const pattern = new RegExp(
      `(^|[^a-z])${escapeRegExp(alias)}(?=$|[^a-z]|\\d)`,
      "i",
    );
    return pattern.test(name);
  }
  return name.includes(alias);
}

function countryFromFlag(name) {
  const match = String(name || "").match(/([\u{1F1E6}-\u{1F1FF}]){2}/u);
  if (!match) return null;
  const code = Array.from(match[0])
    .map((character) => String.fromCharCode(character.codePointAt(0) - 0x1f1e6 + 65))
    .join("");
  return COUNTRY_BY_CODE.get(code) || null;
}

function findCountry(name) {
  const flagCountry = countryFromFlag(name);
  if (flagCountry) return flagCountry;

  const normalized = normalizeNodeName(name);
  if (!normalized) return UNKNOWN_COUNTRY;
  const match = COUNTRY_ALIASES
    .filter(({ alias }) => matchesCountryAlias(normalized, alias))
    .sort((left, right) => right.alias.length - left.alias.length)[0];
  return match ? { code: match.code, name: match.name } : UNKNOWN_COUNTRY;
}

function decodeBase64Utf8(value) {
  const normalized = String(value || "")
    .trim()
    .replace(/-/g, "+")
    .replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function encodeBase64Utf8(value) {
  const bytes = TEXT_ENCODER.encode(value);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}

function decodeUriFragment(value) {
  try {
    return decodeURIComponent(value.replace(/\+/g, " "));
  } catch (_) {
    return value;
  }
}

function extractNodeName(link) {
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

function renameNodeLink(link, name) {
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

export function autoRenameSubscriptionContent(content) {
  const counters = new Map();
  return String(content || "").split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!NODE_LINK_PATTERN.test(trimmed)) return line;

    const country = findCountry(extractNodeName(trimmed));
    const nextNumber = (counters.get(country.code) || 0) + 1;
    counters.set(country.code, nextNumber);
    return renameNodeLink(trimmed, `${country.name}${nextNumber}`);
  }).join("\n");
}

export function subscriptionCacheKey(source) {
  return `${SUBSCRIPTION_CACHE_KEY_PREFIX}${source.id}`;
}

async function readManagedSubscriptionBody(response) {
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_MANAGED_SUBSCRIPTION_BYTES) {
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

export async function readExternalSubscription(env, source) {
  if (!env.CONFIG) throw new Error("Missing KV binding CONFIG");
  if (!source || !source.url) throw new Error("external_subscription_url_required");

  const cacheMinutes = normalizeSubscriptionCacheMinutes(source.cacheMinutes);
  const cacheKey = subscriptionCacheKey(source);
  const now = Date.now();
  if (cacheMinutes > 0) {
    const cached = await env.CONFIG.get(cacheKey, "json");
    const cachedAt = Number(cached && cached.cachedAt);
    const freshUntil = cachedAt + cacheMinutes * 60 * 1000;
    if (
      cached &&
      cached.url === source.url &&
      Number.isFinite(cachedAt) &&
      freshUntil > now
    ) {
      return decodeExternalSubscriptionContent(cached.content);
    }
  }

  let response;
  try {
    response = await fetch(source.url, {
      method: "GET",
      headers: {
        accept: "text/plain, */*;q=0.1",
        "user-agent": "Chimera-Worker/1.0",
      },
      redirect: "follow",
    });
  } catch (_) {
    throw new Error("external_subscription_unreachable");
  }
  if (!response.ok) {
    throw new Error(`external_subscription_http_${response.status}`);
  }

  const content = decodeExternalSubscriptionContent(
    await readManagedSubscriptionBody(response),
  );
  if (cacheMinutes > 0) {
    const cacheSeconds = cacheMinutes * 60;
    await env.CONFIG.put(cacheKey, JSON.stringify({
      url: source.url,
      content,
      cachedAt: now,
    }), { expirationTtl: Math.max(cacheSeconds, 60) });
  } else {
    await env.CONFIG.delete(cacheKey);
  }
  return content;
}

export async function validateAndWarmSubscriptionSources(env, subscriptions) {
  for (const source of subscriptions) {
    if (source.enabled === false) continue;
    // 后台直接维护的文本订阅原样保存，不要求内容匹配特定节点协议。
    if (source.url && !source.content) {
      await readExternalSubscription(env, source);
    }
  }
}

export function privateBase64Encode(value) {
  const bytes = TEXT_ENCODER.encode(value);
  let result = "";
  let chunk = "";

  for (let i = 0; i < bytes.length; i += 3) {
    const remaining = bytes.length - i;
    const a = bytes[i];
    const b = remaining > 1 ? bytes[i + 1] : 0;
    const c = remaining > 2 ? bytes[i + 2] : 0;
    const block = (a << 16) | (b << 8) | c;

    chunk += PRIVATE_B64_ALPHABET[(block >>> 18) & 0x3f];
    chunk += PRIVATE_B64_ALPHABET[(block >>> 12) & 0x3f];
    chunk += remaining > 1
      ? PRIVATE_B64_ALPHABET[(block >>> 6) & 0x3f]
      : "=";
    chunk += remaining > 2
      ? PRIVATE_B64_ALPHABET[block & 0x3f]
      : "=";
    if (chunk.length >= 0x8000) {
      result += chunk;
      chunk = "";
    }
  }

  return result + chunk;
}

export function parseIpv4Tail(value) {
  let address = value;
  if (address.includes(":") && address.includes(".")) {
    address = address.slice(address.lastIndexOf(":") + 1);
  }

  const parts = address.split(".");
  if (parts.length !== 4) return null;
  const numbers = parts.map((part) => {
    if (!/^\d{1,3}$/.test(part)) return NaN;
    return Number(part);
  });
  if (numbers.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return null;
  }
  return numbers[3];
}

export function shiftFromClientIp(clientIp) {
  let address = String(clientIp || "").split(",", 1)[0].trim();
  if (address.startsWith("[") && address.includes("]")) {
    address = address.slice(1, address.indexOf("]"));
  }

  const ipv4Tail = parseIpv4Tail(address);
  if (ipv4Tail !== null) return ipv4Tail % 64;

  if (address.includes(":")) {
    const lastGroup = address.slice(address.lastIndexOf(":") + 1);
    if (lastGroup === "") return 0;
    if (/^[a-f\d]{1,4}$/i.test(lastGroup)) {
      return parseInt(lastGroup, 16) % 64;
    }
  }

  throw new Error("invalid_cf_connecting_ip");
}

export function protectSubscription(value, clientIp) {
  const shift = shiftFromClientIp(clientIp);
  const encoded = privateBase64Encode(value);
  if (shift === 0) return encoded;

  let result = "";

  let chunk = "";
  for (const character of encoded) {
    if (character === "=") {
      chunk += character;
      continue;
    }
    const index = PRIVATE_B64_ALPHABET.indexOf(character);
    if (index < 0) throw new Error("private_base64_encoding_failed");
    chunk += PRIVATE_B64_ALPHABET[(index + shift) % 64];
    if (chunk.length >= 0x8000) {
      result += chunk;
      chunk = "";
    }
  }
  return result + chunk;
}

export function getClientIp(request) {
  const clientIp = request.headers.get("CF-Connecting-IP");
  if (!clientIp) throw new Error("missing_cf_connecting_ip");
  shiftFromClientIp(clientIp);
  return clientIp.trim();
}
