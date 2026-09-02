import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { emitCollection, emitError } from "@/test/firestore";
import WorkspaceListPage from "./page";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada" } }),
}));

describe("WorkspaceListPage", () => {
  it("renders projects from a collection snapshot", async () => {
    renderWithProviders(<WorkspaceListPage />);

    emitCollection([
      {
        id: "p1",
        name: "Quantum group",
        description: "",
        ownerUid: "u1",
        ownerName: "Ada",
        members: [{ uid: "u1", name: "Ada", email: "a@b.c" }],
        memberUids: ["u1"],
        recentEquations: "",
        manuscriptProgress: 0,
        manuscriptDraft: "",
      },
    ]);

    expect(await screen.findByText("Quantum group")).toBeInTheDocument();
  });

  it("shows an error banner when the subscription errors", async () => {
    renderWithProviders(<WorkspaceListPage />);
    emitError("permission-denied");
    await waitFor(() =>
      expect(screen.getByText(/permission/i)).toBeInTheDocument(),
    );
  });

  it("shows an empty-state prompt with no projects", async () => {
    renderWithProviders(<WorkspaceListPage />);
    emitCollection([]);
    expect(
      await screen.findByText(/No workspaces yet/i),
    ).toBeInTheDocument();
  });
});
