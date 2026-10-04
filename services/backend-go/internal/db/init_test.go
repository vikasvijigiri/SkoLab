package db

import (
	"os"
	"strings"
	"testing"
)

func TestInitDBRequiresURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	if err := InitDB(nil); err == nil || !strings.Contains(err.Error(), "DATABASE_URL") {
		t.Fatalf("got %v, want a missing DATABASE_URL error", err)
	}
}

func TestInitDBRejectsMalformedURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://u:p@h:notaport/d")
	if err := InitDB(nil); err == nil || !strings.Contains(err.Error(), "parsing") {
		t.Fatalf("got %v, want a parse error", err)
	}
}

func TestInitDBRejectsInvalidPoolLimits(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://u:p@127.0.0.1:1/d")
	for _, limits := range [][2]string{{"0", "0"}, {"5", "-1"}, {"2", "3"}} {
		t.Setenv("DB_MAX_CONNS", limits[0])
		t.Setenv("DB_MIN_CONNS", limits[1])
		if err := InitDB(nil); err == nil || !strings.Contains(err.Error(), "pool limits") {
			t.Fatalf("max=%s min=%s: got %v, want a pool limit error", limits[0], limits[1], err)
		}
	}
}

func TestInitDBReportsUnreachableDatabase(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://u:p@127.0.0.1:1/d?connect_timeout=1")
	t.Setenv("DB_MIN_CONNS", "0")
	if err := InitDB(nil); err == nil {
		t.Fatal("expected a connection error")
	}
	if Pool != nil {
		t.Fatal("a failed init must not publish a pool")
	}
}

func TestEnvIntFallsBack(t *testing.T) {
	t.Setenv("SKOLAB_TEST_INT", "abc")
	if got := envInt("SKOLAB_TEST_INT", 7); got != 7 {
		t.Fatalf("got %d, want the default", got)
	}
	t.Setenv("SKOLAB_TEST_INT", "9")
	if got := envInt("SKOLAB_TEST_INT", 7); got != 9 {
		t.Fatalf("got %d, want 9", got)
	}
}

// Against the real database in CI: connects, pings, and closes cleanly.
func TestInitDBConnects(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") != "" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	t.Setenv("DATABASE_URL", strings.Replace(url, "postgres://", "postgresql+asyncpg://", 1))
	t.Setenv("DB_MAX_CONNS", "2")
	t.Setenv("DB_MIN_CONNS", "0")
	if err := InitDB(nil); err != nil {
		t.Fatal(err)
	}
	if Pool == nil || Pool.Config().MaxConns != 2 {
		t.Fatal("pool not configured from the environment")
	}
	CloseDB()
	Pool = nil
	CloseDB() // closing twice is harmless
}
