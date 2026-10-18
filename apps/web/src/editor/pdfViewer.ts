import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from "pdfjs-dist/legacy/build/pdf.mjs";

/**
 * A PDF viewer that answers zoom at once, the way Overleaf and pdf.js's own
 * viewer do: the document is parsed once; zooming resizes every page box
 * immediately (the current drawing stretches to fit) and then redraws only
 * the pages in view, sharp, replacing each drawing when its replacement is
 * ready. Pages out of view are drawn as they scroll in. A newer zoom cancels
 * drawing still in progress, so a run of clicks never queues work.
 */

/** Space around and between pages, in CSS pixels (px-4 py-4, mb-4). */
const GUTTER = 32;
const GAP = 16;
/** Wait this long after the last zoom before redrawing sharply. */
const SETTLE_MS = 120;
/** A sharp canvas costs memory: at most 2 device pixels per CSS pixel... */
const MAX_RATIO = 2;
/** ...and at most this many pixels per page canvas (a 2x A4 page is ~4M). */
const MAX_PIXELS = 16_000_000;

interface PageView {
  number: number;
  /** Page size at scale 1, in PDF points. */
  width: number;
  height: number;
  box: HTMLDivElement;
  canvas: HTMLCanvasElement | null;
  /** The scale the current canvas was drawn at; 0 before the first. */
  drawn: number;
  task: RenderTask | null;
  /** The scale the drawing in progress is for. */
  taskScale: number;
}

export class PdfViewer {
  private pages: PageView[] = [];
  private zoom = 1;
  private fit = 1;
  private visible = new Set<number>();
  private observer: IntersectionObserver | null = null;
  private resize: ResizeObserver | null = null;
  private settle: number | undefined;
  private queue: Promise<void> = Promise.resolve();
  private closed = false;

  constructor(
    private readonly host: HTMLElement,
    private readonly doc: PDFDocumentProxy,
    /** Frees the document and its worker (the loading task's destroy). */
    private readonly destroy: () => Promise<void>,
  ) {}

  /** A method, so a check after an await reads the current value. */
  private isClosed(): boolean {
    return this.closed;
  }

  get numPages(): number {
    return this.doc.numPages;
  }

  /** Lays out every page box (sizes only: cheap) and draws the pages that will be in view. */
  async open(zoom: number, keepScrollTop: number | null): Promise<void> {
    this.zoom = zoom;
    const loaded: PDFPageProxy[] = [];
    for (let number = 1; number <= this.doc.numPages; number += 1) loaded.push(await this.doc.getPage(number));
    if (this.isClosed()) return;
    this.pages = loaded.map((page, index) => {
      const base = page.getViewport({ scale: 1 });
      const box = document.createElement("div");
      box.className = "relative mx-auto mb-4 overflow-hidden bg-white shadow-md ring-1 ring-zinc-900/10";
      box.dataset.page = String(index + 1);
      return { number: index + 1, width: base.width, height: base.height, box, canvas: null, drawn: 0, task: null, taskScale: 0 };
    });
    this.measure();
    this.layout();
    // Draw what will be on screen before showing anything, so a recompile
    // replaces the old pages without a blank flash.
    const scroller = this.scroller();
    const top = keepScrollTop ?? 0;
    const bottom = top + (scroller?.clientHeight ?? window.innerHeight);
    await Promise.all(this.pagesBetween(top, bottom).map((page) => this.draw(page)));
    if (this.isClosed()) return;
    this.host.replaceChildren(...this.pages.map((page) => page.box));
    if (scroller && keepScrollTop !== null) scroller.scrollTop = keepScrollTop;
    this.watch();
  }

  /** Answers at once: resizes every page now, redraws the visible ones sharply shortly after. */
  setZoom(zoom: number): void {
    if (zoom === this.zoom || this.pages.length === 0) return;
    const scroller = this.scroller();
    // Keep the point at the centre of the view where it is.
    const anchor = scroller ? (scroller.scrollTop + scroller.clientHeight / 2) / Math.max(1, scroller.scrollHeight) : 0;
    const anchorX = scroller ? (scroller.scrollLeft + scroller.clientWidth / 2) / Math.max(1, scroller.scrollWidth) : 0;
    this.zoom = zoom;
    this.layout();
    if (scroller) {
      scroller.scrollTop = anchor * scroller.scrollHeight - scroller.clientHeight / 2;
      scroller.scrollLeft = anchorX * scroller.scrollWidth - scroller.clientWidth / 2;
    }
    this.scheduleSharp();
  }

  close(): void {
    this.closed = true;
    window.clearTimeout(this.settle);
    this.observer?.disconnect();
    this.resize?.disconnect();
    for (const page of this.pages) page.task?.cancel();
    void this.destroy();
  }

  private scale(): number {
    return this.fit * this.zoom;
  }

