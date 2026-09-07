"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  Sparkles,
  Radar,
  Users2,
  MousePointerClick,
  ListChecks,
  ArrowRight,
  Plus,
} from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Card } from "@/components/ui/Card";
import { Reveal } from "@/components/ui/Reveal";
import { MagneticCTA } from "@/components/ui/MagneticCTA";
import { LandingTryDemo } from "@/components/landing/LandingTryDemo";
import { EASE_STANDARD } from "@/lib/motion";

const OUTCOMES = [
  {
    icon: Radar,
    title: "Measure",
    body:
      "Eight axes of real standing — disruption, novelty, influence, consistency — not just an h-index. One glance reads a whole career.",
    accent: "var(--primary)",
  },
  {
    icon: Sparkles,
    title: "Discover",
    body:
      "The papers and people working closest to your problem, ranked by a similarity engine — surfaced from a click, never a query you have to craft.",
    accent: "var(--accent-violet)",
  },
  {
    icon: Users2,
    title: "Collaborate",
    body:
      "A live workspace — shared manuscript, equations, tasks and chat — synced in real time with your lab.",
    accent: "var(--accent-teal)",
  },
];

const STEPS = [
  { icon: MousePointerClick, text: "Click a field — not a search box." },
  { icon: Plus, text: "Pick the researcher or the paper." },
  { icon: ListChecks, text: "Read the signature, or open a workspace." },
];

const ROLES = [
  { who: "PhD students", line: "See where a field is thin before you commit a thesis to it." },
  { who: "Principal investigators", line: "Benchmark a hire, a collaborator, or your own group in seconds." },
  { who: "Lab groups", line: "One workspace for the draft, the maths, and the meeting notes." },
];

const FAQ = [
  {
    q: "Do I have to write search queries?",
    a: "No. Every surface is built from clickable options fetched live — fields, sub-fields, topics, researchers, papers. Typing is reserved for the manuscript editor and chat.",
  },
  {
    q: "Is it free?",
    a: "The core — author search, impact signatures, discovery, and the CoLab workspace — is free. AI-heavy features (per-profile gap analysis, breakthrough prediction) are a premium add-on.",
  },
  {
    q: "How is “impact” scored?",
    a: "From public data (OpenAlex, Crossref, ORCID, arXiv): citation velocity, disruption index, concept novelty, co-authorship centrality and more, combined into an eight-axis signature. Nothing is invented — every axis traces back to a source.",
  },
  {
    q: "Is my data private?",
    a: "Your profile, workspaces and connections are yours. Public bibliometric data is public; anything you create in a workspace is visible only to the people you invite.",
  },
  {
    q: "Which parts use AI?",
    a: "Deliberately few. Ranking, similarity and metrics are ordinary computation. Large language models are used only for the premium analysis features, and always labelled where they run.",
  },
];

