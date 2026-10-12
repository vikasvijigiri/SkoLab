import { onCLS, onFCP, onINP, onLCP, onTTFB, type Metric } from "web-vitals";

/**
 * Real-user page speed: Core Web Vitals from this visit, sent to the API
 * (POST /api/v1/vitals), which records them in Grafana beside the API's own
 * latency. Nothing identifies the visitor: only the page's first path
 * segment and the five numbers.
 *
 * web-vitals hands over each value once it is final: FCP and TTFB soon
 * after load, LCP, CLS and INP when the page is hidden (tab switch, close,
 * navigation away). Values that arrive together go out as one report, with
 * sendBeacon so they survive the page unloading: a few small reports a visit.
 */

/** Largest values the API accepts; anything above is a measuring glitch. */
const MAX: Record<Metric["name"], number> = { LCP: 60_000, INP: 60_000, FCP: 60_000, TTFB: 60_000, CLS: 100 };

export interface VitalsReport {
  page: string;
  metrics: { name: Metric["name"]; value: number }[];
}

/** "/editor/3f2c" → "/editor"; "/" stays "/". */
export function pageOf(pathname: string): string {
  const first = pathname.split("/").find((part) => part !== "");
  return first ? `/${first}`.slice(0, 100) : "/";
}

/** Rounds to what matters (0.1 ms; 4 decimals for CLS) and drops glitches. */
export function sample(metric: Pick<Metric, "name" | "value">): VitalsReport["metrics"][number] | null {
  const { name, value } = metric;
  if (!Number.isFinite(value) || value < 0 || value > MAX[name]) return null;
  const digits = name === "CLS" ? 1e4 : 10;
  return { name, value: Math.round(value * digits) / digits };
}

type Send = (url: string, body: string) => void;

/** sendBeacon as text/plain (no CORS preflight), else a keepalive fetch. */
export const beacon: Send = (url, body) => {
  try {
    const blob = new Blob([body], { type: "text/plain;charset=UTF-8" });
    if (typeof navigator.sendBeacon === "function" && navigator.sendBeacon(url, blob)) return;
    void fetch(url, { method: "POST", body, headers: { "Content-Type": "text/plain;charset=UTF-8" }, keepalive: true, credentials: "omit" }).catch(() => undefined);
  } catch {
    // Measuring must never break the page.
  }
};

export interface VitalsOptions {
  apiBaseUrl: string;
  pathname?: string;
  send?: Send;
  /** web-vitals' subscribe functions; replaced in tests. */
  observe?: Record<Metric["name"], (callback: (metric: Metric) => void) => void>;
}

/** Starts measuring this page load. Returns flush, for tests. */
export function startVitals({ apiBaseUrl, pathname = location.pathname, send = beacon, observe = { CLS: onCLS, FCP: onFCP, INP: onINP, LCP: onLCP, TTFB: onTTFB } }: VitalsOptions): () => void {
  const url = `${apiBaseUrl}/api/v1/vitals`;
  const page = pageOf(pathname);
  // The latest value per metric; a page restored from the back/forward
  // cache reports again, and that visit is sent as its own report.
  const pending = new Map<Metric["name"], number>();
  let scheduled = false;
  const flush = () => {
    scheduled = false;
    if (pending.size === 0) return;
    const report: VitalsReport = { page, metrics: [...pending].map(([name, value]) => ({ name, value })) };
    pending.clear();
    send(url, JSON.stringify(report));
  };
  const record = (metric: Metric) => {
    const kept = sample(metric);
    if (!kept) return;
    pending.set(kept.name, kept.value);
    // Values reported in the same task (e.g. LCP, CLS and INP on hide) share
    // one report; a microtask still runs before the page unloads.
    if (!scheduled) {
      scheduled = true;
      queueMicrotask(flush);
    }
  };
  for (const subscribe of Object.values(observe)) subscribe(record);
  return flush;
}
