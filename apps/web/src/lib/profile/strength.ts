/**
 * Profile strength — the LinkedIn "All-Star" meter, ported to research identity.
 *
 * Pure and honest: every step maps to a field the profile actually stores, so a
 * percentage is a real statement about what the user has filled in, never a
 * guess. The step table below is the ONLY place a weight is written; the card
 * component reads `label`/`weight` from here and keeps only presentational copy
 * (icon, one-line detail, link target) of its own.
 */

export type ProfileTier = "Beginner" | "Intermediate" | "Advanced" | "All-Star";

/** Everything the meter needs, already resolved from `useMyProfile()` + auth. */
export interface ProfileStrengthContext {
  /** Display name (auth or Firestore). */
  name?: string | null;
  /** An OpenAlex author is resolved, or an OpenAlex id / ORCID is stored. */
  hasLinkedAuthor: boolean;
  /** `firestoreProfile.researchFocus`. */
  researchFocus?: string | null;
  /** `firestoreProfile.academicStatus` — the sign-up default counts as unset. */
  academicStatus?: string | null;
  /** `firestoreProfile.about`. */
  about?: string | null;
  /** `author.expertise.length`. */
  expertiseCount?: number;
  /** `author.skills.length`. */
  skillsCount?: number;
}

export interface ProfileStrengthStep {
  key: "name" | "link" | "focus" | "status" | "about" | "areas";
  label: string;
  /** Percentage points this step contributes; the six sum to 100. */
  weight: number;
  done: boolean;
}

export interface ProfileStrength {
  /** 0–100, rounded. Sum of the weights of the completed steps. */
  percent: number;
  tier: ProfileTier;
  /** All six steps, in canonical order. */
  steps: ProfileStrengthStep[];
  /** The not-done steps, highest-weight first — the "Do next" list. */
  remaining: ProfileStrengthStep[];
}

/** Lower bound (inclusive) of each tier, checked high to low. */
const TIERS: ReadonlyArray<{ min: number; tier: ProfileTier }> = [
  { min: 100, tier: "All-Star" },
  { min: 70, tier: "Advanced" },
  { min: 40, tier: "Intermediate" },
  { min: 0, tier: "Beginner" },
];

/** The sign-up default (`firebase/auth.ts`) — present, but not a real choice. */
const DEFAULT_STATUS = "Researcher";

function filled(v: string | null | undefined): boolean {
  return typeof v === "string" && v.trim().length > 0;
}

export function computeProfileStrength(ctx: ProfileStrengthContext): ProfileStrength {
  const steps: ProfileStrengthStep[] = [
    { key: "link", label: "Link your OpenAlex or ORCID", weight: 30, done: ctx.hasLinkedAuthor },
    { key: "focus", label: "Set your research focus", weight: 20, done: filled(ctx.researchFocus) },
    {
      key: "status",
      label: "Choose your academic status",
      weight: 15,
      done: filled(ctx.academicStatus) && ctx.academicStatus!.trim() !== DEFAULT_STATUS,
    },
    { key: "about", label: "Write a short bio", weight: 15, done: filled(ctx.about) },
    { key: "name", label: "Add your name", weight: 10, done: filled(ctx.name) },
    {
      key: "areas",
      label: "Confirm your research areas & skills",
      weight: 10,
      done: (ctx.expertiseCount ?? 0) + (ctx.skillsCount ?? 0) > 0,
    },
  ];

  const percent = steps.reduce((sum, s) => (s.done ? sum + s.weight : sum), 0);
  const tier = (TIERS.find((t) => percent >= t.min) ?? TIERS[TIERS.length - 1]!).tier;
  const remaining = steps.filter((s) => !s.done).sort((a, b) => b.weight - a.weight);

  return { percent, tier, steps, remaining };
}
