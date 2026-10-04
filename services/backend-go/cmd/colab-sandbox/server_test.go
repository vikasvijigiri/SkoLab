package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/skolab/backend-go/internal/texsandbox"
)

func TestHealthAndMethod(t *testing.T) {
	mux := newMux("unused", texsandbox.NewSlots(1), "test-secret")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if w.Code != http.StatusOK || w.Body.String() != "ok" {
		t.Fatalf("healthz: %d %q", w.Code, w.Body)
	}
	w = httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/compile", nil))
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET /compile: %d, want 405", w.Code)
	}
}

func TestBusyWorkerAsksCallerToRetry(t *testing.T) {
	slots := texsandbox.NewSlots(1)
	if err := slots.Acquire(time.Second); err != nil {
		t.Fatal(err)
	}
	defer slots.Release()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(`{"latex_source":"x"}`)).WithContext(ctx)
	req.Header.Set("X-Internal-Token", "test-secret")
	w := httptest.NewRecorder()
	newMux("unused", slots, "test-secret").ServeHTTP(w, req)
	if w.Code != http.StatusServiceUnavailable || w.Header().Get("Retry-After") == "" {
		t.Fatalf("got %d Retry-After=%q, want 503 with Retry-After", w.Code, w.Header().Get("Retry-After"))
	}
}

func TestMalformedJSONIsRejected(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(`{"latex_source":`))
	req.Header.Set("X-Internal-Token", "test-secret")
	w := httptest.NewRecorder()
	newMux("unused", texsandbox.NewSlots(1), "test-secret").ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("got %d, want 400", w.Code)
	}
}

func TestCompilesARealDocument(t *testing.T) {
	engine, err := exec.LookPath("pdflatex")
	if err != nil {
		t.Skip("pdflatex not installed (the gateway CI job installs it)")
	}
	body := `{"latex_source":"\\documentclass{article}\\begin{document}Hello\\end{document}"}`
	req := httptest.NewRequest(http.MethodPost, "/compile", strings.NewReader(body))
	req.Header.Set("X-Internal-Token", "test-secret")
	w := httptest.NewRecorder()
	newMux(engine, texsandbox.NewSlots(1), "test-secret").ServeHTTP(w, req)
	var result struct{ Status string }
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &result) != nil || result.Status != "compiled" {
		t.Fatalf("got %d %s, want a compiled PDF", w.Code, w.Body)
	}
}

func TestSettings(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	if _, _, _, err := settings(); err == nil || !strings.Contains(err.Error(), "pdflatex") {
		t.Fatalf("got %v, want a missing pdflatex error", err)
	}

	dir := t.TempDir()
	name := "pdflatex"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	if err := os.WriteFile(dir+string(os.PathSeparator)+name, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir)
	t.Setenv("INTERNAL_API_TOKEN", "")
	if _, _, _, err := settings(); err == nil || !strings.Contains(err.Error(), "INTERNAL_API_TOKEN") {
		t.Fatalf("got %v, want a missing token error", err)
	}
	t.Setenv("INTERNAL_API_TOKEN", "secret")
	t.Setenv("COLAB_MAX_CONCURRENT_COMPILES", "3")
	engine, token, n, err := settings()
	if err != nil || engine == "" || token != "secret" || n != 3 {
		t.Fatalf("got %q %q %d %v", engine, token, n, err)
	}
	t.Setenv("COLAB_MAX_CONCURRENT_COMPILES", "many")
	if _, _, n, _ := settings(); n != 2 {
		t.Fatalf("an invalid limit must fall back to 2, got %d", n)
	}
}
