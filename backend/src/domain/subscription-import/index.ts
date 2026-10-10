import { parseDocument } from "yaml";
import { map, scalar } from "./encode.ts";
import { extractNativeNodes, safeEncode } from "./native.ts";
import { decodeBase64, extractUris, isJsonContent, isYamlContent, subscriptionFragments } from "./text.ts";

export function normalizeRemoteUrl(value: string) {
  if (value.length > 8192 || /[\r\n]/.test(value)) return null;
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
  } catch { return null; }
}

export function detectSubscriptionInput(value: string) {
  const urls = new Set<string>(), local: string[] = [];
  for (const fragment of subscriptionFragments(value)) {
    if (isJsonContent(fragment) || isYamlContent(fragment)) { local.push(fragment); continue; }
    for (const uri of extractUris(fragment)) {
      if (/^https?:\/\//i.test(uri)) {
        const url = normalizeRemoteUrl(uri);
        if (url) urls.add(url);
      } else local.push(uri);
    }
  }
  return { urls: [...urls], local };
}

/** 对照客户端最终 URI 解析结果，过滤没有服务器或合法端口的分享链接。 */
export function isUsableNode(uri: string): boolean {
  const portValid = (port: unknown) => /^\d+$/.test(String(port)) && Number(port) >= 1 && Number(port) <= 65535;
  try {
    if (/^vmess:\/\//i.test(uri)) {
      const payload = map(JSON.parse(decodeBase64(uri.slice(8).split("#", 1)[0]) || "{}"));
      return !!scalar(payload.add) && portValid(payload.port ?? 443);
    }
    let value = uri;
    if (/^ss:\/\//i.test(uri) && !uri.split("#", 1)[0].includes("@")) {
      const decoded = decodeBase64(uri.slice(5).split(/[?#]/, 1)[0]);
      if (decoded) value = "ss://" + decoded;
    }
    // Hysteria2 端口跳跃语法不能交给 URL 直接解析。
    value = value.replace(/^(hysteria2|hy2):\/\/([^/?#]+):(\d+)(?:[-,]\d+)+(?=[/?#]|$)/i, "$1://$2:$3");
    const url = new URL(value);
    if (url.protocol === "vless:" && !decodeURIComponent(url.username).trim()) return false;
    const defaultPort = url.protocol === "http:" ? "80" : /^socks/.test(url.protocol) ? "1080" : url.protocol === "wireguard:" ? "51820" : "443";
    return !!url.hostname && portValid(url.port || defaultPort);
  } catch { return false; }
}

export function toStandardUris(input: string | string[], options: { deduplicate?: boolean } = {}) {
  const fragments = typeof input === "string" ? subscriptionFragments(input) : input;
  const uris: string[] = [];
  for (const fragment of fragments) {
    try {
      if (isJsonContent(fragment)) uris.push(...extractNativeNodes(JSON.parse(fragment)));
      else if (isYamlContent(fragment)) {
        const doc = parseDocument(fragment, { version: "1.1", uniqueKeys: true });
        if (doc.errors.length) continue;
        const value: unknown = doc.toJS({ maxAliasCount: 100 });
        uris.push(...(Array.isArray(value) ? value.flatMap(item => safeEncode(map(item))) : extractNativeNodes(value)));
      } else uris.push(...extractUris(fragment));
    } catch { /* 一个损坏片段不能吞掉同次导入的其他来源。 */ }
  }
  const usableUris = uris.filter(isUsableNode);
  return options.deduplicate === false ? usableUris : [...new Set(usableUris)];
}
