import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import SettingsPage from "./page";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", email: "ada@x.io", isAnonymous: false } }),
}));

describe("SettingsPage", () => {
  it("renders the three sections", () => {
    renderWithProviders(<SettingsPage />);
    expect(screen.getByRole("heading", { name: "Appearance" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Notifications" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Account & privacy" })).toBeInTheDocument();
    expect(screen.getByText("ada@x.io")).toBeInTheDocument();
  });

  it("has a working theme picker and links to profile / delete", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SettingsPage />);
    await user.click(screen.getByRole("button", { name: /dark/i }));
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

    expect(screen.getByRole("link", { name: /edit your profile/i })).toHaveAttribute("href", "/profile");
    expect(screen.getByRole("link", { name: /delete your account/i })).toHaveAttribute(
      "href",
      "/profile#danger",
    );
  });
});
