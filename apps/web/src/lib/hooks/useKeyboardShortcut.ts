"use client";

import { useEffect } from "react";

interface ShortcutOptions {
  key: string;
  meta?: boolean;
  ctrl?: boolean;
}

/** Global keyboard shortcut. Checks both metaKey and ctrlKey so `meta: true` works cross-OS (Cmd on Mac, Ctrl on Windows/Linux). */
export function useKeyboardShortcut(opts: ShortcutOptions, handler: () => void, enabled = true) {
  useEffect(() => {
    if (!enabled) return;

    function onKeyDown(e: KeyboardEvent) {
      if (e.key.toLowerCase() !== opts.key.toLowerCase()) return;
      if (opts.meta && !(e.metaKey || e.ctrlKey)) return;
      if (opts.ctrl && !e.ctrlKey) return;
      e.preventDefault();
      handler();
    }

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [opts.key, opts.meta, opts.ctrl, enabled, handler]);
}
