"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { Sparkles, Radar, Users2, Award, ArrowRight } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Orbs } from "@/components/ui/Orbs";
import { Reveal } from "@/components/ui/Reveal";
import { HeroPreview } from "@/components/ui/HeroPreview";
import { MagneticCTA } from "@/components/ui/MagneticCTA";
import { EASE_STANDARD } from "@/lib/motion";

const FEATURES = [
  {
    icon: Sparkles,
    title: "AI Gap Finder",
    body: "Surface unexplored literature gaps and next research frontiers, generated per-profile.",
    accent: "var(--accent-violet)",
  },
  {
    icon: Radar,
    title: "8-Axis Impact Radar",
    body: "Disruption, novelty, influence, complexity, and more — one glance at a researcher's signature.",
    accent: "var(--primary)",
  },
  {
    icon: Users2,
    title: "Live CoLab Workspace",
    body: "Shared equations, manuscripts, tasks and chat — synced in real time with your team.",
    accent: "var(--accent-teal)",
  },
  {
    icon: Award,
    title: "Grant & Journal Matching",
    body: "Personalized funding opportunities and submission targets ranked by fit.",
    accent: "var(--accent-amber)",
  },
];

export default function LandingPage() {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && user) router.replace("/home");
  }, [loading, user, router]);

  return (
    <div className="relative flex min-h-full flex-1 flex-col overflow-hidden">
      <Orbs />
      <div
        className="pointer-events-none absolute inset-0"
        style={{
          backgroundImage: "radial-gradient(circle, color-mix(in srgb, var(--text-muted) 14%, transparent) 1px, transparent 1px)",
          backgroundSize: "28px 28px",
        }}
        aria-hidden
      />

      <header className="relative z-10 flex items-center justify-between px-6 py-6 md:px-12">
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

      <main className="relative z-10 flex flex-1 flex-col items-center px-6 pb-16 pt-10 text-center md:pt-16">
        <motion.span
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="mb-5 inline-flex items-center gap-1.5 rounded-full border border-border bg-surface px-4 py-1.5 font-mono text-[11px] font-medium tracking-wide text-text-muted"
        >
          <Sparkles size={12} className="text-accent-violet" />
          SCIENTIFIC DISCOVERY &amp; ANALYTICS PLATFORM
        </motion.span>

        <motion.h1
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.1, ease: EASE_STANDARD }}
          className="max-w-3xl font-display text-[40px] font-bold leading-[1.1] tracking-tight text-text-primary md:text-[56px]"
        >
          Quantify research impact.
          <br />
          <motion.span
            className="inline-block bg-clip-text text-transparent"
            style={{ backgroundImage: "var(--gradient-hero)", backgroundSize: "200% auto" }}
            animate={{ backgroundPosition: ["0% center", "100% center", "0% center"] }}
            transition={{ duration: 6, repeat: Infinity, ease: "easeInOut" }}
          >
            Predict what&apos;s next.
          </motion.span>
        </motion.h1>

        <motion.p
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.2, ease: EASE_STANDARD }}
          className="mt-6 max-w-xl font-body text-[16px] leading-relaxed text-text-secondary"
        >
          SkoLab links researchers, papers, and institutions in real time — AI-scored metrics,
          career-trajectory prediction, and live collaboration in one platform.
        </motion.p>

        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.3, ease: EASE_STANDARD }}
          className="mt-9 flex flex-col items-center gap-3 sm:flex-row"
        >
          <MagneticCTA onClick={() => router.push("/signup")}>
            Get started free
            <ArrowRight size={16} />
          </MagneticCTA>
          <Link href="/login">
            <Button variant="outlined" className="sm:w-56">
              I already have an account
            </Button>
          </Link>
        </motion.div>

        <HeroPreview />

        <div className="mt-12 flex flex-col items-center gap-3">
          <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-text-muted">
            Built on trusted open research data
          </p>
          <div className="flex flex-wrap items-center justify-center gap-x-6 gap-y-2 font-body text-[13px] font-semibold text-text-secondary">
            {["OpenAlex", "Crossref", "ORCID", "arXiv", "Semantic Scholar"].map((src) => (
              <span key={src}>{src}</span>
            ))}
          </div>
        </div>

        <div className="mt-14 grid w-full max-w-5xl grid-cols-1 gap-4 text-left sm:grid-cols-2 lg:grid-cols-4">
          {FEATURES.map((f, i) => (
            <Reveal key={f.title} delay={i * 0.08}>
              <Card glow accentColor={f.accent} className="h-full">
                <div
                  className="mb-3 flex h-9 w-9 items-center justify-center rounded-[8px]"
                  style={{
                    backgroundColor: `color-mix(in srgb, ${f.accent} 14%, transparent)`,
                    boxShadow: `0 0 20px color-mix(in srgb, ${f.accent} 22%, transparent)`,
                  }}
                >
                  <f.icon size={17} style={{ color: f.accent }} />
                </div>
                <h3 className="font-display text-[15px] font-semibold text-text-primary">{f.title}</h3>
                <p className="mt-1.5 font-body text-[13px] leading-relaxed text-text-secondary">
                  {f.body}
                </p>
              </Card>
            </Reveal>
          ))}
        </div>
      </main>

      <footer className="relative z-10 border-t border-border px-6 py-8 md:px-12">
        <div className="mx-auto flex max-w-5xl flex-col items-center justify-between gap-3 sm:flex-row">
          <span className="font-display text-[14px] font-bold text-text-primary">SkoLab</span>
          <div className="flex flex-wrap items-center justify-center gap-x-5 gap-y-1 font-body text-[12.5px] text-text-muted">
            <Link href="/login" className="transition-colors hover:text-primary">Sign in</Link>
            <Link href="/signup" className="transition-colors hover:text-primary">Get started</Link>
            <span className="transition-colors hover:text-primary">Privacy</span>
            <span className="transition-colors hover:text-primary">Terms</span>
          </div>
          <span className="font-body text-[12px] text-text-muted">© {new Date().getFullYear()} SkoLab</span>
        </div>
      </footer>
    </div>
  );
}
