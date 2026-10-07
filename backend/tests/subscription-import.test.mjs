import assert from "node:assert/strict";
import test from "node:test";
import { detectSubscriptionInput, isUsableNode, toStandardUris } from "../src/domain/subscription-import/index.ts";
import { decodeBase64, encodeBase64 } from "../src/domain/subscription-import/text.ts";
import { importSubscriptionSources } from "../src/services/subscription-import.ts";
import { decodeExternalSubscriptionContent, fetchExternalSubscription, readExternalSubscription } from "../src/services/subscriptions.ts";
import { MAX_MANAGED_SUBSCRIPTION_BYTES } from "../src/config.ts";

const uuid = "11111111-1111-1111-1111-111111111111";
const localUri = `vless://${uuid}@local.example:443`;
const yaml = "proxies:\n  - name: yaml-node\n    type: trojan\n    server: yaml.example\n    port: 443\n    password: secret";
const json = JSON.stringify({ type: "vless", server: "json.example", server_port: 443, uuid });
const parseVmess = uri => JSON.parse(decodeBase64(uri.slice(8)));

test("递归 Base64、独立编码块、换行和 URL-safe 编码与客户端一致", () => {
  const input = detectSubscriptionInput(encodeBase64(encodeBase64(encodeBase64(`https://one.example/sub\nhttps://two.example/sub\n${localUri}`))));
  assert.deepEqual(input.urls, ["https://one.example/sub", "https://two.example/sub"]);
  assert.deepEqual(toStandardUris(input.local), [localUri]);
  const mixed = detectSubscriptionInput(`${localUri}\n${encodeBase64(encodeBase64(`https://one.example/sub\n${yaml}`))}\n${json}\n${encodeBase64("https://two.example/sub")}`);
  assert.deepEqual(mixed.urls, input.urls);
  assert.equal(toStandardUris(mixed.local).length, 3);
  const wrapped = encodeBase64(yaml + "\n" + localUri).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "").match(/.{1,37}/g).join("\n");
  assert.equal(toStandardUris(wrapped).length, 2);
  const inline = detectSubscriptionInput(`${localUri} ${encodeBase64(encodeBase64(json))} https://remote.example/sub`);
  assert.equal(toStandardUris(inline.local).length, 2);
});

test("多个远程地址去重，兼容 Markdown、分隔符和转义 URI", () => {
  const result = detectSubscriptionInput(`说明文字\n[订阅](https://one.example/sub),https://two.example/sub;https://one.example/sub\n${encodeBase64("https://one.example/sub")}\n${localUri.replace("://", "\\://").replace("@", "\\@")} ${localUri}`);
  assert.deepEqual(result.urls, ["https://one.example/sub", "https://two.example/sub"]);
  assert.deepEqual(toStandardUris(result.local), [localUri]);
});

test("配置里的 URL、Base64 密码与节点 query 中的 URL 不拆成订阅", () => {
  const config = JSON.stringify({ outbounds: [{ type: "trojan", server: "json.example", server_port: 443, password: "dGhpcy1pcy1hLXBhc3N3b3Jk", tls: { enabled: true }, transport: { type: "ws", path: "https://transport.example/path" } }], route: { rule_set: [{ url: "https://rules.example/data" }] } });
  const input = detectSubscriptionInput(`${config}\n${yaml}\nexternal-ui-url: https://ui.example/`);
  assert.deepEqual(input.urls, []);
  const nodes = toStandardUris(input.local);
  assert.equal(nodes.length, 2);
  assert.ok(nodes[0].includes("dGhpcy1pcy1hLXBhc3N3b3Jk"));
  const node = localUri + "?ech=https://dns.example/query,https://fallback.example/query";
  const mixed = detectSubscriptionInput(node + "\nhttps://remote.example/sub");
  assert.deepEqual(mixed.urls, ["https://remote.example/sub"]);
  assert.deepEqual(toStandardUris(mixed.local), [node]);
});

