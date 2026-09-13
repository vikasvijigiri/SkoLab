"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { Reveal } from "@/components/ui/Reveal";
import { MathText } from "@/components/ui/MathText";
import { AuthorInline, splitAuthorPair } from "@/components/discovery/AuthorInline";
import { CvSharePanel } from "@/components/profile/CvSharePanel";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useProfileByUid } from "@/lib/hooks/useProfileByUid";
import { shortOpenAlexId } from "@/lib/utils";

export default function ProfileCvPage({ params }: { params: Promise<{ uid: string }> }) {
  const { uid } = use(params);
  return <ProfileCvContent uid={uid} />;
}

/** Exported for tests — the default export is just the Next route wrapper. */
export function ProfileCvContent({ uid }: { uid: string }) {
  const router = useRouter();
  const { user } = useAuth();
  const { firestoreProfile, author, loading, error } = useProfileByUid(uid);
  const isSelf = user?.uid === uid;
  const cvHref = `/profile/cv/${uid}`;

  if (loading) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-6 md:px-8">
        <div className="h-96 animate-pulse rounded-md bg-surface-subtle" />
      </div>
    );
  }

  if (error || !firestoreProfile) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-6 md:px-8">
        <ErrorBanner message={error ?? "We couldn't find that CV."} />
      </div>
    );
  }

  const displayName = firestoreProfile.name || author?.display_name || "Researcher";
  // Same recency sort as the author page's publication list, capped so the
  // printed CV stays to a reasonable length.
  const sortedWorks = author?.works
    ? author.works.toSorted((a, b) => (b.year ?? 0) - (a.year ?? 0)).slice(0, 12)
    : [];
  const contactLine = [firestoreProfile.academicStatus, firestoreProfile.email, firestoreProfile.phone]
    .filter(Boolean)
    .join(" · ");

  // `uid` is a Firebase uid, not the OpenAlex author id `/author/[id]` takes —
  // only link "back to their profile" once the author lookup actually
  // resolved one; otherwise there's nowhere useful to send a visitor who
  // arrived straight off a shared CV link.
  const backHref = isSelf
    ? "/profile"
    : author?.id
      ? `/author/${encodeURIComponent(shortOpenAlexId(author.id))}?name=${encodeURIComponent(displayName)}`
      : null;

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 md:px-8">
      {backHref && (
        <button
          type="button"
          onClick={() => router.push(backHref)}
          className="print:hidden mb-4 flex items-center gap-1.5 font-body text-[13px] font-medium text-text-secondary transition-colors hover:text-primary"
        >
          <ArrowLeft size={15} />
          Back
        </button>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_300px] lg:items-start">
        <Reveal>
          <Card className="print:border-none print:shadow-none">
            <header>
              <h1 className="font-display text-h1 font-bold text-text-primary">{displayName}</h1>
              {contactLine && (
                <p className="mt-1 font-body text-body-s text-text-secondary">{contactLine}</p>
              )}
              {firestoreProfile.researchFocus && (
                <p className="mt-2 font-body text-body-s font-medium text-primary">
                  {firestoreProfile.researchFocus}
                </p>
              )}
            </header>

            {firestoreProfile.about && (
              <section className="mt-6">
                <h2 className="font-display text-h3 font-semibold text-text-primary">About</h2>
                <p className="mt-2 font-body text-body-s leading-relaxed text-text-secondary">
                  {firestoreProfile.about}
                </p>
              </section>
            )}

            {author && (
              <section className="mt-6">
                <h2 className="font-display text-h3 font-semibold text-text-primary">Metrics</h2>
                <div className="mt-3 flex gap-3">
                  {[
                    { label: "H-Index", value: author.h_index },
                    { label: "i10-Index", value: author.i10_index },
                    { label: "Works", value: author.works_count },
                    { label: "Citations", value: author.cited_by_count },
                  ].map((s) => (
                    <div key={s.label} className="flex-1 rounded-md bg-surface-subtle px-3 py-3 text-center">
                      <p className="font-mono text-[17px] font-semibold text-text-primary">
                        <AnimatedCounter to={s.value} />
                      </p>
                      <p className="mt-1 font-body text-[10.5px] uppercase tracking-wide text-text-muted">
                        {s.label}
                      </p>
                    </div>
                  ))}
                </div>
              </section>
            )}

            {sortedWorks.length > 0 && (
              <section className="mt-6">
                <h2 className="font-display text-h3 font-semibold text-text-primary">Publications</h2>
                <div className="mt-3 flex flex-col divide-y divide-border">
                  {sortedWorks.map((w) => (
                    <div key={w.id ?? w.title} className="py-3 first:pt-0 last:pb-0">
                      <p className="font-body text-body-s font-medium text-text-primary">
                        <MathText text={w.title ?? ""} />
                      </p>
                      <p className="mt-1 flex flex-wrap items-baseline gap-x-1 font-body text-[12px] text-text-secondary">
                        {w.authors && w.authors.length > 0 && (
                          <AuthorInline
                            authors={w.authors.map(splitAuthorPair)}
                            max={4}
                            className="text-[12px]"
                          />
                        )}
                        <span className="text-text-muted">
                          {w.journal ? ` · ${w.journal}` : ""}
                          {w.year ? ` · ${w.year}` : ""} · {w.citations} citations
                        </span>
                      </p>
                    </div>
                  ))}
                </div>
              </section>
            )}
          </Card>
        </Reveal>

        {isSelf && <CvSharePanel uid={uid} fromName={displayName} cvHref={cvHref} />}
      </div>
    </div>
  );
}
