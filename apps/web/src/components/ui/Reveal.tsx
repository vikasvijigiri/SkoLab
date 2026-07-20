"use client";

import { motion } from "framer-motion";
import { EASE_STANDARD } from "@/lib/motion";

/** Scroll/mount reveal wrapper — fades and lifts content in once, on entry. */
export function Reveal({
  children,
  delay = 0,
  y = 20,
  className,
}: {
  children: React.ReactNode;
  delay?: number;
  y?: number;
  className?: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: "-40px" }}
      transition={{ duration: 0.5, delay, ease: EASE_STANDARD }}
      className={className}
    >
      {children}
    </motion.div>
  );
}
