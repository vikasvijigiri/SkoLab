import { motion } from "framer-motion";
import { ArrowLeft, Compass, BookOpen, Star } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { SourcePaperCard } from "./SourcePaperCard";
import type { BreakthroughPrediction } from "@/lib/types";

function feasibilityColor(level: BreakthroughPrediction["feasibility"]) {
  if (level === "High") return "var(--accent-teal)";
  if (level === "Medium") return "var(--accent-purple)";
  return "var(--notification)";
}

export function HorizonPredictionResult({
  prediction,
  onReset,
}: {
  prediction: BreakthroughPrediction;
  onReset: () => void;
}) {
  return (
    <motion.div
      key="prediction-result"
      initial={{ opacity: 0, y: 15 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
      className="flex flex-col gap-6"
    >
      <div className="flex justify-between">
        <Button variant="outlined" fullWidth={false} onClick={onReset} className="h-10 gap-1.5 px-4 py-0">
          <ArrowLeft size={14} />
          Forge Another Prediction
        </Button>
      </div>

      <Card accentColor="var(--primary)" className="border-border/50 bg-surface/50 p-6 backdrop-blur-md">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="font-mono text-[11px] font-bold uppercase tracking-wider text-text-muted">
            Innovation Blueprint
          </span>
          <div className="flex gap-2">
            <Badge accentColor="var(--primary)">Horizon: {prediction.time_horizon}</Badge>
            <Badge accentColor={feasibilityColor(prediction.feasibility)}>
              Feasibility: {prediction.feasibility}
            </Badge>
          </div>
        </div>

        <h2 className="mt-3 font-display text-[22px] font-extrabold text-text-primary md:text-[26px]">
          <MathText text={prediction.breakthrough_name} />
        </h2>
        <p className="mt-3 font-body text-[15px] leading-relaxed text-text-secondary">
          <MathText text={prediction.description} />
        </p>
      </Card>

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <Card className="border-border/40 bg-surface/40 flex flex-col gap-3">
          <div className="flex items-center gap-2 text-primary">
            <Compass size={16} />
            <h3 className="font-display text-[15px] font-bold text-text-primary">
              Scientific Foundation &amp; Rationale
            </h3>
          </div>
          <p className="font-body text-[13.5px] leading-relaxed text-text-secondary">
            <MathText text={prediction.scientific_logic} />
          </p>
        </Card>

        <Card className="border-border/40 bg-surface/40 flex flex-col gap-3">
          <div className="flex items-center gap-2 text-primary">
            <Star size={16} />
            <h3 className="font-display text-[15px] font-bold text-text-primary">
              Business Opportunity &amp; Commercialization
            </h3>
          </div>
          <p className="mb-2 font-body text-[13.5px] leading-relaxed text-text-secondary">
            <MathText text={prediction.business_application} />
          </p>
          <div className="flex flex-col gap-2">
            <h4 className="font-mono text-[10px] font-bold uppercase tracking-wider text-text-muted">
              Implementation Milestones
            </h4>
            <ul className="flex flex-col gap-2">
              {prediction.roadmap_steps.map((step, i) => (
                <li key={i} className="flex gap-2.5 font-body text-[12.5px] text-text-secondary">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary font-mono text-[11px] font-semibold">
                    {i + 1}
                  </span>
                  <span>
                    <MathText text={step} />
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </Card>
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-2 text-text-primary">
          <BookOpen size={16} />
          <h3 className="font-display text-[15px] font-bold">Reference Studies &amp; Supporting Literature</h3>
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div className="flex flex-col gap-2.5">
            <span className="font-mono text-[10.5px] font-bold uppercase tracking-wider text-text-muted">
              Pioneering Publications (High-Impact foundations)
            </span>
            {prediction.pioneering_papers.map((p) => (
              <SourcePaperCard key={p.id} paper={p} accentColor="var(--accent-purple)" />
            ))}
          </div>

          <div className="flex flex-col gap-2.5">
            <span className="font-mono text-[10.5px] font-bold uppercase tracking-wider text-text-muted">
              Latest Publications (Active Research Frontier)
            </span>
            {prediction.latest_papers.map((p) => (
              <SourcePaperCard key={p.id} paper={p} accentColor="var(--accent-teal)" />
            ))}
          </div>
        </div>
      </div>
    </motion.div>
  );
}
