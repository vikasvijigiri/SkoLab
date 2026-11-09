import { apiRequest, ApiError } from "./client";

export type EvidenceStatus = "to_review" | "supported_by_author" | "needs_revision";
export interface EvidenceItem {
  id: string; claim: string; citation_key: string; quote: string; locator: string;
  study_type: string; sample: string; findings: string; limitations: string; relevance: string; status: EvidenceStatus;
}
export interface ReproducibilityNotes {
  data_availability: string; code_availability: string; software: string;
  analysis_steps: string; preregistration: string; notes: string;
}
export interface CitationQuality {
  cited_keys: string[]; bibliography_keys: string[]; unresolved_keys: string[];
  citations_without_evidence: string[]; unused_references: string[];
}
export interface ResearchDocument {
  workspace_id: string; items: EvidenceItem[]; reproducibility: ReproducibilityNotes;
  version: number; updated_at: string | null; role: "owner" | "editor" | "commenter" | "viewer"; quality: CitationQuality;
}
export interface ResearchSave {
  base_version: number; items: EvidenceItem[]; reproducibility: ReproducibilityNotes;
}

export const emptyReproducibility: ReproducibilityNotes = {
  data_availability: "", code_availability: "", software: "", analysis_steps: "", preregistration: "", notes: "",
};

export function getResearch(token: string, workspaceId: string, signal?: AbortSignal): Promise<ResearchDocument> {
  return apiRequest<ResearchDocument>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/research`, { token, ...(signal ? { signal } : {}) });
}

export async function saveResearch(token: string, workspaceId: string, value: ResearchSave): Promise<ResearchDocument> {
  try {
    return await apiRequest<ResearchDocument>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/research`, {
      method: "PUT", token, body: value,
    });
  } catch (error) {
    if (error instanceof ApiError && error.code === "version_conflict") {
      const current = error.details.current_version;
      throw new ResearchVersionConflict(typeof current === "number" ? current : 0);
    }
    throw error;
  }
}

export class ResearchVersionConflict extends Error {
  constructor(readonly currentVersion: number) { super("version_conflict"); this.name = "ResearchVersionConflict"; }
}
