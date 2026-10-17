import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { CursorInfo } from "./LatexEditor";
import { MathPreview } from "./MathPreview";

function cursor(tex: string, overrides: Partial<CursorInfo> = {}): CursorInfo {
  return { line: 1, column: 1, focused: true, math: { from: 0, to: tex.length + 2, tex, display: false }, anchor: { left: 40, top: 100, bottom: 120 }, selection: null, ...overrides };
}

describe("math preview", () => {
  it("renders the math under the cursor with the document's macros", async () => {
    render(<MathPreview cursor={cursor("\\R^2")} macros={{ "\\R": "\\mathbb{R}" }} />);
    expect(screen.getByRole("note", { name: "Math preview" })).toBeInTheDocument();
    const box = await screen.findByTestId("math-preview", {}, { timeout: 5000 });
    await waitFor(() => expect(box.querySelector(".katex")).not.toBeNull());
    expect(box.querySelector("annotation")?.textContent).toBe("\\R^2");
    expect(box.dataset.failed).toBe("false");
  });

  it("explains math it can't render", async () => {
    render(<MathPreview cursor={cursor("\\frac{1}{")} macros={{}} />);
    const box = await screen.findByTestId("math-preview", {}, { timeout: 5000 });
    await waitFor(() => expect(box.dataset.failed).toBe("true"));
    expect(box.textContent).toMatch(/expected '}'/i);
  });

  it("stays hidden outside math, without focus, or without a place to show", () => {
    const { container, rerender } = render(<MathPreview cursor={null} macros={{}} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<MathPreview cursor={cursor("x", { focused: false })} macros={{}} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<MathPreview cursor={cursor("x", { anchor: null })} macros={{}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("sits above the line near the bottom of the window", async () => {
    render(<MathPreview cursor={cursor("y", { anchor: { left: 5000, top: window.innerHeight - 30, bottom: window.innerHeight - 10 } })} macros={{}} />);
    const note = screen.getByRole("note");
    expect(note.style.bottom).toBe("38px");
    expect(Number.parseFloat(note.style.left)).toBeLessThan(window.innerWidth);
    await screen.findByTestId("math-preview", {}, { timeout: 5000 });
  });
});
