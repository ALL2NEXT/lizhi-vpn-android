import assert from "node:assert/strict";
import test from "node:test";
import { isUsableNode, toStandardUris } from "../src/domain/subscription-format/index.ts";
import { decodeBase64, encodeBase64 } from "../src/domain/subscription-format/text.ts";
import { decodeExternalSubscriptionContent, fetchExternalSubscription, readExternalSubscription } from "../src/services/subscriptions.ts";
import { MAX_MANAGED_SUBSCRIPTION_BYTES } from "../src/config.ts";

const uuid = "11111111-1111-1111-1111-111111111111";
const localUri = `vless://${uuid}@local.example:443`;
const yaml = "proxies:\n  - name: yaml-node\n    type: trojan\n    server: yaml.example\n    port: 443\n    password: secret";
const parseVmess = uri => JSON.parse(decodeBase64(uri.slice(8)));

test("Clash YAML 支持锚点、单个对象、列表、全局指纹和原生出站", () => {
  const anchor = `common: &base\n  type: vless\n  server: reality.example\n  port: 443\n  uuid: ${uuid}\n  reality-opts: {public-key: key, short-id: abcd}\nglobal-client-fingerprint: chrome\nproxies:\n  - <<: *base\n    name: Reality`;
  const reality = new URL(toStandardUris(anchor)[0]);
  assert.equal(reality.searchParams.get("pbk"), "key");
  assert.equal(reality.searchParams.get("fp"), "chrome");
  assert.equal(toStandardUris(`type: vless\nserver: single.example\nport: 443\nuuid: ${uuid}`).length, 1);
  assert.equal(toStandardUris("- name: first\n  type: trojan\n  server: first.example\n  port: 443\n  password: secret\n- {name: second, type: ss, server: second.example, port: 8388, cipher: aes-128-gcm, password: secret}").length, 2);
  assert.equal(toStandardUris(`outbounds:\n  - type: vless\n    server: native.example\n    server_port: 443\n    uuid: ${uuid}\n    tls: {enabled: true, server_name: edge.example}\n    transport: {type: ws, path: /ws, headers: {Host: host.example}}\nroute:\n  rule_set:\n    - url: https://rules.example/data`).length, 1);
  assert.deepEqual(toStandardUris('outbounds: &loop\n  - outbounds: *loop'), []);
});

test("sing-box / Xray TLS、Reality、WebSocket、xhttp 和 VMess 参数完整保留", () => {
  const native = { type: "vless", tag: "Japan", server: "node.example", server_port: 443, uuid, tls: { enabled: true, server_name: "sni.example", utls: { enabled: true, fingerprint: "chrome" }, ech: { enabled: true, config: ["config"] } }, transport: { type: "ws", path: "/websocket", headers: { Host: "host.example" }, max_early_data: 2048 }, multiplex: { enabled: true } };
  const link = new URL(toStandardUris(JSON.stringify({ outbounds: [native, { type: "direct" }] }))[0]);
  for (const [key, value] of Object.entries({ type: "ws", path: "/websocket", host: "host.example", sni: "sni.example", security: "tls", fp: "chrome", ed: "2048", echConfigList: "config" })) assert.equal(link.searchParams.get(key), value);
  assert.equal(link.href.includes("multiplex"), false);
  const xray = { protocol: "vless", settings: { vnext: [{ address: "reality.example", port: 443, users: [{ id: uuid, flow: "xtls-rprx-vision" }] }] }, streamSettings: { network: "xhttp", security: "reality", realitySettings: { serverName: "sni.example", publicKey: "public-key", shortId: "abcd", fingerprint: "chrome" }, xhttpSettings: { path: "/xhttp", host: "host.example", mode: "auto", extra: { scMaxEachPostBytes: 1000000 } } } };
  const reality = new URL(toStandardUris(JSON.stringify(xray))[0]);
  assert.equal(reality.searchParams.get("pbk"), "public-key");
  assert.equal(reality.searchParams.get("flow"), "xtls-rprx-vision");
  assert.equal(reality.searchParams.get("encryption"), "none");
  assert.equal(JSON.parse(reality.searchParams.get("extra")).scMaxEachPostBytes, 1000000);
  const vmess = toStandardUris(JSON.stringify({ ...native, type: "vmess", tls: { enabled: true, server_name: "tls.example", alpn: ["h2"] }, transport: { type: "grpc", service_name: "service" } }))[0];
  assert.equal(parseVmess(vmess).serviceName, "service");
  assert.equal(parseVmess(vmess).tls, "tls");
  assert.deepEqual(toStandardUris(encodeBase64(encodeBase64(vmess))), [vmess]);
});

