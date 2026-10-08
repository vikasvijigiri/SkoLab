package quota

import (
	"context"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"os"
	"testing"
	"time"
)

func TestPostgresQuotaLedgerAndExpiryCleanup(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL required")
		}
		t.Skip("disposable database unavailable")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	key := "test-quota-" + uuid.NewString()
	defer func() { _, _ = pool.Exec(ctx, "DELETE FROM usage_counters WHERE bucket_key=$1", key) }()
	now := time.Now()
	for _, want := range []int{2, 4} {
		count, _, err := incr(ctx, pool, key, 2, now.Add(time.Hour), now)
		if err != nil || count != want {
			t.Fatalf("ledger count=%d error=%v", count, err)
		}
	}
	if err := Sweep(ctx, pool); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count FROM usage_counters WHERE bucket_key=$1", key).Scan(&count); err != nil || count != 4 {
		t.Fatal("cleanup removed live bucket")
	}
	if _, err := pool.Exec(ctx, "UPDATE usage_counters SET expires_at=NOW()-INTERVAL '1 second' WHERE bucket_key=$1", key); err != nil {
		t.Fatal(err)
	}
	if err := Sweep(ctx, pool); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM usage_counters WHERE bucket_key=$1", key).Scan(&count); err != nil || count != 0 {
		t.Fatal("expired bucket retained")
	}
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	if err := Sweep(cancelled, pool); err == nil {
		t.Fatal("cleanup ignored cancellation")
	}
}

func TestQuotaCleanupWithoutPoolIsSafe(t *testing.T) {
	if err := Sweep(context.Background(), nil); err != nil {
		t.Fatal(err)
	}
}

func TestPostgresRefund(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL required")
		}
		t.Skip("disposable database unavailable")
	}
	t.Setenv("USER_QUOTA_ENABLED", "true")
	t.Setenv("USER_QUOTA_HOURLY_UNITS", "8")
	t.Setenv("USER_QUOTA_DAILY_UNITS", "100")
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	uid := "test-refund-" + uuid.NewString()
	defer func() { _, _ = pool.Exec(ctx, "DELETE FROM usage_counters WHERE bucket_key LIKE $1", "q:"+uid+":%") }()

	receipt, _, _, err := Charge(ctx, pool, uid, 4)
	if err != nil {
		t.Fatal(err)
	}
	receipt.Refund(ctx, pool)
	receipt.Refund(ctx, pool) // a repeated refund never drives the ledger negative
	var total int
	if err := pool.QueryRow(ctx, "SELECT COALESCE(SUM(count), 0) FROM usage_counters WHERE bucket_key LIKE $1", "q:"+uid+":%").Scan(&total); err != nil || total != 0 {
		t.Fatalf("ledger after refund = %d (%v)", total, err)
	}
}
