import { apiRequest } from "./client";
import type { AIStatus, ImproveGoal, ImproveResult, OriginalityDatabase, OriginalityReport, OriginalitySourceName } from "./editorTypes";

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

/** How an AI-writing estimate reads. A model's estimate, never proof. */
export function writingVerdict(score: number): { band: "low" | "moderate" | "high"; label: string } {
  // "Not detected", never "not AI": the detector can miss the newest models' writing.
  if (score < 10) return { band: "low", label: "No AI writing detected" };
  if (score < 40) return { band: "moderate", label: "Some passages read as AI-written" };
  return { band: "high", label: "Much of the text reads as AI-written" };
}

/** What each source is, in words. */
export const SOURCE_LABELS: Record<OriginalitySourceName, { name: string; covers: string }> = {
  openalex: { name: "OpenAlex", covers: "250M+ journal articles, conference papers and books (titles and abstracts)" },
  semantic_scholar: { name: "Semantic Scholar", covers: "full texts of open-access papers, including arXiv and PubMed Central" },
  core: { name: "CORE", covers: "open-access full texts from 10,000+ university and research repositories" },
  europe_pmc: { name: "Europe PMC", covers: "life-science literature, phrases matched in open-access full texts" },
  arxiv: { name: "arXiv", covers: "the newest preprints in physics, mathematics, computer science and more" },
  web: { name: "The web", covers: "web pages, including Wikipedia, course notes and publishers' sites" },
};

export const DATABASE_LABELS: Record<OriginalityDatabase, string> = {
  publications: "Publications",
  full_text: "Open-access full texts",
  preprints: "Preprints",
  web: "Web",
};
