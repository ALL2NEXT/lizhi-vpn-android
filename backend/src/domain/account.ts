import type { AccountType } from "../types/models.ts";

import { MAX_TRAFFIC_BYTES, TEXT_ENCODER } from "../config.ts";
import { cleanText } from "../lib/values.ts";

const REGISTRATION_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export function normalizeUserReference(value: unknown) {
  return normalizeUid(value) || normalizeAccountId(value);
}

export function normalizeNickname(value: unknown, fallback = "") {
  const nickname = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 24);
  return nickname || fallback;
}

export const ACCOUNT_TYPE_BANNED = "banned";
export const ACCOUNT_TYPE_GUEST = "guest";
export const ACCOUNT_TYPE_FORMAL = "formal";

export function normalizeAccountType(value: unknown, fallback: AccountType = ACCOUNT_TYPE_GUEST): AccountType {
  const normalized = cleanText(value);
  return normalized === ACCOUNT_TYPE_BANNED || normalized === ACCOUNT_TYPE_GUEST || normalized === ACCOUNT_TYPE_FORMAL
    ? normalized
    : fallback;
}

export function accountTypeRank(value: unknown) {
  switch (cleanText(value)) {
    case ACCOUNT_TYPE_BANNED:
      return 0;
    case ACCOUNT_TYPE_GUEST:
      return 1;
    case ACCOUNT_TYPE_FORMAL:
      return 2;
    default:
      return 0;
  }
}

export function canAccessAccountType(accountType: AccountType, minimumAccountType: AccountType) {
  return accountTypeRank(accountType) >= accountTypeRank(minimumAccountType);
}

export function isFormalAccount(user: { email?: unknown; status?: unknown; deviceBlocked?: unknown } | null | undefined) {
  return Boolean(user && cleanText(user.email));
}

export function accountTypeForUser(user: { email?: unknown; status?: unknown; deviceBlocked?: unknown } | null | undefined) {
  if (cleanText(user && user.status) === "banned" || Boolean(user && user.deviceBlocked)) {
    return ACCOUNT_TYPE_BANNED;
  }
  return isFormalAccount(user) ? ACCOUNT_TYPE_FORMAL : ACCOUNT_TYPE_GUEST;
}

export function normalizeAndroidSdk(value: unknown, fallback: unknown = 0) {
  const sdk = Number(value);
  if (Number.isInteger(sdk) && sdk >= 0 && sdk <= 1000) return sdk;
  const fallbackSdk = Number(fallback);
  return Number.isInteger(fallbackSdk) && fallbackSdk >= 0 && fallbackSdk <= 1000
    ? fallbackSdk
    : 0;
}

export function defaultNickname(accountId: unknown) {
  return `用户${cleanText(accountId).slice(-6).toUpperCase()}`;
}

export function normalizeAccountId(value: unknown) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

export function normalizeAccountEmail(value: unknown) {
  const email = cleanText(value).toLowerCase();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
    ? email
    : "";
}

export function normalizeAccountPassword(value: unknown) {
  if (typeof value !== "string") return "";
  return value.length >= 8 && value.length <= 128 ? value : "";
}

export function normalizeRegistrationCode(value: unknown) {
  const code = cleanText(value).toUpperCase().replace(/[\s-]/g, "");
  return /^[A-HJ-NP-Z2-9]{15}$/.test(code) ? code : "";
}

export function normalizeVipActivationCode(value: unknown) {
  const code = cleanText(value).toUpperCase().replace(/[\s-]/g, "");
  return /^VIP[A-HJ-NP-Z2-9]{12}$/.test(code) ? code : "";
}

export function randomRegistrationCode() {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return `REG${Array.from(
    bytes,
    (byte) => REGISTRATION_CODE_ALPHABET[byte % REGISTRATION_CODE_ALPHABET.length],
  ).join("")}`;
}

export async function hashAccountPassword(password: string) {
  const iterations = 100000;
  const salt = new Uint8Array(16);
  crypto.getRandomValues(salt);
  const key = await crypto.subtle.importKey(
    "raw",
    TEXT_ENCODER.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations,
      hash: "SHA-256",
    },
    key,
    256,
  );
  return `pbkdf2-sha256$${iterations}$${base64EncodeBytes(salt)}$${base64EncodeBytes(new Uint8Array(derived))}`;
}

export async function verifyAccountPassword(password: string, storedHash: unknown) {
  const parts = String(storedHash || "").split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 10000 || iterations > 1000000) {
    return false;
  }

  let salt;
  let expected;
  try {
    salt = base64DecodeBytes(parts[2]);
    expected = base64DecodeBytes(parts[3]);
  } catch (_) {
    return false;
  }
  if (salt.length !== 16 || expected.length !== 32) return false;

  const key = await crypto.subtle.importKey(
    "raw",
    TEXT_ENCODER.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations, hash: "SHA-256" },
    key,
    expected.length * 8,
  );
  return constantTimeBytesEqual(new Uint8Array(derived), expected);
}

export function base64EncodeBytes(bytes: Uint8Array) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function base64DecodeBytes(value: string) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function constantTimeBytesEqual(left: Uint8Array, right: Uint8Array) {
  return left.length === right.length && crypto.subtle.timingSafeEqual(left, right);
}

export function normalizeUid(value: unknown) {
  const uid = cleanText(value).toLowerCase();
  return /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(uid)
    ? uid
    : "";
}

export function normalizeStoredUid(value: unknown) {
  return normalizeUid(value) || normalizeAccountId(value);
}

export function randomPublicUid() {
  return crypto.randomUUID().toLowerCase();
}

export function normalizeBatchUserReferences(value: unknown) {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.map(normalizeUserReference).filter(Boolean)));
}

export function boundedMegabytes(value: unknown) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number * 1e6) / 1e6, 1e9)
    : 0;
}

export function boundedBytes(value: unknown) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number), MAX_TRAFFIC_BYTES)
    : 0;
}

export function megabytesToBytes(value: unknown) {
  return boundedBytes(boundedMegabytes(value) * 1024 * 1024);
}

export function bytesToMegabytes(value: unknown) {
  return boundedMegabytes(boundedBytes(value) / (1024 * 1024));
}

export function reportBytes(bytesValue: unknown, legacyMegabytesValue: unknown) {
  if (bytesValue !== undefined && bytesValue !== null && bytesValue !== "") {
    return boundedBytes(bytesValue);
  }
  return megabytesToBytes(legacyMegabytesValue);
}
