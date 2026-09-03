"use client";

import { motion } from "framer-motion";

/**
 * Floating animated gradient blooms behind hero/auth sections — the colorful,
 * alive backdrop pattern (soft radial blobs, slow breathing motion) applied
 * with SkoLab's own accent palette rather than a borrowed one.
 */
export function Orbs() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden>
      <motion.div
        animate={{ scale: [1, 1.12, 1], opacity: [0.45, 0.62, 0.45] }}
        transition={{ duration: 9, repeat: Infinity, ease: "easeInOut" }}
        className="absolute -top-40 -left-40 h-[560px] w-[560px] rounded-full"
        style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--primary) 42%, transparent) 0%, transparent 68%)" }}
      />
      <motion.div
        animate={{ scale: [1, 1.1, 1], opacity: [0.3, 0.42, 0.3] }}
        transition={{ duration: 11, repeat: Infinity, ease: "easeInOut", delay: 2 }}
        className="absolute -bottom-52 -right-40 h-[620px] w-[620px] rounded-full"
        style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--accent-teal) 30%, transparent) 0%, transparent 65%)" }}
      />
      <motion.div
        animate={{ y: [0, -28, 0], opacity: [0.22, 0.34, 0.22] }}
        transition={{ duration: 13, repeat: Infinity, ease: "easeInOut", delay: 4 }}
        className="absolute top-[28%] left-[52%] h-[460px] w-[460px] rounded-full"
        style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--accent-violet) 26%, transparent) 0%, transparent 65%)" }}
      />
      <motion.div
        animate={{ x: [0, 20, 0], opacity: [0.2, 0.3, 0.2] }}
        transition={{ duration: 10, repeat: Infinity, ease: "easeInOut", delay: 1 }}
        className="absolute bottom-[10%] left-[8%] h-[360px] w-[360px] rounded-full"
        style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--accent-orange) 24%, transparent) 0%, transparent 65%)" }}
      />
    </div>
  );
}
