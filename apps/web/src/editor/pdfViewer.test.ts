import { afterEach, describe, expect, it, vi } from "vitest";
import type { PDFDocumentProxy } from "pdfjs-dist/legacy/build/pdf.mjs";
import { PdfViewer } from "./pdfViewer";

/** A stand-in for pdf.js: pages 600 x 800 points; each drawing finishes when told to (or at once). */
function fakeDocument(pages: number, { manual = false, size = [600, 800] } = {}) {
  const renders: { scale: number; finish: () => void; cancelled: boolean }[] = [];
  const getPage = vi.fn((number: number) =>
    Promise.resolve({
      number,
      getViewport: ({ scale }: { scale: number }) => ({ width: (size[0] ?? 600) * scale, height: (size[1] ?? 800) * scale }),
      render: ({ viewport }: { viewport: { width: number } }) => {
        let finish: () => void = () => undefined;
        let fail: (error: Error) => void = () => undefined;
        const promise = new Promise<void>((resolve, reject) => {
          finish = resolve;
          fail = reject;
        });
        const entry = { scale: viewport.width / (size[0] ?? 600), finish, cancelled: false };
        renders.push(entry);
        if (!manual) finish();
        return {
          promise,
          cancel: () => {
            entry.cancelled = true;
            fail(new Error("cancelled"));
          },
        };
      },
    }),
  );
  const doc = { numPages: pages, getPage } as unknown as PDFDocumentProxy;
  return { doc, renders, getPage };
}

/** The PDF pane: a scroller 632px wide (so a 600-point page fits at scale 1) around the page column. */
function pane(width = 632) {
  const scroller = document.createElement("div");
  scroller.style.overflowY = "auto";
  let current = width;
  Object.defineProperty(scroller, "clientWidth", { get: () => current, configurable: true });
  Object.defineProperty(scroller, "clientHeight", { get: () => 900, configurable: true });
  Object.defineProperty(scroller, "scrollHeight", { get: () => 4000, configurable: true });
  Object.defineProperty(scroller, "scrollWidth", { get: () => current, configurable: true });
  const host = document.createElement("div");
  scroller.append(host);
  document.body.append(scroller);
  return { scroller, host, resize: (next: number) => (current = next) };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 200));
const boxes = (host: HTMLElement) => [...host.querySelectorAll<HTMLElement>("[data-page]")];

afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

