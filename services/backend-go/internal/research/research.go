// Package research stores the auditable evidence and reproducibility notes
// attached to a collaborative manuscript.
package research

import (
	"context"
	"errors"
	"time"
)

var (
	ErrNotFound    = errors.New("workspace not found")
	ErrReadOnly    = errors.New("workspace is read only")
	ErrUnavailable = errors.New("research store unavailable")
)

// Item records the researcher's own assessment of a source. SkoLab never
// infers that an excerpt proves a claim; status is explicitly author-set.
type Item struct {
	ID          string `json:"id"`
	Claim       string `json:"claim"`
	CitationKey string `json:"citation_key"`
	Quote       string `json:"quote"`
	Locator     string `json:"locator"`
	StudyType   string `json:"study_type"`
	Sample      string `json:"sample"`
	Findings    string `json:"findings"`
	Limitations string `json:"limitations"`
	Relevance   string `json:"relevance"`
	Status      string `json:"status"`
}

// Reproducibility records guide users through disclosure; fields are not
// interpreted as guarantees that a study can be reproduced.
type Reproducibility struct {
	DataAvailability string `json:"data_availability"`
	CodeAvailability string `json:"code_availability"`
	Software         string `json:"software"`
	AnalysisSteps    string `json:"analysis_steps"`
	Preregistration  string `json:"preregistration"`
	Notes            string `json:"notes"`
}

type Document struct {
	WorkspaceID     string          `json:"workspace_id"`
	Items           []Item          `json:"items"`
	Reproducibility Reproducibility `json:"reproducibility"`
	Version         int             `json:"version"`
	UpdatedAt       *time.Time      `json:"updated_at"`
	Role            string          `json:"role"`
	Quality         Quality         `json:"quality"`
}

// Quality reports mechanical coverage issues only; it does not infer whether
// a cited source supports a claim.
type Quality struct {
	CitedKeys                []string `json:"cited_keys"`
	BibliographyKeys         []string `json:"bibliography_keys"`
	UnresolvedKeys           []string `json:"unresolved_keys"`
	CitationsWithoutEvidence []string `json:"citations_without_evidence"`
	UnusedReferences         []string `json:"unused_references"`
}

type Store interface {
	Get(context.Context, string, string) (Document, error)
	Save(context.Context, string, string, Document, int) (Document, error)
}
