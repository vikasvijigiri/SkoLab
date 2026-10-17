import { apiRequest } from "./client";
import type { AIStatus, ImproveGoal, ImproveResult, OriginalityReport } from "./editorTypes";

export type { AIFeature, AIStatus, ImproveGoal, ImproveResult, OriginalityMatch, OriginalityReport, OriginalityResult, OriginalitySource, Suggestion } from "./editorTypes";

/**
 * The AI writing analytics (Go gateway, internal/ai): the improver, which
 * suggests verified edits for a passage, and the originality check, which
 * compares the document with the scholarly record in the background.
 */

const workspacePath = (id: string) => `/api/v1/workspaces/${encodeURIComponent(id)}`;
// The model can take several seconds; the gateway gives up at 30.
const IMPROVE_MS = 40_000;

export function aiStatus(token: string, signal?: AbortSignal): Promise<AIStatus> {
  return apiRequest<AIStatus>("/api/v1/ai/status", { token, ...(signal ? { signal } : {}) });
}

/** Suggestions for text (a passage of main.tex); offsets are into text, in UTF-16 units. */
export function improve(token: string, workspaceId: string, text: string, goal: ImproveGoal, signal?: AbortSignal): Promise<ImproveResult> {
  return apiRequest<ImproveResult>(`${workspacePath(workspaceId)}/ai/improve`, {
    method: "POST",
    token,
    body: { text, goal },
    timeoutMs: IMPROVE_MS,
    ...(signal ? { signal } : {}),
  });
}

export function startOriginality(token: string, workspaceId: string): Promise<{ report: OriginalityReport; left_today: number }> {
  return apiRequest(`${workspacePath(workspaceId)}/ai/originality`, { method: "POST", token });
}

export async function originalityReports(token: string, workspaceId: string, signal?: AbortSignal): Promise<OriginalityReport[]> {
  const body = await apiRequest<{ reports: OriginalityReport[] }>(`${workspacePath(workspaceId)}/ai/originality`, { token, ...(signal ? { signal } : {}) });
  return body.reports;
}

export function originalityReport(token: string, workspaceId: string, reportId: string, signal?: AbortSignal): Promise<OriginalityReport> {
  return apiRequest<OriginalityReport>(`${workspacePath(workspaceId)}/ai/originality/${encodeURIComponent(reportId)}`, { token, ...(signal ? { signal } : {}) });
}

/** How a score reads: a band and the words for it. */
export function verdict(score: number): { band: "low" | "moderate" | "high"; label: string } {
  if (score < 10) return { band: "low", label: "Low overlap" };
  if (score < 25) return { band: "moderate", label: "Some overlap" };
  return { band: "high", label: "High overlap" };
}
