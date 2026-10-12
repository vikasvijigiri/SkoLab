import { describe, expect, it, vi } from "vitest";
import { apiRequest, ApiError } from "./client";
import { syncProfile } from "./profile";

describe("apiRequest", () => {
  it("sends the bearer token and JSON body, and parses the answer", async () => {
    const fetchMock = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(() => Promise.resolve(Response.json({ status: "synced", uid: "u1" })));
    vi.stubGlobal("fetch", fetchMock);
    await expect(syncProfile("tok", "u1", "Ada")).resolves.toEqual({ status: "synced", uid: "u1" });
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe("/api/v1/users/profile/sync");
    expect(init?.method).toBe("POST");
    expect(init?.credentials).toBe("omit");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer tok");
    expect(JSON.parse(init?.body as string)).toEqual({ uid: "u1", name: "Ada" });
  });

  it("turns the gateway's error body into an ApiError", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(Response.json({ code: "email_unverified", error: "Verify your email" }, { status: 403 })));
    const error = await apiRequest("/x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: "email_unverified", message: "Verify your email" });

    vi.stubGlobal("fetch", () => Promise.resolve(Response.json({ code: "version_conflict", error: "moved on", current_version: 4 }, { status: 409 })));
    await expect(apiRequest("/x", { method: "PUT", body: {} })).rejects.toMatchObject({ status: 409, details: { current_version: 4 } });
  });

  it("sends extra headers", async () => {
    const fetchMock = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(() => Promise.resolve(Response.json({})));
    vi.stubGlobal("fetch", fetchMock);
    await apiRequest("/x", { method: "POST", body: {}, headers: { "Idempotency-Key": "k1" } });
    expect(new Headers(fetchMock.mock.calls[0]?.[1].headers).get("Idempotency-Key")).toBe("k1");
  });

  it("copes with a non-JSON error and a 204", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(new Response("<html>bad gateway</html>", { status: 502 })));
    await expect(apiRequest("/x")).rejects.toMatchObject({ status: 502, code: "http_error" });
    vi.stubGlobal("fetch", () => Promise.resolve(new Response(null, { status: 204 })));
    await expect(apiRequest("/x", { method: "DELETE" })).resolves.toBeUndefined();
  });

  it("reports a network failure in words, but lets a caller's abort through", async () => {
    vi.stubGlobal("fetch", () => Promise.reject(new TypeError("Failed to fetch")));
    await expect(apiRequest("/x")).rejects.toMatchObject({ status: 0, code: "network" });
    const controller = new AbortController();
    controller.abort();
    const abort = new DOMException("aborted", "AbortError");
    vi.stubGlobal("fetch", () => Promise.reject(abort));
    await expect(apiRequest("/x", { signal: controller.signal })).rejects.toBe(abort);
  });
});