  /**
   * The scale at which the widest page fits the pane at 100%. Measured on
   * the scrolling pane, never on the page column: that grows with the pages
   * when zoomed in, and fitting to it would feed back into itself.
   */
  private measure(): void {
    const widest = Math.max(...this.pages.map((page) => page.width), 1);
    this.fit = Math.max(0.1, (this.paneWidth() - GUTTER) / widest);
  }

  private paneWidth(): number {
    return this.scroller()?.clientWidth || this.host.clientWidth || 600;
  }

  private layout(): void {
    const scale = this.scale();
    for (const page of this.pages) {
      page.box.style.width = `${Math.floor(page.width * scale)}px`;
      page.box.style.height = `${Math.floor(page.height * scale)}px`;
    }
  }

  private scroller(): HTMLElement | null {
    for (let node = this.host.parentElement; node; node = node.parentElement) {
      const overflow = getComputedStyle(node).overflowY;
      if (overflow === "auto" || overflow === "scroll") return node;
    }
    return null;
  }

  /** Pages overlapping [top, bottom] of the scroller, from the laid-out sizes. */
  private pagesBetween(top: number, bottom: number): PageView[] {
    const scale = this.scale();
    const found: PageView[] = [];
    let y = GAP;
    for (const page of this.pages) {
      const height = Math.floor(page.height * scale);
      if (y + height >= top && y <= bottom) found.push(page);
      y += height + GAP;
    }
    return found.length > 0 ? found : this.pages.slice(0, 1);
  }

  private watch(): void {
    if (typeof IntersectionObserver !== "undefined") {
      // A screen's worth ahead and behind, so scrolling meets drawn pages.
      this.observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            const number = Number((entry.target as HTMLElement).dataset.page);
            if (entry.isIntersecting) this.visible.add(number);
            else this.visible.delete(number);
          }
          this.drawVisible();
        },
        { root: this.scroller(), rootMargin: "100% 0px" },
      );
      for (const page of this.pages) this.observer.observe(page.box);
    } else {
      for (const page of this.pages) this.visible.add(page.number);
      this.drawVisible();
    }
    const pane = this.scroller();
    if (pane && typeof ResizeObserver !== "undefined") {
      let width = this.paneWidth();
      this.resize = new ResizeObserver(() => {
        // A scrollbar coming or going (~15px) is not a resize: refitting to
        // it could make the scrollbar come and go in turn.
        if (Math.abs(this.paneWidth() - width) < 20) return;
        width = this.paneWidth();
        this.measure();
        this.layout();
        this.scheduleSharp();
      });
      this.resize.observe(pane);
    }
  }

  private scheduleSharp(): void {
    window.clearTimeout(this.settle);
    this.settle = window.setTimeout(() => this.drawVisible(), SETTLE_MS);
  }

  /** Redraws, one page at a time in page order, every visible page not drawn at the current scale. */
  private drawVisible(): void {
    const scale = this.scale();
    for (const page of this.pages) {
      // Drawing for a scale that is no longer wanted is wasted work.
      if (page.task && page.taskScale !== scale) page.task.cancel();
    }
    const wanted = this.pages.filter((page) => this.visible.has(page.number) && page.drawn !== scale && !(page.task && page.taskScale === scale));
    this.queue = this.queue.then(async () => {
      for (const page of wanted) {
        if (this.isClosed() || this.scale() !== scale) return;
        await this.draw(page);
      }
    });
  }

  private async draw(page: PageView): Promise<void> {
    const scale = this.scale();
    if (page.drawn === scale) return;
    const pdfPage = await this.doc.getPage(page.number);
    let ratio = Math.min(window.devicePixelRatio || 1, MAX_RATIO);
    const area = page.width * page.height * scale * scale;
    if (area * ratio * ratio > MAX_PIXELS) ratio = Math.max(1, Math.sqrt(MAX_PIXELS / area));
    const viewport = pdfPage.getViewport({ scale: scale * ratio });
    // Drawn off screen, then swapped in: the old drawing stays until then.
    const canvas = document.createElement("canvas");
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    canvas.className = "absolute inset-0 size-full";
    canvas.setAttribute("aria-hidden", "true");
    const task = pdfPage.render({ canvas, viewport });
    page.task = task;
    page.taskScale = scale;
    try {
      await task.promise;
    } catch {
      return; // cancelled by a newer zoom, or closed
    } finally {
      if (page.task === task) page.task = null;
    }
    if (this.isClosed() || this.scale() !== scale) return;
    page.canvas?.remove();
    page.box.append(canvas);
    page.canvas = canvas;
    page.drawn = scale;
    page.box.setAttribute("role", "img");
    page.box.setAttribute("aria-label", `Page ${page.number} of ${this.doc.numPages}`);
  }
}
