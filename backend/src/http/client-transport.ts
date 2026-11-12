import type { Env } from "../types/env.ts";
import { json } from "./responses.ts";

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false });
const mediaType = "application/vnd.chimera.encrypted";
const requestAad = encoder.encode("chimera-transport-v1/request");
const responseAad = encoder.encode("chimera-transport-v1/response");
const maxRequestBytes = 1024 * 1024;
const maxResponseBodyBytes = 16 * 1024 * 1024;
const clockWindowMs = 2 * 60 * 1000;

function concat(...parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const result = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) { result.set(part, offset); offset += part.length; }
  return result;
}

function base64(bytes: Uint8Array): string {
  let result = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    result += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(result);
}

function unbase64(value: unknown, maxBytes: number): Uint8Array<ArrayBuffer> {
  if (typeof value !== "string" || value.length > Math.ceil(maxBytes / 3) * 4 ||
      value.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) {
    throw new Error("invalid_base64");
  }
  const bytes = Uint8Array.from(atob(value), (char) => char.charCodeAt(0));
  if (bytes.length > maxBytes) throw new Error("body_too_large");
  return bytes;
}

async function readLimited(message: Request | Response, limit: number): Promise<Uint8Array<ArrayBuffer>> {
  if (Number(message.headers.get("content-length")) > limit) throw new Error("body_too_large");
  const reader = message.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > limit) throw new Error("body_too_large");
      chunks.push(value);
    }
    return concat(...chunks);
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

type ServerKeys = { privateKey: CryptoKey; publicKey: Uint8Array<ArrayBuffer> };
let cachedKeys: { encoded: string; promise: Promise<ServerKeys> } | undefined;

function serverKeys(env: Env): Promise<ServerKeys> {
  const encoded = env.CLIENT_TRANSPORT_PRIVATE_KEY?.trim();
  if (!encoded) return Promise.reject(new Error("transport_not_configured"));
  if (cachedKeys?.encoded === encoded) return cachedKeys.promise;
  const promise = (async () => {
    const algorithm = { name: "RSA-OAEP", hash: "SHA-256" };
    const privateKey = await crypto.subtle.importKey("pkcs8", unbase64(encoded, 8192), algorithm, true, ["decrypt"]);
    const jwk = await crypto.subtle.exportKey("jwk", privateKey) as JsonWebKey;
    const publicKey = await crypto.subtle.importKey("jwk", {
      kty: "RSA", n: jwk.n, e: jwk.e, ext: true, key_ops: ["encrypt"],
    }, algorithm, true, ["encrypt"]);
    const bits = (privateKey.algorithm as { modulusLength: number }).modulusLength;
    if (![2048, 3072, 4096].includes(bits)) throw new Error("unsupported_rsa_key");
    return { privateKey, publicKey: new Uint8Array(await crypto.subtle.exportKey("spki", publicKey) as ArrayBuffer) };
  })();
  cachedKeys = { encoded, promise };
  return promise;
}

/** Public for the native/WebCrypto interoperability test; no key bytes leave the server. */
export async function openRequestPacket(packet: Uint8Array<ArrayBuffer>, privateKey: CryptoKey) {
  if (packet.length < 6 + 256 + 12 + 16 || packet.length > maxRequestBytes + 6 + 512 + 28 ||
      decoder.decode(packet.subarray(0, 4)) !== "CHM1") throw new Error("invalid_packet");
  const wrappedSize = (packet[4] << 8) | packet[5];
  if (![256, 384, 512].includes(wrappedSize) ||
      wrappedSize !== (privateKey.algorithm as { modulusLength: number }).modulusLength / 8 ||
      packet.length < 6 + wrappedSize + 28) throw new Error("invalid_packet");
  const headerSize = 6 + wrappedSize;
  const keys = new Uint8Array(await crypto.subtle.decrypt(
    { name: "RSA-OAEP" }, privateKey, packet.subarray(6, headerSize),
  ));
  try {
    if (keys.length !== 64) throw new Error("invalid_keys");
    const [requestKey, responseKey] = await Promise.all([
      crypto.subtle.importKey("raw", keys.subarray(0, 32), "AES-GCM", false, ["decrypt"]),
      crypto.subtle.importKey("raw", keys.subarray(32), "AES-GCM", false, ["encrypt"]),
    ]);
    const plaintext = new Uint8Array(await crypto.subtle.decrypt({
      name: "AES-GCM", iv: packet.subarray(headerSize, headerSize + 12), tagLength: 128,
      additionalData: concat(requestAad, packet.subarray(0, headerSize)),
    }, requestKey, packet.subarray(headerSize + 12)));
    if (plaintext.length > maxRequestBytes) throw new Error("body_too_large");
    return { plaintext, responseKey };
  } finally { keys.fill(0); }
}

export async function sealResponsePacket(plaintext: Uint8Array, responseKey: CryptoKey) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({
    name: "AES-GCM", iv, additionalData: responseAad, tagLength: 128,
  }, responseKey, plaintext));
  return concat(encoder.encode("CHR1"), iv, ciphertext);
}

