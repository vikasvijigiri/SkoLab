package db

import (
	"context"
	"fmt"
	"log"
	"os"

	"github.com/jackc/pgx/v5/pgxpool"
)

var Pool *pgxpool.Pool

// InitDB initializes the high-performance connection pool to PostgreSQL using pgx.
func InitDB() error {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		// Fallback local url for development
		dbURL = "postgres://postgres:postgres@localhost:5432/skolab?sslmode=disable"
	}

	config, err := pgxpool.ParseConfig(dbURL)
	if err != nil {
		return fmt.Errorf("error parsing database connection string: %v", err)
	}

	// Optimize connection pool limits for high concurrency
	config.MaxConns = 50
	config.MinConns = 10

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
