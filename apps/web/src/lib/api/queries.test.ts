import { describe, expect, it } from "vitest";
import {
  authorQuery,
  collaboratorsQuery,
  authorSuggestionsQuery,
  openAlexWorksQuery,
  paperAnalysisQuery,
  matchGrantsQuery,
  dailyFeedQuery,
} from "./queries";
import { ApiError, ApiPending } from "./client";
import { makeAuthorResponse } from "@/test/fixtures";
import { server } from "@/test/handlers";
import { http, HttpResponse } from "msw";

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("query option factories", () => {
  it("authorQuery builds the documented key and stays disabled with no name/id", () => {
    expect(authorQuery("Ada", "A1", "CS").queryKey).toEqual([
      "author",
      { id: "A1", name: "Ada", focus: "CS" },
    ]);
    expect(authorQuery("").enabled).toBe(false);
    expect(authorQuery("Ada").enabled).toBe(true);
  });

  it("collaboratorsQuery scopes its key under the author id", () => {
    const key = collaboratorsQuery("A1", "Physics").queryKey;
    expect(key[0]).toBe("author");
    expect(key[1]).toBe("A1");
    expect(key[2]).toBe("collaborators");
  });

  it("authorQuery.queryFn calls /search_author and returns the payload", async () => {
    const expected = makeAuthorResponse({ display_name: "Grace Hopper" });
    server.use(http.get(`${API}/search_author`, () => HttpResponse.json(expected)));
    const data = await authorQuery("Grace Hopper", "A9").queryFn!({} as never);
    expect(data.display_name).toBe("Grace Hopper");
  });

  it("authorSuggestionsQuery is disabled for short input", () => {
    expect(authorSuggestionsQuery("a").enabled).toBe(false);
    expect(authorSuggestionsQuery("ada").enabled).toBe(true);
  });

  it("openAlexWorksQuery keys on q+focus and disables when both empty", () => {
    expect(openAlexWorksQuery({ q: "turing", focus: "cs" }).queryKey).toEqual([
      "openalex-works",
      { q: "turing", focus: "cs" },
    ]);
    expect(openAlexWorksQuery({}).enabled).toBe(false);
    expect(openAlexWorksQuery({ q: "  " }).enabled).toBe(false);
    expect(openAlexWorksQuery({ focus: "physics" }).enabled).toBe(true);
  });

  it("paperAnalysisQuery keys on the identifiers and gates on at least one", () => {
    expect(paperAnalysisQuery({ openalexId: "W9" }).queryKey).toEqual([
      "paper-analysis",
      { title: null, doi: null, openalexId: "W9" },
    ]);
    expect(paperAnalysisQuery({}).enabled).toBe(false);
  });

  it("matchGrantsQuery scopes its key to the author id", () => {
    expect(matchGrantsQuery("A1").queryKey).toEqual(["grants", "A1"]);
    expect(matchGrantsQuery("").enabled).toBe(false);
  });

  describe("dailyFeedQuery — 202/Retry-After polling", () => {
    // Regression coverage for the daily_feed prod incident: uncached
    // generation can take up to ~2m48s (feed.py), so the backend responds
    // 202 + Retry-After rather than holding the connection open (see
    // services/backend/app/core/pending_compute.py). The query must poll
    // on that signal well past providers.tsx's global 1-retry default,
    // and must not treat a genuine error the same way.

    it("queryFn throws ApiPending with the Retry-After value in ms on a 202", async () => {
      server.use(
        http.get(`${API}/api/v1/daily_feed`, () =>
          HttpResponse.json([], { status: 202, headers: { "Retry-After": "4" } }),
        ),
      );
      await expect(dailyFeedQuery("A1").queryFn!({} as never)).rejects.toMatchObject({
        retryAfterMs: 4000,
      });
    });

    it("retry keeps going on ApiPending past the global 1-retry default", () => {
      const { retry } = dailyFeedQuery("A1");
      expect(typeof retry).toBe("function");
      const shouldRetry = retry as (failureCount: number, error: unknown) => boolean;
      expect(shouldRetry(1, new ApiPending(4000))).toBe(true);
      expect(shouldRetry(19, new ApiPending(4000))).toBe(true);
      expect(shouldRetry(20, new ApiPending(4000))).toBe(false);
    });

    it("retry does not retry a real error the same way", () => {
      const { retry } = dailyFeedQuery("A1");
      const shouldRetry = retry as (failureCount: number, error: unknown) => boolean;
      expect(shouldRetry(1, new ApiError(500, "boom"))).toBe(false);
    });

    it("retryDelay uses the server's Retry-After for ApiPending, a fixed fallback otherwise", () => {
      const { retryDelay } = dailyFeedQuery("A1");
      const delay = retryDelay as (failureCount: number, error: unknown) => number;
      expect(delay(1, new ApiPending(4000))).toBe(4000);
      expect(delay(1, new ApiError(500, "boom"))).toBe(1000);
    });
  });
});
