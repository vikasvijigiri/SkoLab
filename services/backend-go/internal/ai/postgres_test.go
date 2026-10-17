package ai

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/skolab/backend-go/internal/quota"
)

// Integration tests against the real schema. CI provides TEST_DATABASE_URL;
// elsewhere they skip.
func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func seedWorkspace(t *testing.T, pool *pgxpool.Pool) (owner, viewer, workspace string) {
	t.Helper()
	ctx := context.Background()
	owner, viewer, workspace = "test-"+uuid.NewString(), "test-"+uuid.NewString(), uuid.NewString()
	for _, id := range []string{owner, viewer} {
		if _, err := pool.Exec(ctx, "INSERT INTO users (id, display_name) VALUES ($1, 'Test')", id); err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		_, _ = pool.Exec(context.Background(), "DELETE FROM workspaces WHERE id = $1", workspace)
		_, _ = pool.Exec(context.Background(), "DELETE FROM users WHERE id = ANY($1)", []string{owner, viewer})
	})
	if _, err := pool.Exec(ctx, "INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'Paper', now())", workspace, owner); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'viewer', 'active', now())", workspace, viewer); err != nil {
		t.Fatal(err)
	}
	return owner, viewer, workspace
}

func TestPostgres_RolesAndSource(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresReports(pool), context.Background()
	owner, viewer, ws := seedWorkspace(t, pool)
	if role, err := store.Role(ctx, ws, owner); err != nil || role != "owner" {
		t.Fatal(role, err)
	}
	if role, err := store.Role(ctx, ws, viewer); err != nil || role != "viewer" {
		t.Fatal(role, err)
	}
	for _, c := range [][2]string{{ws, "test-stranger"}, {"not-a-uuid", owner}, {uuid.NewString(), owner}} {
		if _, err := store.Role(ctx, c[0], c[1]); !errors.Is(err, ErrNotFound) {
			t.Fatalf("%v: %v", c, err)
		}
	}

	source, version, err := store.Source(ctx, ws)
	if err != nil || source != "" || version != 0 {
		t.Fatalf("unsaved: %q %d %v", source, version, err)
	}
	if _, err := pool.Exec(ctx, "INSERT INTO workspace_documents (workspace_id, source, version, updated_at, updated_by) VALUES ($1, 'MAIN', 3, now(), $2)", ws, owner); err != nil {
		t.Fatal(err)
	}
	for path, content := range map[string]string{"sections/b.tex": "SECOND", "A.tex": "FIRST", "refs.bib": "BIB"} {
		if _, err := pool.Exec(ctx, "INSERT INTO workspace_files (id, workspace_id, path, kind, content, size, content_type, version, created_at, updated_at) VALUES ($1, $2, $3, 'text', $4, 1, 'text/plain', 1, now(), now())", uuid.NewString(), ws, path, content); err != nil {
			t.Fatal(err)
		}
	}
	source, version, err = store.Source(ctx, ws)
	if err != nil || version != 3 || !strings.HasPrefix(source, "MAIN") || strings.Index(source, "FIRST") > strings.Index(source, "SECOND") || strings.Contains(source, "BIB") {
		t.Fatalf("%q %d %v", source, version, err)
	}
}

func TestPostgres_ReportLifecycle(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresReports(pool), context.Background()
	owner, _, ws := seedWorkspace(t, pool)

	report, created, err := store.Create(ctx, ws, owner, 4)
	if err != nil || !created || report.Status != "queued" || report.DocumentVersion != 4 {
		t.Fatalf("%+v %v %v", report, created, err)
	}
	again, created, err := store.Create(ctx, ws, owner, 5)
	if err != nil || created || again.ID != report.ID {
		t.Fatalf("one check at a time: %+v %v %v", again, created, err)
	}
	if err := store.Start(ctx, report.ID); err != nil {
		t.Fatal(err)
	}
	result := Result{Score: 12.5, Words: 80, MatchedWords: 10, Matches: []Match{{Sentence: "s", Overlap: 0.8, SourceID: "W1"}}, Sources: []Source{{ID: "W1", Title: "T"}}}
	if err := store.Finish(ctx, report.ID, result); err != nil {
		t.Fatal(err)
	}
	got, err := store.Get(ctx, ws, report.ID)
	if err != nil || got.Status != "done" || *got.Score != 12.5 || got.Result == nil || got.Result.Matches[0].SourceID != "W1" || got.FinishedAt == nil {
		t.Fatalf("%+v %v", got, err)
	}
	// Failing a finished report changes nothing.
	if err := store.Fail(ctx, report.ID, "late"); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.Get(ctx, ws, report.ID); got.Status != "done" {
		t.Fatalf("%+v", got)
	}
	if _, err := store.Get(ctx, uuid.NewString(), report.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}

	second, _, _ := store.Create(ctx, ws, owner, 6)
	if err := store.Fail(ctx, second.ID, strings.Repeat("x", 300)); err != nil {
		t.Fatal(err)
	}
	failed, _ := store.Get(ctx, ws, second.ID)
	if failed.Status != "failed" || len(*failed.Error) != 200 {
		t.Fatalf("%+v", failed)
	}
	list, err := store.List(ctx, ws, 10)
	if err != nil || len(list) != 2 || list[0].ID != second.ID {
		t.Fatalf("newest first: %+v %v", list, err)
	}
}

