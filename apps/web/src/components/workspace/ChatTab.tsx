"use client";

import { useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { Send } from "lucide-react";
import { subscribeMessages, sendMessage } from "@/lib/firebase/workspace";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { cn } from "@/lib/utils";
import type { CollabMessage } from "@/lib/types";

export function ChatTab({
  projectId,
  active = true,
  onUnreadChange,
}: {
  projectId: string;
  /** Whether this panel is the dock's currently-visible tab. When mounted
   *  inactive (a background dock tab), new messages from someone else are
   *  reported via `onUnreadChange` instead of just auto-scrolling in. */
  active?: boolean;
  onUnreadChange?: (unread: boolean) => void;
}) {
  const { user } = useAuth();
  const [messages, setMessages] = useState<CollabMessage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  // 0 (not Date.now()) so the ref's initial value is pure — real "now" is
  // stamped by the mount effect below, before any message can be compared.
  const lastReadAt = useRef(0);

  useEffect(() => {
    lastReadAt.current = Date.now();
  }, []);

  useEffect(() => {
    const unsub = subscribeMessages(
      projectId,
      (msgs) => {
        setMessages(msgs);
        setError(null);
      },
      (err) => setError(friendlyFirestoreError(err))
    );
    return unsub;
  }, [projectId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  // Active tab reads everything up to now; clears any pending unread dot.
  useEffect(() => {
    if (!active) return;
    lastReadAt.current = Date.now();
    onUnreadChange?.(false);
  }, [active, onUnreadChange]);

  // While backgrounded, a new message from someone else lights the dot.
  useEffect(() => {
    if (active || messages.length === 0) return;
    const last = messages[messages.length - 1];
    if (last && last.senderUid !== user?.uid && last.timestamp > lastReadAt.current) {
      onUnreadChange?.(true);
    }
  }, [messages, active, user?.uid, onUnreadChange]);

  async function handleSend(e: React.FormEvent) {
    e.preventDefault();
    if (!user || !text.trim()) return;
    setSending(true);
    try {
      await sendMessage(projectId, user.uid, user.displayName ?? "Researcher", text.trim());
      setText("");
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setSending(false);
    }
  }

  if (error) return <ErrorBanner message={error} />;

  return (
    <div className="flex h-full min-h-[280px] flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto rounded-md bg-surface-subtle p-3">
        {messages.length === 0 && (
          <p className="text-center font-body text-body-s text-text-muted">No messages yet — say hello.</p>
        )}
        <div className="flex flex-col gap-2">
          {messages.map((m, i) => {
            const mine = m.senderUid === user?.uid;
            return (
              <motion.div
                key={m.id}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.25, delay: Math.min(i * 0.02, 0.3) }}
                className={cn("flex flex-col", mine ? "items-end" : "items-start")}
              >
                {!mine && <span className="mb-0.5 font-body text-[11px] text-text-muted">{m.senderName}</span>}
                <div
                  className={cn(
                    "max-w-[75%] rounded-md px-3 py-2 font-body text-body-s shadow-xs",
                    mine ? "bg-primary text-text-on-primary" : "bg-surface text-text-primary"
                  )}
                >
                  {m.text}
                </div>
              </motion.div>
            );
          })}
        </div>
        <div ref={bottomRef} />
      </div>
      <form onSubmit={handleSend} className="mt-3 flex gap-2">
        <Input placeholder="Message the team..." value={text} onChange={(e) => setText(e.target.value)} />
        <Button type="submit" fullWidth={false} loading={sending} className="w-24 gap-2">
          <Send size={14} />
          Send
        </Button>
      </form>
    </div>
  );
}
