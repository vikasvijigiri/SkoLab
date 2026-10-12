import { apiRequest } from "./client";
import type { GrantableRole, Invite, InviteOptions, InvitePreview, Member, Membership } from "./editorTypes";

/**
 * Sharing a workspace: its members and the invite links that add more.
 * Shapes follow services/backend-go/api/openapi.yaml (tag "sharing").
 */

const workspacePath = (id: string) => `/api/v1/workspaces/${encodeURIComponent(id)}`;

export async function listMembers(token: string, workspaceId: string): Promise<Member[]> {
  return (await apiRequest<{ members: Member[] }>(`${workspacePath(workspaceId)}/members`, { token })).members;
}

export function changeMemberRole(token: string, workspaceId: string, userId: string, role: GrantableRole): Promise<{ user_id: string; role: GrantableRole }> {
  return apiRequest(`${workspacePath(workspaceId)}/members/${encodeURIComponent(userId)}`, { method: "PATCH", token, body: { role } });
}

/** Removes a member (owner), or leaves the workspace when userId is the caller. */
export function removeMember(token: string, workspaceId: string, userId: string): Promise<void> {
  return apiRequest(`${workspacePath(workspaceId)}/members/${encodeURIComponent(userId)}`, { method: "DELETE", token });
}

export function getInviteOptions(token: string, workspaceId: string): Promise<InviteOptions> {
  return apiRequest(`${workspacePath(workspaceId)}/invite-options`, { token });
}

export async function listInvites(token: string, workspaceId: string): Promise<Invite[]> {
  return (await apiRequest<{ invites: Invite[] }>(`${workspacePath(workspaceId)}/invites`, { token })).invites;
}

export interface InviteChoice {
  role: GrantableRole;
  expires_in_hours: number;
  max_uses: number | null;
}

/** The answer carries the token; it is never shown again. */
export function createInvite(token: string, workspaceId: string, choice: InviteChoice): Promise<Invite & { token: string }> {
  return apiRequest(`${workspacePath(workspaceId)}/invites`, { method: "POST", token, body: choice });
}

export function revokeInvite(token: string, workspaceId: string, inviteId: string): Promise<void> {
  return apiRequest(`${workspacePath(workspaceId)}/invites/${encodeURIComponent(inviteId)}`, { method: "DELETE", token });
}

export function previewInvite(token: string, inviteToken: string): Promise<InvitePreview> {
  return apiRequest("/api/v1/invites/preview", { method: "POST", token, body: { token: inviteToken } });
}

export function acceptInvite(token: string, inviteToken: string): Promise<Membership> {
  return apiRequest("/api/v1/invites/accept", { method: "POST", token, body: { token: inviteToken } });
}

/**
 * The link to send. The token rides in the fragment, which browsers never
 * send to a server, so it stays out of access logs and Referer headers.
 */
export function inviteLink(inviteToken: string, origin = window.location.origin): string {
  return `${origin}/invite#${inviteToken}`;
}
