"use client";

import { useState, useRef, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { useMutation, useQuery } from "@tanstack/react-query";
import { MessageSquare, Plus, Trash2, Send, Search, BookOpen, AlertCircle, Sparkles, ChevronRight, Loader2 } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Badge } from "@/components/ui/Badge";
import { MathText, MarkdownText } from "@/components/ui/MathText";
import { nexusChat } from "@/lib/api/endpoints";
import { openAlexWorksQuery } from "@/lib/api/queries";
import { useDebounce } from "@/lib/hooks/useDebounce";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";
import type { NexusCollectionPaper, NexusMessage, OpenAlexWork } from "@/lib/types";

export default function NexusPage() {
  const [mobilePane, setMobilePane] = useState<"collection" | "chat">("collection");
  const [activeCollection, setActiveCollection] = useState<NexusCollectionPaper[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const debouncedSearch = useDebounce(searchQuery, 400);
  const [messages, setMessages] = useState<NexusMessage[]>([]);
  const [userMsg, setUserMsg] = useState("");
  const [guardError, setGuardError] = useState<string | null>(null);
  const [resultsDismissed, setResultsDismissed] = useState(false);

  const search = useQuery({
    ...openAlexWorksQuery({ q: debouncedSearch }),
    enabled: debouncedSearch.trim().length >= 3,
  });
  const searchResults: OpenAlexWork[] = search.data ?? [];
  const searching = search.isFetching;

  const chat = useMutation({
    mutationFn: (msgs: NexusMessage[]) =>
      nexusChat(
        activeCollection.map((p) => ({ title: p.title, authors: p.authors, year: p.year, abstract: p.abstract })),
        msgs,
      ),
    onSuccess: (data) => setMessages((prev) => [...prev, { role: "assistant", content: data.content }]),
  });
  const chatLoading = chat.isPending;
  const chatError =
    guardError ?? (chat.isError ? "Failed to synthesize response. Check backend connection." : null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const searchContainerRef = useRef<HTMLDivElement>(null);

  // Close the floating search dropdown on an outside click or Escape.
  useEffect(() => {
    function handlePointerDown(e: MouseEvent) {
      if (searchContainerRef.current && !searchContainerRef.current.contains(e.target as Node)) {
        setResultsDismissed(true);
      }
    }
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setResultsDismissed(true);
    }
    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, []);

  // Auto-scroll chat to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, chatLoading]);

  // Reconstruct abstract from inverted index format returned by OpenAlex
  function reconstructAbstract(invertedIndex?: Record<string, number[]>): string {
    if (!invertedIndex) return "No abstract available.";
    try {
      const words: string[] = [];
      for (const [word, positions] of Object.entries(invertedIndex)) {
        for (const pos of positions) {
          words[pos] = word;
        }
      }
      return words.filter(Boolean).join(" ");
    } catch {
      return "Abstract metadata format error.";
    }
  }

  function handleAddPaper(paper: OpenAlexWork) {
    const shortId = paper.id.split("/").pop() ?? paper.id;
    if (activeCollection.some((p) => p.id === shortId)) return;

    const newPaper: NexusCollectionPaper = {
      id: shortId,
      title: paper.display_name,
      authors: paper.authorships?.map((a) => a.author.display_name) ?? [],
      year: paper.publication_year ?? 0,
      cited_by_count: paper.cited_by_count,
      abstract: reconstructAbstract(paper.abstract_inverted_index),
      doi: paper.doi,
    };

    setActiveCollection((prev) => [...prev, newPaper]);
    setSearchQuery("");
    setResultsDismissed(true);
  }

  function handleRemovePaper(id: string) {
    setActiveCollection((prev) => prev.filter((p) => p.id !== id));
  }

  function handleSendChat(textToSend?: string) {
    const text = textToSend ?? userMsg;
    if (!text.trim() || chatLoading) return;

    if (activeCollection.length === 0) {
      setGuardError("Please add at least one paper to your active collection first.");
      return;
    }

    setGuardError(null);
    if (!textToSend) setUserMsg("");

    const newMessages: NexusMessage[] = [...messages, { role: "user", content: text }];
    setMessages(newMessages);
    chat.mutate(newMessages);
  }

  const starterPrompts = [
    "Compare the methodologies used in these works.",
    "Are there any gaps or conflicts in their findings?",
    "Summarize the main business applications for this research.",
  ];

  return (
    <div className="flex h-[calc(100dvh-4rem)] w-full flex-col overflow-hidden md:h-dvh md:flex-row">
      {/* Mobile-only pane switcher — below md, the collection and chat panes can't fit side by side. */}
      <div className="flex h-12 shrink-0 items-center gap-1 border-b border-border bg-surface-subtle p-1 md:hidden">
        {(["collection", "chat"] as const).map((p) => (
          <motion.button
            key={p}
            onClick={() => setMobilePane(p)}
            whileTap={{ scale: 0.96 }}
            transition={TRANSITION_FAST}
            className={cn(
              "relative flex-1 rounded-md py-1.5 font-body text-[12.5px] font-medium transition-colors duration-[var(--motion-fast)]",
              mobilePane === p ? "text-text-on-primary" : "text-text-secondary"
            )}
          >
            {mobilePane === p && (
              <motion.span
                layoutId="nexus-mobile-pane-pill"
                className="absolute inset-0 rounded-md bg-primary"
                transition={{ type: "spring", stiffness: 400, damping: 32 }}
              />
            )}
            <span className="relative z-10">
              {p === "collection" ? `Collection (${activeCollection.length})` : "Chat"}
            </span>
          </motion.button>
        ))}
      </div>

      {/* LEFT COLUMN: COLLECTION WORKSPACE */}
      <aside
        className={cn(
          "flex w-full flex-col border-r border-border bg-surface-subtle/40 md:flex md:w-[380px] lg:w-[420px] shrink-0",
          mobilePane === "chat" && "hidden md:flex"
        )}
      >
        <div className="flex flex-col gap-3 p-4 border-b border-border">
          <div className="flex items-center gap-2">
            <div className="flex h-7 w-7 items-center justify-center rounded bg-accent-purple/10 text-accent-purple">
              <BookOpen size={15} />
            </div>
            <span className="font-display text-[14px] font-bold text-text-primary">
              Synthesis Collection ({activeCollection.length})
            </span>
          </div>
          <p className="font-body text-[12.5px] leading-snug text-text-muted">
            Search and add papers from the OpenAlex global database.
          </p>

          {/* Search bar */}
          <div className="relative mt-1" ref={searchContainerRef}>
            <Input
              leadingIcon={searching ? <Loader2 size={15} className="animate-spin text-primary" /> : <Search size={15} />}
              placeholder="Search literature to add..."
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value);
                setResultsDismissed(false);
              }}
              className="h-10 text-[13px]"
            />

            {/* Floating search results */}
            <AnimatePresence>
              {searchResults.length > 0 && !resultsDismissed && (
                <motion.div
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: 10 }}
                  className="absolute left-0 right-0 top-11 z-50 max-h-72 overflow-y-auto rounded-md border border-border bg-surface p-1 shadow-elevated"
                >
                  {searchResults.map((paper) => {
                    const shortId = paper.id.split("/").pop() ?? paper.id;
                    const isAdded = activeCollection.some((p) => p.id === shortId);
                    return (
                      <div
                        key={paper.id}
                        role="button"
                        tabIndex={isAdded ? -1 : 0}
                        aria-disabled={isAdded}
                        onClick={() => !isAdded && handleAddPaper(paper)}
                        onKeyDown={(e) => {
                          if ((e.key === "Enter" || e.key === " ") && !isAdded) {
                            e.preventDefault();
                            handleAddPaper(paper);
                          }
                        }}
                        className={`flex items-start justify-between gap-3 rounded p-2.5 text-left transition-colors font-body text-[12.5px] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${
                          isAdded ? "bg-surface-subtle opacity-50 cursor-not-allowed" : "cursor-pointer hover:bg-surface-subtle"
                        }`}
                      >
                        <div className="min-w-0 flex-1">
                          <p className="font-semibold text-text-primary line-clamp-2 leading-snug">
                            <MathText text={paper.display_name} />
                          </p>
                          <p className="text-[11.5px] text-text-muted mt-1">
                            {paper.authorships?.slice(0, 2).map((a) => a.author.display_name).join(", ")}
                            {paper.publication_year ? ` · ${paper.publication_year}` : ""}
                          </p>
                        </div>
                        <motion.button
                          type="button"
                          whileHover={isAdded ? undefined : { scale: 1.12 }}
                          whileTap={isAdded ? undefined : { scale: 0.9 }}
                          transition={TRANSITION_FAST}
                          className="flex h-6 w-6 items-center justify-center rounded-full bg-primary/10 text-primary transition-colors duration-[var(--motion-fast)] hover:bg-primary hover:text-text-on-primary disabled:cursor-not-allowed"
                          disabled={isAdded}
                        >
                          <Plus size={13} />
                        </motion.button>
                      </div>
                    );
                  })}
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>

        {/* Paper Collection Drawer List */}
        <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-3">
          {activeCollection.length === 0 ? (
            <div className="flex flex-col items-center justify-center text-center py-20 px-4">
              <div className="h-12 w-12 rounded-full border border-dashed border-border flex items-center justify-center text-text-muted mb-4">
                <BookOpen size={20} />
              </div>
              <p className="font-body text-[13px] text-text-muted">
                Your workspace collection is empty. Search and add studies to construct your workspace.
              </p>
            </div>
          ) : (
            <AnimatePresence>
              {activeCollection.map((p) => (
                <motion.div
                  key={p.id}
                  layoutId={p.id}
                  initial={{ opacity: 0, scale: 0.96 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.96 }}
                  transition={{ duration: 0.2 }}
                >
                  <Card className="border-border/30 bg-surface/80 shadow-sm relative group p-3 flex flex-col gap-1 pr-8">
                    <motion.button
                      onClick={() => handleRemovePaper(p.id)}
                      whileHover={{ scale: 1.12 }}
                      whileTap={{ scale: 0.9 }}
                      transition={TRANSITION_FAST}
                      className="absolute right-2 top-2 h-6 w-6 rounded flex items-center justify-center text-text-muted transition-colors duration-[var(--motion-fast)] hover:bg-notification/10 hover:text-notification opacity-0 group-hover:opacity-100 focus:opacity-100"
                    >
                      <Trash2 size={13} />
                    </motion.button>
                    <h4 className="font-display text-[13px] font-semibold text-text-primary line-clamp-2 leading-snug">
                      <MathText text={p.title} />
                    </h4>
                    <p className="font-body text-[11.5px] text-text-secondary truncate mt-0.5">
                      {p.authors.join(", ")}
                    </p>
                    <div className="flex items-center gap-1.5 mt-2">
                      <Badge accentColor="var(--accent-purple)" className="px-1.5 text-[10px]">
                        {p.year}
                      </Badge>
                      {p.cited_by_count !== undefined && (
                        <Badge accentColor="var(--accent-teal)" className="px-1.5 text-[10px]">
                          {p.cited_by_count} Citations
                        </Badge>
                      )}
                    </div>
                  </Card>
                </motion.div>
              ))}
            </AnimatePresence>
          )}
        </div>
      </aside>

      {/* RIGHT COLUMN: SYNTHESIZER CHAT CONSOLE */}
      <main
        className={cn(
          "flex-1 flex flex-col bg-page-bg relative min-w-0 md:flex",
          mobilePane === "collection" && "hidden md:flex"
        )}
      >
        {/* Top Header info */}
        <header className="h-16 border-b border-border flex items-center px-6 gap-3 shrink-0 bg-surface/20 backdrop-blur-md">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Sparkles size={16} />
          </div>
          <div className="min-w-0">
            <h2 className="font-display text-[15px] font-bold text-text-primary">
              Nexus Synthesis Assistant
            </h2>
            <p className="font-body text-[12px] text-text-muted truncate">
              {activeCollection.length > 0
                ? `Synthesizing context across ${activeCollection.length} selected studies`
                : "Awaiting workspace papers"}
            </p>
          </div>
        </header>

        {/* Message Feed */}
        <div className="flex-1 overflow-y-auto p-6 flex flex-col gap-4">
          {messages.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center max-w-lg mx-auto text-center py-10 px-4">
              <div className="h-12 w-12 rounded-full bg-primary/10 flex items-center justify-center text-primary mb-4 animate-pulse">
                <MessageSquare size={20} />
              </div>
              <h3 className="font-display text-[16px] font-bold text-text-primary">
                Ask Nexus AI
              </h3>
              <p className="font-body text-[13px] text-text-muted mt-2 leading-relaxed">
                Add papers on the left workspace panel. Once your collection is ready, ask Nexus to analyze, search for methodology overlaps, find knowledge gaps, or synthesize business opportunities.
              </p>

              {/* Starter Prompt Chips */}
              <div className="mt-8 flex flex-col gap-2 w-full">
                {starterPrompts.map((prompt) => (
                  <motion.button
                    key={prompt}
                    onClick={() => handleSendChat(prompt)}
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
                <div
                  key={i}
                  className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}
                >
                  <div
                    className={`max-w-[85%] rounded-lg px-4 py-3 font-body text-[13.5px] leading-relaxed shadow-sm ${
                      msg.role === "user"
                        ? "bg-primary text-text-on-primary rounded-br-none"
                        : "bg-surface border border-border text-text-primary rounded-bl-none"
                    }`}
                  >
                    {/* Preserve line breaks for formatted markdown-like replies.
                        MarkdownText (not MathText) — LLM replies reliably use
                        **bold** headers/lists, which MathText doesn't understand
                        and used to render as literal asterisks. */}
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
                    <span className="font-mono text-[12px] text-text-muted">Nexus is reading & synthesizing...</span>
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

        {/* Input Bar */}
        <div className="p-4 border-t border-border bg-surface/20 backdrop-blur-md shrink-0">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleSendChat();
            }}
            className="flex items-center gap-2 max-w-3xl mx-auto w-full"
          >
            <Input
              placeholder={activeCollection.length === 0 ? "Add papers on the left to activate chat..." : "Ask Nexus about your collection..."}
              value={userMsg}
              onChange={(e) => setUserMsg(e.target.value)}
              disabled={activeCollection.length === 0 || chatLoading}
              className="h-11 text-[13px] bg-surface"
            />
            <Button
              type="submit"
              fullWidth={false}
              disabled={activeCollection.length === 0 || chatLoading || !userMsg.trim()}
              className="h-11 px-5"
            >
              <Send size={15} />
            </Button>
          </form>
        </div>
      </main>
    </div>
  );
}
