import { useMemo } from "react";
import { useAuth } from "../auth/AuthProvider";
import { createDocumentStore, type DocumentStore } from "./documents";

/** The signed-in user's documents. Only used under RequireAccount, so a user exists. */
export function useDocumentStore(): DocumentStore {
  const { user } = useAuth();
  const uid = user?.uid ?? "signed-out";
  return useMemo(() => createDocumentStore(window.localStorage, uid), [uid]);
}
