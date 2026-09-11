import { describe, expect, it, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { getDocs } from "firebase/firestore";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { emitCollection, emitError } from "@/test/firestore";
import WorkspaceListPage from "./page";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada", email: "ada@x.edu" } }),
}));

const params = vi.hoisted(() => ({ current: new URLSearchParams("") }));
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useSearchParams: () => params.current,
  useRouter: () => ({ replace }),
}));

// The test double in src/test/firestore.ts is a loose JS mock; real
// firebase/firestore types are still what TS checks calls against.
const fakeSnap = (docs: { id: string; data: () => unknown }[]) =>
  ({ docs }) as unknown as Awaited<ReturnType<typeof getDocs>>;

beforeEach(() => {
  params.current = new URLSearchParams("");
  replace.mockClear();
  vi.mocked(getDocs).mockReset();
  vi.mocked(getDocs).mockResolvedValue(fakeSnap([]));
});

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

  it("pre-fills and auto-opens the create form when arriving from a Discovery match", async () => {
    params.current = new URLSearchParams({ withResearcher: "A100", withResearcherName: "Marie Curie" });
    renderWithProviders(<WorkspaceListPage />);
    emitCollection([]);
    expect(await screen.findByText(/starting a project with marie curie/i)).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Project name")).toHaveValue("Marie Curie collaboration");
  });

  it("invites the matched researcher once the project is created, when they already have a SkoLab account", async () => {
    params.current = new URLSearchParams({ withResearcher: "A100", withResearcherName: "Marie Curie" });
    vi.mocked(getDocs).mockResolvedValueOnce(
      fakeSnap([{ id: "u9", data: () => ({ uid: "u9", name: "Marie Curie", email: "mc@x.edu" }) }]),
    );
    renderWithProviders(<WorkspaceListPage />);
    emitCollection([]);
    await userEvent.click(await screen.findByText("Create project"));
    expect(await screen.findByText(/marie curie added as editor/i)).toBeInTheDocument();
    expect(replace).toHaveBeenCalledWith("/workspace");
  });

  it("still creates the project and says so plainly when the match has no SkoLab account yet", async () => {
    params.current = new URLSearchParams({ withResearcher: "A100", withResearcherName: "Marie Curie" });
    vi.mocked(getDocs).mockResolvedValueOnce(fakeSnap([]));
    renderWithProviders(<WorkspaceListPage />);
    emitCollection([]);
    await userEvent.click(await screen.findByText("Create project"));
    expect(await screen.findByText(/no skolab account yet for marie curie/i)).toBeInTheDocument();
  });
});
