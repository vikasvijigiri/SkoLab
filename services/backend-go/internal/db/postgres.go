package db

import (
	"context"
	"fmt"
	"log"
	"os"
	"strconv"
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
func InitDB() error {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		// Credential-free local dev fallback — pgx uses the OS user (or PGUSER
		// / PGPASSWORD) when the DSN omits them. Matches services/backend's
		// .env.example convention and keeps a credential-shaped literal out of
		// source. Set DATABASE_URL for anything real.
		dbURL = "postgres://localhost:5432/skolab?sslmode=disable"
	}

	config, err := pgxpool.ParseConfig(dbURL)
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

	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		return fmt.Errorf("error connecting to the database: %v", err)
	}

	if err := pool.Ping(context.Background()); err != nil {
		return fmt.Errorf("database ping failed: %v", err)
	}

	log.Println("Successfully connected to PostgreSQL (pgxpool) in Go Gateway.")
	Pool = pool
	return nil
}

// CloseDB gracefully shuts down the connection pool.
func CloseDB() {
	if Pool != nil {
		Pool.Close()
		log.Println("PostgreSQL connection pool closed.")
	}
}
