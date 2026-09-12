"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { BookOpen, BriefcaseBusiness, Database, FlaskConical, GraduationCap, Lightbulb, Search, UsersRound } from "lucide-react";
import { cn, focusRing } from "@/lib/utils";

const CATEGORIES = [
  { href: "/home", label: "For you", Icon: Lightbulb, tone: "text-accent-orange" },
  { href: "/discovery?type=papers", label: "Papers", Icon: BookOpen, tone: "text-primary" },
  { href: "/discovery?type=people", label: "People", Icon: UsersRound, tone: "text-accent-teal" },
  { href: "/discovery?type=topics", label: "Topics", Icon: Search, tone: "text-accent-indigo" },
  { href: "/discovery?type=methods", label: "Methods", Icon: FlaskConical, tone: "text-accent-violet" },
  { href: "/discovery?type=datasets", label: "Datasets", Icon: Database, tone: "text-accent-emerald" },
  { href: "/discovery?type=grants", label: "Grants", Icon: GraduationCap, tone: "text-accent-rose" },
  { href: "/discovery?type=jobs", label: "Jobs", Icon: BriefcaseBusiness, tone: "text-accent-orange" },
] as const;

export function ResearchCategoryRail() {
  const pathname = usePathname();

  return (
    <nav aria-label="Research categories" className="shrink-0 border-b border-border bg-surface">
      <div className="mx-auto flex h-[52px] w-full max-w-[1240px] items-stretch gap-1 overflow-x-auto px-5 md:px-8 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {CATEGORIES.map(({ href, label, Icon, tone }) => {
          const basePath = href.split("?")[0];
          const active = label === "For you" ? pathname === "/home" : pathname === basePath;
          return (
            <Link
              key={label}
              href={href}
              aria-current={active ? "page" : undefined}
              className={cn(
                "relative flex min-w-max items-center gap-2 rounded-md px-3 font-body text-[12px] font-medium transition-colors",
                active ? "bg-surface-subtle text-text-primary" : "text-text-secondary hover:bg-surface-subtle hover:text-text-primary",
                focusRing,
              )}
            >
              <Icon size={16} strokeWidth={active ? 2.2 : 1.8} className={tone} aria-hidden="true" />
              {label}
              {active && <span className="absolute inset-x-3 -bottom-px h-0.5 rounded-full bg-primary" />}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
