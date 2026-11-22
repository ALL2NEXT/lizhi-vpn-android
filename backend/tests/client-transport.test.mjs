import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, spawnSync } from "node:child_process";
import { createInterface } from "node:readline";
import { once } from "node:events";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { handleClientTransport, openRequestPacket, sealResponsePacket } from "../src/http/client-transport.ts";

const encoder = new TextEncoder();
const requestAad = encoder.encode("chimera-transport-v1/request");
const responseAad = encoder.encode("chimera-transport-v1/response");
const mediaType = "application/vnd.chimera.encrypted";
const generated = generateKeyPairSync("rsa", {
  modulusLength: 3072,
  privateKeyEncoding: { type: "pkcs8", format: "der" },
  publicKeyEncoding: { type: "spki", format: "der" },
});
const publicKey = await crypto.subtle.importKey("spki", generated.publicKey,
  { name: "RSA-OAEP", hash: "SHA-256" }, false, ["encrypt"]);
const privateKey = await crypto.subtle.importKey("pkcs8", generated.privateKey,
  { name: "RSA-OAEP", hash: "SHA-256" }, false, ["decrypt"]);

async function seal(path, { method = "GET", body, timestamp = Date.now() } = {}) {
  const keys = crypto.getRandomValues(new Uint8Array(64));
  const wrapped = new Uint8Array(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, keys));
  const requestKey = await crypto.subtle.importKey("raw", keys.subarray(0, 32), "AES-GCM", false, ["encrypt"]);
  const responseKey = await crypto.subtle.importKey("raw", keys.subarray(32), "AES-GCM", false, ["decrypt"]);
  keys.fill(0);
  const header = Buffer.concat([Buffer.from("CHM1"), Buffer.from([wrapped.length >> 8, wrapped.length & 255]), wrapped]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = encoder.encode(JSON.stringify({
    method, path, timestamp,
    contentType: body === undefined ? "" : "application/json; charset=utf-8",
    body: body === undefined ? "" : Buffer.from(JSON.stringify(body)).toString("base64"),
  }));
  const ciphertext = await crypto.subtle.encrypt({
    name: "AES-GCM", iv, tagLength: 128, additionalData: Buffer.concat([requestAad, header]),
  }, requestKey, payload);
  return { packet: Buffer.concat([header, iv, Buffer.from(ciphertext)]), responseKey };
}

async function open(response, key) {
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), mediaType);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const packet = new Uint8Array(await response.arrayBuffer());
  assert.equal(Buffer.from(packet.subarray(0, 4)).toString(), "CHR1");
  const plain = await crypto.subtle.decrypt({
    name: "AES-GCM", iv: packet.subarray(4, 16), tagLength: 128, additionalData: responseAad,
  }, key, packet.subarray(16));
  const payload = JSON.parse(new TextDecoder().decode(plain));
  const body = Buffer.from(payload.body, "base64").toString();
  return { status: payload.status, headers: new Headers(payload.headers), body, packet };
}

