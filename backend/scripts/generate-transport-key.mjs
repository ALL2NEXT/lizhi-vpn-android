import { generateKeyPairSync } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const destination = resolve(process.argv[2] || fileURLToPath(new URL("../.secrets/client-transport.env", import.meta.url)));
const { privateKey } = generateKeyPairSync("rsa", {
  modulusLength: 3072,
  publicKeyEncoding: { type: "spki", format: "der" },
  privateKeyEncoding: { type: "pkcs8", format: "der" },
});
mkdirSync(dirname(destination), { recursive: true, mode: 0o700 });
writeFileSync(destination, `CLIENT_TRANSPORT_PRIVATE_KEY=${privateKey.toString("base64")}\n`, {
  flag: "wx", mode: 0o600,
});
privateKey.fill(0);
console.log(`Transport secret written to ${destination}`);
