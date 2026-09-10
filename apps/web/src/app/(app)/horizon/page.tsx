"use client";

import { useState, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import { AnimatePresence } from "framer-motion";
import { useMutation } from "@tanstack/react-query";
import { ArrowUpRight, Sparkles, ShieldCheck, FlaskConical } from "lucide-react";
import { getHorizonPrediction } from "@/lib/api/endpoints";
import { cn } from "@/lib/utils";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { HorizonInputForm } from "@/components/horizon/HorizonInputForm";
import { HorizonLoadingPanel, LOADING_STEPS } from "@/components/horizon/HorizonLoadingPanel";
import { HorizonPredictionResult } from "@/components/horizon/HorizonPredictionResult";

export default function HorizonPage() {
  const searchParams = useSearchParams();
  const { author } = useMyProfile();
  const [field, setField] = useState(() => searchParams?.get?.("field") ?? "");
  const [focusArea, setFocusArea] = useState("");
  const [loadingStep, setLoadingStep] = useState(0);

  const predict = useMutation({
    mutationFn: () => getHorizonPrediction(field, focusArea || undefined, author?.id),
    onMutate: () => setLoadingStep(0),
  });
  const loading = predict.isPending;
  const prediction = predict.data ?? null;
  const error = predict.isError
    ? "Foresight engine timed out or encountered an error. Please try again."
    : null;

  // Cycle the loading captions while the prediction is in flight. No synchronous
  // setState in the effect body — only inside the interval callback + cleanup.
  useEffect(() => {
    if (!loading) return;
    const interval = setInterval(() => {
      setLoadingStep((prev) => (prev < LOADING_STEPS.length - 1 ? prev + 1 : prev));
    }, 2000);
    return () => clearInterval(interval);
  }, [loading]);

  function handlePredict(e?: React.FormEvent) {
    if (e) e.preventDefault();
    if (!field.trim()) return;
    predict.mutate();
  }

  return (
    <div
      className={cn(
        "mx-auto min-h-full px-4 py-8 md:px-8",
        prediction ? "max-w-none xl:max-w-7xl xl:px-12" : "max-w-5xl"
      )}
    >
      {/* Page Title & Tagline */}
      <div className="mb-8 flex max-w-3xl flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Sparkles size={18} />
          </div>
          <span className="font-mono text-[12px] font-bold uppercase tracking-wider text-primary">
            Horizon Foresight Engine
          </span>
          <span className="rounded-full border border-border bg-surface px-2.5 py-1 font-body text-[11px] font-medium text-text-muted">
            Evidence-led discovery
          </span>
        </div>
        <h1 className="font-display text-[28px] font-extrabold tracking-tight text-text-primary md:text-[32px]">
          Predict the Next Scientific Frontier
        </h1>
        <p className="max-w-2xl font-body text-[14.5px] leading-relaxed text-text-secondary">
          Analyze pioneering global literature to synthesize business-ready breakthroughs and commercial roadmap predictions.
        </p>
        <div className="flex items-center gap-2 font-body text-[12px] text-text-muted">
          <ArrowUpRight size={14} className="text-primary" />
          Start with a field, then sharpen the signal with an optional focus area.
        </div>
      </div>

      <AnimatePresence mode="wait">
        {!loading && !prediction && (
          <>
            <HorizonInputForm
              field={field}
              error={error}
              onFieldChange={setField}
              onFocusChange={setFocusArea}
              onSelectDomain={setField}
              onSubmit={handlePredict}
            />
            <div className="mt-5 grid gap-3 md:grid-cols-2">
              <div className="flex items-start gap-3 rounded-md border border-accent-teal/20 bg-accent-teal/5 p-4">
                <ShieldCheck size={17} className="mt-0.5 shrink-0 text-accent-teal" />
                <div>
                  <p className="font-display text-h3 font-semibold text-text-primary">Evidence before ambition</p>
                  <p className="mt-1 font-body text-[12px] leading-relaxed text-text-secondary">Every result should name its supporting signal, uncertainty and the evidence that could disprove it.</p>
                </div>
              </div>
              <div className="flex items-start gap-3 rounded-md border border-accent-amber/20 bg-accent-amber/5 p-4">
                <FlaskConical size={17} className="mt-0.5 shrink-0 text-accent-amber" />
                <div>
                  <p className="font-display text-h3 font-semibold text-text-primary">End with an experiment</p>
                  <p className="mt-1 font-body text-[12px] leading-relaxed text-text-secondary">Use the roadmap to define the smallest credible test, then track it in a CoLab project.</p>
                </div>
              </div>
            </div>
          </>
        )}

        {loading && <HorizonLoadingPanel loadingStep={loadingStep} />}

        {prediction && (
          <HorizonPredictionResult prediction={prediction} onReset={() => predict.reset()} />
        )}
      </AnimatePresence>
    </div>
  );
}
