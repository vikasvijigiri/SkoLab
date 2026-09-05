"use client";

import { useState, useRef, useEffect } from "react";
import { motion } from "framer-motion";
import { useMutation, useQuery } from "@tanstack/react-query";
import { nexusChat } from "@/lib/api/endpoints";
import { openAlexWorksQuery } from "@/lib/api/queries";
import { useDebounce } from "@/lib/hooks/useDebounce";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";
import { NexusCollectionPanel } from "@/components/nexus/NexusCollectionPanel";
import { NexusChatPanel } from "@/components/nexus/NexusChatPanel";
import type { NexusCollectionPaper, NexusMessage, OpenAlexWork } from "@/lib/types";

/** OpenAlex returns abstracts as an inverted index — rebuild the text. */
function reconstructAbstract(invertedIndex?: Record<string, number[]>): string {
  if (!invertedIndex) return "No abstract available.";
  try {
    const words: string[] = [];
    for (const [word, positions] of Object.entries(invertedIndex)) {
      for (const pos of positions) words[pos] = word;
    }
    return words.filter(Boolean).join(" ");
  } catch {
    return "Abstract metadata format error.";
  }
}

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

  // Auto-scroll the chat to the newest message.
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, chatLoading]);

  function handleAddPaper(paper: OpenAlexWork) {
    const shortId = paper.id.split("/").pop() ?? paper.id;
    if (activeCollection.some((p) => p.id === shortId)) return;
    setActiveCollection((prev) => [
      ...prev,
      {
        id: shortId,
        title: paper.display_name,
        authors: paper.authorships?.map((a) => a.author.display_name) ?? [],
        year: paper.publication_year ?? 0,
        cited_by_count: paper.cited_by_count,
        abstract: reconstructAbstract(paper.abstract_inverted_index),
        doi: paper.doi,
      },
    ]);
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

  return (
    <div className="flex h-[calc(100dvh-4rem)] w-full flex-col overflow-hidden md:h-dvh md:flex-row">
      {/* Mobile-only pane switcher — below md the two panes can't fit side by side. */}
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

      <NexusCollectionPanel
        activeCollection={activeCollection}
        searchQuery={searchQuery}
        searchResults={searchResults}
        searching={search.isFetching}
        searchErrored={search.isError}
        searchRan={search.isFetched && debouncedSearch.trim().length >= 3}
        resultsDismissed={resultsDismissed}
        searchContainerRef={searchContainerRef}
        mobileHidden={mobilePane === "chat"}
        onSearchChange={(v) => {
          setSearchQuery(v);
          setResultsDismissed(false);
        }}
        onAddPaper={handleAddPaper}
        onRemovePaper={handleRemovePaper}
      />

      <NexusChatPanel
        messages={messages}
        userMsg={userMsg}
        chatLoading={chatLoading}
        chatError={chatError}
        activeCollectionCount={activeCollection.length}
        messagesEndRef={messagesEndRef}
        mobileHidden={mobilePane === "collection"}
        onUserMsgChange={setUserMsg}
        onSend={handleSendChat}
      />
    </div>
  );
}
