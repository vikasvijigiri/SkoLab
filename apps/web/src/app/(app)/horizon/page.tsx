"use client";

import { useState, useEffect } from "react";
import { AnimatePresence } from "framer-motion";
import { useMutation } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import { getHorizonPrediction } from "@/lib/api/endpoints";
import { cn } from "@/lib/utils";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { HorizonInputForm } from "@/components/horizon/HorizonInputForm";
import { HorizonLoadingPanel, LOADING_STEPS } from "@/components/horizon/HorizonLoadingPanel";
import { HorizonPredictionResult } from "@/components/horizon/HorizonPredictionResult";

export default function HorizonPage() {
  const { author } = useMyProfile();
  const [field, setField] = useState("");
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
          <HorizonInputForm
            field={field}
            focusArea={focusArea}
            error={error}
            onFieldChange={setField}
            onFocusChange={setFocusArea}
            onSelectDomain={setField}
            onSubmit={handlePredict}
          />
        )}

        {loading && <HorizonLoadingPanel loadingStep={loadingStep} />}

        {prediction && (
          <HorizonPredictionResult prediction={prediction} onReset={() => predict.reset()} />
        )}
      </AnimatePresence>
    </div>
  );
}
