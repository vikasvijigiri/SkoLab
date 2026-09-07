"use client";

import { useState } from "react";
import Link from "next/link";
import { Palette, ShieldCheck, Bell, Sun, Moon, MonitorSmartphone, ChevronRight } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useLocalStorage } from "@/lib/hooks/useLocalStorage";
import { applyTheme, initialTheme, type Theme } from "@/lib/theme";
import { cn } from "@/lib/utils";

const THEMES: { key: Theme; label: string; Icon: typeof Sun }[] = [
  { key: "light", label: "Light", Icon: Sun },
  { key: "dark", label: "Dark", Icon: Moon },
  { key: "system", label: "System", Icon: MonitorSmartphone },
];

function Section({
  icon: Icon,
  title,
  desc,
  children,
  id,
}: {
  icon: typeof Palette;
  title: string;
  desc: string;
  children: React.ReactNode;
  id?: string;
}) {
  return (
    <section id={id} className="scroll-mt-6">
      <div className="mb-2 flex items-center gap-2">
        <span className="flex h-8 w-8 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Icon size={16} />
        </span>
        <div>
          <h2 className="font-display text-h3 font-semibold text-text-primary">{title}</h2>
          <p className="font-body text-[12px] text-text-muted">{desc}</p>
        </div>
      </div>
      <Card>{children}</Card>
    </section>
  );
}

export default function SettingsPage() {
  const { user } = useAuth();
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const [badge, setBadge] = useLocalStorage<boolean>("notifications:badge", true);

  function pick(t: Theme) {
    setTheme(t);
    applyTheme(t);
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-8 px-4 py-8 md:px-6">
      <div>
        <h1 className="font-display text-display-m font-bold text-text-primary">Settings</h1>
        <p className="mt-1 font-body text-body-s text-text-secondary">
          Appearance, notifications and your account.
        </p>
      </div>

      <Section icon={Palette} title="Appearance" desc="How SkoLab looks on this device.">
        <div className="flex flex-col gap-2">
          <span className="font-body text-body-s font-medium text-text-primary">Theme</span>
          <div className="flex gap-2">
            {THEMES.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => pick(t.key)}
                className={cn(
                  "flex flex-1 flex-col items-center gap-2 rounded-md border px-3 py-3 font-body text-[12px] font-medium transition-colors",
                  theme === t.key
                    ? "border-primary bg-primary/5 text-primary"
                    : "border-border text-text-secondary hover:border-primary/40 hover:text-text-primary",
                )}
              >
                <t.Icon size={17} />
                {t.label}
              </button>
            ))}
          </div>
        </div>
      </Section>

      <Section
        icon={Bell}
        title="Notifications"
        desc="New connections and papers from your network appear in the bell."
      >
        <label className="flex cursor-pointer items-center justify-between gap-4">
          <span className="font-body text-body-s text-text-primary">
            Show the unread count badge on the bell
          </span>
          <input
            type="checkbox"
            checked={badge}
            onChange={(e) => setBadge(e.target.checked)}
            className="h-4 w-4 accent-[var(--primary)]"
          />
        </label>
        <p className="mt-2 font-body text-[11.5px] leading-relaxed text-text-muted">
          Granular per-type controls (connection requests, workspace invites, mentions) arrive with
          the dedicated notifications service.
        </p>
      </Section>

      <Section
        id="account"
        icon={ShieldCheck}
        title="Account & privacy"
        desc="Your identity and how your data is used."
      >
        <dl className="flex flex-col divide-y divide-border">
          <div className="flex items-center justify-between py-3">
            <dt className="font-body text-body-s text-text-muted">Signed in as</dt>
            <dd className="font-body text-body-s font-medium text-text-primary">
              {user?.isAnonymous ? "Guest session" : (user?.email ?? "Researcher")}
            </dd>
          </div>
          <Link
            href="/profile"
            className="flex items-center justify-between py-3 font-body text-body-s text-text-secondary transition-colors hover:text-primary"
          >
            Edit your profile, research focus and links
            <ChevronRight size={15} />
          </Link>
          <Link
            href="/profile#danger"
            className="flex items-center justify-between py-3 font-body text-body-s text-text-secondary transition-colors hover:text-notification"
          >
            Delete your account
            <ChevronRight size={15} />
          </Link>
        </dl>
        <p className="mt-2 font-body text-[11.5px] leading-relaxed text-text-muted">
          Public bibliometric data (OpenAlex, Crossref, ORCID) is public. Anything you create in a
          workspace is visible only to people you invite.
        </p>
      </Section>
    </div>
  );
}
