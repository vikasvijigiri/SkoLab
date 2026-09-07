"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AnimatePresence, motion } from "framer-motion";
import { ArrowLeft, ArrowRight, Check, Sparkles, X } from "lucide-react";
import { AuthCard } from "@/components/auth/AuthCard";
import { Button } from "@/components/ui/Button";
import { friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { updateResearcherProfile } from "@/lib/firebase/auth";
import { syncUserProfile } from "@/lib/api/endpoints";
import {
  openAlexFieldsQuery,
  openAlexSubfieldsQuery,
  openAlexTopicsQuery,
  openAlexAuthorMatchQuery,
} from "@/lib/api/queries";
import type { OpenAlexAuthorHit } from "@/lib/types";
import { EASE_STANDARD, TRANSITION_FAST } from "@/lib/motion";

// Career stage is a universal academic enum (not research data), so it is a
// fixed pick-list rather than a fetched one — everything topical below comes
// from the OpenAlex taxonomy.
const STATUS_OPTIONS = [
  "PhD Student",
  "Postdoc",
  "Research Scientist",
  "Assistant Professor",
  "Professor",
  "Lecturer",
  "Industry Researcher",
  "Independent Researcher",
];

const STEP_TITLES = ["Your field", "Is this you?", "Interests"];

type Chip = { id: string; display_name: string };

function ChipRow({
  options,
  selected,
  onPick,
  loading,
  emptyHint,
}: {
  options: Chip[];
  selected: string | string[];
  onPick: (value: string) => void;
  loading?: boolean;
  emptyHint?: string;
}) {
  const isSelected = (v: string) =>
    Array.isArray(selected) ? selected.includes(v) : selected === v;

  if (loading) {
    return (
      <div className="flex flex-wrap gap-2">
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} className="h-8 w-24 animate-pulse rounded-full bg-surface-subtle" />
        ))}
      </div>
    );
  }
  if (options.length === 0) {
    return <p className="font-body text-[12px] text-text-muted">{emptyHint}</p>;
  }
  return (
    <div className="flex flex-wrap gap-2">
      {options.map((opt) => (
        <motion.button
          key={opt.id}
          type="button"
          onClick={() => onPick(opt.display_name)}
          transition={TRANSITION_FAST}
          className={`cursor-pointer rounded-full border px-3 py-2 font-body text-[12px] font-medium transition-colors duration-[var(--motion-fast)] ${
            isSelected(opt.display_name)
              ? "border-primary bg-primary text-text-on-primary"
              : "border-border-input bg-surface-input text-text-secondary hover:border-primary/40 hover:text-text-primary"
          }`}
          style={{ transitionTimingFunction: "var(--ease-standard)" }}
        >
          {opt.display_name}
        </motion.button>
      ))}
    </div>
  );
}

