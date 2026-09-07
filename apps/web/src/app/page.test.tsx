import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import LandingPage from "./page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: null, loading: false, configured: true }),
}));

describe("LandingPage", () => {
  it("leads with the outcome headline and a single primary CTA", () => {
    renderWithProviders(<LandingPage />);
    expect(screen.getByText(/Know any researcher's real standing/i)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /get started free/i }).length).toBeGreaterThan(0);
  });

  it("carries the conversion sections: proof, how-it-works, FAQ, final CTA", () => {
    renderWithProviders(<LandingPage />);
    expect(screen.getByText(/Built on/i)).toBeInTheDocument();
    expect(screen.getByText(/How it works/i)).toBeInTheDocument();
    expect(screen.getByText(/Do I have to write search queries\?/i)).toBeInTheDocument();
    expect(screen.getByText(/See your own impact signature\./i)).toBeInTheDocument();
  });

  it("renders the ungated click-only demo with a real researcher signature", async () => {
    renderWithProviders(<LandingPage />);
    // From the default msw /api/openalex/* handlers: field auto-selects, then
    // the researcher list resolves to one hit whose signature renders.
    expect((await screen.findAllByText("Ada Lovelace")).length).toBeGreaterThan(0);
    expect(screen.getByText(/Try it — no sign-up, no typing/i)).toBeInTheDocument();
  });
});
