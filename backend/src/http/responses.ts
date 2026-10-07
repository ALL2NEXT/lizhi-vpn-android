import type { Env } from "../types/env.ts";

import { CLIENT_IP_HEADER, LEGACY_CLIENT_IP_HEADER } from "../config.ts";
import { cleanText } from "../lib/values.ts";

export async function isAdmin(request: Request, env: Env) {
  const token = cleanText(env.ADMIN_TOKEN);
  const authorization = cleanText(request.headers.get("authorization"));
  if (!token) return false;

  const encoder = new TextEncoder();
  const [providedHash, expectedHash] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(authorization)),
    crypto.subtle.digest("SHA-256", encoder.encode(`Bearer ${token}`)),
  ]);
  return crypto.subtle.timingSafeEqual(providedHash, expectedHash);
}

export function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
    "access-control-allow-headers": "Content-Type, Authorization",
    "access-control-expose-headers": `${CLIENT_IP_HEADER}, ${LEGACY_CLIENT_IP_HEADER}`,
  };
}

export function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...corsHeaders(),
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}