func TestPostgres_OnlyTheNewestReportsAreKept(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresReports(pool), context.Background()
	owner, _, ws := seedWorkspace(t, pool)
	for i := 0; i < keepReports+3; i++ {
		r, _, err := store.Create(ctx, ws, owner, i)
		if err != nil {
			t.Fatal(err)
		}
		if err := store.Fail(ctx, r.ID, "done"); err != nil {
			t.Fatal(err)
		}
	}
	var n int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM originality_reports WHERE workspace_id = $1", ws).Scan(&n); err != nil || n != keepReports {
		t.Fatalf("%d %v", n, err)
	}
}

func TestPostgres_InterruptedChecksAreFailedAndStaleOnesDoNotBlock(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresReports(pool), context.Background()
	owner, _, ws := seedWorkspace(t, pool)
	old, _, _ := store.Create(ctx, ws, owner, 1)
	if _, err := pool.Exec(ctx, "UPDATE originality_reports SET created_at = created_at - interval '1 hour' WHERE id = $1", old.ID); err != nil {
		t.Fatal(err)
	}
	// A stale queued report does not block a new one.
	fresh, created, err := store.Create(ctx, ws, owner, 2)
	if err != nil || !created || fresh.ID == old.ID {
		t.Fatalf("%+v %v %v", fresh, created, err)
	}
	n, err := store.Interrupted(ctx, 5*time.Minute)
	if err != nil || n < 1 {
		t.Fatalf("%d %v", n, err)
	}
	if got, _ := store.Get(ctx, ws, old.ID); got.Status != "failed" || !strings.Contains(*got.Error, "restart") {
		t.Fatalf("%+v", got)
	}
	if got, _ := store.Get(ctx, ws, fresh.ID); got.Status != "queued" {
		t.Fatalf("a fresh check is left alone: %+v", got)
	}
}

func TestPostgres_AllowancesSpendRefundAndStop(t *testing.T) {
	pool := testPool(t)
	ctx, b := context.Background(), LedgerBudgets{Pool: pool}
	key := "test:ai:" + uuid.NewString()
	t.Cleanup(func() {
		_, _ = pool.Exec(context.Background(), "DELETE FROM usage_counters WHERE bucket_key LIKE $1", key+"%")
	})
	left, refund, err := b.Charge(ctx, key, 1, 2)
	if err != nil || left != 1 {
		t.Fatal(left, err)
	}
	refund()
	if b.Left(ctx, key, 2) != 2 {
		t.Fatal("refund")
	}
	for i := 0; i < 2; i++ {
		if _, _, err := b.Charge(ctx, key, 1, 2); err != nil {
			t.Fatal(err)
		}
	}
	var exceeded *quota.Exceeded
	if _, _, err := b.Charge(ctx, key, 1, 2); !errors.As(err, &exceeded) || exceeded.RetryAfter <= 0 {
		t.Fatal(err)
	}
	if b.Left(ctx, key, 2) != 0 {
		t.Fatal("an over-limit charge must not stay charged")
	}
}

func TestNilPoolStoreIsUnavailable(t *testing.T) {
	store, ctx := NewPostgresReports(nil), context.Background()
	if _, err := store.Role(ctx, uuid.NewString(), "u"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, _, err := store.Source(ctx, "w"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, _, err := store.Create(ctx, "w", "u", 1); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.Get(ctx, "w", "r"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.List(ctx, "w", 1); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if err := store.Start(ctx, "r"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.Interrupted(ctx, time.Minute); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestMetricsCountRequestsProvidersAndEdits(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	if err := UseMeter(sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader)).Meter("test")); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instruments.Store(nil) })
	ctx := context.Background()
	countRequest(ctx, "improve", "ok")
	countProvider(ctx, "model", "ok", 2*time.Second)
	countEdits(ctx, 3, []string{refusedPlaceholders, refusedNotFound})
	var data metricdata.ResourceMetrics
	if err := reader.Collect(ctx, &data); err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, scope := range data.ScopeMetrics {
		for _, m := range scope.Metrics {
			seen[m.Name] = true
		}
	}
	for _, name := range []string{"skolab.ai.requests", "skolab.ai.provider.calls", "skolab.ai.provider.duration", "skolab.ai.edits"} {
		if !seen[name] {
			t.Fatalf("missing %s in %v", name, seen)
		}
	}
}

func TestFromEnv(t *testing.T) {
	t.Setenv("GROQ_API", "k")
	t.Setenv("OPENALEX_API_KEY", " oa ")
	t.Setenv("AI_IMPROVE_DAILY_PER_USER", "7")
	t.Setenv("AI_SEARCH_DAILY_LIMIT", "nonsense")
	c := FromEnv()
	if !c.ImproveEnabled || !c.OriginalityEnabled || c.OpenAlexKey != "oa" || c.ImprovePerUser != 7 || c.SearchDaily != 800 || c.MaxProbes != 10 {
		t.Fatalf("%+v", c)
	}
	t.Setenv("GROQ_API", "")
	t.Setenv("OPENALEX_API_KEY", "")
	if c := FromEnv(); c.ImproveEnabled || c.OriginalityEnabled {
		t.Fatalf("%+v", c)
	}
}
