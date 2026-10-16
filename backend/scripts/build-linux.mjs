import { cpSync, mkdirSync } from "node:fs";
import { build } from "esbuild";

const outputDirectory = "dist/linux";

await build({
  entryPoints: ["src/linux/server.ts"],
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node22",
  loader: {
    ".html": "text",
    ".css": "text",
    ".js": "text",
  },
  outfile: `${outputDirectory}/server.mjs`,
});

const migrationsOutput = `${outputDirectory}/migrations/subscriptions`;
mkdirSync(migrationsOutput, { recursive: true });
cpSync("migrations/subscriptions", migrationsOutput, { recursive: true });
console.info(`Linux backend bundle and migrations written to ${outputDirectory}`);
