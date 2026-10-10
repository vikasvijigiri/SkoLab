import { useCallback } from "react";
import { ApiError } from "../api/client";
import { useAuth } from "./AuthProvider";

/** A function that returns the signed-in user's ID token, or throws a 401 ApiError. */
export function useIdToken(): () => Promise<string> {
  const { service } = useAuth();
  return useCallback(async () => {
    const token = await service.getIdToken();
    if (!token) throw new ApiError(401, "unauthenticated", "Your session expired. Sign in again.");
    return token;
  }, [service]);
}
