"use client";

import { useRef } from "react";
import { motion, useMotionValue, useSpring } from "framer-motion";

/** Magnetic, shimmering gradient CTA — for the single highest-priority action on a page (landing hero). */
export function MagneticCTA({
  children,
  onClick,
  className,
}: {
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const sx = useSpring(x, { stiffness: 300, damping: 25 });
  const sy = useSpring(y, { stiffness: 300, damping: 25 });

  function handleMouseMove(e: React.MouseEvent<HTMLButtonElement>) {
    const rect = ref.current?.getBoundingClientRect();
    if (!rect) return;
    x.set((e.clientX - (rect.left + rect.width / 2)) * 0.25);
    y.set((e.clientY - (rect.top + rect.height / 2)) * 0.25);
  }
  function reset() {
    x.set(0);
    y.set(0);
  }

  return (
    <motion.button
      ref={ref}
      style={{ x: sx, y: sy }}
      onMouseMove={handleMouseMove}
      onMouseLeave={reset}
      onClick={onClick}
      whileHover={{ scale: 1.04 }}
      whileTap={{ scale: 0.96 }}
      className={`relative overflow-hidden rounded-md ${className ?? ""}`}
    >
      <span
        className="relative z-10 flex items-center justify-center gap-2 px-7 py-3.5 font-body text-[14px] font-semibold text-white"
        style={{ background: "var(--gradient-hero)", boxShadow: "var(--shadow-glow-primary)" }}
      >
        <motion.span
          className="pointer-events-none absolute inset-0"
          style={{ background: "linear-gradient(105deg, transparent 35%, rgba(255,255,255,0.25) 50%, transparent 65%)" }}
          animate={{ x: ["-100%", "200%"] }}
          transition={{ duration: 2.6, repeat: Infinity, repeatDelay: 1.4, ease: "easeInOut" }}
        />
        <span className="relative z-10">{children}</span>
      </span>
    </motion.button>
  );
}
