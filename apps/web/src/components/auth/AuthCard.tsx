"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowLeft } from "lucide-react";
import { Orbs } from "@/components/ui/Orbs";

export function AuthCard({ children }: { children: React.ReactNode }) {
  return (
    // min-h-dvh floor + overflow-x-clip (Orbs already self-clip). No vertical
    // clip, so a tall card (e.g. onboarding step 3) grows the page and the
    // document scrolls instead of the bottom being cut off.
    <div className="relative flex min-h-dvh flex-col overflow-x-clip">
      <Orbs />

      <header className="relative z-10 flex shrink-0 items-center gap-3 px-6 py-6 md:px-10">
        <Link
          href="/"
          aria-label="Back to home"
          className="group flex h-8 w-8 items-center justify-center rounded-full border border-border bg-surface text-text-secondary transition-[color,transform,box-shadow] duration-[var(--motion-fast)] hover:scale-110 hover:text-primary hover:shadow-[0_4px_14px_color-mix(in_srgb,var(--primary)_25%,transparent)] active:scale-95"
          style={{ transitionTimingFunction: "var(--ease-standard)" }}
        >
          <ArrowLeft size={16} className="transition-transform duration-200 group-hover:-translate-x-0.5" />
        </Link>
        <Link href="/" className="font-display text-[18px] font-bold text-text-primary">
          SkoLab
        </Link>
      </header>

      <div className="relative z-10 flex flex-1 flex-col items-center px-4 pb-10 pt-2">
        <motion.div
          initial={{ opacity: 0, y: 16, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
          // my-auto centers the card when there's spare vertical room, and
          // collapses to natural flow (top-aligned, page scrolls) when the
          // card is taller than the viewport.
          className="my-auto w-full max-w-md"
        >
          <div className="overflow-hidden rounded-[18px] border border-border bg-surface shadow-elevated">
            <div className="h-1 w-full" style={{ background: "var(--gradient-hero)" }} aria-hidden />
            <div className="p-6 sm:p-8">{children}</div>
          </div>
          <p className="mt-5 text-center font-body text-[12px] text-text-muted">
            © {new Date().getFullYear()} SkoLab · Terms · Privacy
          </p>
        </motion.div>
      </div>
    </div>
  );
}
