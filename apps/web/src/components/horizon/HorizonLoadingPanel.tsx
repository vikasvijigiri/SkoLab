import { motion, AnimatePresence } from "framer-motion";
import { Sparkles } from "lucide-react";

export const LOADING_STEPS = [
  "Querying OpenAlex global research corpus...",
  "Retrieving pioneering & latest publication frameworks...",
  "Ingesting abstracts and mapping conceptual overlaps...",
  "Simulating cross-disciplinary innovation trajectories...",
  "Synthesizing commercial feasibility and drafting blueprint...",
];

export function HorizonLoadingPanel({ loadingStep }: { loadingStep: number }) {
  return (
    <motion.div
      key="loading-state"
      initial={{ opacity: 0, scale: 0.97 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.97 }}
      className="flex flex-col items-center justify-center py-20 text-center"
    >
      <div className="relative mb-8 flex h-24 w-24 items-center justify-center">
        <motion.div
          className="absolute inset-0 rounded-full border-2 border-primary/20 border-t-primary"
          animate={{ rotate: 360 }}
          transition={{ duration: 1.5, repeat: Infinity, ease: "linear" }}
        />
        <motion.div
          className="absolute inset-2 rounded-full border border-dashed border-primary/30"
          animate={{ rotate: -360 }}
          transition={{ duration: 4, repeat: Infinity, ease: "linear" }}
        />
        <Sparkles size={28} className="animate-pulse text-primary" />
      </div>

      <h3 className="font-display text-[18px] font-bold text-text-primary">Synthesizing Future Horizon</h3>
      <div className="mt-2.5 h-6 overflow-hidden">
        <AnimatePresence mode="wait">
          <motion.p
            key={loadingStep}
            initial={{ y: 15, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: -15, opacity: 0 }}
            transition={{ duration: 0.25 }}
            className="font-mono text-[12.5px] text-text-muted"
          >
            {LOADING_STEPS[loadingStep]}
          </motion.p>
        </AnimatePresence>
      </div>

      <div className="mt-6 flex gap-1.5">
        {LOADING_STEPS.map((_, i) => (
          <div
            key={i}
            className={`h-1 w-8 rounded-full transition-colors duration-500 ${
              i <= loadingStep ? "bg-primary" : "bg-surface-subtle"
            }`}
          />
        ))}
      </div>
    </motion.div>
  );
}
