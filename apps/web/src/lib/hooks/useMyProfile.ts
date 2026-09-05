"use client";

import { useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "./AuthProvider";
import { useFirestoreDoc } from "./useFirestoreDoc";
import { authorQuery } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/client";
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
  // The name to resolve against OpenAlex — prefer the "name as published"
  // (authorName), which onboarding collects specifically because a display
  // name and a byline often differ ("Sam Park" vs "Samuel J. Park").
  const lookupName = firestoreProfile?.authorName?.trim() || name;
  // openAlexId holds either an OpenAlex author id (A123…) or an ORCID
  // (0000-0000-0000-0000, optionally as an orcid.org URL) — search_author
  // accepts both; the Go side normalises an ORCID to `orcid:…` for OpenAlex.
  const lookupId = firestoreProfile?.openAlexId?.trim() || undefined;

  const authorQ = useQuery({
    ...authorQuery(
      lookupName,
      lookupId,
      firestoreProfile?.researchFocus || undefined,
    ),
    enabled: Boolean(lookupName || lookupId),
  });

  const loading = profileLoading || (Boolean(lookupName || lookupId) && authorQ.isLoading);

  // "Unresolved" = no OpenAlex match to work from yet. Two shapes, both a
  // calm cold-start state rather than a red "something broke" banner: nothing
  // to look up at all (no name/id, e.g. a fresh guest), or a 404 from
  // search_author (a name OpenAlex doesn't index). Neither has a Retry that
  // could help — the fix is to add an identity in Profile.
  const nothingToLookUp = !lookupName && !lookupId && !profileLoading;
  const authorNotFound =
    authorQ.error instanceof ApiError && authorQ.error.status === 404;
  const unresolved = nothingToLookUp || authorNotFound;

  const error =
    profileError ??
    (!authorNotFound && authorQ.error instanceof Error ? authorQ.error.message : null) ??
    null;

  const refetch = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: authorQuery(lookupName).queryKey });
  }, [queryClient, lookupName]);

  return {
    firestoreProfile: firestoreProfile ?? null,
    author: authorQ.data ?? null,
    loading,
    error,
    /** True when there's no OpenAlex match to work from yet — no identity
     * entered, or entered but not indexed. A cold-start state, not an error:
     * consumers render a calm "connect your work" prompt, not an error banner. */
    unresolved,
    refetch,
  };
}
