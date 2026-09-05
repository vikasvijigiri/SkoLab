import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada Lovelace" }, signOut: vi.fn() }),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/profile",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/hooks/useMyProfile", () => ({
  useMyProfile: () => ({
    firestoreProfile: {
      name: "Ada Lovelace",
      researchFocus: "Analytical Engines",
      about: "First programmer.",
      academicStatus: "Researcher",
    },
    author: null,
    loading: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

import ProfilePage from "./page";

describe("ProfilePage", () => {
  it("renders the profile fields from useMyProfile", () => {
    renderWithProviders(<ProfilePage />);
    expect(screen.getByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByText("Analytical Engines")).toBeInTheDocument();
    expect(screen.getByText("First programmer.")).toBeInTheDocument();
  });

  it("reveals the edit form when the edit control is clicked", async () => {
    renderWithProviders(<ProfilePage />);
    const editBtn = screen.getByRole("button", { name: /edit/i });
    await userEvent.click(editBtn);
    expect(screen.getByLabelText("Name")).toHaveValue("Ada Lovelace");
    expect(screen.getByLabelText("Research focus")).toHaveValue("Analytical Engines");
  });
});
