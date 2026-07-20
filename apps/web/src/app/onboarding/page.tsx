"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { AuthCard } from "@/components/auth/AuthCard";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { updateResearcherProfile } from "@/lib/firebase/auth";
import { syncUserProfile } from "@/lib/api/endpoints";
import { TRANSITION_FAST } from "@/lib/motion";

const FOCUS_OPTIONS = [
  "Machine Learning",
  "Physics",
  "Biology",
  "Chemistry",
  "Materials Science",
  "Climate Science",
  "Neuroscience",
  "Economics",
];

export default function OnboardingPage() {
  const router = useRouter();
  const { user, getIdToken } = useAuth();
  const [focus, setFocus] = useState("");
  const [status, setStatus] = useState("Researcher");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!user) return;
    setLoading(true);
    setError(null);
    try {
      await updateResearcherProfile(user.uid, {
        name: user.displayName ?? "",
        researchFocus: focus,
        academicStatus: status,
      });
      const idToken = await getIdToken();
      if (idToken) {
        await syncUserProfile(idToken, user.uid, user.displayName ?? "", focus).catch((err) => {
          // Best-effort backend sync — profile already lives in Firestore either way,
          // but a silent failure here previously had zero visibility.
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
      <h1 className="font-display text-[24px] font-bold text-text-primary">
        Tell us about your research
      </h1>
      <p className="mt-1.5 font-body text-[14px] text-text-secondary">
        This tailors your feed, matches, and recommendations.
      </p>

      <form onSubmit={handleSubmit} className="mt-6 flex flex-col gap-4">
        <div>
          <label className="mb-1.5 block font-body text-[13px] font-medium text-text-secondary">
            Primary research focus
          </label>
          <div className="flex flex-wrap gap-2">
            {FOCUS_OPTIONS.map((opt) => (
              <motion.button
                key={opt}
                type="button"
                onClick={() => setFocus(opt)}
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                transition={TRANSITION_FAST}
                className={`cursor-pointer rounded-full px-3 py-1.5 font-body text-[12px] font-medium transition-colors duration-[var(--motion-fast)] ${
                  focus === opt ? "bg-primary text-text-on-primary" : "bg-surface-subtle text-text-secondary"
                }`}
                style={{ transitionTimingFunction: "var(--ease-standard)" }}
              >
                {opt}
              </motion.button>
            ))}
          </div>
        </div>

        <Input
          label="Academic status"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          placeholder="e.g. PhD Candidate"
        />

        {error && <p className="font-body text-[13px] text-notification">{error}</p>}

        <Button type="submit" loading={loading} disabled={!focus}>
          Finish setup
        </Button>
        <Button type="button" variant="text" onClick={() => router.push("/home")}>
          Skip for now
        </Button>
      </form>
    </AuthCard>
  );
}
