import assert from "node:assert/strict";
import test from "node:test";
import {
  detectSubscriptionInput,
  toStandardUris,
} from "../src/core/subscription-import/index.ts";
import { encodeBase64 } from "../src/core/subscription-import/text.ts";

const uuid = "11111111-1111-1111-1111-111111111111";

test("直接节点 URI 可以标准化并过滤无效节点", () => {
  const uri = `vless://${uuid}@direct.example:443?security=tls#direct`;
  assert.deepEqual(toStandardUris(`${uri}\nvless://bad@example.com:70000`), [uri]);
});

test("多层 Base64 订阅可以递归解码", () => {
  const uri = `trojan://secret@base64.example:443#base64`;
  const input = encodeBase64(encodeBase64(encodeBase64(uri)));
  assert.deepEqual(toStandardUris(input), [uri]);
});

test("Clash YAML 可以转换为标准 URI", () => {
  const yaml = `proxies:
  - name: yaml-node
    type: trojan
    server: yaml.example
    port: 443
    password: secret
    sni: yaml.example`;
  const nodes = toStandardUris(yaml);
  assert.equal(nodes.length, 1);
  assert.match(nodes[0], /^trojan:\/\//);
  assert.match(nodes[0], /yaml\.example:443/);
});

test("sing-box JSON 可以转换为标准 URI", () => {
  const config = {
    outbounds: [
      {
        type: "vless",
        tag: "json-node",
        server: "json.example",
        server_port: 443,
        uuid,
        tls: { enabled: true, server_name: "json.example" },
      },
    ],
  };
  const nodes = toStandardUris(JSON.stringify(config));
  assert.equal(nodes.length, 1);
  assert.match(nodes[0], /^vless:\/\//);
  assert.match(nodes[0], new RegExp(uuid));
});

test("混合输入会区分远程订阅与本地节点", () => {
  const uri = `vless://${uuid}@local.example:443`;
  const input = detectSubscriptionInput(`${uri}\nhttps://remote.example/sub`);
  assert.deepEqual(input.urls, ["https://remote.example/sub"]);
  assert.deepEqual(input.local, [uri]);
});

test("节点查询参数中的 URL 不会被误识别为远程订阅", () => {
  const uri = `vless://${uuid}@local.example:443?ech=https://dns.example/query,https://fallback.example/query`;
  const input = detectSubscriptionInput(`${uri}\nhttps://remote.example/sub`);
  assert.deepEqual(input.urls, ["https://remote.example/sub"]);
  assert.deepEqual(toStandardUris(input.local), [uri]);
});

test("无效内容不会抛出异常", () => {
  assert.deepEqual(toStandardUris("not a subscription\n{\"broken\": true}"), []);
});