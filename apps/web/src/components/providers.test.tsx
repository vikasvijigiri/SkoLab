import { describe, expect, it, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { Providers } from "./providers";

const authState: { user: { uid: string } | null } = { user: null };
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({
    user: authState.user,
    loading: false,
    configured: true,
    getIdToken: vi.fn(),
    signOut: vi.fn(),
  }),
}));

function ClientCapture({ onClient }: { onClient: (c: QueryClient) => void }) {
  onClient(useQueryClient());
  return null;
}

describe("Providers — cache invalidation on auth change", () => {
  beforeEach(() => {
    authState.user = null;
  });

  it("does not invalidate on first mount, even into a signed-in session", () => {
    authState.user = { uid: "u1" };
    let client: QueryClient | undefined;
    render(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );
    const spy = vi.spyOn(client as QueryClient, "invalidateQueries");
    expect(spy).not.toHaveBeenCalled();
  });

  it("invalidates every query when the signed-in uid changes (account switch)", () => {
    authState.user = { uid: "u1" };
    let client: QueryClient | undefined;
    const { rerender } = render(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );
    const spy = vi.spyOn(client as QueryClient, "invalidateQueries");

    authState.user = { uid: "u2" };
    rerender(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("invalidates every query on sign-out", () => {
    authState.user = { uid: "u1" };
    let client: QueryClient | undefined;
    const { rerender } = render(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );
    const spy = vi.spyOn(client as QueryClient, "invalidateQueries");

    authState.user = null;
    rerender(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("does not invalidate on a re-render that keeps the same uid", () => {
    authState.user = { uid: "u1" };
    let client: QueryClient | undefined;
    const { rerender } = render(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );
    const spy = vi.spyOn(client as QueryClient, "invalidateQueries");

    rerender(
      <Providers>
        <ClientCapture onClient={(c) => (client = c)} />
      </Providers>,
    );

    expect(spy).not.toHaveBeenCalled();
  });
});
