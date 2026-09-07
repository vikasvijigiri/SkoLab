"use client";

import { useRef } from "react";
import { motion, useMotionValue, useSpring } from "framer-motion";

/** Magnetic solid-fill CTA — for the single highest-priority action on a page (landing hero). */
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
      className={`relative overflow-hidden rounded-md ${className ?? ""}`}
    >
      <span
        className="relative z-10 flex items-center justify-center gap-2 bg-accent-signal px-8 py-4 font-body text-[14px] font-semibold text-text-on-primary shadow-signal"
      >
        {children}
      </span>
    </motion.button>
  );
}
