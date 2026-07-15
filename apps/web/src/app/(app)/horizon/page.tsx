"use client";

import { useState, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Sparkles, ArrowRight, ArrowLeft, RefreshCw, Compass, AlertCircle, BookOpen, ExternalLink, Calendar, Star } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Badge, Chip } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { apiRequest } from "@/lib/api/client";
import { cn } from "@/lib/utils";

interface PaperSource {
  id: string;
  title: string;
  authors: string[];
  year: number;
  cited_by_count: number;
  doi?: string;
}

interface BreakthroughPrediction {
  breakthrough_name: string;
  description: string;
  scientific_logic: string;
  business_application: string;
  time_horizon: string;
  feasibility: "High" | "Medium" | "Low";
  roadmap_steps: string[];
  pioneering_papers: PaperSource[];
  latest_papers: PaperSource[];
}

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

const LOADING_STEPS = [
  "Querying OpenAlex global research corpus...",
  "Retrieving pioneering & latest publication frameworks...",
  "Ingesting abstracts and mapping conceptual overlaps...",
  "Simulating cross-disciplinary innovation trajectories...",
  "Synthesizing commercial feasibility and drafting blueprint...",
];

export default function HorizonPage() {
  const [field, setField] = useState("");
  const [focusArea, setFocusArea] = useState("");
  const [loading, setLoading] = useState(false);
  const [loadingStep, setLoadingStep] = useState(0);
  const [prediction, setPrediction] = useState<BreakthroughPrediction | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Animate loading text steps sequentially
  useEffect(() => {
    let interval: NodeJS.Timeout;
    if (loading) {
      interval = setInterval(() => {
        setLoadingStep((prev) => {
          if (prev < LOADING_STEPS.length - 1) return prev + 1;
          return prev;
        });
      }, 2000);
    } else {
      setLoadingStep(0);
    }
    return () => clearInterval(interval);
  }, [loading]);

  async function handlePredict(e?: React.FormEvent) {
    if (e) e.preventDefault();
    if (!field.trim()) return;

    setLoading(true);
    setError(null);
    setPrediction(null);

    try {
      const data = await apiRequest<BreakthroughPrediction>("/api/v1/discovery/predict", {
        method: "POST",
        body: { field, focus_area: focusArea },
      });
      setPrediction(data);
    } catch (err) {
      console.error(err);
      setError("Foresight engine timed out or encountered an error. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  function handleSelectDomain(domainName: string) {
    setField(domainName);
  }

  function getFeasibilityColor(level: "High" | "Medium" | "Low") {
    if (level === "High") return "var(--accent-teal)";
    if (level === "Medium") return "var(--accent-purple)";
    return "var(--notification)";
  }

  return (
    <div
      className={cn(
        "mx-auto min-h-full px-4 py-8 md:px-8",
        prediction ? "max-w-none xl:max-w-7xl xl:px-12" : "max-w-5xl"
      )}
    >
      {/* Page Title & Tagline */}
      <div className="mb-8 flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Sparkles size={18} />
          </div>
          <span className="font-mono text-[12px] font-bold uppercase tracking-wider text-primary">
            Horizon Foresight Engine
          </span>
        </div>
        <h1 className="font-display text-[28px] font-extrabold tracking-tight text-text-primary md:text-[32px]">
          Predict the Next Scientific Frontier
        </h1>
        <p className="max-w-2xl font-body text-[14.5px] leading-relaxed text-text-secondary">
          Analyze pioneering global literature to synthesize business-ready breakthroughs and commercial roadmap predictions.
        </p>
      </div>

      <AnimatePresence mode="wait">
        {!loading && !prediction && (
          <motion.div
            key="input-form"
            initial={{ opacity: 0, y: 15 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -15 }}
            transition={{ duration: 0.3 }}
            className="flex flex-col gap-8"
          >
            {/* Input card with premium border top */}
            <Card accentColor="var(--primary)" className="border-border/50 bg-surface/60 backdrop-blur-md">
              <form onSubmit={handlePredict} className="flex flex-col gap-5">
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <label className="font-body text-[12.5px] font-bold text-text-secondary">
                      Scientific or Technological Field
                    </label>
                    <Input
                      placeholder="e.g. CRISPR gene editing, Neuromorphic chips"
                      required
                      value={field}
                      onChange={(e) => setField(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="font-body text-[12.5px] font-bold text-text-secondary">
                      Focus Area / Sub-discipline (Optional)
                    </label>
                    <Input
                      placeholder="e.g. Cancer therapeutics, Edge robotics"
                      value={focusArea}
                      onChange={(e) => setFocusArea(e.target.value)}
                    />
                  </div>
                </div>

                <div className="flex justify-end pt-2">
                  <Button type="submit" fullWidth={false} className="gap-2 px-8">
                    Forge Discovery
                    <ArrowRight size={16} />
                  </Button>
                </div>
              </form>
            </Card>

            {/* Frontier domain quick-select cards */}
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
                    onClick={() => handleSelectDomain(domain.name)}
                    className="cursor-pointer"
                  >
                    <Card
                      glow
                      accentColor={domain.color}
                      className="flex h-full flex-col justify-between border-border/40 bg-surface/30 p-4 transition-all duration-300 hover:border-primary/30"
                    >
                      <div>
                        <h4 className="font-display text-[14.5px] font-semibold text-text-primary">
                          {domain.name}
                        </h4>
                        <p className="mt-1.5 font-body text-[12.5px] leading-snug text-text-muted">
                          {domain.desc}
                        </p>
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
                className="flex items-center gap-2 rounded-md border border-notification/20 bg-notification/10 p-3 text-notification"
              >
                <AlertCircle size={16} />
                <span className="font-body text-[13px] font-medium">{error}</span>
              </motion.div>
            )}
          </motion.div>
        )}

        {/* LOADING STATE PANEL */}
        {loading && (
          <motion.div
            key="loading-state"
            initial={{ opacity: 0, scale: 0.97 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.97 }}
            className="flex flex-col items-center justify-center py-20 text-center"
          >
            {/* Spinning AI Orb simulation */}
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

            <h3 className="font-display text-[18px] font-bold text-text-primary">
              Synthesizing Future Horizon
            </h3>
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

            {/* Stepped progress indicators */}
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
        )}

        {/* PREDICTION RESULT DASHBOARD */}
        {prediction && (
          <motion.div
            key="prediction-result"
            initial={{ opacity: 0, y: 15 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4 }}
            className="flex flex-col gap-6"
          >
            {/* Navigation back */}
            <div className="flex justify-between">
              <Button
                variant="outlined"
                fullWidth={false}
                onClick={() => setPrediction(null)}
                className="h-10 gap-1.5 px-4 py-0"
              >
                <ArrowLeft size={14} />
                Forge Another Prediction
              </Button>
            </div>

            {/* Main prediction header */}
            <Card accentColor="var(--primary)" className="border-border/50 bg-surface/50 p-6 backdrop-blur-md">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="font-mono text-[11px] font-bold uppercase tracking-wider text-text-muted">
                  Innovation Blueprint
                </span>
                <div className="flex gap-2">
                  <Badge accentColor="var(--primary)">
                    Horizon: {prediction.time_horizon}
                  </Badge>
                  <Badge accentColor={getFeasibilityColor(prediction.feasibility)}>
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
              {/* Scientific Logic */}
              <Card className="border-border/40 bg-surface/40 flex flex-col gap-3">
                <div className="flex items-center gap-2 text-primary">
                  <Compass size={16} />
                  <h3 className="font-display text-[15px] font-bold text-text-primary">
                    Scientific Foundation & Rationale
                  </h3>
                </div>
                <p className="font-body text-[13.5px] leading-relaxed text-text-secondary">
                  <MathText text={prediction.scientific_logic} />
                </p>
              </Card>

              {/* Business Value & Roadmap */}
              <Card className="border-border/40 bg-surface/40 flex flex-col gap-3">
                <div className="flex items-center gap-2 text-primary">
                  <Star size={16} />
                  <h3 className="font-display text-[15px] font-bold text-text-primary">
                    Business Opportunity & Commercialization
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
                        <span><MathText text={step} /></span>
                      </li>
                    ))}
                  </ul>
                </div>
              </Card>
            </div>

            {/* Scientific source papers used */}
            <div className="flex flex-col gap-3">
              <div className="flex items-center gap-2 text-text-primary">
                <BookOpen size={16} />
                <h3 className="font-display text-[15px] font-bold">
                  Reference Studies & Supporting Literature
                </h3>
              </div>

              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {/* Pioneering literature */}
                <div className="flex flex-col gap-2.5">
                  <span className="font-mono text-[10.5px] font-bold uppercase tracking-wider text-text-muted">
                    Pioneering Publications (High-Impact foundations)
                  </span>
                  {prediction.pioneering_papers.map((p) => (
                    <Card key={p.id} className="border-border/30 bg-surface/30 flex flex-col gap-1.5">
                      <h4 className="font-display text-[13.5px] font-semibold text-text-primary line-clamp-2">
                        <MathText text={p.title} />
                      </h4>
                      <p className="font-body text-[12px] text-text-secondary">
                        {p.authors.join(", ")} · {p.year}
                      </p>
                      <div className="flex items-center justify-between mt-2 pt-1 border-t border-border/10">
                        <Badge accentColor="var(--accent-purple)">
                          {p.cited_by_count} Citations
                        </Badge>
                        {p.doi && (
                          <a
                            href={p.doi.startsWith("http") ? p.doi : `https://doi.org/${p.doi}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="flex items-center gap-1 font-body text-[11.5px] text-primary hover:underline"
                          >
                            Read Study
                            <ExternalLink size={10} />
                          </a>
                        )}
                      </div>
                    </Card>
                  ))}
                </div>

                {/* Latest literature */}
                <div className="flex flex-col gap-2.5">
                  <span className="font-mono text-[10.5px] font-bold uppercase tracking-wider text-text-muted">
                    Latest Publications (Active Research Frontier)
                  </span>
                  {prediction.latest_papers.map((p) => (
                    <Card key={p.id} className="border-border/30 bg-surface/30 flex flex-col gap-1.5">
                      <h4 className="font-display text-[13.5px] font-semibold text-text-primary line-clamp-2">
                        <MathText text={p.title} />
                      </h4>
                      <p className="font-body text-[12px] text-text-secondary">
                        {p.authors.join(", ")} · {p.year}
                      </p>
                      <div className="flex items-center justify-between mt-2 pt-1 border-t border-border/10">
                        <Badge accentColor="var(--accent-teal)">
                          {p.cited_by_count} Citations
                        </Badge>
                        {p.doi && (
                          <a
                            href={p.doi.startsWith("http") ? p.doi : `https://doi.org/${p.doi}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="flex items-center gap-1 font-body text-[11.5px] text-primary hover:underline"
                          >
                            Read Study
                            <ExternalLink size={10} />
                          </a>
                        )}
                      </div>
                    </Card>
                  ))}
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
