/*
 * Small, dependency-free value helpers shared across Worker domains.
 */

export function cleanText(value) {
  return typeof value === "string" ? value.trim() : "";
}

export function plainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

export function parseFlag(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return !["0", "false", "off", "no"].includes(String(value).trim().toLowerCase());
}

