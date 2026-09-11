import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import { mockBreakthroughPrediction } from "@/test/fixtures";
import HorizonPage from "./page";

vi.mock("@/lib/hooks/useMyProfile", () => ({
  useMyProfile: () => ({ author: { id: "A1" }, firestoreProfile: null, loading: false, error: null, refetch: vi.fn() }),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("HorizonPage — click-only", () => {
  it("has no text inputs and predicts after clicking a field chip", async () => {
    server.use(
      http.post(`${API}/api/v1/discovery/predict`, () =>
        HttpResponse.json({ ...mockBreakthroughPrediction, breakthrough_name: "Fusion compilers" }),
      ),
    );
    const user = userEvent.setup();
    const { container } = renderWithProviders(<HorizonPage />);

    const chip = await screen.findByRole("button", { name: "Physics and Astronomy" });
    expect(container.querySelector("input, textarea")).toBeNull();
    await user.click(chip);
    await user.click(screen.getByRole("button", { name: /forge discovery/i }));
    expect(await screen.findByText("Fusion compilers")).toBeInTheDocument();
  });

  it("shows a retryable error when the foresight call fails", async () => {
    server.use(http.post(`${API}/api/v1/discovery/predict`, () => new HttpResponse(null, { status: 500 })));
    const user = userEvent.setup();
    renderWithProviders(<HorizonPage />);
    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    await user.click(screen.getByRole("button", { name: /forge discovery/i }));
    expect(await screen.findByText(/Foresight engine timed out/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /retry/i })).toBeInTheDocument();
  });

  it("flags a fallback prediction as estimated instead of presenting it as genuine analysis", async () => {
    server.use(
      http.post(`${API}/api/v1/discovery/predict`, () =>
        HttpResponse.json({ ...mockBreakthroughPrediction, is_fallback: true }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<HorizonPage />);
    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    await user.click(screen.getByRole("button", { name: /forge discovery/i }));
    expect(await screen.findByText(/Estimated — AI unavailable/i)).toBeInTheDocument();
  });

  it("does not show the estimated badge for a genuine prediction", async () => {
    server.use(
      http.post(`${API}/api/v1/discovery/predict`, () => HttpResponse.json(mockBreakthroughPrediction)),
    );
    const user = userEvent.setup();
    renderWithProviders(<HorizonPage />);
    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    await user.click(screen.getByRole("button", { name: /forge discovery/i }));
    await screen.findByText(mockBreakthroughPrediction.breakthrough_name);
    expect(screen.queryByText(/Estimated — AI unavailable/i)).not.toBeInTheDocument();
  });
});
