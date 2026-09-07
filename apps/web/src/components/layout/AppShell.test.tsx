import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AppShell } from "./AppShell";

vi.mock("next/navigation", () => ({ usePathname: () => "/home" }));
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada", email: "a@x.io" }, signOut: vi.fn() }),
}));
vi.mock("@/lib/hooks/useMyProfile", () => ({ useMyProfile: () => ({ author: null }) }));
vi.mock("@/components/command/CommandPaletteProvider", () => ({
  useCommandPalette: () => ({ toggle: vi.fn() }),
}));

describe("AppShell", () => {
  it("provides a Skip to content link that targets the main landmark (WCAG 2.4.1)", () => {
    renderWithProviders(
      <AppShell>
        <p>page body</p>
      </AppShell>,
    );
    const skip = screen.getByRole("link", { name: /skip to content/i });
    expect(skip).toHaveAttribute("href", "#main-content");
    expect(document.getElementById("main-content")?.tagName).toBe("MAIN");
  });

  it("renders one main landmark and a labelled bottom nav", () => {
    renderWithProviders(
      <AppShell>
        <p>page body</p>
      </AppShell>,
    );
    expect(screen.getAllByRole("main")).toHaveLength(1);
    expect(screen.getAllByRole("navigation", { name: /primary/i }).length).toBeGreaterThan(0);
  });
});
