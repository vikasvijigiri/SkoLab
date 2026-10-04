package websocket

import (
	"context"
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	gorillaws "github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestConnectionAdmissionConcurrentAndReleased(t *testing.T) {
	h := NewHubWithRedis(nil)
	var wg sync.WaitGroup
	var mu sync.Mutex
	releases := []func(){}
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			release, ok := h.reserve("same-user", "same-workspace")
			if ok {
				mu.Lock()
				releases = append(releases, release)
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if len(releases) != 4 {
		t.Fatalf("admitted %d connections, expected 4", len(releases))
	}
	for _, release := range releases {
		release()
		release()
	}
	if h.connections != 0 || len(h.users) != 0 || len(h.workspaces) != 0 {
		t.Fatal("reservation leaked")
	}
	release, ok := h.reserve("same-user", "same-workspace")
	if !ok {
		t.Fatal("could not reconnect")
	}
	release()
}

func TestConnectionAdmissionWorkspaceAndGlobalCaps(t *testing.T) {
	h := NewHubWithRedis(nil)
	for i := 0; i < 16; i++ {
		release, ok := h.reserve(fmt.Sprint(i), "one")
		if !ok {
			t.Fatal("early rejection")
		}
		defer release()
	}
	if _, ok := h.reserve("extra", "one"); ok {
		t.Fatal("workspace cap bypassed")
	}
	for i := 16; i < 64; i++ {
		release, ok := h.reserve(fmt.Sprint(i), fmt.Sprint(i))
		if !ok {
			t.Fatal("early global rejection")
		}
		defer release()
	}
	if _, ok := h.reserve("extra", "extra"); ok {
		t.Fatal("global cap bypassed")
	}
}

func TestOutboundBytesBoundedAndReleased(t *testing.T) {
	h := NewHubWithRedis(nil)
	c := &Client{hub: h, send: make(chan []byte, 8)}
	payload := make([]byte, 512*1024)
	if !c.enqueue(payload) || !c.enqueue(payload) {
		t.Fatal("valid budget refused")
	}
	if c.enqueue(payload) {
		t.Fatal("client byte cap bypassed")
	}
	c.closeQueue()
	if h.queuedBytes.Load() != 0 || c.queuedBytes.Load() != 0 {
		t.Fatal("queue budget leaked")
	}
	h.queuedBytes.Store(16 * 1024 * 1024)
	other := &Client{hub: h, send: make(chan []byte, 8)}
	if other.enqueue([]byte("x")) {
		t.Fatal("global byte cap bypassed")
	}
	if other.queuedBytes.Load() != 0 || h.queuedBytes.Load() != 16*1024*1024 {
		t.Fatal("failed admission leaked budget")
	}
}

func TestSocketChecksSessionBeforeUpgrade(t *testing.T) {
	// The existing socket test helper supplies a user but no valid session.
	h := NewHubWithRedis(nil)
	h.CheckSession = func(context.Context, string, int64) error { return errors.New("revoked") }
	_, response, err := gorillaws.DefaultDialer.Dial(socketServer(t, h), nil)
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("revoked upgrade accepted: %v %v", response, err)
	}
	if response != nil {
		response.Body.Close()
	}
}

func TestReauthorizeClosesOnSessionRevocation(t *testing.T) {
	h := NewHubWithRedis(nil)
	go h.Run()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = h.Shutdown(ctx)
	})
	var revoked atomic.Bool
	h.CheckSession = func(_ context.Context, uid string, authTime int64) error {
		if uid != "member" || authTime != 123 {
			return errors.New("wrong session identity")
		}
		if revoked.Load() {
			return errors.New("revoked")
		}
		return nil
	}
	router := gin.New()
	router.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "member")
		c.Set("session_auth_time", int64(123))
		serveWs(h, staticWorkspaceAuthorizer{allowed: true}, c, 5*time.Millisecond)
	})
	server := httptest.NewServer(router)
	t.Cleanup(server.Close)
	conn := dial(t, "ws"+strings.TrimPrefix(server.URL, "http")+"/ws/colab/paper", "")
	revoked.Store(true)
	if code := closeCode(t, conn); code != gorillaws.ClosePolicyViolation {
		t.Fatalf("revoked socket close=%d", code)
	}
}
