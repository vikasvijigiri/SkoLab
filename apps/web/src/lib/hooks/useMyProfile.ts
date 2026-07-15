"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "./AuthProvider";
import { getResearcherProfile } from "@/lib/firebase/auth";
import { searchAuthor } from "@/lib/api/endpoints";
import type { SkoLabUser, AuthorResponse } from "@/lib/types";

interface MyProfileState {
  firestoreProfile: SkoLabUser | null;
  author: AuthorResponse | null;
  loading: boolean;
  error: string | null;
}

/**
 * Combines the Firestore `researchers/{uid}` document (name, focus, about — the
 * mobile app's source of truth for profile fields) with the backend's OpenAlex
 * enrichment (`search_author`, h-index/works/metrics). Mirrors FeedViewModel's
 * bootstrap sequence: resolve identity first, then everything else can key off it.
 */
export function useMyProfile() {
  const { user } = useAuth();
  const [state, setState] = useState<MyProfileState>({
    firestoreProfile: null,
    author: null,
    loading: true,
    error: null,
  });
  const [refetchToken, setRefetchToken] = useState(0);

  useEffect(() => {
    if (!user) {
      setState({ firestoreProfile: null, author: null, loading: false, error: null });
      return;
    }
    let cancelled = false;

    (async () => {
      setState((s) => ({ ...s, loading: true, error: null }));

      // Firestore and the OpenAlex author lookup are independent data sources — a
      // Firestore failure (e.g. security rules) must not prevent attempting the
      // OpenAlex search, since `user.displayName` from Firebase Auth alone is
      // often enough to resolve an author. Previously a Firestore error here
      // aborted the whole function, silently starving every consumer of `author`
      // (metrics card, daily feed's topic, etc.) even when it was recoverable.
      let profile: SkoLabUser | null = null;
      let profileError: string | null = null;
      try {
        profile = await getResearcherProfile(user.uid);
      } catch (err) {
        console.error("useMyProfile: getResearcherProfile failed", err);
        profileError = (err as Error).message || "Profile lookup failed.";
      }
      if (cancelled) return;
      setState((s) => ({ ...s, firestoreProfile: profile }));

      const name = profile?.name || user.displayName || "";
      if (!name) {
        if (!cancelled) {
          setState((s) => ({
            ...s,
            loading: false,
            error: profileError || "No name on file — set one in Profile to enable metrics lookup.",
          }));
        }
        return;
      }

      try {
        const author = await searchAuthor(name, profile?.openAlexId || undefined, profile?.researchFocus);
        if (!cancelled) setState((s) => ({ ...s, author, loading: false, error: null }));
      } catch (err) {
        console.error("useMyProfile: searchAuthor failed", err);
        if (!cancelled) {
          setState((s) => ({
            ...s,
            loading: false,
            error: (err as Error).message || profileError || "Author lookup failed.",
          }));
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [user, refetchToken]);

  const refetch = useCallback(() => setRefetchToken((t) => t + 1), []);

  return { ...state, refetch };
}
