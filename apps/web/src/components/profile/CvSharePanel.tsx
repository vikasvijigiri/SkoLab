"use client";

import { useState } from "react";
import { Link2, Check, Download, Mail, MessageCircle, Send } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { useMyConnections } from "@/lib/hooks/useMyConnections";
import { shareCv } from "@/lib/firebase/cvShare";

/**
 * Click-first CV sharing (decisions/0023): copy-link and Download PDF need no
 * typing at all; "Send to a connection" is a one-click list of people the
 * viewer actually works with (see useMyConnections — CoLab Workspace
 * membership, the only real connections graph this app has); free-text
 * email/phone is the explicit fallback for someone not on SkoLab yet, not
 * the default path.
 */
export function CvSharePanel({
  uid,
  fromName,
  cvHref,
}: {
  uid: string;
  fromName: string;
  cvHref: string;
}) {
  const { connections, loading } = useMyConnections(uid);
  const [copied, setCopied] = useState(false);
  const [sentTo, setSentTo] = useState<Set<string>>(new Set());
  const [sendingTo, setSendingTo] = useState<string | null>(null);
  const [contact, setContact] = useState("");

  const fullUrl = typeof window !== "undefined" ? `${window.location.origin}${cvHref}` : cvHref;

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(fullUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      /* clipboard blocked — no-op */
    }
  }

  async function sendTo(connectionUid: string) {
    setSendingTo(connectionUid);
    try {
      await shareCv({ toUid: connectionUid, fromUid: uid, fromName, cvHref });
      setSentTo((prev) => new Set(prev).add(connectionUid));
    } catch {
      /* best-effort — the recipient just won't see it; no error UI for a
         low-stakes, retryable action */
    } finally {
      setSendingTo(null);
    }
  }

  const looksLikeEmail = contact.includes("@");
  const mailHref = `mailto:${looksLikeEmail ? contact.trim() : ""}?subject=${encodeURIComponent(
    `${fromName}'s CV on SkoLab`,
  )}&body=${encodeURIComponent(`Here's my CV on SkoLab: ${fullUrl}`)}`;
  const smsHref = `sms:${!looksLikeEmail ? contact.trim() : ""}?body=${encodeURIComponent(
    `Here's my CV on SkoLab: ${fullUrl}`,
  )}`;

  return (
    <Card className="print:hidden">
      <h2 className="font-display text-h3 font-semibold text-text-primary">Share this CV</h2>

      <button
        type="button"
        onClick={copyLink}
        className="mt-3 flex w-full items-center gap-2.5 rounded-md border border-border px-3 py-2.5 font-body text-body-s font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
      >
        {copied ? <Check size={15} className="text-accent-teal" /> : <Link2 size={15} className="text-primary" />}
        {copied ? "Link copied" : "Copy link"}
      </button>
      <p className="mb-1 mt-1 truncate pl-1 font-mono text-[10px] text-text-muted">Copies {fullUrl}</p>

      <button
        type="button"
        onClick={() => window.print()}
        className="mt-2 flex w-full items-center gap-2.5 rounded-md border border-border px-3 py-2.5 font-body text-body-s font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
      >
        <Download size={15} className="text-accent-teal" />
        Download PDF
      </button>

      <p className="mb-2 mt-5 font-mono text-[10px] font-semibold uppercase tracking-wide text-text-muted">
        Send to a connection
      </p>
      {loading ? (
        <div className="flex flex-col gap-1">
          {[0, 1].map((i) => (
            <div key={i} className="h-10 animate-pulse rounded-md bg-surface-subtle" />
          ))}
        </div>
      ) : connections.length === 0 ? (
        <p className="font-body text-[12px] text-text-muted">
          You don&apos;t have any connections yet — work with someone in a CoLab project and
          they&apos;ll show up here. Use email or phone below in the meantime.
        </p>
      ) : (
        <div className="flex flex-col gap-1 overflow-hidden rounded-md border border-border">
          {connections.map((c) => {
            const sent = sentTo.has(c.uid);
            return (
              <button
                key={c.uid}
                type="button"
                disabled={sent || sendingTo === c.uid}
                onClick={() => sendTo(c.uid)}
                className="flex items-center gap-2.5 border-b border-border bg-surface px-3 py-2 text-left last:border-b-0 transition-colors hover:bg-surface-subtle disabled:cursor-default"
              >
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-accent-teal font-display text-[11px] font-bold text-text-on-primary">
                  {c.name.slice(0, 1).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1 truncate font-body text-body-s text-text-primary">
                  {c.name}
                </span>
                {sent ? (
                  <Check size={14} className="shrink-0 text-accent-teal" />
                ) : (
                  <Send size={13} className="shrink-0 text-text-muted" />
                )}
              </button>
            );
          })}
        </div>
      )}

      <p className="mb-2 mt-5 font-mono text-[10px] font-semibold uppercase tracking-wide text-text-muted">
        Not on SkoLab yet
      </p>
      <Input
        placeholder="Email or phone number"
        value={contact}
        onChange={(e) => setContact(e.target.value)}
      />
      <div className="mt-2 flex gap-2">
        <a
          href={contact.trim() ? mailHref : undefined}
          aria-disabled={!contact.trim()}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-border px-3 py-2 font-body text-[12.5px] font-medium text-text-secondary transition-colors aria-disabled:pointer-events-none aria-disabled:opacity-50 hover:border-primary/40 hover:text-text-primary"
        >
          <Mail size={13} /> Email
        </a>
        <a
          href={contact.trim() ? smsHref : undefined}
          aria-disabled={!contact.trim()}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-border px-3 py-2 font-body text-[12.5px] font-medium text-text-secondary transition-colors aria-disabled:pointer-events-none aria-disabled:opacity-50 hover:border-primary/40 hover:text-text-primary"
        >
          <MessageCircle size={13} /> Text
        </a>
      </div>

      <div className="mt-5 flex items-start gap-2 rounded-md border border-accent-teal/25 bg-accent-teal/[0.06] px-3 py-2.5">
        <Check size={14} className="mt-0.5 shrink-0 text-accent-teal" />
        <p className="font-body text-[11px] leading-relaxed text-text-secondary">
          Anyone signed in to SkoLab who opens this link sees this same page — no connection
          required, just a SkoLab account.
        </p>
      </div>
    </Card>
  );
}
