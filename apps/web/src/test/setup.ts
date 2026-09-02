import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { server } from "./handlers";
import { reset as resetFirestore } from "./firestore";

// The Firebase SDK is never wanted for real under jsdom. Stub `firebase/firestore`
// with the test double (see ./firestore.ts) and `requireDb` with a sentinel.
vi.mock("firebase/firestore", async () => (await import("./firestore")).firestoreMock);
vi.mock("@/lib/firebase/client", () => ({
  isFirebaseConfigured: true,
  requireDb: () => ({}),
  requireAuth: () => ({}),
  auth: {},
  db: {},
  GOOGLE_WEB_CLIENT_ID: "test-client-id",
}));

// jsdom implements neither observer API nor matchMedia; several UI primitives
// (AnimatedCounter, Reveal, charts, useMediaQuery) touch them on mount.
class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
vi.stubGlobal("IntersectionObserver", NoopObserver);
vi.stubGlobal("ResizeObserver", NoopObserver);
if (!window.matchMedia) {
  vi.stubGlobal(
    "matchMedia",
    (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  );
}
window.scrollTo = () => {};
// jsdom implements neither; workspace ChatTab auto-scrolls its message list.
Element.prototype.scrollIntoView = () => {};

// The network boundary is mocked once for the whole suite. A test that needs a
// different response calls `server.use(...)` and it is reset after.
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  resetFirestore();
});
afterAll(() => server.close());
