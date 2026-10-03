package db

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

var Pool *pgxpool.Pool

// InitDB initializes the high-performance connection pool to PostgreSQL using pgx.
// tracer (may be nil) receives one span per statement.
// No local fallback DSN — Supabase is the only database backend, in every
// environment (2026-09-26, "Supabase only" pass). DATABASE_URL must be set.
func InitDB(tracer pgx.QueryTracer) error {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		return fmt.Errorf("DATABASE_URL is not set — point it at your Supabase project")
	}

	config, err := pgxpool.ParseConfig(NormalizeURL(dbURL))
	if err != nil {
		return fmt.Errorf("error parsing database connection string: %v", err)
	}

	// Sized for the Supabase free transaction pooler (pgBouncer, ~60 shared
	// server connections total, shared with the Python service). Overridable
	// via DB_MAX_CONNS / DB_MIN_CONNS.
	config.MaxConns = int32(envInt("DB_MAX_CONNS", 15))
	config.MinConns = int32(envInt("DB_MIN_CONNS", 3))
	config.MaxConnLifetime = 30 * time.Minute
	config.MaxConnIdleTime = 5 * time.Minute
	config.HealthCheckPeriod = 1 * time.Minute

	// pgBouncer in transaction mode does not support session-level prepared
	// statements: a connection is reassigned between statements, so a cached
	// prepared statement resolves against the wrong session. QueryExecModeExec
	// sends each query on the simple protocol with no statement caching.
	config.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeExec
	config.ConnConfig.Tracer = tracer

	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		return fmt.Errorf("error connecting to the database: %v", err)
	}

	if err := pool.Ping(context.Background()); err != nil {
		return fmt.Errorf("database ping failed: %v", err)
	}

	slog.Info("Successfully connected to PostgreSQL (pgxpool) in Go Gateway.")
	Pool = pool
	return nil
}

// NormalizeURL accepts the SQLAlchemy driver form the Python service uses
// (postgresql+asyncpg://...), so both processes in the container can share
// one DATABASE_URL. Other URLs are returned unchanged.
func NormalizeURL(url string) string {
	for _, prefix := range []string{"postgresql+asyncpg://", "postgres+asyncpg://"} {
		if strings.HasPrefix(url, prefix) {
			return "postgresql://" + strings.TrimPrefix(url, prefix)
		}
	}
	return url
}

// CloseDB gracefully shuts down the connection pool.
func CloseDB() {
	if Pool != nil {
		Pool.Close()
		slog.Info("PostgreSQL connection pool closed.")
	}
}
