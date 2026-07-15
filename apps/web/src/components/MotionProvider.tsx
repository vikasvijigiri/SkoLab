"use client";

import { MotionConfig } from "framer-motion";

/**
 * `reducedMotion="user"` makes every framer-motion animation in the app respect the OS
 * "reduce motion" setting. Without this, framer-motion's JS-driven springs (nav pill,
 * tab pills, card hover-lift, button shimmer, etc.) bypass the CSS `prefers-reduced-motion`
 * rule in globals.css entirely, since they don't animate via CSS transition/animation.
 */
export function MotionProvider({ children }: { children: React.ReactNode }) {
  return <MotionConfig reducedMotion="user">{children}</MotionConfig>;
}