describe("PdfViewer", () => {
  it("lays out every page to fit the pane and draws them", async () => {
    const { host } = pane();
    const { doc } = fakeDocument(3);
    const destroy = vi.fn(() => Promise.resolve());
    const viewer = new PdfViewer(host, doc, destroy);
    await viewer.open(1, null);
    await settle();
    expect(viewer.numPages).toBe(3);
    expect(boxes(host).map((box) => [box.style.width, box.style.height])).toEqual([
      ["600px", "800px"],
      ["600px", "800px"],
      ["600px", "800px"],
    ]);
    for (const [index, box] of boxes(host).entries()) {
      expect(box).toHaveAttribute("aria-label", `Page ${index + 1} of 3`);
      expect(box.querySelector("canvas")?.width).toBe(600);
    }
    viewer.close();
    expect(destroy).toHaveBeenCalledOnce();
  });

  it("resizes at once on zoom, then redraws sharply without loading the document again", async () => {
    const { host } = pane();
    const { doc, renders } = fakeDocument(2);
    const viewer = new PdfViewer(host, doc, () => Promise.resolve());
    await viewer.open(1, null);
    await settle();
    const drawn = renders.length;

    viewer.setZoom(1.25);
    // Synchronously: the new size, the old drawing still in place.
    expect(boxes(host)[0]?.style.width).toBe("750px");
    expect(boxes(host)[0]?.querySelector("canvas")?.width).toBe(600);

    await settle();
    expect(renders.slice(drawn).map((render) => render.scale)).toEqual([1.25, 1.25]);
    expect(boxes(host)[0]?.querySelectorAll("canvas")).toHaveLength(1);
    expect(boxes(host)[0]?.querySelector("canvas")?.width).toBe(750);

    // The same zoom again is nothing to do.
    viewer.setZoom(1.25);
    await settle();
    expect(renders.length).toBe(drawn + 2);
    viewer.close();
  });

  it("cancels drawing for a zoom that a newer one replaced", async () => {
    const { host } = pane();
    const { doc, renders } = fakeDocument(1, { manual: true });
    const viewer = new PdfViewer(host, doc, () => Promise.resolve());
    const opening = viewer.open(1, null);
    await vi.waitFor(() => expect(renders).toHaveLength(1));
    renders[0]?.finish();
    await opening;

    viewer.setZoom(1.5);
    await vi.waitFor(() => expect(renders).toHaveLength(2));
    viewer.setZoom(2);
    expect(boxes(host)[0]?.style.width).toBe("1200px");
    await vi.waitFor(() => expect(renders).toHaveLength(3));
    expect(renders[1]?.cancelled).toBe(true);
    renders[2]?.finish();
    await vi.waitFor(() => expect(boxes(host)[0]?.querySelector("canvas")?.width).toBe(1200));
    viewer.close();
  });

  it("does nothing after closing, or before opening", async () => {
    const { host } = pane();
    const { doc, renders } = fakeDocument(1, { manual: true });
    const viewer = new PdfViewer(host, doc, () => Promise.resolve());
    viewer.setZoom(2);
    expect(host.children).toHaveLength(0);

    const opening = viewer.open(1, null);
    await vi.waitFor(() => expect(renders).toHaveLength(1));
    viewer.close();
    expect(renders[0]?.cancelled).toBe(true);
    await opening;
    expect(host.children).toHaveLength(0);
  });

  it("keeps the reader's place: a recompile at the same scroll position, a zoom about the centre", async () => {
    const { host, scroller } = pane();
    const { doc } = fakeDocument(5);
    const viewer = new PdfViewer(host, doc, () => Promise.resolve());
    await viewer.open(1, 1700);
    expect(scroller.scrollTop).toBe(1700);
    viewer.setZoom(2);
    // The centre (1700 + 450) / 4000 of the way down stays there.
    expect(scroller.scrollTop).toBeCloseTo((2150 / 4000) * 4000 - 450, 0);
    viewer.close();
  });

  it("caps a page canvas's pixels however far it is zoomed", async () => {
    const { host } = pane();
    vi.stubGlobal("devicePixelRatio", 3);
    const { doc } = fakeDocument(1, { size: [2400, 3200] });
    const viewer = new PdfViewer(host, doc, () => Promise.resolve());
    await viewer.open(1, null);
    viewer.setZoom(2);
    await settle();
    const canvas = boxes(host)[0]?.querySelector("canvas");
    expect((canvas?.width ?? 0) * (canvas?.height ?? 0)).toBeLessThanOrEqual(16_000_000);
    expect(canvas?.width).toBeGreaterThan(0);
    viewer.close();
  });

  it("draws pages as they come into view, and refits when the pane is resized", async () => {
    const observed: Element[] = [];
    let intersect: (entries: { target: Element; isIntersecting: boolean }[]) => void = () => undefined;
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(callback: typeof intersect) {
          intersect = callback;
        }
        observe(element: Element) {
          observed.push(element);
        }
        disconnect() {}
      },
    );
    let resized: () => void = () => undefined;
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(callback: () => void) {
          resized = callback;
        }
        observe() {}
        disconnect() {}
      },
    );
    const { host, resize } = pane();
    const { doc, renders } = fakeDocument(4);
    const viewer = new PdfViewer(host, doc, () => Promise.resolve());
    await viewer.open(1, null);
    expect(observed).toHaveLength(4);
    // Only the first two pages fall in the first 900px.
    const first = renders.length;
    expect(first).toBe(2);

    intersect([{ target: boxes(host)[3] as Element, isIntersecting: true }]);
    await settle();
    expect(boxes(host)[3]?.querySelector("canvas")).not.toBeNull();
    intersect([{ target: boxes(host)[3] as Element, isIntersecting: false }]);

    // A scrollbar's worth is not a resize; a real one refits.
    resize(620);
    resized();
    expect(boxes(host)[0]?.style.width).toBe("600px");
    resize(332);
    resized();
    expect(boxes(host)[0]?.style.width).toBe("300px");
    viewer.close();
  });
});
