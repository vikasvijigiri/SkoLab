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

  it("edit form is click-only — chips for focus/status, no text inputs (About is the one textarea)", async () => {
    const { container } = renderWithProviders(<ProfilePage />);
    await userEvent.click(screen.getByRole("button", { name: /edit/i }));

    // Name is shown, not an input.
    expect(container.querySelectorAll("input").length).toBe(0);
    expect(container.querySelectorAll("textarea").length).toBe(1); // About only

    // Field chips load from the taxonomy handler.
    expect(await screen.findByRole("button", { name: "Physics and Astronomy" })).toBeInTheDocument();
    expect(screen.getByText(/Current: Analytical Engines/)).toBeInTheDocument();
  });

  it("picking a field chip previews the new research focus", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />);
    await user.click(screen.getByRole("button", { name: /edit/i }));
    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    expect(screen.getByText("→ Physics and Astronomy")).toBeInTheDocument();
  });
});
