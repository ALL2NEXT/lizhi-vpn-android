import {
  DEFAULT_EXPERIENCE_THRESHOLDS,
  MAX_ACCOUNT_EXPERIENCE,
  MAX_TRAFFIC_BYTES,
  TEXT_ENCODER,
} from "../config.js";
import { cleanText } from "../lib/values.js";

const INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const REGISTRATION_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export function normalizeUserReference(value) {
  return normalizeUid(value) || normalizeAccountId(value);
}

export function normalizeNickname(value, fallback = "") {
  const nickname = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 24);
  return nickname || fallback;
}

export function normalizeLevel(value, fallback = 1) {
  const level = Number(value);
  return Number.isInteger(level) && level >= 0 && level <= 6 ? level : fallback;
}

/** Only accounts with an email credential are formal accounts eligible for experience. */
export function isEmailBoundAccount(user) {
  return Boolean(user && cleanText(user.email));
}

export function normalizeExperience(value, fallback = 0) {
  const experience = Number(value);
  if (Number.isInteger(experience) && experience >= 0 && experience <= MAX_ACCOUNT_EXPERIENCE) {
    return experience;
  }
  const fallbackExperience = Number(fallback);
  return Number.isInteger(fallbackExperience) && fallbackExperience >= 0
    ? Math.min(fallbackExperience, MAX_ACCOUNT_EXPERIENCE)
    : 0;
}

export function normalizeExperienceThresholds(value) {
  const values = Array.isArray(value) ? value : [];
  return DEFAULT_EXPERIENCE_THRESHOLDS.map((fallback, index) => {
    const threshold = Number(values[index]);
    return Number.isInteger(threshold) && threshold > 0 && threshold <= MAX_ACCOUNT_EXPERIENCE
      ? threshold
      : fallback;
  });
}

export function accountProgress(experienceTotalValue, thresholds, status = "active") {
  const experienceTotal = normalizeExperience(experienceTotalValue);
  if (status === "banned") {
    return { level: 0, experience: experienceTotal, experienceTotal, status: "banned" };
  }

  let level = 1;
  let experience = experienceTotal;
  const normalizedThresholds = normalizeExperienceThresholds(thresholds);
  while (level < 6) {
    const required = normalizedThresholds[level - 1];
    if (experience < required) break;
    experience -= required;
    level += 1;
  }
  return { level, experience, experienceTotal, status: "active" };
}

export function experienceTotalForLevel(levelValue, thresholds) {
  const level = normalizeLevel(levelValue, 1);
  if (level <= 1) return 0;
  return normalizeExperienceThresholds(thresholds)
    .slice(0, Math.min(level - 1, 5))
    .reduce((total, threshold) => total + threshold, 0);
}

export function normalizeAndroidSdk(value, fallback = 0) {
  const sdk = Number(value);
  if (Number.isInteger(sdk) && sdk >= 0 && sdk <= 1000) return sdk;
  const fallbackSdk = Number(fallback);
  return Number.isInteger(fallbackSdk) && fallbackSdk >= 0 && fallbackSdk <= 1000
    ? fallbackSdk
    : 0;
}

export function defaultNickname(accountId) {
  return `流萤用户${cleanText(accountId).slice(-6).toUpperCase()}`;
}

export function normalizeAccountId(value) {
  const accountId = cleanText(value).toLowerCase();
  return /^[a-f0-9]{64}$/.test(accountId) ? accountId : "";
}

export function normalizeAccountEmail(value) {
  const email = cleanText(value).toLowerCase();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
    ? email
    : "";
}

export function normalizeAccountPassword(value) {
  if (typeof value !== "string") return "";
  return value.length >= 8 && value.length <= 128 ? value : "";
}

export function normalizeTelegramJoinCode(value) {
  const code = cleanText(value).replace(/[\u0000-\u001f\u007f]/g, "");
  return code.length >= 1 && code.length <= 128 ? code : "";
}

export function normalizeInviteCode(value) {
  const code = cleanText(value).toUpperCase().replace(/[\s-]/g, "");
  return /^[A-HJ-NP-Z2-9]{8}$/.test(code) ? code : "";
}

export function normalizeRegistrationCode(value) {
  const code = cleanText(value).toUpperCase().replace(/[\s-]/g, "");
  return /^[A-HJ-NP-Z2-9]{15}$/.test(code) ? code : "";
}

export function randomInviteCode() {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => INVITE_CODE_ALPHABET[byte % INVITE_CODE_ALPHABET.length]).join("");
}

export function randomRegistrationCode() {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return `REG${Array.from(
    bytes,
    (byte) => REGISTRATION_CODE_ALPHABET[byte % REGISTRATION_CODE_ALPHABET.length],
  ).join("")}`;
}

export async function hashAccountPassword(password) {
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

export async function verifyAccountPassword(password, storedHash) {
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

export function base64EncodeBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function base64DecodeBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function constantTimeBytesEqual(left, right) {
  return left.length === right.length && crypto.subtle.timingSafeEqual(left, right);
}

export function normalizeUid(value) {
  const uid = cleanText(value).toLowerCase();
  return /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(uid)
    ? uid
    : "";
}

export function normalizeStoredUid(value) {
  return normalizeUid(value) || normalizeAccountId(value);
}

export function randomPublicUid() {
  return crypto.randomUUID().toLowerCase();
}

export function normalizeBatchUserReferences(value) {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.map(normalizeUserReference).filter(Boolean)));
}

export function boundedMegabytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number * 1e6) / 1e6, 1e9)
    : 0;
}

export function boundedBytes(value) {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) && number > 0
    ? Math.min(Math.round(number), MAX_TRAFFIC_BYTES)
    : 0;
}

export function megabytesToBytes(value) {
  return boundedBytes(boundedMegabytes(value) * 1024 * 1024);
}

export function bytesToMegabytes(value) {
  return boundedMegabytes(boundedBytes(value) / (1024 * 1024));
}

export function reportBytes(bytesValue, legacyMegabytesValue) {
  if (bytesValue !== undefined && bytesValue !== null && bytesValue !== "") {
    return boundedBytes(bytesValue);
  }
  return megabytesToBytes(legacyMegabytesValue);
}
