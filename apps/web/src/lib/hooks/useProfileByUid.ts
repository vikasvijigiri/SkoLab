"use client";

import { useQuery } from "@tanstack/react-query";
import { useFirestoreDoc } from "./useFirestoreDoc";
import { authorQuery } from "@/lib/api/queries";
import type { SkoLabUser } from "@/lib/types";

/**
 * Same shape and lookup logic as `useMyProfile`, but for an arbitrary uid
 * instead of the signed-in user — powers the CV route, which must be able to
 * render *any* researcher's CV (any signed-in SkoLab user may read any
 * `researchers/{uid}` doc, per firestore.rules — the same access Discovery
 * and the author page already rely on).
 *
 * Deliberately a separate hook rather than a refactor of `useMyProfile`
 * (which is wired to `useAuth()`'s current user) — keeps that hook's already
 * -tested behavior untouched.
 */
export function useProfileByUid(uid: string | null) {
  const {
    data: firestoreProfile,
    loading: profileLoading,
    error: profileError,
  } = useFirestoreDoc<SkoLabUser>(uid ? `researchers/${uid}` : null);

  const name = firestoreProfile?.name ?? "";
  const lookupName = firestoreProfile?.authorName?.trim() || name;
  const lookupId = firestoreProfile?.openAlexId?.trim() || undefined;

  const authorQ = useQuery({
    ...authorQuery(lookupName, lookupId, firestoreProfile?.researchFocus || undefined),
    enabled: Boolean(lookupName || lookupId),
  });

  const loading = profileLoading || (Boolean(lookupName || lookupId) && authorQ.isLoading);

  return {
    firestoreProfile: firestoreProfile ?? null,
    author: authorQ.data ?? null,
    loading,
    error: profileError,
  };
}
