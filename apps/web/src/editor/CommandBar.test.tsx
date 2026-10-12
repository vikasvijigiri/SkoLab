import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CommandBar, score, type Command } from "./CommandBar";

function commands(run: (id: string) => void = () => undefined): Command[] {
  return [
    { id: "compile", title: "Compile", group: "Actions", keys: "Mod+Enter", run: () => {
      run("compile");
    } },
    { id: "bold", title: "Bold", group: "Format", keys: "Mod+B", keywords: "textbf strong", run: () => {
      run("bold");
    } },
    { id: "figure", title: "Insert figure", group: "Insert", note: "/figure", run: () => {
      run("figure");
    } },
    { id: "pdf", title: "Layout: PDF only", group: "View", run: () => {
      run("pdf");
    } },
  ];
}

describe("command bar", () => {
  it("scores matches: whole words first, then letters in order", () => {
    expect(score("", "anything")).toBe(0);
    expect(score("pdf", "Layout: PDF only")).toBeGreaterThan(score("pdf", "Paste a dull file"));
    expect(score("lpo", "Layout: PDF only")).toBeGreaterThan(0);
    expect(score("xyz", "Layout: PDF only")).toBe(-1);
  });

  it("lists commands by group, filters as you type and runs the chosen one with Enter", async () => {
    const onPick = vi.fn();
    render(<CommandBar commands={commands()} onPick={onPick} onClose={vi.fn()} />);
    const input = screen.getByRole("combobox", { name: "Search commands" });
    expect(input).toHaveFocus();
    expect(screen.getByText("Actions")).toBeInTheDocument();
    expect(screen.getAllByRole("option")).toHaveLength(4);

    await userEvent.type(input, "strong");
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual([expect.stringContaining("Bold") as string]);
    await userEvent.clear(input);
    await userEvent.type(input, "fig");
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: "figure" }));
  });

  it("moves with the arrow keys and wraps around", () => {
    const onPick = vi.fn();
    render(<CommandBar commands={commands()} onPick={onPick} onClose={vi.fn()} />);
    const input = screen.getByRole("combobox");
    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(input.getAttribute("aria-activedescendant")).toMatch(/-3$/);
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "n", ctrlKey: true });
    fireEvent.keyDown(input, { key: "p", ctrlKey: true });
    fireEvent.keyDown(input, { key: "End", ctrlKey: true });
    fireEvent.keyDown(input, { key: "Home", ctrlKey: true });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Tab" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: "bold" }));
  });

  it("picks with the pointer, says when nothing matches, and closes on Escape or a click outside", async () => {
    const onPick = vi.fn();
    const onClose = vi.fn();
    const { container } = render(<CommandBar commands={commands()} onPick={onPick} onClose={onClose} />);
    fireEvent.mouseMove(screen.getByRole("option", { name: /Layout/ }));
    expect(screen.getByRole("option", { name: /Layout/ })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("option", { name: /Layout/ }));
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: "pdf" }));

    await userEvent.type(screen.getByRole("combobox"), "zzzz");
    expect(screen.getByText("Nothing matches “zzzz”.")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    expect(onPick).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });
    fireEvent.click(container.querySelector("[aria-hidden='true'].absolute") as Element);
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
