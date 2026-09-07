import { describe, expect, it, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { TopBar } from "./TopBar";

vi.mock("next/navigation", () => ({
  usePathname: () => "/home",
}));

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({
    user: { uid: "u1", displayName: "Ada Lovelace", email: "ada@x.io" },
    signOut: vi.fn(),
  }),
}));

vi.mock("@/lib/hooks/useMyProfile", () => ({
  useMyProfile: () => ({ author: { id: "A1" } }),
}));

vi.mock("@/components/command/CommandPaletteProvider", () => ({
  useCommandPalette: () => ({ toggle: vi.fn() }),
}));

beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
});

describe("TopBar", () => {
  it("renders logo, search, primary nav, bell and account menu", () => {
    renderWithProviders(<TopBar />);
    expect(screen.getByRole("link", { name: /skolab home/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Search" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Discovery" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "CoLab" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /notifications/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /account menu/i })).toBeInTheDocument();
  });

  it("opens the account menu with Profile / Settings / Sign out", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TopBar />);
    await user.click(screen.getByRole("button", { name: /account menu/i }));

    expect(screen.getByRole("menuitem", { name: /your profile/i })).toHaveAttribute("href", "/profile");
    expect(screen.getByRole("menuitem", { name: /^settings$/i })).toHaveAttribute("href", "/settings");
    expect(screen.getByRole("menuitem", { name: /sign out/i })).toBeInTheDocument();
    expect(screen.getByText("ada@x.io")).toBeInTheDocument();
  });

  it("opens the notifications panel", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TopBar />);
    await user.click(screen.getByRole("button", { name: /notifications/i }));
    expect(await screen.findByRole("menu")).toBeInTheDocument();
    expect(screen.getByText("Notifications")).toBeInTheDocument();
  });
});
