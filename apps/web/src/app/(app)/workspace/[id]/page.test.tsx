import { describe, expect, it, vi } from "vitest";
import { act, renderWithProviders, screen } from "@/test/render";
import { emitCollection, emitDoc } from "@/test/firestore";
import { WorkspaceDetailContent } from "./page";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada" } }),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

describe("WorkspaceDetailContent", () => {
  it("renders the project once a doc snapshot arrives", async () => {
    renderWithProviders(<WorkspaceDetailContent id="p1" />);
    act(() => {
      emitDoc(
      {
        name: "Quantum group",
        ownerUid: "u1",
        ownerName: "Ada",
        description: "",
        members: [{ uid: "u1", name: "Ada", email: "a@b.c" }],
        memberUids: ["u1"],
        recentEquations: "",
        manuscriptProgress: 0,
        manuscriptDraft: "",
      },
        "p1",
      );
    });
    expect(await screen.findByText("Quantum group")).toBeInTheDocument();
  });

  it("opens a manuscript in editable writing mode with the core CoLab controls", async () => {
    renderWithProviders(<WorkspaceDetailContent id="p1" />);
    act(() => {
      emitDoc(
      {
        name: "Quantum group",
        ownerUid: "u1",
        ownerName: "Ada",
        description: "",
        members: [{ uid: "u1", name: "Ada", email: "a@b.c" }],
        memberUids: ["u1"],
        recentEquations: "",
        manuscriptProgress: 0,
        manuscriptDraft: "",
      },
        "p1",
      );
    });
    act(() => emitCollection([
      {
        id: "main",
        title: "main",
        body: "# Abstract\nA source-linked manuscript draft.",
        order: 0,
        updatedAt: Date.now(),
        updatedByUid: "u1",
        updatedByName: "Ada",
      },
    ]));

    expect(await screen.findByRole("textbox", { name: "Manuscript editor" })).toHaveValue(
      "# Abstract\nA source-linked manuscript draft.",
    );
    expect(screen.getByRole("button", { name: /Compile PDF/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add block" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Share with lab" })).toBeInTheDocument();
  });

  it("stays on the skeleton for a not-found project", async () => {
    const { container } = renderWithProviders(
      <WorkspaceDetailContent id="p1" />,
    );
    act(() => emitDoc(null));
    await new Promise((r) => setTimeout(r, 20));
    expect(container.querySelector(".animate-pulse")).not.toBeNull();
  });
});
