package ai

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/document"
	"github.com/skolab/backend-go/internal/quota"
)

var (
	ErrNotFound    = errors.New("not found")
	ErrUnavailable = errors.New("store unavailable")
)

const (
	keepReports   = 20              // per workspace
	checkTimeout  = 2 * time.Minute // one check, all searches included
	staleAfter    = 5 * time.Minute // a queued or running report older than this was interrupted
	maxSourceSize = 400_000         // characters of LaTeX checked
	listReports   = 10              // reports listed per workspace
)

// Report is one originality check.
type Report struct {
	ID              string     `json:"id"`
	WorkspaceID     string     `json:"workspace_id"`
	Status          string     `json:"status"` // queued, running, done, failed
	DocumentVersion int        `json:"document_version"`
	Score           *float64   `json:"score"`
	Result          *Result    `json:"result"`
	Error           *string    `json:"error"`
	CreatedAt       time.Time  `json:"created_at"`
	FinishedAt      *time.Time `json:"finished_at"`
}

// ReportStore keeps reports and reads what they need.
type ReportStore interface {
	// Role is the caller's role in the workspace, or ErrNotFound.
	Role(ctx context.Context, workspaceID, userID string) (string, error)
	// Source is the workspace's LaTeX (main.tex, then its other .tex files)
	// and main.tex's version.
	Source(ctx context.Context, workspaceID string) (string, int, error)
	// Create queues a report, unless one is already queued or running for
	// the workspace: then that one is answered with created false.
	Create(ctx context.Context, workspaceID, userID string, version int) (Report, bool, error)
	Get(ctx context.Context, workspaceID, id string) (Report, error)
	List(ctx context.Context, workspaceID string, limit int) ([]Report, error)
	Start(ctx context.Context, id string) error
	Finish(ctx context.Context, id string, result Result) error
	Fail(ctx context.Context, id, reason string) error
	// Interrupted fails reports a restart left queued or running.
	Interrupted(ctx context.Context, olderThan time.Duration) (int64, error)
}

// ── Runner ─────────────────────────────────────────────────────────────────

// Runner runs queued checks in the background, a few at a time.
type Runner struct {
	store     ReportStore
	search    Searcher
	maxProbes int
	slots     chan struct{}
}

// NewRunner runs up to parallel checks at once with search.
func NewRunner(store ReportStore, search Searcher, maxProbes, parallel int) *Runner {
	return &Runner{store: store, search: search, maxProbes: maxProbes, slots: make(chan struct{}, max(parallel, 1))}
}

// Submit starts report r in the background. done (when not nil) is called
// once it has finished, for tests.
func (r *Runner) Submit(report Report, done func()) {
	go func() {
		if done != nil {
			defer done()
		}
		r.slots <- struct{}{}
		defer func() { <-r.slots }()
		ctx, cancel := context.WithTimeout(context.Background(), checkTimeout)
		defer cancel()
		defer func() {
			if v := recover(); v != nil {
				slog.Error("ai: originality check panicked", "report", report.ID, "panic", fmt.Sprint(v))
				_ = r.store.Fail(context.Background(), report.ID, "The check stopped unexpectedly. Try again.")
			}
		}()
		r.run(ctx, report)
	}()
}

func (r *Runner) run(ctx context.Context, report Report) {
	started := time.Now()
	if err := r.store.Start(ctx, report.ID); err != nil {
		slog.Error("ai: could not start an originality check", "report", report.ID, "err", err)
		return
	}
	source, _, err := r.store.Source(ctx, report.WorkspaceID)
	if err == nil {
		var result Result
		if result, err = Check(ctx, source, r.search, r.maxProbes); err == nil {
			if err = r.store.Finish(ctx, report.ID, result); err == nil {
				outcome := "ok"
				if result.Partial {
					outcome = "partial"
				}
				countRequest(ctx, "originality", outcome)
				slog.Info("ai: originality check finished", "report", report.ID, "score", result.Score, "probes", result.Probes, "sources", result.SourcesChecked, "took_ms", time.Since(started).Milliseconds())
				return
			}
		}
	}
	reason := "The check could not be completed. Try again."
	switch {
	case errors.Is(err, ErrTooShort):
		reason = "There is too little prose to check yet: write at least 30 words."
		countRequest(ctx, "originality", "too_short")
	case errors.Is(err, context.DeadlineExceeded):
		reason = "The check took too long. Try again."
		countRequest(ctx, "originality", "timeout")
	default:
		slog.Warn("ai: originality check failed", "report", report.ID, "err", err)
		countRequest(ctx, "originality", "failed")
	}
	if err := r.store.Fail(context.WithoutCancel(ctx), report.ID, reason); err != nil {
		slog.Error("ai: could not record a failed check", "report", report.ID, "err", err)
	}
}

