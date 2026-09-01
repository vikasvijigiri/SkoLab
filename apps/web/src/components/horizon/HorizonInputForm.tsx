import { motion } from "framer-motion";
import { ArrowRight, AlertCircle } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";

const FRONTIER_DOMAINS = [
  {
    name: "Quantum Machine Learning",
    desc: "Merging quantum algorithms with neural network architectures.",
    color: "var(--accent-purple)",
  },
  {
    name: "CRISPR Gene Modulation",
    desc: "Targeted cellular modifications and genomic therapeutics.",
    color: "var(--accent-teal)",
  },
  {
    name: "Fusion Power Logistics",
    desc: "Optimizing plasma confinement systems via predictive modeling.",
    color: "var(--accent-cyan)",
  },
  {
    name: "Metamaterials in Aerospace",
    desc: "Designing structures with custom electromagnetic and physical properties.",
    color: "var(--accent-teal)",
  },
];

interface Props {
  field: string;
  focusArea: string;
  error: string | null;
  onFieldChange: (v: string) => void;
  onFocusChange: (v: string) => void;
  onSelectDomain: (name: string) => void;
  onSubmit: (e?: React.FormEvent) => void;
}

export function HorizonInputForm({
  field,
  focusArea,
  error,
  onFieldChange,
  onFocusChange,
  onSelectDomain,
  onSubmit,
}: Props) {
  return (
    <motion.div
      key="input-form"
      initial={{ opacity: 0, y: 15 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -15 }}
      transition={{ duration: 0.3 }}
      className="flex flex-col gap-8"
    >
      <Card accentColor="var(--primary)" className="border-border/50 bg-surface/60 backdrop-blur-md">
        <form onSubmit={onSubmit} className="flex flex-col gap-5">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <Input
              label="Scientific or Technological Field"
              placeholder="e.g. CRISPR gene editing, Neuromorphic chips"
              required
              value={field}
              onChange={(e) => onFieldChange(e.target.value)}
            />
            <Input
              label="Focus Area / Sub-discipline (Optional)"
              placeholder="e.g. Cancer therapeutics, Edge robotics"
              value={focusArea}
              onChange={(e) => onFocusChange(e.target.value)}
            />
          </div>

          <div className="flex justify-end pt-2">
            <Button type="submit" fullWidth={false} className="gap-2 px-8">
              Forge Discovery
              <ArrowRight size={16} />
            </Button>
          </div>
        </form>
      </Card>

      <div className="flex flex-col gap-3">
        <h3 className="font-body text-[13px] font-bold tracking-wide uppercase text-text-muted">
          Pre-selected Frontiers
        </h3>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {FRONTIER_DOMAINS.map((domain, i) => (
            <motion.div
              key={domain.name}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, delay: i * 0.05 }}
              onClick={() => onSelectDomain(domain.name)}
              className="cursor-pointer"
            >
              <Card
                glow
                accentColor={domain.color}
                className="flex h-full flex-col justify-between border-border/40 bg-surface/30 p-4 transition-all duration-300 hover:border-primary/30"
              >
                <div>
                  <h4 className="font-display text-[14.5px] font-semibold text-text-primary">{domain.name}</h4>
                  <p className="mt-1.5 font-body text-[12.5px] leading-snug text-text-muted">{domain.desc}</p>
                </div>
                <div className="mt-4 flex items-center justify-end text-primary">
                  <ArrowRight size={14} className="opacity-60 transition-transform group-hover:translate-x-1" />
                </div>
              </Card>
            </motion.div>
          ))}
        </div>
      </div>

      {error && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="flex items-center gap-2.5 rounded-md border border-notification/20 bg-notification/10 p-3 text-notification"
        >
          <AlertCircle size={16} className="shrink-0" />
          <span className="min-w-0 flex-1 font-body text-[13px] font-medium">{error}</span>
          <button
            onClick={() => onSubmit()}
            className="shrink-0 cursor-pointer rounded-md border border-notification/30 px-2.5 py-1 font-body text-[12px] font-medium text-notification transition-colors duration-[var(--motion-fast)] hover:bg-notification/10"
          >
            Retry
          </button>
        </motion.div>
      )}
    </motion.div>
  );
}
