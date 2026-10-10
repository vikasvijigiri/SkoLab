import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import { resetTemplateCache } from "../api/editor";

// jsdom has no layout. CodeMirror measures ranges and, on some timings,
// calls document.execCommand from an animation frame after a test ends;
// without these stubs that surfaces as an unhandled error in a random file.
const missing = (target: object, name: string, value: unknown) => {
  if (!(name in target)) Object.defineProperty(target, name, { value, configurable: true, writable: true });
};
missing(Range.prototype, "getClientRects", () => Object.assign([], { item: () => null }));
missing(Range.prototype, "getBoundingClientRect", () => new DOMRect(0, 0, 0, 0));
missing(Document.prototype, "execCommand", () => false);

afterEach(() => {
  cleanup();
  resetTemplateCache();
});
