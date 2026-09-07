"use client";

import { useEffect, type RefObject } from "react";

/**
 * Calls `onOutside` when a pointerdown / touchstart lands outside `ref`, or when
 * Escape is pressed. Used by the top-bar dropdown menus. No-op while `active` is
 * false so a closed menu isn't listening.
 */
export function useClickOutside(
  ref: RefObject<HTMLElement | null>,
  onOutside: () => void,
  active = true,
) {
  useEffect(() => {
    if (!active) return;

    function onPointer(e: Event) {
      const el = ref.current;
      if (el && !el.contains(e.target as Node)) onOutside();
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onOutside();
    }

    document.addEventListener("pointerdown", onPointer, true);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer, true);
      document.removeEventListener("keydown", onKey);
    };
  }, [ref, onOutside, active]);
}
