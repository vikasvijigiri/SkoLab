import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { WorkspaceResearchActions } from "./ResearchTools";

describe("WorkspaceResearchActions", () => {
  it("exposes live call, share, template and originality actions", async () => {
    const user = userEvent.setup();
    const applyTemplate = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("open", vi.fn());
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });

    renderWithProviders(
      <WorkspaceResearchActions
        projectId="project-1"
        projectName="Quantum Spin Liquids"
        documentTitle="main"
        documentBody={'# Introduction\nA claim with https://doi.org/example and "a quoted passage that is long enough".'}
        documents={[]}
        canEdit
        onApplyTemplate={applyTemplate}
      />,
    );

    await user.click(screen.getByRole("button", { name: /call/i }));
    expect(window.open).toHaveBeenCalledWith(
      "https://meet.jit.si/skolab-project-1",
      "_blank",
      "noopener,noreferrer",
    );

    await user.click(screen.getByRole("button", { name: /templates/i }));
    await user.click(screen.getByRole("button", { name: /IMRaD/i }));
    expect(applyTemplate).toHaveBeenCalledWith(expect.stringContaining("## 1. Introduction"));

    await user.click(screen.getByRole("button", { name: /originality/i }));
    expect(screen.getByText(/Originality preflight/i)).toBeVisible();
    expect(screen.getByText(/not an internet plagiarism score/i)).toBeVisible();
  });
});
