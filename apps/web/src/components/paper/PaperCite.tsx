"use client";

import { useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Quote, Check, ChevronDown } from "lucide-react";
import { useClickOutside } from "@/lib/hooks/useClickOutside";
import { buildApa, buildBibtex } from "@/lib/openalex-work";
import { cn, focusRing } from "@/lib/utils";
import type { OpenAlexWork } from "@/lib/types";

/** "Cite" control — copies a BibTeX entry or an APA line to the clipboard. */
export function PaperCite({ work }: { work: OpenAlexWork }) {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState<"bibtex" | "apa" | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside(ref, () => setOpen(false), open);

  async function copy(kind: "bibtex" | "apa") {
    const text = kind === "bibtex" ? buildBibtex(work) : buildApa(work);
    try {
      await navigator.clipboard.writeText(text);
      setCopied(kind);
      setTimeout(() => setCopied(null), 1600);
    } catch {
      /* clipboard blocked — no-op */
    }
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        className={cn(
          "inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 font-body text-[12px] font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary",
          focusRing,
        )}
      >
        <Quote size={12} aria-hidden="true" />
        Cite
        <ChevronDown size={12} className={cn("transition-transform", open && "rotate-180")} aria-hidden="true" />
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            role="menu"
            initial={{ opacity: 0, y: -4, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.98 }}
            transition={{ duration: 0.12 }}
            className="absolute right-0 top-9 z-20 w-40 overflow-hidden rounded-lg border border-border bg-surface shadow-elevated"
          >
            {(["bibtex", "apa"] as const).map((kind) => (
              <button
                key={kind}
                type="button"
                role="menuitem"
                onClick={() => copy(kind)}
                className="flex w-full items-center justify-between px-3 py-2 font-body text-[12.5px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
              >
                {kind === "bibtex" ? "Copy BibTeX" : "Copy APA"}
                {copied === kind && <Check size={13} className="text-accent-emerald" />}
              </button>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