test("多个 YAML 文档、相邻 JSON 和损坏配置不会吞掉其他来源", () => {
  const input = detectSubscriptionInput(`${yaml}\n---\n${yaml.replace("yaml.example", "second.example")}\n${json}\n${localUri}\nhttps://remote.example/sub`);
  assert.equal(toStandardUris(input.local).length, 4);
  assert.deepEqual(input.urls, ["https://remote.example/sub"]);
  const adjacent = detectSubscriptionInput(json + json.replace("json.example", "second.example") + " https://remote.example/sub");
  assert.equal(toStandardUris(adjacent.local).length, 2);
  assert.deepEqual(adjacent.urls, input.urls);
  const bad = detectSubscriptionInput(`{"outbounds": [\nhttps://remote.example/sub\n${yaml}\nunrelated-text\n${localUri}`);
  assert.equal(toStandardUris(bad.local).length, 2);
  assert.deepEqual(bad.urls, input.urls);
});

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

test("无关配置、未知协议、无效端口和损坏链接不会生成空本地源", async () => {
  for (const value of ["////not-base64", "external-ui-url: https://ui.example", '{"route":{"url":"https://rules.example"}}', '{"outbounds":[{"type":"direct"},{"type":"selector"}]}', "vless://id@host:0", "vless://id@host:65536", "vmess://ignored"]) assert.deepEqual(toStandardUris(value), []);
  await assert.rejects(importSubscriptionSources("no nodes", "guest"), /subscription_import_no_nodes/);
  assert.equal((await importSubscriptionSources("", "formal")).sources[0].minAccountType, "formal");
  await assert.rejects(importSubscriptionSources(null, "guest"), /invalid_subscription_import_input/);
  await assert.rejects(importSubscriptionSources("中".repeat(MAX_MANAGED_SUBSCRIPTION_BYTES / 2), "guest"), /managed_subscription_too_large/);
  await assert.rejects(importSubscriptionSources(Array.from({ length: 21 }, (_, i) => `https://source${i}.example/sub`).join("\n"), "guest"), /subscription_import_too_many_urls/);
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

test("智能导入自动命名，远程源保留 URL，部分失败保留成功结果和顺序", async t => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    calls.push({ url, agent: options.headers["user-agent"] });
    if (url.includes("bad.example")) return new Response("no", { status: 503 });
    if (url.includes("one.example")) return new Response(yaml, { headers: { "profile-title": "base64:" + encodeBase64("日本订阅") } });
    return new Response(encodeBase64(encodeBase64(json)), { headers: { "content-disposition": "attachment; filename*=UTF-8''%E8%AE%A2%E9%98%85.yaml" } });
  });
  const result = await importSubscriptionSources(`${localUri}\nhttps://one.example/sub\nhttps://bad.example/sub\nhttps://two.example/sub\nhttps://one.example/sub`, "formal");
  assert.deepEqual(result.sources.map(source => source.name), ["导入内容", "日本订阅", "订阅"]);
  assert.equal(result.nodeCount, 3);
  assert.equal(result.failedSources.length, 1);
  assert.equal(result.failedSources[0].error, "external_subscription_http_503");
  assert.deepEqual(result.sources.map(source => source.url), ["", "https://one.example/sub", "https://two.example/sub"]);
  assert.equal(result.sources[1].content, "");
  assert.equal(result.sources[0].content, localUri);
  assert.ok(result.sources.every(source => source.minAccountType === "formal" && source.enabled && !source.autoRename));
  assert.equal(calls.filter(call => call.url.includes("bad.example")).length, 7);
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

test("无效本地配置丢弃但不影响远程；全部失败可返回逐来源错误", async t => {
  t.mock.method(globalThis, "fetch", async url => url.includes("bad.example") ? new Response("bad", { status: 404 }) : new Response(localUri));
  const result = await importSubscriptionSources('{"outbounds":[{"type":"direct"}]}\nhttps://valid.example/sub', "guest");
  assert.equal(result.sources.length, 1);
  const failure = await importSubscriptionSources("https://bad.example/sub", "guest");
  assert.equal(failure.sources.length, 0);
  assert.equal(failure.failedSources[0].error, "external_subscription_http_404");
});

test("超大上游响应按字节拒绝且不会再次拉取", async t => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async () => { calls++; return new Response("too big", { headers: { "content-length": String(MAX_MANAGED_SUBSCRIPTION_BYTES + 1) } }); });
  await assert.rejects(fetchExternalSubscription("https://example.com/sub"), /managed_subscription_too_large/);
  assert.equal(calls, 1);
});
