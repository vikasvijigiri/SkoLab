// Command colab-sandbox is a minimal, standalone HTTP service that does
// nothing but compile LaTeX under services/backend-go/internal/texsandbox.
//
// It is deliberately separate from the API binary and its credentials.
// Each compile gets a fresh temporary directory and bounded subprocess.
// A long-lived worker does not provide a fresh container per request;
// stronger isolation requires an ephemeral job runtime. See deploy/RELIABILITY.md.
//
// This binary intentionally knows nothing about Firebase, Postgres, or
// quotas — that all stays in the gateway (internal/colab), which is the
// only caller. The smaller this binary's own footprint and secret set, the
// smaller the blast radius if the sandbox itself is ever compromised.
package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"syscall"
	"time"
	"unicode/utf8"

	"github.com/skolab/backend-go/internal/texsandbox"
)

const maxRequestBytes = 1024 * 1024 // Includes UTF-8 and JSON escapes for 100,000 characters.

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	engine, token, maxConcurrent, err := settings()
	if err != nil {
		slog.Error("refusing to start", "err", err)
		os.Exit(1)
	}
	mux := newMux(engine, texsandbox.NewSlots(maxConcurrent), token)

	port := os.Getenv("PORT") // Cloud Run convention
	if port == "" {
		port = "8081"
	}
	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadTimeout:       10 * time.Second,
		ReadHeaderTimeout: 5 * time.Second,
		// Comfortably above texsandbox.CompileTimeout + admission wait so a
		// legitimate slow compile is never cut off by the HTTP layer itself.
		WriteTimeout: texsandbox.CompileTimeout + 15*time.Second,
	}

	go func() {
		slog.Info("colab-sandbox starting", "addr", srv.Addr, "max_concurrent", maxConcurrent)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	slog.Info("shutdown signal received, draining in-flight compiles")
	ctx, cancel := context.WithTimeout(context.Background(), texsandbox.CompileTimeout+10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		slog.Error("graceful shutdown did not complete cleanly", "err", err)
	}
}

// settings reads and checks the worker's configuration.
func settings() (engine, token string, maxConcurrent int, err error) {
	engine, err = lookPathPdflatex()
	if err != nil {
		return "", "", 0, fmt.Errorf("pdflatex not found on PATH: %w", err)
	}
	token = os.Getenv("INTERNAL_API_TOKEN")
	if token == "" {
		return "", "", 0, errors.New("INTERNAL_API_TOKEN is unset; this worker must not accept unauthenticated compiles")
	}
	return engine, token, envInt("COLAB_MAX_CONCURRENT_COMPILES", 2), nil
}

// newMux serves the health probe and the compile endpoint.
func newMux(engine string, slots texsandbox.Slots, token string) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/compile", compileHandler(engine, slots, token))
	return mux
}

type compileRequest struct {
	LatexSource string `json:"latex_source"`
	Engine      string `json:"engine"`
}

func compileHandler(engine string, slots texsandbox.Slots, token string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		// Constant-time comparison: this worker's only defence against being
		// called by anyone other than the gateway is this shared secret.
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Internal-Token")), []byte(token)) != 1 {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		body, err := io.ReadAll(io.LimitReader(r.Body, maxRequestBytes+1))
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if len(body) > maxRequestBytes {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			return
		}
		var req compileRequest
		if err := json.Unmarshal(body, &req); err != nil || req.LatexSource == "" || utf8.RuneCountInString(req.LatexSource) > 100_000 || (req.Engine != "" && req.Engine != "pdflatex") {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		const admissionWait = 5 * time.Second
		if err := slots.AcquireContext(r.Context(), admissionWait); err != nil {
			w.Header().Set("Retry-After", "5")
			w.WriteHeader(http.StatusServiceUnavailable)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"status": "error",
				"errors": []string{"Compile workers are busy. Try again shortly."},
			})
			return
		}
		defer slots.Release()

		dir, err := os.MkdirTemp("", "skolab-tex-")
		if err != nil {
			slog.Error("failed to create sandbox temp dir", "err", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		defer os.RemoveAll(dir)

		started := time.Now()
		result := texsandbox.Compile(r.Context(), engine, dir, req.LatexSource)
		slog.Info("compile", "status", result.Status, "ms", time.Since(started).Milliseconds(),
			"src_bytes", len(req.LatexSource))

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(result)
	}
}

func lookPathPdflatex() (string, error) {
	return exec.LookPath("pdflatex")
}

func envInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}
