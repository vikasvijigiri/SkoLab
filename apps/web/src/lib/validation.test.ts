import { describe, expect, it } from "vitest";
import { emailError, fallbackName, nameError, passwordError, passwordStrength } from "./validation";

describe("emailError", () => {
  it.each([
    ["", "Enter your email address."],
    ["   ", "Enter your email address."],
    ["ada", "Enter a valid email address, like name@example.com."],
    ["ada@example", "Enter a valid email address, like name@example.com."],
    ["a b@example.com", "Enter a valid email address, like name@example.com."],
  ])("rejects %j", (value, message) => expect(emailError(value)).toBe(message));

  it("accepts a normal address with surrounding spaces", () => expect(emailError("  ada@example.com ")).toBeNull());
});

describe("passwordError", () => {
  it("only requires presence when signing in", () => {
    expect(passwordError("", "existing")).toBe("Enter your password.");
    expect(passwordError("x", "existing")).toBeNull();
  });

  it("enforces length and strength for new passwords", () => {
    expect(passwordError("", "new")).toBe("Create a password.");
    expect(passwordError("short1", "new")).toBe("Use at least 8 characters.");
    expect(passwordError("a".repeat(129), "new")).toBe("Use at most 128 characters.");
    expect(passwordError("password", "new")).toMatch(/harder to guess/);
    expect(passwordError("correct horse 42", "new")).toBeNull();
  });
});

describe("passwordStrength", () => {
  it.each([
    ["abc", 0, "Too short"],
    ["aaaaaaaaaa", 1, "Weak"],
    ["Password", 1, "Weak"],
    ["abcdefgh1", 1, "Weak"],
    ["abcdefG1", 2, "Fair"],
    ["abcdefgh1234", 2, "Fair"],
    ["abcdefG1!", 3, "Good"],
    ["Abcdefgh1234!xyz", 4, "Strong"],
  ])("rates %j as %i (%s)", (value, score, label) => expect(passwordStrength(value)).toEqual({ score, label }));
});

describe("nameError", () => {
  it("requires a printable name of at most 255 characters", () => {
    expect(nameError("  ")).toBe("Enter your name.");
    expect(nameError("a".repeat(256))).toBe("Use at most 255 characters.");
    expect(nameError("Ada\u0000")).toMatch(/letters, spaces/);
    expect(nameError("Ada Lovelace")).toBeNull();
    expect(nameError("😀".repeat(255))).toBeNull();
  });
});

describe("fallbackName", () => {
  it("uses the email's local part", () => {
    expect(fallbackName("ada@example.com")).toBe("ada");
    expect(fallbackName(null)).toBe("SkoLab user");
    expect(fallbackName("@example.com")).toBe("SkoLab user");
  });
});
