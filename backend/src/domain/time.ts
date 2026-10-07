/*
 * Beijing business timestamps and reporting period keys.
 */

export function usagePeriodKeys(date: Date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {} as Record<string, string>);
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, hour: `${day}T${parts.hour}`, month: `${parts.year}-${parts.month}` };
}

export function beijingTimestamp(date = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== "literal") result[part.type] = part.value;
    return result;
  }, {} as Record<string, string>);
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`;
}

export function normalizeReportTimestamp(value: unknown, fallback: string | null = ""): string | null {
  if (value === undefined || value === null || value === "") return fallback;
  const date = typeof value === "number"
    ? new Date(value)
    : new Date(String(value));
  return Number.isFinite(date.getTime()) ? beijingTimestamp(date) : null;
}

