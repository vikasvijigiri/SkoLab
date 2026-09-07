import { describe, expect, it, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { AppShell } from "./AppShell";

vi.mock("next/navigation", () => ({
  usePathname: () => "/home",
}));

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { displayName: "Ada Lovelace", email: "ada@x.io" }, signOut: vi.fn() }),
}));

vi.mock("@/components/command/CommandPaletteProvider", () => ({
  useCommandPalette: () => ({ toggle: vi.fn() }),
}));

vi.mock("@/components/ui/ThemeToggle", () => ({ ThemeToggle: () => <div data-testid="theme-toggle" /> }));

beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    /* jsdom without storage */
  }
});

describe("AppShell sidebar", () => {
  it("renders the primary nav expanded with labels", () => {
    renderWithProviders(
      <AppShell>
        <div>content</div>
      </AppShell>,
    );
    // Sidebar + mobile bottom-nav both render under jsdom, so each label appears twice.
    expect(screen.getAllByRole("link", { name: "Home" }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("link", { name: "Discovery" }).length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /collapse sidebar/i })).toBeInTheDocument();
  });

  it("exposes a keyboard-operable resize separator", () => {
    renderWithProviders(
      <AppShell>
        <div>content</div>
      </AppShell>,
    );
    const sep = screen.getByRole("separator", { name: /resize sidebar/i });
    expect(sep).toHaveAttribute("aria-orientation", "vertical");
    expect(sep).toHaveAttribute("tabindex", "0");
    expect(Number(sep.getAttribute("aria-valuenow"))).toBeGreaterThan(0);
  });

  it("collapses to an icon rail: labels hide, expand control appears", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AppShell>
        <div>content</div>
      </AppShell>,
    );
    await user.click(screen.getByRole("button", { name: /collapse sidebar/i }));

    // The nav link is still there (icon), but its accessible name is now the title.
    expect(screen.queryByRole("button", { name: /collapse sidebar/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /expand sidebar/i })).toBeInTheDocument();
    // Resize handle is not rendered while collapsed.
    expect(screen.queryByRole("separator", { name: /resize sidebar/i })).not.toBeInTheDocument();
  });
});
