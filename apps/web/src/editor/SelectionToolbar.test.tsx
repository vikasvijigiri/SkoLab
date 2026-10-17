import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { CursorInfo } from "./LatexEditor";
import { SelectionToolbar } from "./SelectionToolbar";

function cursor(selection: CursorInfo["selection"], focused = true): CursorInfo {
  return { line: 1, column: 1, focused, math: null, anchor: null, selection };
}

describe("selection toolbar", () => {
  it("offers the goals above a selection and keeps the editor's focus", async () => {
    const onPick = vi.fn();
    const onOpen = vi.fn();
    render(<SelectionToolbar cursor={cursor({ from: 10, to: 40, left: 120, top: 300, bottom: 320 })} onPick={onPick} onOpen={onOpen} />);
    const bar = screen.getByRole("toolbar", { name: "Improve the selection with AI" });
    expect(bar.style.top).toBe("252px");
    const grammar = screen.getByRole("button", { name: "Grammar" });
    // Taking focus would clear the editor's selection: mousedown is cancelled.
    expect(fireEvent.mouseDown(grammar)).toBe(false);
    await userEvent.click(grammar);
    expect(onPick).toHaveBeenCalledWith("grammar");
    await userEvent.click(screen.getByRole("button", { name: "Improve" }));
    expect(onOpen).toHaveBeenCalled();
  });

  it("sits below a selection at the top of the window", () => {
    render(<SelectionToolbar cursor={cursor({ from: 0, to: 9, left: 10, top: 40, bottom: 60 })} onPick={vi.fn()} onOpen={vi.fn()} />);
    expect(screen.getByRole("toolbar").style.top).toBe("68px");
  });

  it("hides without a real selection or focus", () => {
    const { rerender } = render(<SelectionToolbar cursor={cursor(null)} onPick={vi.fn()} onOpen={vi.fn()} />);
    expect(screen.queryByRole("toolbar")).not.toBeInTheDocument();
    rerender(<SelectionToolbar cursor={cursor({ from: 3, to: 5, left: 0, top: 200, bottom: 220 })} onPick={vi.fn()} onOpen={vi.fn()} />);
    expect(screen.queryByRole("toolbar")).not.toBeInTheDocument();
    rerender(<SelectionToolbar cursor={cursor({ from: 3, to: 50, left: 0, top: 200, bottom: 220 }, false)} onPick={vi.fn()} onOpen={vi.fn()} />);
    expect(screen.queryByRole("toolbar")).not.toBeInTheDocument();
  });
});
