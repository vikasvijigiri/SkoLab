package main

import (
	"context"
	"net"
	"net/http"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestRunRefusesWithoutAToken(t *testing.T) {
	if _, err := exec.LookPath("pdflatex"); err != nil {
		t.Skip("pdflatex not installed")
	}
	t.Setenv("INTERNAL_API_TOKEN", "")
	err := run(context.Background(), "127.0.0.1:0", func(net.Addr) { t.Fatal("must refuse before listening") })
	if err == nil || !strings.Contains(err.Error(), "INTERNAL_API_TOKEN") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunServesThenDrains(t *testing.T) {
	if _, err := exec.LookPath("pdflatex"); err != nil {
		t.Skip("pdflatex not installed")
	}
	t.Setenv("INTERNAL_API_TOKEN", "test-secret")
	if err := run(context.Background(), "256.0.0.1:bad", nil); err == nil {
		t.Fatal("unusable address accepted")
	}

	ctx, cancel := context.WithCancel(context.Background())
	addrs := make(chan net.Addr, 1)
	done := make(chan error, 1)
	go func() { done <- run(ctx, "127.0.0.1:0", func(a net.Addr) { addrs <- a }) }()
	addr := <-addrs
	resp, err := http.Get("http://" + addr.String() + "/healthz")
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("health: %v %v", resp, err)
	}
	resp.Body.Close()
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("worker did not drain")
	}
}
