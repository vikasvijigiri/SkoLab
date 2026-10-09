import { describe, expect, it } from "vitest";
import { messageFor, toAuthError } from "./errors";
import { AuthError } from "./types";

describe("toAuthError", () => {
  it.each([
    ["auth/invalid-credential", "invalid-credentials"],
    ["auth/user-not-found", "invalid-credentials"],
    ["auth/wrong-password", "invalid-credentials"],
    ["auth/email-already-in-use", "email-in-use"],
    ["auth/too-many-requests", "too-many-requests"],
    ["auth/popup-closed-by-user", "popup-closed"],
    ["auth/network-request-failed", "network"],
    ["auth/unauthorized-domain", "unauthorized-domain"],
    ["auth/something-new", "unknown"],
  ])("maps %s to %s", (code, expected) => expect(toAuthError({ code }).code).toBe(expected));

  it("handles non-Firebase values and keeps AuthErrors as they are", () => {
    expect(toAuthError(new Error("boom")).code).toBe("unknown");
    expect(toAuthError(null).code).toBe("unknown");
    const original = new AuthError("network");
    expect(toAuthError(original)).toBe(original);
  });
});

describe("messageFor", () => {
  it("never says whether the account exists", () => {
    const wrongPassword = messageFor({ code: "auth/wrong-password" });
    expect(messageFor({ code: "auth/user-not-found" })).toBe(wrongPassword);
    expect(wrongPassword).not.toMatch(/no account|not found|doesn't exist/i);
  });

  it("points to email and password when Google sign-in isn't allowed on this address", () => {
    expect(messageFor({ code: "auth/unauthorized-domain" })).toMatch(/email and password/);
  });
});
