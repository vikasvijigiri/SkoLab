import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import SignupPage from "./page";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ configured: true }),
}));

const mocks = vi.hoisted(() => ({
  hasGoogleRedirectPending: vi.fn(() => false),
  clearGoogleRedirectPending: vi.fn(),
  completeGoogleRedirectSignIn: vi.fn(async () => null as { user: unknown; isNewUser: boolean } | null),
  signInWithGoogle: vi.fn(async () => undefined),
  signUpWithEmail: vi.fn(),
}));
vi.mock("@/lib/firebase/auth", () => ({
  hasGoogleRedirectPending: mocks.hasGoogleRedirectPending,
  clearGoogleRedirectPending: mocks.clearGoogleRedirectPending,
  completeGoogleRedirectSignIn: mocks.completeGoogleRedirectSignIn,
  signInWithGoogle: mocks.signInWithGoogle,
  signUpWithEmail: mocks.signUpWithEmail,
}));

beforeEach(() => {
  push.mockClear();
  mocks.hasGoogleRedirectPending.mockReset().mockReturnValue(false);
  mocks.clearGoogleRedirectPending.mockClear();
  mocks.completeGoogleRedirectSignIn.mockReset().mockResolvedValue(null);
});

describe("SignupPage — Google redirect completion", () => {
  it("shows the ordinary form when no redirect is pending", () => {
    renderWithProviders(<SignupPage />);
    expect(screen.getByRole("heading", { name: "Create your account" })).toBeInTheDocument();
    expect(screen.queryByText(/signing you in/i)).not.toBeInTheDocument();
  });

  it("shows a loading view immediately when a redirect is pending", () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockImplementation(() => new Promise(() => {}));
    renderWithProviders(<SignupPage />);
    expect(screen.getByText(/signing you in/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Create your account" })).not.toBeInTheDocument();
  });

  it("routes a brand-new Google signup to onboarding, not home", async () => {
    mocks.hasGoogleRedirectPending.mockReturnValue(true);
    mocks.completeGoogleRedirectSignIn.mockResolvedValue({ user: {}, isNewUser: true });
    renderWithProviders(<SignupPage />);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/onboarding"));
    expect(mocks.clearGoogleRedirectPending).toHaveBeenCalled();
  });
});
