import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { isBoolean, oneOf, readSetting, useSetting, writeSetting } from "./storage";
import { applyTheme, savedTheme, setTheme } from "./theme";

describe("settings", () => {
  it("reads what was written, and the default for anything missing, malformed or invalid", () => {
    expect(readSetting("k", 1, (value): value is number => typeof value === "number")).toBe(1);
    writeSetting("k", 5);
    expect(readSetting("k", 1, (value): value is number => typeof value === "number")).toBe(5);
    localStorage.setItem("k", "{not json");
    expect(readSetting("k", 1, (value): value is number => typeof value === "number")).toBe(1);
    writeSetting("k", "text");
    expect(readSetting("k", 1, (value): value is number => typeof value === "number")).toBe(1);
  });

  it("survives storage that throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(readSetting("k", true, isBoolean)).toBe(true);
    expect(() => writeSetting("k", false)).not.toThrow();
  });

  it("keeps a setting in state and in storage", () => {
    const { result } = renderHook(() => useSetting("layout", "split", oneOf("split", "pdf")));
    expect(result.current[0]).toBe("split");
    act(() => result.current[1]("pdf"));
    expect(result.current[0]).toBe("pdf");
    expect(localStorage.getItem("layout")).toBe('"pdf"');
  });

  it("applies and remembers a theme", () => {
    expect(savedTheme()).toBe("system");
    setTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(savedTheme()).toBe("dark");
    applyTheme("system");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });
});
