import { apiRequest } from "./client";

export interface ProfileSyncResult {
  status: "synced";
  uid: string;
}

/** Creates or updates the caller's profile. Required before using workspaces. */
export function syncProfile(token: string, uid: string, name: string): Promise<ProfileSyncResult> {
  return apiRequest<ProfileSyncResult>("/api/v1/users/profile/sync", {
    method: "POST",
    token,
    body: { uid, name },
  });
}
