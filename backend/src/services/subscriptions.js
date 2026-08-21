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
      return assertPlainSubscriptionContent(cached.content);
    }
  }

  let response;
  try {
    response = await fetch(source.url, {
      method: "GET",
      headers: {
        accept: "text/plain, */*;q=0.1",
        "user-agent": "FireflyVPN-Worker/1.0",
      },
      redirect: "follow",
    });
  } catch (_) {
    throw new Error("external_subscription_unreachable");
  }
  if (!response.ok) {
    throw new Error(`external_subscription_http_${response.status}`);
  }

  const content = assertPlainSubscriptionContent(
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
    if (source.content) {
      assertPlainSubscriptionContent(source.content);
    } else if (source.url) {
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