test("Xray 每个服务器和用户独立提取，WireGuard endpoints 和 peers 转换", () => {
  const vmess = toStandardUris(JSON.stringify({ protocol: "vmess", settings: { vnext: [{ address: "one.example", port: 443, users: [{ id: uuid }, { id: "22222222-2222-2222-2222-222222222222" }] }, { address: "two.example", port: 8443, users: [{ id: uuid }] }] } }));
  assert.deepEqual(vmess.map(uri => parseVmess(uri).add), ["one.example", "one.example", "two.example"]);
  const endpoint = { endpoints: [{ type: "wireguard", private_key: "secret", address: ["10.0.0.2/32"], peers: [{ address: "wg.example", port: 51820, public_key: "public-key", pre_shared_key: "psk", reserved: [1, 2, 3] }] }] };
  const wg = new URL(toStandardUris(JSON.stringify(endpoint))[0]);
  assert.equal(wg.searchParams.get("publickey"), "public-key");
  assert.equal(wg.searchParams.get("reserved"), "1,2,3");
  assert.equal(wg.searchParams.get("address"), "10.0.0.2/32");
  const xray = { protocol: "wireguard", settings: { secretKey: "secret", address: ["10.0.0.2/32"], peers: [{ endpoint: "[2001:db8::1]:51820", publicKey: "key", keepAlive: 30, allowedIPs: ["0.0.0.0/0"] }] } };
  assert.equal(new URL(toStandardUris(JSON.stringify(xray))[0]).searchParams.get("allowed_ips"), "0.0.0.0/0");
});

test("Clash 支持全部客户端协议，端口跳跃、SS 插件和证书参数保留", () => {
  const protocols = ["socks5", "socks4", "socks4a", "http", "ss", "vmess", "vless", "trojan", "hysteria2", "anytls", "tuic", "naive", "wireguard"];
  const nodes = protocols.map(type => ({ type, name: type, server: "node.example", port: 443, username: "user", password: "pass", uuid, cipher: "aes-128-gcm", "private-key": "secret", "public-key": "public-key", certificate: "cert", "tls-min-version": "1.2" }));
  const result = toStandardUris(JSON.stringify({ proxies: nodes }));
  assert.equal(result.length, protocols.length);
  const hy2 = toStandardUris(JSON.stringify({ type: "hysteria2", server: "[2001:db8::1]", port: 443, ports: "443,8443-8450", password: "secret", "hop-interval": "30s" }))[0];
  assert.ok(hy2.includes(":[443") === false);
  assert.ok(hy2.includes("]:443,8443-8450"));
  assert.equal(isUsableNode(hy2), true);
  const ss = new URL(toStandardUris(JSON.stringify({ type: "ss", server: "ss.example", port: 8388, cipher: "aes-128-gcm", password: "password", plugin: "obfs", "plugin-opts": { mode: "http", host: "host.example" } }))[0]);
  assert.equal(ss.searchParams.get("plugin"), "obfs-local;obfs=http;obfs-host=host.example");
});

test("无关配置、未知协议、无效端口和损坏链接不会生成节点", () => {
  for (const value of ["////not-base64", "external-ui-url: https://ui.example", '{"route":{"url":"https://rules.example"}}', '{"outbounds":[{"type":"direct"},{"type":"selector"}]}', "vless://id@host:0", "vless://id@host:65536", "vmess://ignored"]) assert.deepEqual(toStandardUris(value), []);
});

test("URI 省略端口沿用客户端默认值，VLESS 必须携带身份", () => {
  assert.equal(toStandardUris("vless://host.example:443").length, 0);
  for (const uri of ["vless://id@host.example", "socks5://host.example", "trojan://secret@host.example", "wireguard://key@host.example"]) assert.equal(isUsableNode(uri), true);
  assert.equal(isUsableNode("vmess://" + encodeBase64(JSON.stringify({ add: "host.example", id: uuid }))), true);
});

test("元数据命名保留标题原文，认证订阅以 Basic Auth 获取", async t => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.equal(url, "https://example.com/sub");
    assert.equal(options.headers.authorization, "Basic " + encodeBase64("user:pass"));
    calls++;
    return new Response(localUri, { headers: calls === 1 ? { "profile-title": '"Name%20A"' } : { "profile-title": "BASE64:invalid", "subscription-name": "Fallback" } });
  });
  assert.equal((await fetchExternalSubscription("https://user:pass@example.com/sub")).name, "Name%20A");
  assert.equal((await fetchExternalSubscription("https://user:pass@example.com/sub")).name, "Fallback");
});

test("远程格式自动尝试 User-Agent，更新及 KV 缓存仍使用同一转换器", async t => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (_, options) => {
    calls++;
    assert.ok(options.signal instanceof AbortSignal);
    return options.headers["user-agent"].startsWith("NekoBox") ? new Response("no nodes") : new Response(yaml);
  });
  const remote = await fetchExternalSubscription("https://www.example.com/sub");
  assert.equal(remote.name, "example.com");
  assert.equal(calls, 2);
  assert.equal(remote.content, decodeExternalSubscriptionContent(encodeBase64(encodeBase64(yaml))));
  const cache = new Map();
  const env = { CONFIG: { get: async key => cache.get(key), put: async (key, value) => cache.set(key, JSON.parse(value)), delete: async key => cache.delete(key) } };
  const source = { id: "1", url: "https://example.com/sub", cacheMinutes: 30 };
  assert.equal(await readExternalSubscription(env, source), remote.content);
  assert.equal(await readExternalSubscription(env, source), remote.content);
  assert.equal(calls, 4);
});

test("超大上游响应按字节拒绝且不会再次拉取", async t => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async () => { calls++; return new Response("too big", { headers: { "content-length": String(MAX_MANAGED_SUBSCRIPTION_BYTES + 1) } }); });
  await assert.rejects(fetchExternalSubscription("https://example.com/sub"), /managed_subscription_too_large/);
  assert.equal(calls, 1);
});