export default function LandingPage() {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && user) router.replace("/home");
  }, [loading, user, router]);

  return (
    <div className="relative flex min-h-full flex-1 flex-col bg-page-bg">
      {/* ── Nav — no exit links ─────────────────────────────────────────── */}
      <header className="relative z-10 flex items-center justify-between px-6 py-5 md:px-12">
        <span className="font-display text-[20px] font-bold text-text-primary">SkoLab</span>
        <div className="flex items-center gap-4">
          <ThemeToggle />
          <Link
            href="/login"
            className="font-body text-[14px] font-medium text-text-secondary transition-colors hover:text-primary"
          >
            Sign in
          </Link>
        </div>
      </header>

      <main className="relative z-10 flex flex-1 flex-col items-center px-6 pb-24">
        {/* ── Hero ──────────────────────────────────────────────────────── */}
        <section className="flex w-full max-w-3xl flex-col items-center pt-12 text-center md:pt-20">
          <motion.span
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5 }}
            className="mb-6 inline-flex items-center gap-1.5 rounded-full border border-border bg-surface px-4 py-2 font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-text-muted"
          >
            <Sparkles size={12} className="text-accent-violet" />
            The impact layer for research
          </motion.span>

          <motion.h1
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.1, ease: EASE_STANDARD }}
            className="max-w-3xl font-display text-[40px] font-bold leading-[1.05] tracking-[-0.02em] text-text-primary md:text-display-xl"
          >
            Know any researcher&apos;s real standing
            <br />
            <span className="text-primary">in one glance.</span>
          </motion.h1>

          <motion.p
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.2, ease: EASE_STANDARD }}
            className="mt-6 max-w-xl font-body text-[16px] leading-relaxed text-text-secondary"
          >
            Impact signatures, career-trajectory prediction, and a live collaboration
            workspace for research teams. No search queries to write.
          </motion.p>

          <motion.div
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.3, ease: EASE_STANDARD }}
            className="mt-8 flex flex-col items-center gap-4 sm:flex-row"
          >
            <MagneticCTA onClick={() => router.push("/signup")}>
              Get started free
              <ArrowRight size={16} />
            </MagneticCTA>
            <Link href="/login" className="font-body text-[13.5px] font-medium text-text-secondary hover:text-primary">
              I already have an account
            </Link>
          </motion.div>

          <LandingTryDemo />
        </section>

        {/* ── Proof strip ───────────────────────────────────────────────── */}
        <section className="mt-24 flex w-full max-w-4xl flex-col items-center gap-4 border-y border-border py-8 text-center">
          <p className="font-display text-[16px] font-semibold text-text-primary">
            Every researcher in the open record — <span className="text-primary">~2.4M</span> profiles,{" "}
            <span className="text-primary">240M+</span> papers, every field.
          </p>
          <div className="flex flex-wrap items-center justify-center gap-x-8 gap-y-1">
            <span className="eyebrow">Built on</span>
            {["OpenAlex", "Crossref", "ORCID", "arXiv"].map((s) => (
              <span key={s} className="font-body text-[13px] font-semibold text-text-secondary">
                {s}
              </span>
            ))}
          </div>
        </section>

        {/* ── The one idea ──────────────────────────────────────────────── */}
        <Reveal>
          <section className="mt-24 max-w-2xl text-center">
            <p className="eyebrow">
              <span className="text-primary">01 —</span> The one idea
            </p>
            <p className="mt-4 font-display text-h2 font-bold leading-snug text-text-primary md:text-display-m">
              A name is all you give it. You get back a full impact signature, a
              citation-velocity curve, and the researchers working closest to the
              same problem.
            </p>
          </section>
        </Reveal>

        {/* ── Outcomes ──────────────────────────────────────────────────── */}
        <section className="mt-24 grid w-full max-w-5xl grid-cols-1 gap-4 text-left md:grid-cols-3">
          {OUTCOMES.map((o, i) => (
            <Reveal key={o.title} delay={i * 0.08}>
              <Card accentColor={o.accent} className="h-full">
                <div
                  className="mb-4 flex h-9 w-9 items-center justify-center rounded-xs"
                  style={{ backgroundColor: `color-mix(in srgb, ${o.accent} 12%, var(--surface))` }}
                >
                  <o.icon size={17} style={{ color: o.accent }} />
                </div>
                <h3 className="font-display text-h3 font-semibold text-text-primary">{o.title}</h3>
                <p className="mt-2 font-body text-body-s leading-relaxed text-text-secondary">{o.body}</p>
              </Card>
            </Reveal>
          ))}
        </section>

        {/* ── How it works ──────────────────────────────────────────────── */}
        <Reveal>
          <section className="mt-24 w-full max-w-3xl">
            <p className="eyebrow text-center">
              <span className="text-primary">02 —</span> How it works
            </p>
            <ol className="mt-6 flex flex-col gap-4 sm:flex-row">
              {STEPS.map((s, i) => (
                <li
                  key={i}
                  className="flex flex-1 items-start gap-3 rounded-md border border-border bg-surface p-5"
                >
                  <span className="data flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[12px] font-bold text-primary">
                    {i + 1}
                  </span>
                  <div>
                    <s.icon size={16} className="mb-2 text-text-muted" />
                    <p className="font-body text-body-s leading-relaxed text-text-primary">{s.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </section>
        </Reveal>

        {/* ── Who it's for ──────────────────────────────────────────────── */}
        <section className="mt-24 grid w-full max-w-4xl grid-cols-1 gap-4 sm:grid-cols-3">
          {ROLES.map((r) => (
            <div key={r.who} className="rounded-md border border-border bg-surface p-5">
              <p className="font-display text-[14px] font-semibold text-text-primary">{r.who}</p>
              <p className="mt-1 font-body text-body-s leading-relaxed text-text-secondary">{r.line}</p>
            </div>
          ))}
        </section>

        {/* ── FAQ ───────────────────────────────────────────────────────── */}
        <section className="mt-24 w-full max-w-2xl">
          <p className="eyebrow text-center">
            <span className="text-primary">03 —</span> Questions
          </p>
          <div className="mt-6 flex flex-col gap-2">
            {FAQ.map((item) => (
              <details
                key={item.q}
                className="group rounded-[10px] border border-border bg-surface px-4 py-3 [&_summary::-webkit-details-marker]:hidden"
              >
                <summary className="flex cursor-pointer list-none items-center justify-between font-body text-[13.5px] font-semibold text-text-primary">
                  {item.q}
                  <Plus
                    size={15}
                    className="shrink-0 text-text-muted transition-transform duration-200 group-open:rotate-45"
                  />
                </summary>
                <p className="mt-2 font-body text-[12.5px] leading-relaxed text-text-secondary">{item.a}</p>
              </details>
            ))}
          </div>
        </section>

        {/* ── Final CTA ─────────────────────────────────────────────────── */}
        <Reveal>
          <section className="mt-24 flex w-full max-w-3xl flex-col items-center gap-4 rounded-lg border border-border bg-surface px-8 py-12 text-center shadow-card">
            <h2 className="font-display text-display-m font-bold text-text-primary">
              See your own impact signature.
            </h2>
            <p className="max-w-md font-body text-body leading-relaxed text-text-secondary">
              Free to start. No search queries, no credit card.
            </p>
            <MagneticCTA onClick={() => router.push("/signup")}>
              Get started free
              <ArrowRight size={16} />
            </MagneticCTA>
          </section>
        </Reveal>
      </main>

      <footer className="relative z-10 border-t border-border px-6 py-8 md:px-12">
        <div className="mx-auto flex max-w-5xl flex-col items-center justify-between gap-3 sm:flex-row">
          <span className="font-display text-[14px] font-bold text-text-primary">SkoLab</span>
          <div className="flex flex-wrap items-center justify-center gap-x-5 gap-y-1 font-body text-[12.5px] text-text-muted">
            <Link href="/login" className="transition-colors hover:text-primary">Sign in</Link>
            <Link href="/signup" className="transition-colors hover:text-primary">Get started</Link>
            <span>Privacy</span>
            <span>Terms</span>
          </div>
          <span className="font-body text-[12px] text-text-muted">© {new Date().getFullYear()} SkoLab</span>
        </div>
      </footer>
    </div>
  );
}
