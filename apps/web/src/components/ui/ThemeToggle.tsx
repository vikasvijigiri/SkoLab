"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import { applyTheme, initialTheme, nextTheme } from "@/lib/theme";
import { TRANSITION_FAST, TRANSITION_NORMAL } from "@/lib/motion";

export function ThemeToggle() {
  const [theme, setTheme] = useState(initialTheme);
  const [hovered, setHovered] = useState(false);

  const cycle = () => {
    const next = nextTheme(theme);
    setTheme(next);
    applyTheme(next);
  };

  return (
    <motion.button
      onClick={cycle}
      onHoverStart={() => setHovered(true)}
      onHoverEnd={() => setHovered(false)}
      aria-label={`Theme: ${theme}`}
      title={`Theme: ${theme} (click to change)`}
      suppressHydrationWarning
      whileHover={{ scale: 1.12, boxShadow: "0 4px 14px color-mix(in srgb, var(--primary) 25%, transparent)" }}
      whileTap={{ scale: 0.92 }}
      transition={TRANSITION_FAST}
      className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full border border-border bg-surface text-text-muted transition-colors duration-[var(--motion-fast)] hover:text-primary"
      style={{ transitionTimingFunction: "var(--ease-standard)" }}
    >
      <motion.span
        className="flex items-center justify-center"
        animate={{ rotate: hovered ? 25 : 0 }}
        transition={TRANSITION_NORMAL}
      >
        {theme === "light" && (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="4.5" />
            <path d="M12 2.5v2M12 19.5v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M2.5 12h2M19.5 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4" strokeLinecap="round" />
          </svg>
        )}
        {theme === "dark" && (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" strokeLinejoin="round" />
          </svg>
        )}
        {theme === "system" && (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="4.5" width="18" height="12" rx="1.5" />
            <path d="M8 20h8M12 16.5V20" strokeLinecap="round" />
          </svg>
        )}
      </motion.span>
    </motion.button>
  );
}
