import type { RefObject } from "react";
import { motion } from "framer-motion";
import { Sparkles, MessageSquare, ChevronRight, Loader2, AlertCircle, Send } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { MarkdownText } from "@/components/ui/MathText";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";
import type { NexusMessage } from "@/lib/types";

const STARTER_PROMPTS = [
  "Compare the methodologies used in these works.",
  "Are there any gaps or conflicts in their findings?",
  "Summarize the main business applications for this research.",
];

interface Props {
  messages: NexusMessage[];
  userMsg: string;
  chatLoading: boolean;
  chatError: string | null;
  activeCollectionCount: number;
  messagesEndRef: RefObject<HTMLDivElement | null>;
  mobileHidden: boolean;
  onUserMsgChange: (v: string) => void;
  onSend: (textToSend?: string) => void;
}

export function NexusChatPanel({
  messages,
  userMsg,
  chatLoading,
  chatError,
  activeCollectionCount,
  messagesEndRef,
  mobileHidden,
  onUserMsgChange,
  onSend,
}: Props) {
  return (
    <main
      className={cn(
        "flex-1 flex flex-col bg-page-bg relative min-w-0 md:flex",
        mobileHidden && "hidden md:flex"
      )}
    >
      <header className="h-16 border-b border-border flex items-center px-6 gap-3 shrink-0 bg-surface/20 backdrop-blur-md">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Sparkles size={16} />
        </div>
        <div className="min-w-0">
          <h2 className="font-display text-[15px] font-bold text-text-primary">Nexus Synthesis Assistant</h2>
          <p className="font-body text-[12px] text-text-muted truncate">
            {activeCollectionCount > 0
              ? `Synthesizing context across ${activeCollectionCount} selected studies`
              : "Awaiting workspace papers"}
          </p>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto p-6 flex flex-col gap-4">
        {messages.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center max-w-lg mx-auto text-center py-10 px-4">
            <div className="h-12 w-12 rounded-full bg-primary/10 flex items-center justify-center text-primary mb-4 animate-pulse">
              <MessageSquare size={20} />
            </div>
            <h3 className="font-display text-[16px] font-bold text-text-primary">Ask Nexus AI</h3>
            <p className="font-body text-[13px] text-text-muted mt-2 leading-relaxed">
              Add papers on the left workspace panel. Once your collection is ready, ask Nexus to analyze, search for
              methodology overlaps, find knowledge gaps, or synthesize business opportunities.
            </p>

            <div className="mt-8 flex flex-col gap-2 w-full">
              {STARTER_PROMPTS.map((prompt) => (
                <motion.button
                  key={prompt}
                  onClick={() => onSend(prompt)}
                  whileHover={{ y: -2, boxShadow: "var(--shadow-card-hover)" }}
                  whileTap={{ scale: 0.98 }}
                  transition={TRANSITION_FAST}
                  className="w-full text-left p-3 rounded-lg border border-border bg-surface/40 hover:bg-surface/90 hover:border-primary/30 transition-colors duration-[var(--motion-fast)] font-body text-[12.5px] text-text-secondary flex items-center justify-between"
                >
                  <span>{prompt}</span>
                  <ChevronRight size={14} className="text-text-muted" />
                </motion.button>
              ))}
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-4 max-w-3xl mx-auto w-full">
            {messages.map((msg, i) => (
              <div key={i} className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}>
                <div
                  className={`max-w-[85%] rounded-lg px-4 py-3 font-body text-[13.5px] leading-relaxed shadow-sm ${
                    msg.role === "user"
                      ? "bg-primary text-text-on-primary rounded-br-none"
                      : "bg-surface border border-border text-text-primary rounded-bl-none"
                  }`}
                >
                  {/* MarkdownText (not MathText) — LLM replies reliably use **bold**
                      headers/lists, which MathText renders as literal asterisks. */}
                  <div className="whitespace-pre-wrap">
                    <MarkdownText text={msg.content} />
                  </div>
                </div>
              </div>
            ))}

            {chatLoading && (
              <div className="flex justify-start">
                <div className="bg-surface border border-border rounded-lg rounded-bl-none px-4 py-3 flex items-center gap-2">
                  <Loader2 size={16} className="animate-spin text-primary" />
                  <span className="font-mono text-[12px] text-text-muted">Nexus is reading &amp; synthesizing...</span>
                </div>
              </div>
            )}

            {chatError && (
              <div className="flex items-center gap-2 rounded-md border border-notification/20 bg-notification/10 p-3 text-notification font-body text-[12.5px]">
                <AlertCircle size={15} />
                <span>{chatError}</span>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      <div className="p-4 border-t border-border bg-surface/20 backdrop-blur-md shrink-0">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSend();
          }}
          className="flex items-center gap-2 max-w-3xl mx-auto w-full"
        >
          <Input
            placeholder={
              activeCollectionCount === 0
                ? "Add papers on the left to activate chat..."
                : "Ask Nexus about your collection..."
            }
            value={userMsg}
            onChange={(e) => onUserMsgChange(e.target.value)}
            disabled={activeCollectionCount === 0 || chatLoading}
            className="h-11 text-[13px] bg-surface"
          />
          <Button
            type="submit"
            fullWidth={false}
            disabled={activeCollectionCount === 0 || chatLoading || !userMsg.trim()}
            className="h-11 px-5"
          >
            <Send size={15} />
          </Button>
        </form>
      </div>
    </main>
  );
}
