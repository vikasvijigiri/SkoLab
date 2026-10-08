package main

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
)

// cleanEnv clears the settings run reads, so a developer's shell cannot
// change what these tests prove, and restores global modes afterwards.
func cleanEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{"GIN_MODE", "INTERNAL_API_TOKEN", "SENTRY_DSN", "SHARED_STATE_REDIS_URL",
		"SHARED_STATE_REQUIRED", "COLAB_REQUIRE_SANDBOX", "COLAB_SANDBOX_URL", "COLAB_SANDBOX_HOSTPORT",
		"OTEL_EXPORTER_OTLP_ENDPOINT", "GOOGLE_APPLICATION_CREDENTIALS", "PYTHON_BACKEND_URL"} {
		t.Setenv(key, "")
	}
	t.Setenv("OTEL_SDK_DISABLED", "true")
	logger := slog.Default()
	t.Cleanup(func() { gin.SetMode(gin.TestMode); slog.SetDefault(logger) })
}

func TestRunRefusesUnsafeDeployments(t *testing.T) {
	cases := map[string]struct {
		env  map[string]string
		want string
	}{
		"release without internal token": {map[string]string{"GIN_MODE": "release"}, "INTERNAL_API_TOKEN"},
		"release without firebase": {map[string]string{"GIN_MODE": "release", "INTERNAL_API_TOKEN": "t",
			"FIREBASE_CONFIG": "", "GOOGLE_CLOUD_PROJECT": ""}, "Firebase initialization failed"}, // ci.yml's images job greps this
		"required sandbox missing":      {map[string]string{"COLAB_REQUIRE_SANDBOX": "true", "DATABASE_URL": "postgres://127.0.0.1:1/x"}, "COLAB_SANDBOX_URL"},
		"required shared state missing": {map[string]string{"SHARED_STATE_REQUIRED": "true", "DATABASE_URL": "postgres://127.0.0.1:1/x"}, "SHARED_STATE_REDIS_URL"},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			cleanEnv(t)
			for k, v := range tc.env {
				t.Setenv(k, v)
			}
			err := run(context.Background(), "127.0.0.1:0", func(net.Addr) { t.Fatal("must refuse before listening") })
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want mention of %s", err, tc.want)
			}
		})
	}
}

func TestRunReportsAnUnusableAddress(t *testing.T) {
	cleanEnv(t)
	t.Setenv("DATABASE_URL", "postgres://127.0.0.1:1/x") // development: a down database is a warning
	if err := run(context.Background(), "256.0.0.1:bad", nil); err == nil || !strings.Contains(err.Error(), "listen") {
		t.Fatalf("err = %v", err)
	}
}

// The whole gateway boots, serves and drains cleanly on cancellation.
func TestRunServesThenDrains(t *testing.T) {
	cleanEnv(t)
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	t.Setenv("DATABASE_URL", url)
	ctx, cancel := context.WithCancel(context.Background())
	addrs := make(chan net.Addr, 1)
	done := make(chan error, 1)
	go func() { done <- run(ctx, "127.0.0.1:0", func(a net.Addr) { addrs <- a }) }()

	var base string
	select {
	case a := <-addrs:
		base = "http://" + a.String()
	case err := <-done:
		t.Fatalf("run exited early: %v", err)
	case <-time.After(30 * time.Second):
		t.Fatal("gateway never listened")
	}
	resp, err := http.Get(base + "/gateway-health")
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("liveness: %v %v", resp, err)
	}
	resp.Body.Close()
	// Python is not running here, so readiness must say so rather than lie.
	resp, err = http.Get(base + "/readyz")
	if err != nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("readiness without python: %v %v", resp, err)
	}
	resp.Body.Close()

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("drain: %v", err)
		}
	case <-time.After(35 * time.Second):
		t.Fatal("gateway did not drain")
	}
}

func TestMaintainSweepsUntilCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { maintain(ctx, nil, time.Millisecond); close(done) }()
	time.Sleep(5 * time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("maintenance loop ignored cancellation")
	}

	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		return
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	ctx, cancel = context.WithCancel(context.Background())
	done = make(chan struct{})
	go func() { maintain(ctx, pool, time.Hour); close(done) }() // one pass, then wait
	time.Sleep(200 * time.Millisecond)
	cancel()
	<-done
}
