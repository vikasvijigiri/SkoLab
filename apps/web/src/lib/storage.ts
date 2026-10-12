import { useCallback, useState } from "react";

/**
 * Small per-browser preferences (layout, auto-compile, theme). Storage can be
 * missing or throw (private windows, blocked site data); every read falls
 * back to the default and every write is best effort.
 */
export function readSetting<T>(key: string, fallback: T, valid: (value: unknown) => value is T): T {
  try {
    const raw = window.localStorage.getItem(key);
    if (raw === null) return fallback;
    const value: unknown = JSON.parse(raw);
    return valid(value) ? value : fallback;
  } catch {
    return fallback;
  }
}

export function writeSetting(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Not remembered; the page still works.
  }
}

/** A preference kept in state and in storage. The key is read once; remount to change it. */
export function useSetting<T>(key: string, fallback: T, valid: (value: unknown) => value is T): [T, (value: T) => void] {
  const [value, setValue] = useState(() => readSetting(key, fallback, valid));
  const update = useCallback(
    (next: T) => {
      setValue(next);
      writeSetting(key, next);
    },
    [key],
  );
  return [value, update];
}

export const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

export function oneOf<T extends string>(...options: readonly T[]) {
  return (value: unknown): value is T => typeof value === "string" && (options as readonly string[]).includes(value);
}
