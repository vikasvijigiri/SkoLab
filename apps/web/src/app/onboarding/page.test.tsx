import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import OnboardingPage from "./page";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({
    user: { uid: "u1", displayName: "Ada Lovelace" },
    getIdToken: vi.fn().mockResolvedValue("tok"),
  }),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/lib/firebase/auth", () => ({
  updateResearcherProfile: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("@/lib/api/endpoints", async (orig) => ({
  ...(await orig<typeof import("@/lib/api/endpoints")>()),
  syncUserProfile: vi.fn().mockResolvedValue({ status: "ok", uid: "u1" }),
}));

describe("OnboardingPage — click-only", () => {
  it("has no text inputs or textareas on any step", async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<OnboardingPage />);

    // Step 0 — fields load as chips.
    expect(await screen.findByRole("button", { name: "Physics and Astronomy" })).toBeInTheDocument();
    expect(container.querySelector("input, textarea")).toBeNull();

    await user.click(screen.getByRole("button", { name: "Physics and Astronomy" }));
    expect(await screen.findByRole("button", { name: "Condensed Matter Physics" })).toBeInTheDocument();

    // Step 1 — "is this you?" author card, no inputs.
    await user.click(screen.getByRole("button", { name: /next/i }));
    expect(await screen.findByText(/Analytical Engine Institute/)).toBeInTheDocument();
    expect(container.querySelector("input, textarea")).toBeNull();

    // Step 2 — topic chips, no inputs.
    await user.click(screen.getByRole("button", { name: /next/i }));
    expect(await screen.findByRole("button", { name: "PhD Student" })).toBeInTheDocument();
    expect(container.querySelector("input, textarea")).toBeNull();
  });

  it("adds a topic by clicking its chip", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OnboardingPage />);

    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    await user.click(await screen.findByRole("button", { name: "Condensed Matter Physics" }));
    await user.click(screen.getByRole("button", { name: /next/i }));
    await user.click(await screen.findByRole("button", { name: /next/i }));

    await user.click(await screen.findByRole("button", { name: "+ Superconductivity" }));
    expect(screen.getByText("Topics you follow (1/6)")).toBeInTheDocument();
  });
});