// BudgetedSearch charges one unit of the shared search budget before each
// OpenAlex search, and counts and times every call.
func BudgetedSearch(b Budgets, limit int, o OpenAlex) Searcher {
	return func(ctx context.Context, query string) ([]Work, error) {
		_, refund, err := b.Charge(ctx, searchKey, 1, limit)
		var exceeded *quota.Exceeded
		if errors.As(err, &exceeded) {
			countProvider(ctx, "search", "over_budget", 0)
			return nil, ErrBudget
		}
		if err != nil {
			return nil, err
		}
		started := time.Now()
		works, err := o.Search(ctx, query)
		switch {
		case errors.Is(err, ErrBudget):
			countProvider(ctx, "search", "busy", time.Since(started))
		case err != nil:
			refund()
			countProvider(ctx, "search", "error", time.Since(started))
		default:
			countProvider(ctx, "search", "ok", time.Since(started))
		}
		return works, err
	}
}

// ── Handlers ───────────────────────────────────────────────────────────────

func (h *handlers) startCheck(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	ctx := c.Request.Context()
	if len(c.Request.URL.Query()) > 0 {
		fail(c, http.StatusBadRequest, "unknown_parameter", "This request takes no query parameters")
		return
	}
	if !h.workspaceRole(c, uid, true) {
		return
	}
	if !h.Config.OriginalityEnabled {
		countRequest(ctx, "originality", "unconfigured")
		fail(c, http.StatusServiceUnavailable, "ai_unconfigured", "The originality check is not set up yet")
		return
	}
	if h.Budgets.Left(ctx, searchKey, h.Config.SearchDaily) < 1 {
		countRequest(ctx, "originality", "over_budget")
		c.Header("Retry-After", "3600")
		fail(c, http.StatusTooManyRequests, "ai_capacity", "SkoLab has used today's originality-check capacity. Try again tomorrow.")
		return
	}
	workspaceID := c.Param("id")
	_, version, err := h.Reports.Source(ctx, workspaceID)
	if err != nil {
		storeFailure(c, err)
		return
	}
	// A check already under way is answered as it is, without charging.
	left, refund, ok := spend(c, h.Budgets, uid, "originality", h.Config.OriginalityPerUser, "", 0)
	if !ok {
		countRequest(ctx, "originality", "over_budget")
		return
	}
	report, created, err := h.Reports.Create(ctx, workspaceID, uid, version)
	if err != nil {
		refund()
		storeFailure(c, err)
		return
	}
	if !created {
		refund()
		left++
	} else {
		h.Runner.Submit(report, nil)
	}
	c.Header("Location", "/api/v1/workspaces/"+workspaceID+"/ai/originality/"+report.ID)
	c.JSON(http.StatusAccepted, gin.H{"report": report, "left_today": left})
}

func (h *handlers) listChecks(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	if len(c.Request.URL.Query()) > 0 {
		fail(c, http.StatusBadRequest, "unknown_parameter", "This request takes no query parameters")
		return
	}
	if !h.workspaceRole(c, uid, false) {
		return
	}
	reports, err := h.Reports.List(c.Request.Context(), c.Param("id"), listReports)
	if err != nil {
		storeFailure(c, err)
		return
	}
	if reports == nil {
		reports = []Report{}
	}
	c.JSON(http.StatusOK, gin.H{"reports": reports})
}

func (h *handlers) getCheck(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	if len(c.Request.URL.Query()) > 0 {
		fail(c, http.StatusBadRequest, "unknown_parameter", "This request takes no query parameters")
		return
	}
	if !h.workspaceRole(c, uid, false) {
		return
	}
	id := c.Param("report")
	if _, err := uuid.Parse(id); err != nil {
		fail(c, http.StatusNotFound, "not_found", "Report not found")
		return
	}
	report, err := h.Reports.Get(c.Request.Context(), c.Param("id"), id)
	if err != nil {
		storeFailure(c, err)
		return
	}
	c.JSON(http.StatusOK, report)
}

