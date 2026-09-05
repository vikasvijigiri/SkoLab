"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { AnimatePresence, motion } from "framer-motion";
import { ArrowLeft, ArrowRight, Check, Sparkles, X } from "lucide-react";
import { AuthCard } from "@/components/auth/AuthCard";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { updateResearcherProfile } from "@/lib/firebase/auth";
import { syncUserProfile } from "@/lib/api/endpoints";
import { EASE_STANDARD, TRANSITION_FAST } from "@/lib/motion";

const FIELDS = [
  "Machine Learning",
  "Physics",
  "Biology",
  "Chemistry",
  "Materials Science",
  "Climate Science",
  "Neuroscience",
  "Economics",
];

// A light hint list per macro field — shown as clickable chips under the
// specific-area input. Not exhaustive; the input stays free text.
const SUBFIELDS: Record<string, string[]> = {
  Physics: ["Condensed Matter Physics", "Quantum Information", "Astrophysics", "Particle Physics", "Optics & Photonics"],
  "Machine Learning": ["NLP", "Computer Vision", "Reinforcement Learning", "Generative Models", "ML Theory"],
  Biology: ["Genomics", "Molecular Biology", "Ecology", "Structural Biology", "Synthetic Biology"],
  Chemistry: ["Organic Chemistry", "Electrochemistry", "Catalysis", "Computational Chemistry", "Polymer Science"],
  "Materials Science": ["2D Materials", "Metamaterials", "Semiconductors", "Batteries & Energy Storage", "Nanomaterials"],
  "Climate Science": ["Atmospheric Modeling", "Oceanography", "Carbon Capture", "Climate Policy", "Paleoclimate"],
  Neuroscience: ["Computational Neuroscience", "Systems Neuroscience", "Neuroimaging", "Neurodegeneration", "Cognitive Neuroscience"],
  Economics: ["Econometrics", "Behavioral Economics", "Development Economics", "Market Design", "Macroeconomics"],
};

const ORCID_RE = /^(?:https?:\/\/orcid\.org\/)?\d{4}-\d{4}-\d{4}-\d{3}[\dX]$/i;

const STEP_TITLES = ["Your field", "Connect your work", "Interests"];