export default function OnboardingPage() {
  const router = useRouter();
  const { user, getIdToken } = useAuth();

  const [step, setStep] = useState(0);

  // Step 0 — field / sub-field, both chosen by click (id + label kept).
  const [field, setField] = useState<Chip | null>(null);
  const [subfield, setSubfield] = useState<Chip | null>(null);

  // Step 1 — the OpenAlex author the user picks as "me".
  const [me, setMe] = useState<OpenAlexAuthorHit | null>(null);
  const [declinedMatch, setDeclinedMatch] = useState(false);

  // Step 2 — interests (tap to add) + career stage.
  const [interests, setInterests] = useState<string[]>([]);
  const [status, setStatus] = useState("Researcher");

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fieldsQ = useQuery(openAlexFieldsQuery());
  const subfieldsQ = useQuery(openAlexSubfieldsQuery(field?.id));
  const topicsQ = useQuery(openAlexTopicsQuery(subfield?.id));
  const matchQ = useQuery(openAlexAuthorMatchQuery(user?.displayName ?? undefined));

  const fields = fieldsQ.data ?? [];
  const subfields = subfieldsQ.data ?? [];
  const matches = matchQ.data ?? [];

  const canAdvance = step === 0 ? Boolean(field) : true;

  const researchFocus = useMemo(() => {
    const parts = [subfield?.display_name || field?.display_name, ...interests];
    return parts.filter(Boolean).join(" · ");
  }, [subfield, field, interests]);

  const suggestedTopics = useMemo(
    () => (topicsQ.data ?? []).filter((t) => !interests.includes(t.display_name)),
    [topicsQ.data, interests],
  );

  function pickField(name: string) {
    setField(fields.find((f) => f.display_name === name) ?? null);
    setSubfield(null); // a new field invalidates the sub-field pick
  }

  function toggleInterest(v: string) {
    setInterests((prev) =>
      prev.includes(v) ? prev.filter((x) => x !== v) : prev.length < 6 ? [...prev, v] : prev,
    );
  }

  async function finish(skipped = false) {
    if (!user) return;
    setLoading(true);
    setError(null);
    try {
      // Prefer the exact OpenAlex author id; fall back to its ORCID.
      const openAlexId = me ? me.id : "";
      const focus = skipped ? "" : researchFocus;
      const resolvedName = me?.display_name || user.displayName || "";
      await updateResearcherProfile(user.uid, {
        name: user.displayName ?? resolvedName,
        authorName: skipped ? (user.displayName ?? "") : resolvedName,
        researchFocus: focus,
        academicStatus: status.trim() || "Researcher",
        openAlexId,
      });
      const idToken = await getIdToken();
      if (idToken) {
        await syncUserProfile(idToken, user.uid, user.displayName ?? resolvedName, focus).catch(
          (err) => console.warn("[Onboarding] Backend profile sync failed:", err),
        );
      }
      router.push("/home");
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setLoading(false);
    }
  }

  return (
    <AuthCard>
      <div className="flex items-center gap-2">
        {STEP_TITLES.map((_, i) => (
          <span
            key={i}
            className={`h-1.5 flex-1 rounded-full transition-colors duration-[var(--motion-normal)] ${
              i <= step ? "bg-primary" : "bg-border"
            }`}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          />
        ))}
      </div>

      <h1 className="mt-4 font-display text-h2 font-bold text-text-primary">
        {STEP_TITLES[step]}
      </h1>
      <p className="mt-1 font-body text-body-s text-text-secondary">
        {step === 0 && "This tailors your feed, matches, impact radar and peer suggestions."}
        {step === 1 && "Pick your OpenAlex profile so your metrics and network are live from day one."}
        {step === 2 && "Tap a few topics you follow. We use these to rank papers and researchers for you."}
      </p>

      <div className="relative mt-5 min-h-[240px]">
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={step}
            initial={{ opacity: 0, x: 16 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -16 }}
            transition={{ duration: 0.25, ease: EASE_STANDARD }}
            className="flex flex-col gap-4"
          >
            {step === 0 && (
              <>
                <div>
                  <span className="eyebrow mb-2 block">
                    Primary field
                  </span>
                  <ChipRow
                    options={fields}
                    selected={field?.display_name ?? ""}
                    onPick={pickField}
                    loading={fieldsQ.isPending}
                    emptyHint="Couldn't load fields — check your connection and go back a step."
                  />
                </div>

                {field && (
                  <div>
                    <span className="eyebrow mb-2 block">
                      Specific area <span className="text-text-muted">(optional)</span>
                    </span>
                    <ChipRow
                      options={subfields}
                      selected={subfield?.display_name ?? ""}
                      onPick={(name) =>
                        setSubfield(subfields.find((s) => s.display_name === name) ?? null)
                      }
                      loading={subfieldsQ.isPending}
                      emptyHint="No sub-areas listed for this field."
                    />
                  </div>
                )}
              </>
            )}

            {step === 1 && (
              <>
                {matchQ.isPending ? (
                  <div className="flex flex-col gap-2">
                    {[0, 1, 2].map((i) => (
                      <div key={i} className="h-16 animate-pulse rounded-md bg-surface-subtle" />
                    ))}
                  </div>
                ) : matches.length === 0 || declinedMatch ? (
                  <div className="rounded-md bg-surface-subtle px-3 py-3 font-body text-body-s leading-relaxed text-text-muted">
                    <Sparkles size={12} className="mr-1 inline text-accent-violet" />
                    {matches.length === 0
                      ? "We couldn't find a matching OpenAlex profile from your name. You can link it later in Profile — your metrics stay empty until then."
                      : "No problem — you can link your profile later in Profile."}
                    {matches.length > 0 && declinedMatch && (
                      <button
                        type="button"
                        onClick={() => setDeclinedMatch(false)}
                        className="ml-1 cursor-pointer font-medium text-primary hover:underline"
                      >
                        show matches again
                      </button>
                    )}
                  </div>
                ) : (
                  <div className="flex flex-col gap-2">
                    {matches.slice(0, 5).map((a) => (
                      <button
                        key={a.id}
                        type="button"
                        onClick={() => setMe(a)}
                        className={`flex items-start justify-between gap-3 rounded-md border p-3 text-left transition-colors ${
                          me?.id === a.id
                            ? "border-primary bg-primary/10"
                            : "border-border-input bg-surface-input hover:border-primary/40"
                        }`}
                      >
                        <div className="min-w-0">
                          <p className="truncate font-body text-body-s font-medium text-text-primary">
                            {a.display_name}
                          </p>
                          <p className="truncate font-body text-[12px] text-text-secondary">
                            {a.institution || "Independent"}
                          </p>
                          <p className="data mt-1 text-[10.5px] uppercase tracking-wide text-text-muted">
                            {a.works_count} works · h {a.h_index}
                            {a.orcid ? " · ORCID linked" : ""}
                          </p>
                        </div>
                        {me?.id === a.id && <Check size={16} className="mt-0.5 shrink-0 text-primary" />}
                      </button>
                    ))}
                    <button
                      type="button"
                      onClick={() => {
                        setMe(null);
                        setDeclinedMatch(true);
                      }}
                      className="mt-1 cursor-pointer self-start font-body text-[12px] text-text-muted underline-offset-2 hover:text-text-secondary hover:underline"
                    >
                      None of these are me
                    </button>
                  </div>
                )}
              </>
            )}

            {step === 2 && (
              <>
                <div>
                  <span className="eyebrow mb-2 block">
                    Topics you follow {interests.length > 0 && `(${interests.length}/6)`}
                  </span>
                  {interests.length > 0 && (
                    <div className="mb-3 flex flex-wrap gap-2">
                      {interests.map((t) => (
                        <span
                          key={t}
                          className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 font-body text-[12px] font-medium text-primary"
                        >
                          {t}
                          <button
                            type="button"
                            aria-label={`Remove ${t}`}
                            onClick={() => toggleInterest(t)}
                            className="cursor-pointer rounded-full text-primary/70 hover:text-primary"
                          >
                            <X size={12} />
                          </button>
                        </span>
                      ))}
                    </div>
                  )}
                  {topicsQ.isPending ? (
                    <div className="flex flex-wrap gap-2">
                      {[0, 1, 2, 3, 4, 5].map((i) => (
                        <div key={i} className="h-7 w-28 animate-pulse rounded-full bg-surface-subtle" />
                      ))}
                    </div>
                  ) : suggestedTopics.length > 0 ? (
                    <div className="flex flex-wrap gap-2">
                      {suggestedTopics.slice(0, 14).map((t) => (
                        <button
                          key={t.id}
                          type="button"
                          disabled={interests.length >= 6}
                          onClick={() => toggleInterest(t.display_name)}
                          className="cursor-pointer rounded-full border border-border px-2.5 py-1 font-body text-[11.5px] text-text-muted transition-colors hover:border-primary/40 hover:text-text-primary disabled:opacity-40"
                        >
                          + {t.display_name}
                        </button>
                      ))}
                    </div>
                  ) : (
                    <p className="font-body text-[12px] text-text-muted">
                      Pick a specific area in step 1 to see topic suggestions.
                    </p>
                  )}
                </div>

                <div>
                  <span className="eyebrow mb-2 block">
                    Academic status
                  </span>
                  <div className="flex flex-wrap gap-2">
                    {STATUS_OPTIONS.map((opt) => (
                      <button
                        key={opt}
                        type="button"
                        onClick={() => setStatus(opt)}
                        className={`cursor-pointer rounded-full border px-2.5 py-1 font-body text-[11.5px] transition-colors ${
                          status === opt
                            ? "border-primary bg-primary/10 text-primary"
                            : "border-border text-text-muted hover:border-primary/40 hover:text-text-primary"
                        }`}
                      >
                        {opt}
                      </button>
                    ))}
                  </div>
                </div>
              </>
            )}
          </motion.div>
        </AnimatePresence>
      </div>

      {error && <p className="mt-2 font-body text-body-s text-notification">{error}</p>}

      <div className="mt-4 flex items-center gap-2">
        {step > 0 && (
          <Button variant="text" fullWidth={false} onClick={() => setStep((s) => s - 1)} className="gap-1">
            <ArrowLeft size={15} />
            Back
          </Button>
        )}
        <div className="flex-1" />
        {step < 2 ? (
          <Button fullWidth={false} disabled={!canAdvance} onClick={() => setStep((s) => s + 1)} className="gap-1 px-6">
            Next
            <ArrowRight size={15} />
          </Button>
        ) : (
          <Button fullWidth={false} loading={loading} onClick={() => finish(false)} className="gap-1 px-6">
            <Check size={15} />
            Finish setup
          </Button>
        )}
      </div>

      <button
        type="button"
        onClick={() => finish(true)}
        disabled={loading}
        className="mx-auto mt-4 block cursor-pointer font-body text-[12px] text-text-muted underline-offset-2 transition-colors hover:text-text-secondary hover:underline disabled:opacity-50"
      >
        Skip for now — you can add this later in Profile
      </button>
    </AuthCard>
  );
}