func storeFailure(c *gin.Context, err error) {
	if errors.Is(err, ErrNotFound) {
		fail(c, http.StatusNotFound, "not_found", "Not found")
		return
	}
	fail(c, http.StatusServiceUnavailable, "unavailable", "AI features are temporarily unavailable")
}

// ── Postgres ───────────────────────────────────────────────────────────────

const queryTimeout = 3 * time.Second

type postgresReports struct{ pool *pgxpool.Pool }

// NewPostgresReports is the production ReportStore.
func NewPostgresReports(pool *pgxpool.Pool) ReportStore { return &postgresReports{pool} }

func unavailable(err error) error { return errors.Join(ErrUnavailable, err) }

func (s *postgresReports) Role(ctx context.Context, workspaceID, userID string) (string, error) {
	if s.pool == nil {
		return "", ErrUnavailable
	}
	if _, err := uuid.Parse(workspaceID); err != nil {
		return "", ErrNotFound
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	role, err := document.CallerRole(ctx, s.pool, workspaceID, userID)
	if errors.Is(err, document.ErrNotFound) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", unavailable(err)
	}
	return role, nil
}

func (s *postgresReports) Source(ctx context.Context, workspaceID string) (string, int, error) {
	if s.pool == nil {
		return "", 0, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	var source string
	var version int
	err := s.pool.QueryRow(ctx, `SELECT source, version FROM workspace_documents WHERE workspace_id = $1`, workspaceID).Scan(&source, &version)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return "", 0, unavailable(err)
	}
	var b strings.Builder
	b.WriteString(source)
	rows, err := s.pool.Query(ctx, `SELECT content FROM workspace_files
		WHERE workspace_id = $1 AND kind = 'text' AND lower(path) LIKE '%.tex' ORDER BY lower(path)`, workspaceID)
	if err != nil {
		return "", 0, unavailable(err)
	}
	defer rows.Close()
	for rows.Next() && b.Len() < maxSourceSize {
		var content *string
		if err := rows.Scan(&content); err != nil {
			return "", 0, unavailable(err)
		}
		if content != nil {
			// Included files have no preamble; give them a body to keep.
			b.WriteString("\n\\begin{document}\n")
			b.WriteString(*content)
			b.WriteString("\n\\end{document}\n")
		}
	}
	if err := rows.Err(); err != nil {
		return "", 0, unavailable(err)
	}
	text := b.String()
	if len(text) > maxSourceSize {
		text = text[:maxSourceSize]
	}
	return text, version, nil
}

const reportColumns = `id, workspace_id, status, document_version, score, result, error, created_at, finished_at`

func scanReport(row pgx.Row) (Report, error) {
	var r Report
	var raw []byte
	if err := row.Scan(&r.ID, &r.WorkspaceID, &r.Status, &r.DocumentVersion, &r.Score, &raw, &r.Error, &r.CreatedAt, &r.FinishedAt); err != nil {
		return Report{}, err
	}
	r.CreatedAt = r.CreatedAt.UTC()
	if r.FinishedAt != nil {
		utc := r.FinishedAt.UTC()
		r.FinishedAt = &utc
	}
	if len(raw) > 0 {
		var result Result
		if err := json.Unmarshal(raw, &result); err == nil {
			r.Result = &result
		}
	}
	return r, nil
}