export default function OnboardingPage() {
  const router = useRouter();
  const { user, getIdToken } = useAuth();

  const [step, setStep] = useState(0);
  const [field, setField] = useState("");
  const [area, setArea] = useState("");
  const [authorName, setAuthorName] = useState(user?.displayName ?? "");
  const [orcid, setOrcid] = useState("");
  const [interests, setInterests] = useState<string[]>([]);
  const [interestDraft, setInterestDraft] = useState("");
  const [status, setStatus] = useState("Researcher");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const orcidValid = orcid.trim() === "" || ORCID_RE.test(orcid.trim());
  const canAdvance = step === 0 ? Boolean(field) : step === 1 ? orcidValid : true;

  const researchFocus = useMemo(() => {
    const parts = [area.trim() || field, ...interests];
    return parts.filter(Boolean).join(" · ");
  }, [area, field, interests]);

  function addInterest() {
    const v = interestDraft.trim().replace(/,$/, "");
    if (v && !interests.includes(v) && interests.length < 6) {
      setInterests((prev) => [...prev, v]);
    }
    setInterestDraft("");
  }

  async function finish(skipped = false) {
    if (!user) return;
    setLoading(true);
    setError(null);
    try {
      const openAlexId = orcid.trim() ? orcid.trim().replace(/^https?:\/\/orcid\.org\//i, "") : "";
      const focus = skipped ? "" : researchFocus;
      await updateResearcherProfile(user.uid, {
        name: user.displayName ?? authorName,
        authorName: skipped ? (user.displayName ?? "") : authorName.trim() || (user.displayName ?? ""),
        researchFocus: focus,
        academicStatus: status.trim() || "Researcher",
        openAlexId,
      });
      const idToken = await getIdToken();
      if (idToken) {
        await syncUserProfile(idToken, user.uid, user.displayName ?? authorName, focus).catch((err) => {
          console.warn("[Onboarding] Backend profile sync failed:", err);
        });
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
      <div className="flex items-center gap-1.5">
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

      <h1 className="mt-4 font-display text-[22px] font-bold text-text-primary">
        {STEP_TITLES[step]}
      </h1>
      <p className="mt-1 font-body text-[13.5px] text-text-secondary">
        {step === 0 && "This tailors your feed, matches, impact radar and peer suggestions."}
        {step === 1 && "Link your published work so your metrics and network are live from day one."}
        {step === 2 && "A few topics you follow. We use these to rank papers and researchers for you."}
      </p>

      <div className="relative mt-5 min-h-[220px]">
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
                <div role="group" aria-labelledby="ob-field-label">
                  <span id="ob-field-label" className="mb-1.5 block font-body text-[12.5px] font-medium text-text-secondary">
                    Primary field
                  </span>
                  <div className="flex flex-wrap gap-2">
                    {FIELDS.map((opt) => (
                      <motion.button
                        key={opt}
                        type="button"
                        onClick={() => setField(opt)}
                        whileHover={{ scale: 1.04 }}
                        whileTap={{ scale: 0.96 }}
                        transition={TRANSITION_FAST}
                        className={`cursor-pointer rounded-full border px-3 py-1.5 font-body text-[12px] font-medium transition-colors duration-[var(--motion-fast)] ${
                          field === opt
                            ? "border-primary bg-primary text-text-on-primary shadow-[var(--shadow-glow-primary)]"
                            : "border-border-input bg-surface-input text-text-secondary hover:border-primary/40 hover:text-text-primary"
                        }`}
                        style={{ transitionTimingFunction: "var(--ease-standard)" }}
                      >
                        {opt}
                      </motion.button>
                    ))}
                  </div>
                </div>

                <div>
                  <Input
                    label="Specific area (optional)"
                    placeholder="e.g. Condensed Matter Physics"
                    value={area}
                    onChange={(e) => setArea(e.target.value)}
                  />
                  {field && SUBFIELDS[field] && (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {SUBFIELDS[field].map((s) => (
                        <button
                          key={s}
                          type="button"
                          onClick={() => setArea(s)}
                          className="cursor-pointer rounded-full border border-border px-2.5 py-1 font-body text-[11.5px] text-text-muted transition-colors hover:border-primary/40 hover:text-text-primary"
                        >
                          {s}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </>
            )}

            {step === 1 && (
              <>
                <Input
                  label="Name as it appears on your papers"
                  placeholder="e.g. S. J. Park"
                  value={authorName}
                  onChange={(e) => setAuthorName(e.target.value)}
                />
                <Input
                  label="ORCID (optional, most precise match)"
                  placeholder="0000-0000-0000-0000"
                  value={orcid}
                  onChange={(e) => setOrcid(e.target.value)}
                  error={orcidValid ? undefined : "That doesn't look like an ORCID (0000-0000-0000-0000)."}
                />
                <p className="rounded-md bg-surface-subtle px-3 py-2.5 font-body text-[12px] leading-relaxed text-text-muted">
                  <Sparkles size={12} className="mr-1 inline text-accent-violet" />
                  Without this, your impact metrics, daily brief and &ldquo;researchers you
                  may know&rdquo; stay empty until we can match your work.
                </p>
              </>
            )}

            {step === 2 && (
              <>
                <div>
                  <span className="mb-1.5 block font-body text-[12.5px] font-medium text-text-secondary">
                    Topics you follow
                  </span>
                  <Input
                    placeholder="Type a topic, press Enter"
                    value={interestDraft}
                    onChange={(e) => setInterestDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === ",") {
                        e.preventDefault();
                        addInterest();
                      }
                    }}
                  />
                  {interests.length > 0 && (
                    <div className="mt-2.5 flex flex-wrap gap-1.5">
                      {interests.map((t) => (
                        <span
                          key={t}
                          className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 font-body text-[12px] font-medium text-primary"
                        >
                          {t}
                          <button
                            type="button"
                            aria-label={`Remove ${t}`}
                            onClick={() => setInterests((prev) => prev.filter((x) => x !== t))}
                            className="cursor-pointer rounded-full text-primary/70 hover:text-primary"
                          >
                            <X size={12} />
                          </button>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <Input
                  label="Academic status"
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                  placeholder="e.g. PhD Candidate"
                />
              </>
            )}
          </motion.div>
        </AnimatePresence>
      </div>

      {error && <p className="mt-2 font-body text-[13px] text-notification">{error}</p>}

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
