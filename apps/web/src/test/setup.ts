import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./handlers";

// The network boundary is mocked once for the whole suite. A test that needs a
// different response calls `server.use(...)` and it is reset after.
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
