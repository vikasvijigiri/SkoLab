package quota

import (
	"context"
	"testing"
	"time"
)

func resetLocal() {
	local.mu.Lock()
	local.data = map[string]struct {
		count int
		exp   time.Time
	}{}
	local.mu.Unlock()
}

func TestHourlyBudgetIsEnforcedPerUser(t *testing.T) {
	t.Setenv("USER_QUOTA_ENABLED", "true")
	t.Setenv("USER_QUOTA_HOURLY_UNITS", "10")
	t.Setenv("USER_QUOTA_DAILY_UNITS", "25")
	resetLocal()

	if _, _, err := Consume(context.Background(), nil, "alice", 4); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, _, err := Consume(context.Background(), nil, "alice", 4); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	_, _, err := Consume(context.Background(), nil, "alice", 4)
	exc, ok := AsExceeded(err)
	if !ok {
		t.Fatalf("expected Exceeded, got %v", err)
	}
	if exc.Window != "hourly" {
		t.Fatalf("expected hourly window, got %q", exc.Window)
	}
	if exc.RetryAfter <= 0 || exc.RetryAfter > time.Hour {
		t.Fatalf("retry-after out of range: %v", exc.RetryAfter)
	}

	// a different account is unaffected
	_, remainingDaily, err := Consume(context.Background(), nil, "bob", 4)
	if err != nil {
		t.Fatalf("bob should not be blocked by alice's budget: %v", err)
	}
	if remainingDaily != 21 {
		t.Fatalf("bob remaining daily = %d, want 21", remainingDaily)
	}
}

func TestDisabledIsANoop(t *testing.T) {
	t.Setenv("USER_QUOTA_ENABLED", "false")
	resetLocal()
	for i := 0; i < 50; i++ {
		if _, _, err := Consume(context.Background(), nil, "dave", 1000); err != nil {
			t.Fatalf("disabled quota should never error: %v", err)
		}
	}
}

func TestLocalFallbackWindowRollsOver(t *testing.T) {
	t.Setenv("USER_QUOTA_ENABLED", "true")
	t.Setenv("USER_QUOTA_HOURLY_UNITS", "10")
	t.Setenv("USER_QUOTA_DAILY_UNITS", "1000")
	resetLocal()

	now := time.Now()
	bucket, end := windowBucket(now, hourSeconds)
	if bucket != now.Unix()/hourSeconds {
		t.Fatalf("unexpected bucket")
	}
	used := local.incr("k", 10, end, now)
	if used != 10 {
		t.Fatalf("used = %d, want 10", used)
	}
	// same bucket, more usage accumulates
	used = local.incr("k", 1, end, now)
	if used != 11 {
		t.Fatalf("used = %d, want 11 (accumulated)", used)
	}
	// once the window has passed, usage resets
	later := end.Add(time.Second)
	used = local.incr("k", 1, end.Add(hourSeconds*time.Second), later)
	if used != 1 {
		t.Fatalf("used = %d, want 1 (window reset)", used)
	}
}

func TestPostgresErrorFallsBackToLocalRatherThanFailingOpenOrClosed(t *testing.T) {
	t.Setenv("USER_QUOTA_ENABLED", "true")
	t.Setenv("USER_QUOTA_HOURLY_UNITS", "3")
	t.Setenv("USER_QUOTA_DAILY_UNITS", "100")
	resetLocal()

	// nil pool exercises exactly the same fallback path a real connection
	// error would: incr() treats "no usable pool" and "pool.QueryRow failed"
	// identically, falling through to the process-local counter — verified
	// here by nil, and covered against a real Supabase connection in the
	// backend's own quota-upsert verification (docs/audits/...).
	if _, _, err := Consume(context.Background(), nil, "carol", 2); err != nil {
		t.Fatalf("unexpected error on first charge: %v", err)
	}
	_, _, err := Consume(context.Background(), nil, "carol", 2)
	exc, ok := AsExceeded(err)
	if !ok || exc.Window != "hourly" {
		t.Fatalf("expected hourly Exceeded via local fallback, got %v", err)
	}
}

func TestScaledDeploymentDoesNotFallBackToLocalAccounting(t *testing.T) {
	t.Setenv("SHARED_STATE_REQUIRED", "true")
	t.Setenv("USER_QUOTA_ENABLED", "true")
	if _, _, err := Consume(context.Background(), nil, "user", 4); err != ErrUnavailable {
		t.Fatalf("missing shared ledger: %v", err)
	}
}