function isClientPath(path: string): boolean {
  return path === "/api/client" || path === "/api/app/proxy-packages" ||
    path.startsWith("/api/client/notifications") || path.startsWith("/api/account/") ||
    path.startsWith("/api/subscription/");
}

function restoreRequest(outer: Request, plaintext: Uint8Array<ArrayBuffer>) {
  const payload: unknown = JSON.parse(decoder.decode(plaintext));
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) throw new Error("invalid_payload");
  const { method, path, contentType, body, timestamp } = payload as Record<string, unknown>;
  if (typeof method !== "string" || !["GET", "POST", "PATCH", "DELETE"].includes(method) ||
      typeof path !== "string" || path.length > 8192 || !path.startsWith("/api/") ||
      path.includes("#") || path.includes("\\") || /[\u0000-\u0020]/.test(path) ||
      typeof contentType !== "string" || contentType.length > 256 ||
      typeof timestamp !== "number" || !Number.isSafeInteger(timestamp) ||
      Math.abs(Date.now() - timestamp) > clockWindowMs) throw new Error("invalid_payload");
  const url = new URL(path, new URL(outer.url).origin);
  if (!isClientPath(url.pathname) || url.pathname + url.search !== path) throw new Error("invalid_target");
  const bytes = unbase64(body, maxRequestBytes);
  if (method === "GET" && bytes.length) throw new Error("invalid_body");
  const headers = new Headers();
  for (const name of ["user-agent", "cf-connecting-ip", "cf-ray"]) {
    const value = outer.headers.get(name);
    if (value) headers.set(name, value);
  }
  if (contentType) headers.set("content-type", contentType);
  return {
    request: new Request(url, {
      method, headers, signal: outer.signal,
      ...(method === "GET" ? {} : { body: bytes }),
    }),
    timestamp,
  };
}

async function rememberRequest(env: Env, packet: Uint8Array<ArrayBuffer>, timestamp: number) {
  const db = env.USERS_DB;
  if (!db) throw new Error("replay_store_unavailable");
  const id = base64(new Uint8Array(await crypto.subtle.digest("SHA-256", packet)));
  // A database uniqueness constraint works across Worker isolates and Linux processes.
  const results = await db.batch([
    db.prepare("CREATE TABLE IF NOT EXISTS client_transport_nonces (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)"),
    db.prepare("DELETE FROM client_transport_nonces WHERE expires_at < ?").bind(Date.now()),
    db.prepare("INSERT OR IGNORE INTO client_transport_nonces (id, expires_at) VALUES (?, ?)")
      .bind(id, timestamp + clockWindowMs),
  ]);
  if (results[2]?.meta?.changes !== 1) throw new Error("replayed_request");
}

async function encryptedResponse(response: Response, responseKey: CryptoKey) {
  const body = await readLimited(response, maxResponseBodyBytes);
  const excluded = new Set(["content-length", "content-encoding", "transfer-encoding", "connection", "set-cookie"]);
  const payload = encoder.encode(JSON.stringify({
    status: response.status,
    headers: [...response.headers].filter(([name]) => !excluded.has(name)),
    body: base64(body),
  }));
  try {
    return new Response(await sealResponsePacket(payload, responseKey), {
      headers: { "content-type": mediaType, "cache-control": "no-store" },
    });
  } finally { body.fill(0); payload.fill(0); }
}

export async function handleClientTransport(
  request: Request,
  env: Env,
  dispatch: (request: Request) => Promise<Response>,
): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  if (path === "/api/transport/key" && request.method === "GET") {
    try {
      const keys = await serverKeys(env);
      return new Response(keys.publicKey, {
        headers: { "content-type": "application/pkix-key", "cache-control": "no-store" },
      });
    } catch { return json({ error: "transport_unavailable" }, 503); }
  }
  if (path !== "/api/transport") {
    if (env.CLIENT_TRANSPORT_PRIVATE_KEY?.trim() && isClientPath(path) && request.method !== "OPTIONS") {
      return json({ error: "encrypted_transport_required" }, 426);
    }
    return dispatch(request);
  }
  if (request.method !== "POST" || request.headers.get("content-type") !== mediaType || url.search) {
    return json({ error: "invalid_encrypted_request" }, 400);
  }
  let keys: ServerKeys;
  try { keys = await serverKeys(env); }
  catch { return json({ error: "transport_unavailable" }, 503); }
  let opened: Awaited<ReturnType<typeof openRequestPacket>>;
  let restored: ReturnType<typeof restoreRequest>;
  try {
    const packet = await readLimited(request, maxRequestBytes + 6 + 512 + 28);
    opened = await openRequestPacket(packet, keys.privateKey);
    try { restored = restoreRequest(request, opened.plaintext); }
    finally { opened.plaintext.fill(0); }
    await rememberRequest(env, packet, restored.timestamp);
  } catch { return json({ error: "invalid_encrypted_request" }, 400); }
  try {
    const response = await dispatch(restored.request);
    return await encryptedResponse(response, opened.responseKey);
  } catch {
    return encryptedResponse(json({ error: "internal_error" }, 500), opened.responseKey);
  }
}
