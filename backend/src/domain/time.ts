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

export interface BeijingDateTimeRange {
  start: string;
  end: string;
  endExclusive: string;
  startMilliseconds: number;
  endMilliseconds: number;
  endExclusiveMilliseconds: number;
}

/** 将 datetime-local 分钟值按北京时间解析，并让结束分钟包含在范围内。 */
export function normalizeBeijingDateTimeRange(
  startValue: unknown,
  endValue: unknown,
  maximumDurationMilliseconds: number,
): BeijingDateTimeRange | null {
  const parseMinute = (value: unknown) => {
    if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null;
    const milliseconds = Date.parse(`${value}:00+08:00`);
    if (!Number.isFinite(milliseconds) || beijingTimestamp(new Date(milliseconds)).slice(0, 16) !== value) return null;
    return { value, milliseconds };
  };

  const start = parseMinute(startValue);
  const end = parseMinute(endValue);
  if (!start || !end || start.milliseconds > end.milliseconds ||
    end.milliseconds - start.milliseconds > maximumDurationMilliseconds) return null;

  const endExclusiveMilliseconds = end.milliseconds + 60_000;
  return {
    start: beijingTimestamp(new Date(start.milliseconds)),
    end: beijingTimestamp(new Date(end.milliseconds)),
    endExclusive: beijingTimestamp(new Date(endExclusiveMilliseconds)),
    startMilliseconds: start.milliseconds,
    endMilliseconds: end.milliseconds,
    endExclusiveMilliseconds,
  };
}

export function normalizeReportTimestamp(value: unknown, fallback: string | null = ""): string | null {
  if (value === undefined || value === null || value === "") return fallback;
  const date = typeof value === "number"
    ? new Date(value)
    : new Date(String(value));
  return Number.isFinite(date.getTime()) ? beijingTimestamp(date) : null;
}

