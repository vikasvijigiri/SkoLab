/** "9 Oct 2026", in the reader's locale. */
export function formatDate(iso: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { day: "numeric", month: "short", year: "numeric" }).format(new Date(iso));
}

/** "9 Oct 2026, 10:24", in the reader's locale and time zone. */
export function formatDateTime(iso: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
}

const STEPS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 3600],
  ["month", 30 * 24 * 3600],
  ["week", 7 * 24 * 3600],
  ["day", 24 * 3600],
  ["hour", 3600],
  ["minute", 60],
];

/** "just now", "5 minutes ago", "yesterday". */
export function timeAgo(iso: string, now = Date.now(), locale?: string): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  if (Math.abs(seconds) < 45) return "just now";
  const format = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  for (const [unit, size] of STEPS) {
    if (Math.abs(seconds) >= size || unit === "minute") return format.format(Math.round(seconds / size), unit);
  }
  return "just now";
}
