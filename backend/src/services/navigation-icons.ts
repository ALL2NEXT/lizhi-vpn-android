import type { Env } from "../types/env.ts";
import { observedFetch } from "./system-logs.ts";

const MAX_ICON_BYTES = 128 * 1024;
const MAX_PAGE_BYTES = 512 * 1024;
const SUPPORTED_IMAGE_TYPES = new Set([
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/gif",
]);

function validatePublicWebsiteUrl(value: string) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("invalid_navigation_icon_url");
  }

  const hostname = url.hostname.toLowerCase().replace(/\.$/, "");
  const isIpLiteral = /^\[|^[0-9.]+$/.test(hostname);
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username || url.password || !hostname || isIpLiteral ||
    hostname === "localhost" || hostname.endsWith(".localhost") ||
    hostname.endsWith(".local") || hostname.endsWith(".internal") ||
    hostname.endsWith(".lan") || hostname.endsWith(".test") || hostname.endsWith(".home.arpa") ||
    (url.port && !((url.protocol === "http:" && url.port === "80") || (url.protocol === "https:" && url.port === "443")))
  ) {
    throw new Error("invalid_navigation_icon_url");
  }

  return url;
}

async function fetchPublicUrl(env: Env, url: URL, accept: string) {
  let current = validatePublicWebsiteUrl(url.toString());
  for (let redirects = 0; redirects <= 4; redirects += 1) {
    const response = await observedFetch(env, current, {
      method: "GET",
      redirect: "manual",
      headers: {
        Accept: accept,
        "User-Agent": "Liuying-Website-Icon-Fetcher/1.0",
      },
    });
    if (![301, 302, 303, 307, 308].includes(response.status)) return { response, url: current };

    const location = response.headers.get("location");
    if (!location || redirects === 4) throw new Error("navigation_icon_redirect_blocked");
    const next = validatePublicWebsiteUrl(new URL(location, current).toString());
    if (current.protocol === "https:" && next.protocol !== "https:") {
      throw new Error("navigation_icon_redirect_blocked");
    }
    current = next;
  }
  throw new Error("navigation_icon_redirect_blocked");
}

async function readLimitedBody(response: Response, maximumBytes: number) {
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) return null;

  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    return bytes.length <= maximumBytes ? bytes : null;
  }

  const reader = response.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    totalBytes += value.length;
    if (totalBytes > maximumBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }

  const output = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.length;
  }
  return output;
}

function detectImageType(bytes: Uint8Array) {
  if (bytes.length >= 6 && bytes[0] === 0 && bytes[1] === 0 && bytes[2] === 1 && bytes[3] === 0) {
    return "image/x-icon";
  }
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
    return "image/png";
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return "image/jpeg";
  }
  if (bytes.length >= 6) {
    const signature = String.fromCharCode(...bytes.subarray(0, 6));
    if (signature === "GIF87a" || signature === "GIF89a") return "image/gif";
  }
  if (bytes.length >= 12) {
    const riff = String.fromCharCode(...bytes.subarray(0, 4));
    const webp = String.fromCharCode(...bytes.subarray(8, 12));
    if (riff === "RIFF" && webp === "WEBP") return "image/webp";
  }
  return "";
}

function extractSupportedImage(bytes: Uint8Array) {
  const mimeType = detectImageType(bytes);
  if (SUPPORTED_IMAGE_TYPES.has(mimeType)) return { bytes, mimeType };

  // 优先提取 ICO 内嵌 PNG；旧式 DIB 资源则保留 ICO，由 Android 解码。
  if (mimeType === "image/x-icon") {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const count = Math.min(view.getUint16(4, true), 64);
    for (let index = 0; index < count; index += 1) {
      const entryOffset = 6 + index * 16;
      if (entryOffset + 16 > bytes.length) break;
      const imageLength = view.getUint32(entryOffset + 8, true);
      const imageOffset = view.getUint32(entryOffset + 12, true);
      if (imageLength === 0 || imageLength > MAX_ICON_BYTES || imageOffset + imageLength > bytes.length) continue;
      const embedded = bytes.subarray(imageOffset, imageOffset + imageLength);
      if (detectImageType(embedded) === "image/png") return { bytes: embedded, mimeType: "image/png" };
    }
    return { bytes, mimeType: "image/x-icon" };
  }
  return null;
}

function encodeBase64(bytes: Uint8Array) {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  }
  return btoa(binary);
}

