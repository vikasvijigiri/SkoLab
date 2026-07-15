"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowLeft } from "lucide-react";
import { Orbs } from "@/components/ui/Orbs";

export function AuthCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative flex min-h-full flex-1 flex-col overflow-hidden">
      <Orbs />

      <header className="relative z-10 flex items-center gap-3 px-6 py-6 md:px-10">
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

      <div className="relative z-10 flex flex-1 items-center justify-center px-4 pb-16">
        <motion.div
          initial={{ opacity: 0, y: 16, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
          className="w-full max-w-md rounded-[16px] border border-border bg-surface p-8 shadow-none"
        >
          {children}
        </motion.div>
      </div>
    </div>
  );
}
