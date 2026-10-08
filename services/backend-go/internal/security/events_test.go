package security

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

// capture swaps the default logger and the meter for the test.
func capture(t *testing.T) (*bytes.Buffer, *sdkmetric.ManualReader) {
	t.Helper()
	var logs bytes.Buffer
	saved := slog.Default()
	slog.SetDefault(slog.New(slog.NewJSONHandler(&logs, nil)))
	reader := sdkmetric.NewManualReader()
	if err := UseMeter(sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader)).Meter("t")); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { slog.SetDefault(saved); counter.Store(nil); store.Store(nil) })
	return &logs, reader
}

func ginContext(user string) *gin.Context {
	gin.SetMode(gin.TestMode)
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest(http.MethodDelete, "/x", nil)
	c.Request.Header.Set("CF-Connecting-IP", "203.0.113.7")
	c.Request.Header.Set("X-Forwarded-For", "6.6.6.6") // client-controlled, never trusted
	c.Set("user_id", user)
	c.Set("request_id", "req-1")
	return c
}

func counts(t *testing.T, reader *sdkmetric.ManualReader) map[string]int64 {
	t.Helper()
	var data metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &data); err != nil {
		t.Fatal(err)
	}
	out := map[string]int64{}
	for _, scope := range data.ScopeMetrics {
		for _, m := range scope.Metrics {
			for _, p := range m.Data.(metricdata.Sum[int64]).DataPoints {
				event, _ := p.Attributes.Value("event")
				outcome, _ := p.Attributes.Value("outcome")
				out[event.AsString()+"/"+outcome.AsString()] += p.Value
			}
		}
	}
	return out
}

func TestRecordLogsAndCountsWithTrustedContext(t *testing.T) {
	logs, reader := capture(t)
	Record(ginContext("ada"), Event{Name: AuthTokenInvalid, Outcome: Denied, Reason: "token_invalid"})
	Record(ginContext("ada"), Event{Name: AuthTokenInvalid, Outcome: Denied})

	line := logs.String()
	for _, want := range []string{`"msg":"security_event"`, `"event":"auth.token_invalid"`, `"outcome":"denied"`,
		`"user_id":"ada"`, `"ip":"203.0.113.7"`, `"request_id":"req-1"`, `"level":"WARN"`} {
		if !strings.Contains(line, want) {
			t.Fatalf("log missing %s: %s", want, line)
		}
	}
	if strings.Contains(line, "6.6.6.6") {
		t.Fatal("a client-supplied X-Forwarded-For must never be recorded as the IP")
	}
	if got := counts(t, reader)["auth.token_invalid/denied"]; got != 2 {
		t.Fatalf("counter = %d, want 2", got)
	}
}

func TestAuditWithoutAStoreStillRecords(t *testing.T) {
	logs, reader := capture(t)
	Audit(ginContext("ada"), Event{Name: AccountDeleted, Outcome: Allowed})
	if !strings.Contains(logs.String(), `"event":"account.deleted"`) || counts(t, reader)["account.deleted/allowed"] != 1 {
		t.Fatalf("logs = %s", logs)
	}
}

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

func TestAuditAppendsADurableRowThatOutlivesTheAccount(t *testing.T) {
	pool := testPool(t)
	_, reader := capture(t)
	UseStore(pool)
	actor := "deleted-" + uuid.NewString() // no users row exists: no foreign key
	t.Cleanup(func() {
		_, _ = pool.Exec(context.Background(), "DELETE FROM security_audit_log WHERE actor_id = $1", actor)
	})

	Audit(ginContext(actor), Event{Name: WorkspaceDeleted, Outcome: Allowed, WorkspaceID: "ws-9"})

	var event, outcome, workspace, ip, requestID string
	err := pool.QueryRow(context.Background(),
		"SELECT event, outcome, workspace_id, ip, request_id FROM security_audit_log WHERE actor_id = $1", actor).
		Scan(&event, &outcome, &workspace, &ip, &requestID)
	if err != nil {
		t.Fatal(err)
	}
	if event != WorkspaceDeleted || outcome != Allowed || workspace != "ws-9" || ip != "203.0.113.7" || requestID != "req-1" {
		t.Fatalf("row = %s %s %s %s %s", event, outcome, workspace, ip, requestID)
	}
	if counts(t, reader)["audit.write_failed/failed"] != 0 {
		t.Fatal("unexpected audit write failure")
	}
}

func TestFailedAuditWriteIsReportedNotFatal(t *testing.T) {
	pool := testPool(t)
	_, reader := capture(t)
	broken, err := pgxpool.New(context.Background(), pool.Config().ConnString())
	if err != nil {
		t.Fatal(err)
	}
	broken.Close() // every write now fails
	UseStore(broken)
	Audit(ginContext("ada"), Event{Name: WorkspaceCreated, Outcome: Allowed})
	if counts(t, reader)["audit.write_failed/failed"] != 1 {
		t.Fatal("a failed audit write must be counted")
	}
}

func TestAuditRetentionDefaultsToAYearWithAFloor(t *testing.T) {
	day := 24 * time.Hour
	for value, want := range map[string]time.Duration{"": 365 * day, "junk": 365 * day, "90": 90 * day, "1": 30 * day, "-5": 30 * day} {
		t.Setenv("SECURITY_AUDIT_RETENTION_DAYS", value)
		if got := AuditRetention(); got != want {
			t.Fatalf("%q: %v, want %v", value, got, want)
		}
	}
}

func TestSweepAuditRemovesOnlyExpiredRows(t *testing.T) {
	if err := SweepAudit(context.Background(), nil, time.Hour); err != nil {
		t.Fatal(err)
	}
	pool := testPool(t)
	ctx := context.Background()
	actor := "retention-" + uuid.NewString()
	t.Cleanup(func() { _, _ = pool.Exec(ctx, "DELETE FROM security_audit_log WHERE actor_id = $1", actor) })
	for _, age := range []string{"400 days", "1 day"} {
		if _, err := pool.Exec(ctx, `INSERT INTO security_audit_log (occurred_at, event, outcome, actor_id)
			VALUES (NOW() AT TIME ZONE 'UTC' - $1::interval, 'account.deleted', 'allowed', $2)`, age, actor); err != nil {
			t.Fatal(err)
		}
	}
	if err := SweepAudit(ctx, pool, 365*24*time.Hour); err != nil {
		t.Fatal(err)
	}
	var kept int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM security_audit_log WHERE actor_id = $1", actor).Scan(&kept); err != nil || kept != 1 {
		t.Fatalf("rows kept = %d (%v), want only the recent one", kept, err)
	}
}
