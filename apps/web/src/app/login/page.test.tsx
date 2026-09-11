import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import LoginPage from "./page";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

const auth = vi.hoisted(() => ({ configured: true }));
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ configured: auth.configured }),
}));

const mocks = vi.hoisted(() => ({
  hasGoogleRedirectPending: vi.fn(() => false),
  clearGoogleRedirectPending: vi.fn(),
  completeGoogleRedirectSignIn: vi.fn(async () => null as { user: unknown; isNewUser: boolean } | null),
  signInWithGoogle: vi.fn(async () => undefined),
  signInWithEmail: vi.fn(),
  signInAsGuest: vi.fn(),
}));
vi.mock("@/lib/firebase/auth", () => ({
  hasGoogleRedirectPending: mocks.hasGoogleRedirectPending,
  clearGoogleRedirectPending: mocks.clearGoogleRedirectPending,
  completeGoogleRedirectSignIn: mocks.completeGoogleRedirectSignIn,
  signInWithGoogle: mocks.signInWithGoogle,
  signInWithEmail: mocks.signInWithEmail,
  signInAsGuest: mocks.signInAsGuest,
}));

beforeEach(() => {
  auth.configured = true;
  push.mockClear();
  mocks.hasGoogleRedirectPending.mockReset().mockReturnValue(false);
  mocks.clearGoogleRedirectPending.mockClear();
  mocks.completeGoogleRedirectSignIn.mockReset().mockResolvedValue(null);
});

describe("LoginPage — Google redirect completion", () => {
  it("shows the ordinary form when no redirect is pending", () => {
    renderWithProviders(<LoginPage />);
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(screen.queryByText(/signing you in/i)).not.toBeInTheDocument();
  });

  it("shows a loading view immediately, not the idle form, when a redirect is pending", () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockImplementation(() => new Promise(() => {})); // never resolves in this test
    renderWithProviders(<LoginPage />);
    expect(screen.getByText(/signing you in/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Welcome back" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /continue with google/i })).not.toBeInTheDocument();
  });

  it("navigates to /home and clears the flag once the redirect resolves for a returning user", async () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockResolvedValue({ user: {}, isNewUser: false });
    renderWithProviders(<LoginPage />);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/home"));
    expect(mocks.clearGoogleRedirectPending).toHaveBeenCalled();
  });

  it("navigates to /onboarding for a brand-new Google user", async () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockResolvedValue({ user: {}, isNewUser: true });
    renderWithProviders(<LoginPage />);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/onboarding"));
  });

  it("falls back to the ordinary form if the flag was stale (no real redirect result)", async () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockResolvedValue(null);
    renderWithProviders(<LoginPage />);
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it("shows the form with an error if completing the redirect fails", async () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockRejectedValue({ code: "auth/network-request-failed" });
    renderWithProviders(<LoginPage />);
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(await screen.findByText("Network error — check your connection and try again.")).toBeInTheDocument();
    expect(mocks.clearGoogleRedirectPending).toHaveBeenCalled();
  });

  it("never calls completeGoogleRedirectSignIn when Firebase isn't configured, and clears any stale flag", async () => {
    auth.configured = false;
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    renderWithProviders(<LoginPage />);
    await waitFor(() => expect(mocks.clearGoogleRedirectPending).toHaveBeenCalled());
    expect(mocks.completeGoogleRedirectSignIn).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });
});
