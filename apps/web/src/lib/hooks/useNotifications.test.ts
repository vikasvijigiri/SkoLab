import { describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createElement, type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/handlers";
import { useNotifications, type Notification } from "./useNotifications";
import type { ActivityItem } from "@/lib/types";

function findById(items: Notification[], id: string): Notification {
  const found = items.find((n) => n.id === id);
  expect(found, `expected a notification with id ${id}`).toBeDefined();
  return found as Notification;
}

vi.mock("./AuthProvider", () => ({
  useAuth: () => ({ getIdToken: async () => null }),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return createElement(QueryClientProvider, { client: qc }, children);
}

const FEED: ActivityItem[] = [
  {
    id: "citation:A1:50",
    type: "citation_received",
    verb: "received new citations",
    ts: "2026-09-13T00:00:00Z",
    href: "/author/A1",
    count: 3,
  },
  {
    id: "tracked:W1",
    type: "tracked_researcher_paper",
    verb: "published a new paper",
    ts: "2026-09-12T00:00:00Z",
    actor: { id: "A2", display_name: "Sofia Reyes" },
    object: { kind: "work", id: "W1", title: "Memory-Efficient Feature Dictionaries at Scale" },
    href: "/paper/W1",
  },
  {
    id: "conn:A3:20260911",
    type: "connection_made",
    verb: "is now connected with you",
    ts: "2026-09-11T00:00:00Z",
    actor: { id: "A3", display_name: "Priya Raman" },
    href: "/author/A3",
  },
  {
    id: "trend:W9",
    type: "trending",
    verb: "is gaining attention in Physics",
    ts: "2026-09-10T00:00:00Z",
    object: { kind: "work", id: "W9", title: "Some trending paper" },
    href: "/paper/W9",
  },
];

describe("useNotifications", () => {
  it("maps every personal kind to real, specific text and drops the public trending floor", async () => {
    server.use(http.get(`${API}/api/v1/activity_feed`, () => HttpResponse.json({ items: FEED, degraded: false })));
    const { result } = renderHook(() => useNotifications("A0", "u1"), { wrapper });

    await waitFor(() => expect(result.current.loading).toBe(false));
    const items = result.current.items;

    expect(items.find((n) => n.id === "trend:W9")).toBeUndefined(); // trending is not personal — never in the inbox

    const citation = findById(items, "citation:A1:50");
    expect(citation.kind).toBe("citation");
    expect(citation.filter).toBe("citations");
    expect(citation.text).toContain("3 new citations");

    const tracked = findById(items, "tracked:W1");
    expect(tracked.kind).toBe("tracked_paper");
    expect(tracked.filter).toBe("following");
    expect(tracked.text).toContain("Sofia Reyes");
    expect(tracked.text).toContain("Memory-Efficient Feature Dictionaries at Scale");

    const connection = findById(items, "conn:A3:20260911");
    expect(connection.kind).toBe("connection");
    expect(connection.filter).toBe("connections");
    expect(connection.text).toContain("Priya Raman");
  });

  it("singular citation copy for a delta of one", async () => {
    const single: ActivityItem[] = [
      { id: "c1", type: "citation_received", verb: "received a new citation", ts: "2026-09-13T00:00:00Z", href: "/author/A1", count: 1 },
    ];
    server.use(http.get(`${API}/api/v1/activity_feed`, () => HttpResponse.json({ items: single, degraded: false })));
    const { result } = renderHook(() => useNotifications("A0", "u1"), { wrapper });
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    expect(findById(result.current.items, "c1").text).toBe(
      "Your papers picked up a new citation since you last checked",
    );
  });

  it("surfaces a fetch error and a working refetch", async () => {
    server.use(http.get(`${API}/api/v1/activity_feed`, () => HttpResponse.error()));
    const { result } = renderHook(() => useNotifications("A0", "u1"), { wrapper });
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(typeof result.current.refetch).toBe("function");
  });

  it("respects a custom cap", async () => {
    server.use(http.get(`${API}/api/v1/activity_feed`, () => HttpResponse.json({ items: FEED, degraded: false })));
    const { result } = renderHook(() => useNotifications("A0", "u1", { cap: 2 }), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.items.length).toBeLessThanOrEqual(2);
  });
});
