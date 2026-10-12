import { describe, expect, it, vi } from "vitest";
import type { Metric } from "web-vitals";
import { beacon, pageOf, sample, startVitals } from "./vitals";

type Observer = (callback: (metric: Metric) => void) => void;

function fakeObservers() {
  const callbacks: Partial<Record<Metric["name"], (metric: Metric) => void>> = {};
  const on =
    (name: Metric["name"]): Observer =>
    (callback) => {
      callbacks[name] = callback;
    };
  const observe: Record<Metric["name"], Observer> = { CLS: on("CLS"), FCP: on("FCP"), INP: on("INP"), LCP: on("LCP"), TTFB: on("TTFB") };
  const emit = (name: Metric["name"], value: number) => callbacks[name]?.({ name, value } as Metric);
  return { observe, emit };
}

const tick = () => new Promise<void>((resolve) => queueMicrotask(resolve));

describe("page vitals", () => {
  it("names pages by their first path segment only", () => {
    expect(pageOf("/")).toBe("/");
    expect(pageOf("")).toBe("/");
    expect(pageOf("/sign-in")).toBe("/sign-in");
    expect(pageOf("/editor/3f2c-private")).toBe("/editor");
    expect(pageOf("//x//y")).toBe("/x");
    expect(pageOf(`/${"a".repeat(300)}`)).toHaveLength(100);
  });

  it("rounds values and drops impossible ones", () => {
    expect(sample({ name: "LCP", value: 1234.5678 })).toEqual({ name: "LCP", value: 1234.6 });
    expect(sample({ name: "CLS", value: 0.123456 })).toEqual({ name: "CLS", value: 0.1235 });
    expect(sample({ name: "INP", value: -1 })).toBeNull();
    expect(sample({ name: "TTFB", value: Number.NaN })).toBeNull();
    expect(sample({ name: "FCP", value: 60_001 })).toBeNull();
    expect(sample({ name: "CLS", value: 101 })).toBeNull();
  });

  it("sends values that arrive together as one report", async () => {
    const send = vi.fn<(url: string, body: string) => void>();
    const { observe, emit } = fakeObservers();
    startVitals({ apiBaseUrl: "https://api.example", pathname: "/editor/abc", send, observe });
    emit("TTFB", 80.04);
    emit("FCP", 410);
    await tick();
    emit("LCP", 900);
    emit("CLS", 0.01);
    emit("INP", 64);
    emit("LCP", 950); // a later value replaces the earlier one
    await tick();
    expect(send.mock.calls.map(([url, body]) => [url, JSON.parse(body) as unknown])).toEqual([
      ["https://api.example/api/v1/vitals", { page: "/editor", metrics: [{ name: "TTFB", value: 80 }, { name: "FCP", value: 410 }] }],
      ["https://api.example/api/v1/vitals", { page: "/editor", metrics: [{ name: "LCP", value: 950 }, { name: "CLS", value: 0.01 }, { name: "INP", value: 64 }] }],
    ]);
  });

  it("sends nothing for glitches alone, and flush is safe to call", async () => {
    const send = vi.fn();
    const { observe, emit } = fakeObservers();
    const flush = startVitals({ apiBaseUrl: "", pathname: "/", send, observe });
    emit("LCP", Number.POSITIVE_INFINITY);
    await tick();
    flush();
    expect(send).not.toHaveBeenCalled();
  });

  it("uses sendBeacon as text/plain, and falls back to a keepalive fetch", async () => {
    const sendBeacon = vi.fn<(url: string, data: Blob) => boolean>().mockReturnValue(true);
    Object.defineProperty(navigator, "sendBeacon", { value: sendBeacon, configurable: true });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));
    beacon("https://api.example/api/v1/vitals", '{"a":1}');
    const blob = sendBeacon.mock.calls[0]?.[1];
    expect(blob?.type).toBe("text/plain;charset=utf-8");
    expect(await blob?.text()).toBe('{"a":1}');
    expect(fetchSpy).not.toHaveBeenCalled();

    sendBeacon.mockReturnValue(false); // the browser's beacon queue is full
    beacon("https://api.example/api/v1/vitals", '{"a":2}');
    expect(fetchSpy).toHaveBeenCalledWith("https://api.example/api/v1/vitals", expect.objectContaining({ method: "POST", body: '{"a":2}', keepalive: true, credentials: "omit" }));

    fetchSpy.mockRejectedValue(new Error("offline"));
    expect(() => beacon("https://api.example/api/v1/vitals", "{}")).not.toThrow();
    sendBeacon.mockImplementation(() => {
      throw new TypeError("blocked");
    });
    expect(() => beacon("https://api.example/api/v1/vitals", "{}")).not.toThrow();
    Reflect.deleteProperty(navigator, "sendBeacon");
  });
});
