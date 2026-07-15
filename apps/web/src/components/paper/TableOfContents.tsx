"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import { useIsDesktop } from "@/lib/hooks/useMediaQuery";

interface Section {
  id: string;
  label: string;
}

/** Scrollspy in-page nav for the Paper reading layout. Only observes on desktop — a no-op on the mobile single-column flow. */
export function TableOfContents({ sections }: { sections: Section[] }) {
  const isDesktop = useIsDesktop();
  const [activeId, setActiveId] = useState<string | null>(sections[0]?.id ?? null);

  useEffect(() => {
    if (!isDesktop || sections.length === 0) return;

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible[0]) setActiveId(visible[0].target.id);
      },
      { rootMargin: "-15% 0px -70% 0px" }
    );

    for (const section of sections) {
      const el = document.getElementById(section.id);
      if (el) observer.observe(el);
    }

    return () => observer.disconnect();
  }, [isDesktop, sections]);

  if (sections.length === 0) return null;

  return (
    <nav className="flex flex-col gap-0.5">
      <span className="mb-2 font-mono text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
        On this page
      </span>
      {sections.map((section) => (
        <a
          key={section.id}
          href={`#${section.id}`}
          className={cn(
            "border-l-2 py-1.5 pl-3 font-body text-[12.5px] leading-snug transition-colors duration-[var(--motion-fast)]",
            activeId === section.id
              ? "border-primary text-text-primary font-medium"
              : "border-border text-text-muted hover:text-text-primary"
          )}
          style={{ transitionTimingFunction: "var(--ease-standard)" }}
        >
          {section.label}
        </a>
      ))}
    </nav>
  );
}
