"use client";

import { useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "./AuthProvider";
import { useFirestoreDoc } from "./useFirestoreDoc";
import { authorQuery } from "@/lib/api/queries";
import type { SkoLabUser } from "@/lib/types";

/**
 * Combines the Firestore `researchers/{uid}` document (name, focus, about — the
 * mobile app's source of truth for profile fields) with the backend's OpenAlex
 * enrichment (`search_author`, h-index/works/metrics).
 *
 * The Firestore half is a live `onSnapshot` subscription (a profile edit on
 * another device reflects here), the author half is a TanStack Query. The two
 * error sources stay independent — a Firestore failure (e.g. security rules)
 * must not prevent attempting the OpenAlex search, since `user.displayName`
 * alone is often enough to resolve an author.
 */
export function useMyProfile() {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const {
    data: firestoreProfile,
    loading: profileLoading,
    error: profileError,
  } = useFirestoreDoc<SkoLabUser>(user ? `researchers/${user.uid}` : null);

  const name = firestoreProfile?.name || user?.displayName || "";

  const authorQ = useQuery({
    ...authorQuery(
      name,
      firestoreProfile?.openAlexId || undefined,
      firestoreProfile?.researchFocus || undefined,
    ),
    enabled: Boolean(name),
  });

  const loading = profileLoading || (Boolean(name) && authorQ.isLoading);

  const error =
    profileError ??
    (authorQ.error instanceof Error ? authorQ.error.message : null) ??
    (!name && !profileLoading
      ? "No name on file — set one in Profile to enable metrics lookup."
      : null);

  const refetch = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: authorQuery(name).queryKey });
  }, [queryClient, name]);

  return {
    firestoreProfile: firestoreProfile ?? null,
    author: authorQ.data ?? null,
    loading,
    error,
    refetch,
  };
}