test("客户端加密传输覆盖账号、查询参数、错误响应和持久化重放检查", async (t) => {
  const buildDir = fileURLToPath(new URL("../.wrangler/build/", import.meta.url));
  const assets = readdirSync(buildDir).filter((name) => /\.(html|css|client\.js)$/.test(name));
  const mf = new Miniflare(convertV4MiniflareOptions({
    cf: false, modulesRoot: buildDir,
    modules: [
      { type: "ESModule", path: join(buildDir, "serverless.js") },
      ...assets.map((name) => ({ type: "Text", path: join(buildDir, name) })),
    ],
    compatibilityDate: "2026-08-14", compatibilityFlags: ["nodejs_compat"],
    kvNamespaces: ["CONFIG"], d1Databases: ["USERS_DB"],
    bindings: {
      ADMIN_PATH: "local-admin", ADMIN_TOKEN: "local-test-token",
      CLIENT_TRANSPORT_PRIVATE_KEY: generated.privateKey.toString("base64"),
    },
  }));
  t.after(() => mf.dispose());
  const send = (packet) => mf.dispatchFetch("https://worker.test/api/transport", {
    method: "POST", headers: { "content-type": mediaType, "user-agent": "Chimera/1.0.0" }, body: packet,
  });

  await t.test("只公开公钥，配置密钥后拒绝明文业务请求", async () => {
    const key = await mf.dispatchFetch("https://worker.test/api/transport/key");
    assert.equal(key.headers.get("content-type"), "application/pkix-key");
    assert.deepEqual(Buffer.from(await key.arrayBuffer()), generated.publicKey);
    for (const path of ["/api/client", "/api/account/register", "/api/subscription/1", "/api/app/proxy-packages"]) {
      assert.equal((await mf.dispatchFetch("https://worker.test" + path)).status, 426);
    }
    assert.equal((await mf.dispatchFetch("https://worker.test/health")).status, 200);
    assert.equal((await mf.dispatchFetch("https://worker.test/local-admin")).status, 200);
  });

  const deviceId = "a".repeat(64);
  await t.test("账号注册和配置查询均通过 AES-GCM，网络正文不含账号或设备信息", async () => {
    const registration = await seal("/api/account/register", { method: "POST", body: { deviceId, email: "transport@example.com", password: "test-password" } });
    assert.equal(registration.packet.includes(Buffer.from(deviceId)), false);
    const result = await open(await send(registration.packet), registration.responseKey);
    assert.equal(result.status, 200);
    assert.equal(JSON.parse(result.body).ok, true);
    assert.equal(result.packet.includes(Buffer.from(deviceId)), false);
    const query = await seal(`/api/client?deviceId=${deviceId}`);
    assert.equal(query.packet.includes(Buffer.from("deviceId")), false);
    const config = await open(await send(query.packet), query.responseKey);
    assert.equal(config.status, 200);
    assert.ok(Array.isArray(JSON.parse(config.body).subscriptions));
  });

  await t.test("业务 400 错误也被加密且保留原状态码和 Content-Type", async () => {
    const sealed = await seal("/api/account/register", { method: "POST", body: {} });
    const result = await open(await send(sealed.packet), sealed.responseKey);
    assert.equal(result.status, 400);
    assert.match(result.headers.get("content-type"), /application\/json/);
    assert.equal(JSON.parse(result.body).error, "invalid_device_id");
  });

  await t.test("篡改 GCM 标签、封装密钥、版本或截断报文均不进入业务层", async () => {
    const sealed = await seal("/api/account/register", { method: "POST", body: { deviceId: "b".repeat(64) } });
    for (const index of [0, 4, 9, sealed.packet.length - 1]) {
      const corrupted = Buffer.from(sealed.packet);
      corrupted[index] ^= 1;
      assert.equal((await send(corrupted)).status, 400);
    }
    assert.equal((await send(sealed.packet.subarray(0, 200))).status, 400);
  });

  await t.test("串行与并发重放都只接受一次", async () => {
    const first = await seal(`/api/client?deviceId=${deviceId}`);
    assert.equal((await send(first.packet)).status, 200);
    assert.equal((await send(first.packet)).status, 400);
    const second = await seal(`/api/client?deviceId=${deviceId}`);
    const results = await Promise.all([send(second.packet), send(second.packet)]);
    assert.deepEqual(results.map((response) => response.status).sort(), [200, 400]);
  });

  await t.test("拒绝过期、未来、管理接口、嵌套传输和路径逃逸", async () => {
    for (const timestamp of [Date.now() - 180000, Date.now() + 180000]) {
      const stale = await seal("/api/client", { timestamp });
      assert.equal((await send(stale.packet)).status, 400);
    }
    for (const path of ["/local-admin/api/state", "/api/transport", "/api/account/../transport", "/api/client#fragment"]) {
      assert.equal((await send((await seal(path)).packet)).status, 400);
    }
  });
});

test("密钥未配置或格式错误时不发送伪加密成功响应", async () => {
  for (const env of [{}, { CLIENT_TRANSPORT_PRIVATE_KEY: "not-a-key" }]) {
    const response = await handleClientTransport(new Request("https://worker.test/api/transport/key"), env,
      async () => { assert.fail("must not dispatch"); });
    assert.equal(response.status, 503);
  }
});

const executable = process.env.CHIMERA_NATIVE_TRANSPORT_TEST_EXE;
for (const corrupt of [false, true]) {
  test(`C++ native 与 WebCrypto 互通${corrupt ? "并拒绝损坏的响应标签" : "并限制会话只能使用一次"}`,
    { skip: !executable }, async () => {
      const child = spawn(executable, [], { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
      const exit = once(child, "exit");
      const lines = createInterface({ input: child.stdout });
      const packetLine = once(lines, "line");
      const payload = Buffer.from('账号密码\u0000UTF-8:🔐');
      child.stdin.write(`${generated.publicKey.toString("hex")}\n${payload.toString("hex")}\n`);
      const [hex] = await packetLine;
      const packet = Uint8Array.from(Buffer.from(hex, "hex"));
      const opened = await openRequestPacket(packet, privateKey);
      assert.deepEqual(Buffer.from(opened.plaintext), payload);
      const corruptedRequest = Uint8Array.from(packet);
      corruptedRequest[corruptedRequest.length - 1] ^= 1;
      await assert.rejects(openRequestPacket(corruptedRequest, privateKey));
      const response = encoder.encode(JSON.stringify({ ok: true, data: "服务端响应🔑".repeat(10000) }));
      const encrypted = await sealResponsePacket(response, opened.responseKey);
      if (corrupt) encrypted[encrypted.length - 1] ^= 1;
      const responseLine = corrupt ? null : once(lines, "line");
      child.stdin.end(Buffer.from(encrypted).toString("hex") + "\n");
      if (responseLine) {
        const [decrypted] = await responseLine;
        assert.deepEqual(Buffer.from(decrypted, "hex"), Buffer.from(response));
      }
      const [code] = await exit;
      assert.equal(code, corrupt ? 1 : 0);
      lines.close();
    });
}

test("C++ native 拒绝非法公钥", { skip: !executable }, () => {
  const result = spawnSync(executable, [], { input: "01\n\n", encoding: "utf8", windowsHide: true });
  assert.equal(result.status, 1);
});