func (s *postgresReports) Create(ctx context.Context, workspaceID, userID string, version int) (Report, bool, error) {
	if s.pool == nil {
		return Report{}, false, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Report{}, false, unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	// One check at a time per workspace: serialize on the workspace row.
	if _, err := tx.Exec(ctx, `SELECT 1 FROM workspaces WHERE id = $1 FOR UPDATE`, workspaceID); err != nil {
		return Report{}, false, unavailable(err)
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	existing, err := scanReport(tx.QueryRow(ctx, `SELECT `+reportColumns+` FROM originality_reports
		WHERE workspace_id = $1 AND status IN ('queued', 'running') AND created_at > $2
		ORDER BY created_at DESC LIMIT 1`, workspaceID, now.Add(-staleAfter)))
	if err == nil {
		return existing, false, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Report{}, false, unavailable(err)
	}
	report, err := scanReport(tx.QueryRow(ctx, `INSERT INTO originality_reports (id, workspace_id, requested_by, status, document_version, created_at)
		VALUES ($1, $2, $3, 'queued', $4, $5) RETURNING `+reportColumns, uuid.NewString(), workspaceID, userID, version, now))
	if err != nil {
		return Report{}, false, unavailable(err)
	}
	if _, err := tx.Exec(ctx, `DELETE FROM originality_reports WHERE workspace_id = $1 AND id NOT IN (
		SELECT id FROM originality_reports WHERE workspace_id = $1 ORDER BY created_at DESC LIMIT $2)`, workspaceID, keepReports); err != nil {
		return Report{}, false, unavailable(err)
	}
	if err := tx.Commit(ctx); err != nil {
		return Report{}, false, unavailable(err)
	}
	return report, true, nil
}

func (s *postgresReports) Get(ctx context.Context, workspaceID, id string) (Report, error) {
	if s.pool == nil {
		return Report{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	report, err := scanReport(s.pool.QueryRow(ctx, `SELECT `+reportColumns+` FROM originality_reports WHERE workspace_id = $1 AND id = $2`, workspaceID, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Report{}, ErrNotFound
	}
	if err != nil {
		return Report{}, unavailable(err)
	}
	return report, nil
}

func (s *postgresReports) List(ctx context.Context, workspaceID string, limit int) ([]Report, error) {
	if s.pool == nil {
		return nil, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	rows, err := s.pool.Query(ctx, `SELECT `+reportColumns+` FROM originality_reports WHERE workspace_id = $1 ORDER BY created_at DESC LIMIT $2`, workspaceID, limit)
	if err != nil {
		return nil, unavailable(err)
	}
	defer rows.Close()
	reports := []Report{}
	for rows.Next() {
		report, err := scanReport(rows)
		if err != nil {
			return nil, unavailable(err)
		}
		reports = append(reports, report)
	}
	if err := rows.Err(); err != nil {
		return nil, unavailable(err)
	}
	return reports, nil
}

func (s *postgresReports) exec(ctx context.Context, sql string, args ...any) error {
	if s.pool == nil {
		return ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	if _, err := s.pool.Exec(ctx, sql, args...); err != nil {
		return unavailable(err)
	}
	return nil
}

func now() time.Time { return time.Now().UTC().Truncate(time.Microsecond) }

func (s *postgresReports) Start(ctx context.Context, id string) error {
	return s.exec(ctx, `UPDATE originality_reports SET status = 'running', started_at = $2 WHERE id = $1 AND status = 'queued'`, id, now())
}

func (s *postgresReports) Finish(ctx context.Context, id string, result Result) error {
	raw, err := json.Marshal(result)
	if err != nil {
		return err
	}
	// As text: with the production pool's QueryExecModeExec, Postgres is not
	// asked for parameter types, and a []byte would be sent as bytea.
	return s.exec(ctx, `UPDATE originality_reports SET status = 'done', score = $2, result = $3::jsonb, finished_at = $4 WHERE id = $1`, id, result.Score, string(raw), now())
}

func (s *postgresReports) Fail(ctx context.Context, id, reason string) error {
	if len(reason) > 200 {
		reason = reason[:200]
	}
	return s.exec(ctx, `UPDATE originality_reports SET status = 'failed', error = $2, finished_at = $3 WHERE id = $1 AND status IN ('queued', 'running')`, id, reason, now())
}

func (s *postgresReports) Interrupted(ctx context.Context, olderThan time.Duration) (int64, error) {
	if s.pool == nil {
		return 0, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tag, err := s.pool.Exec(ctx, `UPDATE originality_reports SET status = 'failed', error = 'Interrupted by a restart. Run the check again.', finished_at = $2
		WHERE status IN ('queued', 'running') AND created_at < $1`, now().Add(-olderThan), now())
	if err != nil {
		return 0, unavailable(err)
	}
	return tag.RowsAffected(), nil
}
