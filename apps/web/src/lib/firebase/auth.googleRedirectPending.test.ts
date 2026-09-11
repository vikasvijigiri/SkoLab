import { describe, expect, it, beforeEach } from "vitest";
import {
  markGoogleRedirectPending,
  hasGoogleRedirectPending,
  clearGoogleRedirectPending,
} from "./auth";

describe("Google redirect pending flag", () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it("is false before anything marks it", () => {
    expect(hasGoogleRedirectPending()).toBe(false);
  });

  it("is true after marking, false after clearing", () => {
    markGoogleRedirectPending();
    expect(hasGoogleRedirectPending()).toBe(true);
    clearGoogleRedirectPending();
    expect(hasGoogleRedirectPending()).toBe(false);
  });

  it("clearing when nothing was marked is a no-op, not a throw", () => {
    expect(() => clearGoogleRedirectPending()).not.toThrow();
    expect(hasGoogleRedirectPending()).toBe(false);
  });
});