function parseAttributes(tag: string) {
  const attributes: Record<string, string> = {};
  const pattern = /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g;
  let match;
  while ((match = pattern.exec(tag))) {
    attributes[match[1].toLowerCase()] = (match[2] ?? match[3] ?? match[4] ?? "")
      .replaceAll("&amp;", "&")
      .replaceAll("&#39;", "'")
      .replaceAll("&quot;", '"');
  }
  return attributes;
}

function findIconUrls(html: string, pageUrl: URL) {
  const candidates = [];
  const tags = html.match(/<link\b[^>]*>/gi) || [];
  for (const tag of tags) {
    const attributes = parseAttributes(tag);
    const rel = (attributes.rel || "").toLowerCase().split(/\s+/);
    if (!rel.some((value) => value === "icon" || value === "shortcut" || value === "apple-touch-icon")) continue;
    if (!attributes.href) continue;
    try {
      const iconUrl = validatePublicWebsiteUrl(new URL(attributes.href, pageUrl).toString());
      candidates.push(iconUrl);
    } catch {
      // 忽略不安全或无效的站点图标地址，继续尝试其他公开图标地址。
    }
  }
  return candidates;
}

async function fetchImage(env: Env, url: URL): Promise<{ icon: { iconBase64: string; iconMimeType: string }; reason?: never } | { icon?: never; reason: string }> {
  try {
    const { response } = await fetchPublicUrl(env, url, "image/png,image/jpeg,image/webp,image/gif,image/x-icon,image/vnd.microsoft.icon,image/*;q=0.8");
    if (!response.ok) return { reason: "not_found" };
    const declaredType = (response.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
    const isHtml = declaredType === "text/html" || declaredType === "application/xhtml+xml";
    if (isHtml) {
      const pageBytes = await readLimitedBody(response, MAX_PAGE_BYTES);
      const pageText = pageBytes ? new TextDecoder().decode(pageBytes).toLowerCase() : "";
      const isChallenge = pageText.includes("making sure you're not a bot") ||
        pageText.includes("/.within.website/") || pageText.includes("cf-chl-");
      return { reason: isChallenge ? "site_challenge" : "unsupported_format" };
    }
    // 有些站点给 ICO 或 PNG 配错 MIME；按文件签名校验，避免因此误拒绝图标。
    const bytes = await readLimitedBody(response, MAX_ICON_BYTES);
    if (!bytes || !bytes.length) return { reason: "unsupported_format" };
    const image = extractSupportedImage(bytes);
    if (!image) return { reason: "unsupported_format" };
    return { icon: { iconBase64: encodeBase64(image.bytes), iconMimeType: image.mimeType } };
  } catch (error) {
    if (error instanceof Error && error.message === "navigation_icon_redirect_blocked") {
      return { reason: "redirect_blocked" };
    }
    return { reason: "unreachable" };
  }
}

export async function fetchWebsiteIcon(env: Env, value: string) {
  const pageUrl = validatePublicWebsiteUrl(value);
  let finalPageUrl = pageUrl;
  let html = "";
  let sawChallenge = false;
  let sawUnsupportedFormat = false;

  try {
    const result = await fetchPublicUrl(env, pageUrl, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");
    finalPageUrl = result.url;
    const contentType = (result.response.headers.get("content-type") || "").toLowerCase();
    if (result.response.ok && (contentType.includes("text/html") || contentType.includes("application/xhtml+xml"))) {
      const bytes = await readLimitedBody(result.response, MAX_PAGE_BYTES);
      if (bytes) html = new TextDecoder().decode(bytes);
    }
  } catch {
    // 首页无法读取时仍尝试标准 favicon.ico 地址。
  }

  const pageLower = html.toLowerCase();
  sawChallenge = pageLower.includes("making sure you're not a bot") ||
    pageLower.includes("/.within.website/") || pageLower.includes("cf-chl-");

  const iconUrls = findIconUrls(html, finalPageUrl);
  iconUrls.push(new URL("/favicon.ico", finalPageUrl));
  for (const iconUrl of new Map(iconUrls.map((url) => [url.toString(), url])).values()) {
    const result = await fetchImage(env, iconUrl);
    if (result.icon) return result.icon;
    if (result.reason === "site_challenge") sawChallenge = true;
    if (result.reason === "unsupported_format") sawUnsupportedFormat = true;
  }
  if (sawChallenge) throw new Error("navigation_icon_site_challenge");
  if (sawUnsupportedFormat) throw new Error("navigation_icon_unsupported_format");
  throw new Error("navigation_icon_not_found");
}
