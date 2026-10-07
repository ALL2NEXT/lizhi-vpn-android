import type { GrowthPeriod } from "../types/models.ts";
import type { GrowthRow, TrafficRow, ActivityRow } from "../types/database.ts";

import { boundedBytes, bytesToMegabytes } from "./account.ts";
import { usagePeriodKeys } from "./time.ts";
import { cleanText } from "../lib/values.ts";

export function normalizeGrowthPeriod(value: unknown): GrowthPeriod {
  return value === "week" || value === "month" ? value : "day";
}

export function normalizeGrowthDateKey(value: unknown): string | null {
  const dateKey = cleanText(value);
  if (!dateKey) return "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) return null;
  const date = dayKeyToUtcDate(dateKey);
  return utcDateKey(date) === dateKey ? dateKey : null;
}

export function buildUserGrowthPoints(
  rows: GrowthRow[],
  period: GrowthPeriod,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const day = normalizeGrowthDateKey(row && row.date_key);
    const count = Number(row && row.new_users);
    if (!day || !Number.isInteger(count) || count <= 0) return null;
    return {
      day,
      key: userGrowthPeriodKey(dayKeyToUtcDate(day), period),
      count,
    };
  }).filter((record) => record !== null);

  if (records.length === 0) return [];

  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const points = [];
  const counts = new Map<string, number>();
  let totalUsers = 0;
  records.forEach((record) => {
    if (record.day < startDay) totalUsers += record.count;
    if (record.day >= startDay && record.day <= endDay) {
      counts.set(record.key, (counts.get(record.key) || 0) + record.count);
    }
  });
  let key = firstKey;

  while (key <= lastKey) {
    const newUsers = counts.get(key) || 0;
    totalUsers += newUsers;
    points.push({ key, newUsers, totalUsers });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

export function buildUserTrafficPoints(
  rows: TrafficRow[],
  period: GrowthPeriod,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const day = normalizeGrowthDateKey(row && row.date_key);
    if (!day) return null;
    return {
      day,
      key: userGrowthPeriodKey(dayKeyToUtcDate(day), period),
      uploadBytes: boundedBytes(row && row.upload_bytes),
      downloadBytes: boundedBytes(row && row.download_bytes),
    };
  }).filter((record) => record !== null);

  if (records.length === 0) return [];
  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const counts = new Map<string, { uploadBytes: number; downloadBytes: number }>();
  records.forEach((record) => {
    if (record.day < startDay || record.day > endDay) return;
    const current = counts.get(record.key) || { uploadBytes: 0, downloadBytes: 0 };
    current.uploadBytes += record.uploadBytes;
    current.downloadBytes += record.downloadBytes;
    counts.set(record.key, current);
  });

  const points = [];
  let key = firstKey;
  while (key <= lastKey) {
    const current = counts.get(key) || { uploadBytes: 0, downloadBytes: 0 };
    const uploadMb = bytesToMegabytes(current.uploadBytes);
    const downloadMb = bytesToMegabytes(current.downloadBytes);
    points.push({
      key,
      uploadMb,
      downloadMb,
      totalMb: Math.round((uploadMb + downloadMb) * 1e6) / 1e6,
    });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

export function buildUserActivityPoints(
  rows: ActivityRow[],
  period: GrowthPeriod,
  now = new Date(),
  rangeStart = "",
  rangeEnd = "",
) {
  const records = rows.map((row) => {
    const day = normalizeGrowthDateKey(row && row.date_key);
    const userId = Number(row && row.user_id);
    if (!day || !Number.isInteger(userId) || userId <= 0) return null;
    return {
      day,
      key: userGrowthPeriodKey(dayKeyToUtcDate(day), period),
      userId,
    };
  }).filter((record) => record !== null);

  if (records.length === 0) return [];
  const firstDataKey = records.map((record) => record.key).sort()[0];
  const todayKey = usagePeriodKeys(now).day;
  const firstDataDay = records.map((record) => record.day).sort()[0];
  const startDay = rangeStart || firstDataDay;
  const requestedEndDay = rangeEnd || todayKey;
  const endDay = requestedEndDay < todayKey ? requestedEndDay : todayKey;
  if (startDay > endDay) return [];

  const currentKey = userGrowthPeriodKey(now, period);
  const requestedStartKey = userGrowthPeriodKey(dayKeyToUtcDate(startDay), period);
  const requestedEndKey = userGrowthPeriodKey(dayKeyToUtcDate(endDay), period);
  const firstKey = requestedStartKey > firstDataKey ? requestedStartKey : firstDataKey;
  const lastKey = requestedEndKey < currentKey ? requestedEndKey : currentKey;
  if (firstKey > lastKey) return [];

  const counts = new Map<string, Set<number>>();
  records.forEach((record) => {
    if (record.day < startDay || record.day > endDay) return;
    const users = counts.get(record.key) || new Set<number>();
    users.add(record.userId);
    counts.set(record.key, users);
  });

  const points = [];
  let key = firstKey;
  while (key <= lastKey) {
    points.push({
      key,
      activeUsers: (counts.get(key) || new Set<number>()).size,
    });
    key = nextUserGrowthPeriodKey(key, period);
  }
  return points;
}

export function userGrowthPeriodKey(date: Date, period: GrowthPeriod) {
  const { day, month } = usagePeriodKeys(date);
  if (period === "month") return month;
  if (period === "week") return mondayOfDayKey(day);
  return day;
}

export function mondayOfDayKey(dayKey: string) {
  const date = dayKeyToUtcDate(dayKey);
  const daysSinceMonday = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - daysSinceMonday);
  return utcDateKey(date);
}

export function nextUserGrowthPeriodKey(key: string, period: GrowthPeriod) {
  if (period === "month") {
    const [year, month] = key.split("-").map(Number);
    const date = new Date(Date.UTC(year, month, 1));
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
  }
  const date = dayKeyToUtcDate(key);
  date.setUTCDate(date.getUTCDate() + (period === "week" ? 7 : 1));
  return utcDateKey(date);
}

export function dayKeyToUtcDate(dayKey: string) {
  const [year, month, day] = dayKey.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day));
}

export function utcDateKey(date: Date) {
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}
