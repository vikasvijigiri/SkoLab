import { describe, expect, it, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { ThemeToggle } from "./ThemeToggle";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

describe("ThemeToggle", () => {
  it("defaults to light (the SSR-safe value) when nothing is persisted", () => {
    renderWithProviders(<ThemeToggle />);
    expect(screen.getByRole("button", { name: "Theme: light" })).toBeInTheDocument();
  });

  it("reflects a theme already persisted in localStorage on mount", () => {
    localStorage.setItem("skolab-theme", "dark");
    renderWithProviders(<ThemeToggle />);
    expect(screen.getByRole("button", { name: "Theme: dark" })).toBeInTheDocument();
  });

  it("cycles light -> dark -> system -> light on click, applying the DOM attribute and persisting each step", async () => {
    renderWithProviders(<ThemeToggle />);
    const button = screen.getByRole("button", { name: "Theme: light" });

    await userEvent.click(button);
    expect(screen.getByRole("button", { name: "Theme: dark" })).toBeInTheDocument();
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(localStorage.getItem("skolab-theme")).toBe("dark");

    await userEvent.click(screen.getByRole("button", { name: "Theme: dark" }));
    expect(screen.getByRole("button", { name: "Theme: system" })).toBeInTheDocument();
    // "system" means no explicit override -- the attribute comes off entirely.
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    expect(localStorage.getItem("skolab-theme")).toBe("system");

    await userEvent.click(screen.getByRole("button", { name: "Theme: system" }));
    expect(screen.getByRole("button", { name: "Theme: light" })).toBeInTheDocument();
  });
});
