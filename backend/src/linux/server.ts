import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath, URL as NodeURL } from "node:url";
import { resolve } from "node:path";
import { fetchBackend, scheduledBackend, type BackendExecutionContext } from "../worker-core.ts";
import { resolveAdminBasePath } from "../config.ts";
import { initializeUserSchema } from "../services/user-store.ts";
import type { Env } from "../types/env.ts";
import { createLinuxStorage } from "./storage.ts";

const host = process.env.HOST?.trim() || "127.0.0.1";
const port = Number.parseInt(process.env.PORT || "8788", 10);
const adminPath = process.env.ADMIN_PATH?.trim() || "";
const adminToken = process.env.ADMIN_TOKEN || "";
const dataDirectory = resolve(process.env.LINUX_DATA_DIR || "./data/linux");
const migrationDirectory = process.env.LINUX_MIGRATIONS_DIR
  ? resolve(process.env.LINUX_MIGRATIONS_DIR)
  : fileURLToPath(new NodeURL("./migrations/subscriptions/", import.meta.url));

if (!resolveAdminBasePath(adminPath)) {
  throw new Error("ADMIN_PATH must be 1-128 letters, numbers, underscores, or hyphens");
}
if (!adminToken.trim()) throw new Error("ADMIN_TOKEN must be set before starting the Linux backend");
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("PORT must be an integer between 1 and 65535");
}

const storage = createLinuxStorage(dataDirectory, migrationDirectory);
const env: Env = {
  ...storage,
  ADMIN_PATH: adminPath,
  ADMIN_TOKEN: adminToken,
  SERVICE_NAME: "chimera-linux",
  CLIENT_TRANSPORT_PRIVATE_KEY: process.env.CLIENT_TRANSPORT_PRIVATE_KEY,
};

await initializeUserSchema(env);

const pendingTasks = new Set<Promise<unknown>>();
const executionContext = (): BackendExecutionContext => ({
  waitUntil(promise: Promise<unknown>) {
    const task = Promise.resolve(promise);
    pendingTasks.add(task);
    void task.catch((error: unknown) => {
      console.error(JSON.stringify({
        message: "background_task_failed",
        error: error instanceof Error ? error.message : String(error),
      }));
    }).finally(() => pendingTasks.delete(task));
  },
});

function requestFromNode(message: IncomingMessage, signal: AbortSignal) {
  const forwardedProto = message.headers["x-forwarded-proto"];
  const proto = (Array.isArray(forwardedProto) ? forwardedProto[0] : forwardedProto)
    ?.split(",", 1)[0].trim().toLowerCase() === "https" ? "https" : "http";
  const authority = message.headers.host || `${host}:${port}`;
  const url = new URL(message.url || "/", `${proto}://${authority}`);
  const headers = new Headers();
  const hopByHopHeaders = new Set([
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade", "host",
  ]);
  for (const [name, value] of Object.entries(message.headers)) {
    if (value === undefined || hopByHopHeaders.has(name.toLowerCase())) continue;
    if (Array.isArray(value)) value.forEach((item) => headers.append(name, item));
    else headers.set(name, value);
  }

  const method = message.method || "GET";
  const hasBody = method !== "GET" && method !== "HEAD";
  const init = {
    method,
    headers,
    signal,
    ...(hasBody ? {
      body: Readable.toWeb(message) as unknown as globalThis.ReadableStream<Uint8Array>,
      duplex: "half" as const,
    } : {}),
  } as RequestInit & { duplex?: "half" };
  return new Request(url, init);
}

async function sendResponse(response: Response, outgoing: ServerResponse) {
  outgoing.statusCode = response.status;
  outgoing.statusMessage = response.statusText;
  for (const [name, value] of response.headers) {
    if (name.toLowerCase() === "set-cookie") continue;
    outgoing.setHeader(name, value);
  }
  const cookies = response.headers.getSetCookie();
  if (cookies.length) outgoing.setHeader("set-cookie", cookies);
  if (!response.body) {
    outgoing.end();
    return;
  }
  await pipeline(
    Readable.fromWeb(response.body as unknown as import("node:stream/web").ReadableStream),
    outgoing,
  );
}

const server = createServer((incoming, outgoing) => {
  const abortController = new AbortController();
  incoming.once("aborted", () => abortController.abort());
  void (async () => {
    try {
      const request = requestFromNode(incoming, abortController.signal);
      const response = await fetchBackend(request, env, executionContext());
      await sendResponse(response, outgoing);
    } catch (error) {
      if (outgoing.destroyed) return;
      console.error(JSON.stringify({
        message: "linux_http_request_failed",
        error: error instanceof Error ? error.message : String(error),
      }));
      if (!outgoing.headersSent) {
        outgoing.statusCode = 500;
        outgoing.setHeader("content-type", "application/json; charset=utf-8");
        outgoing.end('{"error":"internal_error"}');
      } else {
        outgoing.destroy();
      }
    }
  })();
});

server.on("clientError", (_error, socket) => {
  socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
});

async function runScheduledCleanup() {
  const tasks: Promise<unknown>[] = [];
  const context: BackendExecutionContext = {
    waitUntil(promise: Promise<unknown>) { tasks.push(Promise.resolve(promise)); },
  };
  scheduledBackend(env, context);
  await Promise.allSettled(tasks);
}

function millisecondsUntilNextCleanup() {
  const now = new Date();
  const next = new Date(now);
  next.setUTCHours(19, 0, 0, 0);
  if (next.getTime() <= now.getTime()) next.setUTCDate(next.getUTCDate() + 1);
  return next.getTime() - now.getTime();
}

let cleanupTimer: NodeJS.Timeout;
function scheduleCleanup() {
  cleanupTimer = setTimeout(() => {
    void runScheduledCleanup()
      .catch((error: unknown) => {
        console.error(JSON.stringify({
          message: "scheduled_cleanup_failed",
          error: error instanceof Error ? error.message : String(error),
        }));
      })
      .finally(scheduleCleanup);
  }, millisecondsUntilNextCleanup());
  cleanupTimer.unref();
}

server.listen(port, host, () => {
  console.info(JSON.stringify({
    message: "linux_backend_started",
    service: env.SERVICE_NAME,
    host,
    port,
    dataDirectory,
  }));
  scheduleCleanup();
});

let shuttingDown = false;
async function shutdown(signal: string) {
  if (shuttingDown) return;
  shuttingDown = true;
  clearTimeout(cleanupTimer);
  console.info(JSON.stringify({ message: "linux_backend_stopping", signal }));
  await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
  await Promise.allSettled([...pendingTasks]);
  storage.close();
  process.exitCode = 0;
}

process.once("SIGINT", () => { void shutdown("SIGINT"); });
process.once("SIGTERM", () => { void shutdown("SIGTERM"); });
