import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { SegmentedControl } from "./SegmentedControl";

const OPTIONS = [
  { value: "researchers", label: "Researchers" },
  { value: "papers", label: "Papers" },
];

describe("SegmentedControl", () => {
  it("renders every option as a tab", () => {
    render(<SegmentedControl options={OPTIONS} value="researchers" onChange={() => {}} />);
    expect(screen.getAllByRole("tab")).toHaveLength(2);
    expect(screen.getByRole("tab", { name: "Researchers" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Papers" })).toHaveAttribute("aria-selected", "false");
  });

  it("calls onChange with the clicked option's value", async () => {
    const onChange = vi.fn();
    render(<SegmentedControl options={OPTIONS} value="researchers" onChange={onChange} />);
    await userEvent.click(screen.getByRole("tab", { name: "Papers" }));
    expect(onChange).toHaveBeenCalledWith("papers");
  });

  it("moves the selection with arrow keys", async () => {
    const onChange = vi.fn();
    render(<SegmentedControl options={OPTIONS} value="researchers" onChange={onChange} />);
    screen.getByRole("tab", { name: "Researchers" }).focus();
    await userEvent.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("papers");
  });
});
