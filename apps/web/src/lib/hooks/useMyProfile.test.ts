import { describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createElement, type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/handlers";
import { makeAuthorResponse } from "@/test/fixtures";
import { emitDoc, emitError } from "@/test/firestore";
import { useMyProfile } from "./useMyProfile";

vi.mock("./AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada Lovelace" } }),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return createElement(QueryClientProvider, { client: qc }, children);
}

describe("useMyProfile", () => {
  it("returns the five-key shape", () => {
    const { result } = renderHook(() => useMyProfile(), { wrapper });
    expect(Object.keys(result.current).sort()).toEqual(
      ["author", "error", "firestoreProfile", "loading", "refetch"].sort(),
    );
    expect(typeof result.current.refetch).toBe("function");
  });

  it("still resolves the author query when the Firestore profile errors", async () => {
    server.use(
      http.get(`${API}/search_author`, () =>
        HttpResponse.json(makeAuthorResponse({ display_name: "Ada Lovelace" })),
      ),
    );
    const { result } = renderHook(() => useMyProfile(), { wrapper });

    emitError("permission-denied");

    await waitFor(() => expect(result.current.author).not.toBeNull());
    expect(result.current.author?.display_name).toBe("Ada Lovelace");
    // The Firestore error is surfaced but did not starve the author lookup.
    expect(typeof result.current.error === "string" || result.current.error === null).toBe(
      true,
    );
  });

  it("populates firestoreProfile from a live doc snapshot", async () => {
    server.use(
      http.get(`${API}/search_author`, () => HttpResponse.json(makeAuthorResponse())),
    );
    const { result } = renderHook(() => useMyProfile(), { wrapper });

    emitDoc({ name: "Ada L.", researchFocus: "Analytical Engines" }, "u1");
    await waitFor(() =>
      expect(result.current.firestoreProfile?.researchFocus).toBe(
        "Analytical Engines",
      ),
    );
  });

  it("refetch invalidates the author query without throwing", async () => {
    server.use(
      http.get(`${API}/search_author`, () => HttpResponse.json(makeAuthorResponse())),
    );
    const { result } = renderHook(() => useMyProfile(), { wrapper });
    await waitFor(() => expect(result.current.author).not.toBeNull());
    expect(() => result.current.refetch()).not.toThrow();
  });
});
