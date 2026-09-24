import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AppShell } from "./AppShell";

const route = vi.hoisted(() => ({ pathname: "/home" }));
vi.mock("next/navigation", () => ({ usePathname: () => route.pathname }));
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada", email: "a@x.io" }, signOut: vi.fn() }),
}));
vi.mock("@/lib/hooks/useMyProfile", () => ({ useMyProfile: () => ({ author: null }) }));
vi.mock("@/components/command/CommandPaletteProvider", () => ({
  useCommandPalette: () => ({ toggle: vi.fn() }),
}));

describe("AppShell", () => {
  beforeEach(() => {
    route.pathname = "/home";
  });

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

  it("lets a manuscript workspace own the full viewport and its navigation", () => {
    route.pathname = "/workspace/project-1";
    renderWithProviders(
      <AppShell>
        <p>manuscript body</p>
      </AppShell>,
    );

    expect(screen.queryByRole("navigation", { name: /primary/i })).not.toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveClass("overflow-hidden");
  });
});
