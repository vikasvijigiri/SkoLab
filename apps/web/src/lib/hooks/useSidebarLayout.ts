"use client";

import { useCallback, useState } from "react";
import { useLocalStorage } from "./useLocalStorage";

/** Expanded width bounds, the default, and the collapsed icon-rail width. */
export const SIDEBAR_MIN = 208;
export const SIDEBAR_MAX = 384;
export const SIDEBAR_DEFAULT = 244;
export const SIDEBAR_RAIL = 60;

const clamp = (n: number) => Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, Math.round(n)));

export interface SidebarLayout {
  /** Current rendered width in px (rail width when collapsed, live value while dragging). */
  width: number;
  collapsed: boolean;
  /** True only while a resize drag is in progress — callers disable the width transition. */
  dragging: boolean;
  toggleCollapsed: () => void;
  /** Spread onto the drag handle element. Pointer-drag to resize, ←/→ to nudge, double-click to reset. */
  resizeHandleProps: {
    role: "separator";
    "aria-orientation": "vertical";
    "aria-label": string;
    "aria-valuenow": number;
    "aria-valuemin": number;
    "aria-valuemax": number;
    tabIndex: 0;
    onPointerDown: (e: React.PointerEvent) => void;
    onKeyDown: (e: React.KeyboardEvent) => void;
    onDoubleClick: () => void;
  };
}

/**
 * Persistent sidebar width + collapse state for AppShell.
 *
 * - Width is stored in `localStorage` (`sidebar:width`), clamped to
 *   [SIDEBAR_MIN, SIDEBAR_MAX]; while dragging it lives in local state and is
 *   persisted once on pointer-up, so we don't hammer `localStorage` per frame.
 * - Collapse is a separate flag (`sidebar:collapsed`); collapsed renders the
 *   fixed SIDEBAR_RAIL width regardless of the stored width.
 */
export function useSidebarLayout(): SidebarLayout {
  const [collapsed, setCollapsed] = useLocalStorage("sidebar:collapsed", false);
  const [storedWidth, setStoredWidth] = useLocalStorage("sidebar:width", SIDEBAR_DEFAULT);
  const [liveWidth, setLiveWidth] = useState<number | null>(null);

  const baseWidth = clamp(storedWidth);
  const width = collapsed ? SIDEBAR_RAIL : (liveWidth ?? baseWidth);

  const toggleCollapsed = useCallback(() => setCollapsed(!collapsed), [collapsed, setCollapsed]);

  const onPointerDown = useCallback(
    (e: React.PointerEvent) => {
      if (collapsed) return;
      e.preventDefault();
      const startX = e.clientX;
      const startW = baseWidth;
      setLiveWidth(startW);

      const move = (ev: PointerEvent) => setLiveWidth(clamp(startW + (ev.clientX - startX)));
      const up = () => {
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        setLiveWidth((final) => {
          if (final != null) setStoredWidth(final);
          return null;
        });
      };
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
    },
    [collapsed, baseWidth, setStoredWidth],
  );

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (collapsed) return;
      if (e.key === "ArrowLeft") {
        e.preventDefault();
        setStoredWidth(clamp(baseWidth - 16));
      } else if (e.key === "ArrowRight") {
        e.preventDefault();
        setStoredWidth(clamp(baseWidth + 16));
      }
    },
    [collapsed, baseWidth, setStoredWidth],
  );

  const onDoubleClick = useCallback(() => setStoredWidth(SIDEBAR_DEFAULT), [setStoredWidth]);

  return {
    width,
    collapsed,
    dragging: liveWidth != null,
    toggleCollapsed,
    resizeHandleProps: {
      role: "separator",
      "aria-orientation": "vertical",
      "aria-label": "Resize sidebar",
      "aria-valuenow": width,
      "aria-valuemin": SIDEBAR_MIN,
      "aria-valuemax": SIDEBAR_MAX,
      tabIndex: 0,
      onPointerDown,
      onKeyDown,
      onDoubleClick,
    },
  };
}
