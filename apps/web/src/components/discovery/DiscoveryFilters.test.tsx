import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { DEFAULT_FILTER_STATE, DiscoveryFilters, type DiscoveryFacets } from "./DiscoveryFilters";

const facets: DiscoveryFacets = {
  countries: [
    { code: "US", count: 12 },
    { code: "GB", count: 4 },
  ],
  instTypes: [{ type: "education", count: 20 }],
};

function setup(over: Partial<Parameters<typeof DiscoveryFilters>[0]> = {}) {
  const onChange = vi.fn();
  const onSortChange = vi.fn();
  const onReset = vi.fn();
  const utils = renderWithProviders(
    <DiscoveryFilters
      state={DEFAULT_FILTER_STATE}
      sort="fit"
      facets={facets}
      onChange={onChange}
      onSortChange={onSortChange}
      onReset={onReset}
      {...over}
    />,
  );
  return { onChange, onSortChange, onReset, ...utils };
}

describe("DiscoveryFilters — click-only", () => {
  it("has no text inputs anywhere in the rail", () => {
    const { container } = setup();
    expect(container.querySelector("input, textarea")).toBeNull();
  });

  it("toggling a career-stage chip emits the updated state and flips aria-pressed", async () => {
    const user = userEvent.setup();
    const { onChange } = setup();
    const chip = screen.getByRole("button", { name: "Emerging" });
    expect(chip).toHaveAttribute("aria-pressed", "false");
    await user.click(chip);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ careerStage: ["emerging"] }),
    );
  });

  it("emits topicalFocusMin when a focus stop is chosen", async () => {
    const user = userEvent.setup();
    const { onChange } = setup();
    await user.click(screen.getByRole("tab", { name: "≥50%" }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ topicalFocusMin: 0.5 }));
  });

  it("builds country chips from facets and hides the group when there are none", () => {
    const { rerender } = setup();
    expect(screen.getByRole("button", { name: /US · 12/ })).toBeInTheDocument();
    rerender(
      <DiscoveryFilters
        state={DEFAULT_FILTER_STATE}
        sort="fit"
        facets={{ countries: [], instTypes: [] }}
        onChange={vi.fn()}
        onSortChange={vi.fn()}
        onReset={vi.fn()}
      />,
    );
    expect(screen.queryByText("Country")).not.toBeInTheDocument();
  });

  it("shows Reset only when a filter is active and calls onReset", async () => {
    const user = userEvent.setup();
    const { onReset } = setup({ state: { ...DEFAULT_FILTER_STATE, hasOrcid: true } });
    const reset = screen.getByRole("button", { name: /reset/i });
    await user.click(reset);
    expect(onReset).toHaveBeenCalled();
  });

  it("hides Reset at the default state + default sort", () => {
    setup();
    expect(screen.queryByRole("button", { name: /reset/i })).not.toBeInTheDocument();
  });
});
