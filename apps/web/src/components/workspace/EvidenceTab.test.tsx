import { afterEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EvidenceTab } from "./EvidenceTab";

afterEach(() => vi.restoreAllMocks());

describe("EvidenceTab", () => {
  it("searches the paper proxy and inserts a citation for a selected result", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [{ id: "https://openalex.org/W123", title: "Reliable evidence", publication_year: 2025, cited_by_count: 12 }],
    }));
    const onInsertCitation = vi.fn();
    const user = await import("@testing-library/user-event").then((mod) => mod.default.setup());
    renderWithProviders(<EvidenceTab onInsertCitation={onInsertCitation} />);
    await user.type(screen.getByLabelText("Search papers"), "evidence");
    await user.click(screen.getByRole("button", { name: "Search evidence" }));
    expect(await screen.findByText("Reliable evidence")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cite" }));
    expect(onInsertCitation).toHaveBeenCalledWith("\\cite{openalex:W123}");
  });

  it("rejects malformed proxy responses with a recoverable error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({ bad: true }) }));
    const user = await import("@testing-library/user-event").then((mod) => mod.default.setup());
    renderWithProviders(<EvidenceTab onInsertCitation={vi.fn()} />);
    await user.type(screen.getByLabelText("Search papers"), "evidence");
    await user.click(screen.getByRole("button", { name: "Search evidence" }));
    expect(await screen.findByText("The evidence response was invalid.")).toBeInTheDocument();
  });
});
