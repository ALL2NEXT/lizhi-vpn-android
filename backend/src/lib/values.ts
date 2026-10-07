/*
 * Small, dependency-free value helpers shared across Worker domains.
 */

export function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

export function plainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function parseFlag(value: unknown, fallback: boolean): boolean {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return !["0", "false", "off", "no"].includes(String(value).trim().toLowerCase());
}

