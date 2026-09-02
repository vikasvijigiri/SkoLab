import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { emitDoc } from "@/test/firestore";
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
    expect(await screen.findByText("Quantum group")).toBeInTheDocument();
  });

  it("stays on the skeleton for a not-found project", async () => {
    const { container } = renderWithProviders(
      <WorkspaceDetailContent id="p1" />,
    );
    emitDoc(null);
    await new Promise((r) => setTimeout(r, 20));
    expect(container.querySelector(".animate-pulse")).not.toBeNull();
  });
});
