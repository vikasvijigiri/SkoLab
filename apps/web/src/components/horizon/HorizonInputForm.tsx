import { useState } from "react";
import { motion } from "framer-motion";
import { ArrowRight, AlertCircle, Crosshair, Layers3 } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Chip } from "@/components/ui/Badge";
import { openAlexFieldsQuery, openAlexSubfieldsQuery } from "@/lib/api/queries";
import type { OpenAlexTaxon } from "@/lib/types";

// Curated "try one of these" shortcuts — editorial, not user data.
const FRONTIER_DOMAINS = [
  { name: "Quantum Machine Learning", desc: "Quantum algorithms meeting neural architectures.", color: "var(--accent-purple)" },
  { name: "CRISPR Gene Modulation", desc: "Targeted cellular modifications and genomic therapeutics.", color: "var(--accent-teal)" },
  { name: "Fusion Power Logistics", desc: "Predictive modelling of plasma confinement systems.", color: "var(--accent-cyan)" },
  { name: "Metamaterials in Aerospace", desc: "Structures with custom electromagnetic properties.", color: "var(--accent-teal)" },
];

interface Props {
  field: string;
  error: string | null;
  onFieldChange: (v: string) => void;
  onFocusChange: (v: string) => void;
  onSelectDomain: (name: string) => void;
  onSubmit: (e?: React.FormEvent) => void;
}

export function HorizonInputForm({
  field,
  error,
  onFieldChange,
  onFocusChange,
  onSelectDomain,
  onSubmit,
}: Props) {
  const [fieldTaxon, setFieldTaxon] = useState<OpenAlexTaxon | null>(null);
  const [focusTaxon, setFocusTaxon] = useState<OpenAlexTaxon | null>(null);

  const fieldsQ = useQuery(openAlexFieldsQuery());
  const subfieldsQ = useQuery(openAlexSubfieldsQuery(fieldTaxon?.id));

  function pickField(t: OpenAlexTaxon) {
    setFieldTaxon(t);
    setFocusTaxon(null);
    onFieldChange(t.display_name);
    onFocusChange("");
  }
  function pickFocus(t: OpenAlexTaxon) {
    setFocusTaxon(t);
    onFocusChange(t.display_name);
  }

  return (
    <motion.div
      key="input-form"
      initial={{ opacity: 0, y: 15 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -15 }}
      transition={{ duration: 0.3 }}
      className="flex flex-col gap-8"
    >
      <Card accentColor="var(--primary)" className="border-border/50 bg-surface/70 backdrop-blur-md">
        <div className="flex flex-col gap-5">
          <div className="flex items-start gap-3 border-b border-border/70 pb-4">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Layers3 size={17} />
            </div>
            <div>
              <h2 className="font-display text-[17px] font-bold text-text-primary">Define your frontier</h2>
              <p className="mt-1 font-body text-[12px] leading-relaxed text-text-muted">
                Choose a research field to map its next high-impact opportunity.
              </p>
            </div>
          </div>
          <div>
            <span className="mb-2 flex items-center gap-1.5 font-body text-body-s font-medium text-text-secondary">
              <Crosshair size={14} className="text-primary" />
              Scientific or technological field
            </span>
            {fieldsQ.isPending ? (
              <div className="flex flex-wrap gap-2">
                {[0, 1, 2, 3, 4].map((i) => (
                  <div key={i} className="h-7 w-28 animate-pulse rounded-full bg-surface-subtle" />
                ))}
              </div>
            ) : (
              <div className="flex flex-wrap gap-2">
                {(fieldsQ.data ?? []).map((t) => (
                  <Chip key={t.id} selected={fieldTaxon?.id === t.id} onClick={() => pickField(t)}>
                    {t.display_name}
                  </Chip>
                ))}
              </div>
            )}
          </div>

          {fieldTaxon && (
            <div>
              <span className="mb-2 block font-body text-body-s font-medium text-text-secondary">
                Focus area <span className="text-text-muted">(optional)</span>
              </span>
              {subfieldsQ.isPending ? (
                <div className="flex flex-wrap gap-2">
                  {[0, 1, 2].map((i) => (
                    <div key={i} className="h-7 w-28 animate-pulse rounded-full bg-surface-subtle" />
                  ))}
                </div>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {(subfieldsQ.data ?? []).map((t) => (
                    <Chip key={t.id} selected={focusTaxon?.id === t.id} onClick={() => pickFocus(t)}>
                      {t.display_name}
                    </Chip>
                  ))}
                </div>
              )}
            </div>
          )}

          <div className="flex justify-end pt-2">
            <Button
              type="button"
              fullWidth={false}
              disabled={!field.trim()}
              onClick={() => onSubmit()}
              className="gap-2 px-8"
            >
              Forge Discovery
              <ArrowRight size={16} />
            </Button>
          </div>
        </div>
      </Card>

      <div className="flex flex-col gap-3">
        <div>
          <h3 className="font-body text-body-s font-bold uppercase tracking-wide text-text-muted">
            Jump straight into a frontier
          </h3>
          <p className="mt-1 font-body text-[12px] text-text-muted">Curated starting points for a faster first pass.</p>
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {FRONTIER_DOMAINS.map((domain, i) => (
            <motion.button
              key={domain.name}
              type="button"
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, delay: i * 0.05 }}
              onClick={() => {
                setFieldTaxon(null);
                setFocusTaxon(null);
                onSelectDomain(domain.name);
              }}
              className="cursor-pointer text-left"
            >
              <Card
                glow
                accentColor={domain.color}
                className="flex h-full flex-col justify-between border-border/40 bg-surface/30 p-4 transition-[border-color,background-color] duration-300 hover:border-primary/30"
              >
                <div>
                  <h4 className="font-display text-[14.5px] font-semibold text-text-primary">{domain.name}</h4>
                  <p className="mt-2 font-body text-body-s leading-snug text-text-muted">{domain.desc}</p>
                </div>
                <div className="mt-4 flex items-center justify-end text-primary">
                  <ArrowRight size={14} className="opacity-60" />
                </div>
              </Card>
            </motion.button>
          ))}
        </div>
      </div>

      {error && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="flex items-center gap-3 rounded-md border border-notification/20 bg-notification/10 p-3 text-notification"
        >
          <AlertCircle size={16} className="shrink-0" />
          <span className="min-w-0 flex-1 font-body text-body-s font-medium">{error}</span>
          <button
            type="button"
            onClick={() => onSubmit()}
            className="shrink-0 cursor-pointer rounded-md border border-notification/30 px-3 py-1 font-body text-[12px] font-medium text-notification transition-colors duration-[var(--motion-fast)] hover:bg-notification/10"
          >
            Retry
          </button>
        </motion.div>
      )}
    </motion.div>
  );
}
