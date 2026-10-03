package websocket

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/gin-gonic/gin"
	gorillaws "github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
	"github.com/skolab/backend-go/internal/pubsub"
)

func sharedHub(t *testing.T, addr string) *Hub {
	t.Helper()
	h := NewHubWithRedis(&pubsub.RedisClient{Client: redis.NewClient(&redis.Options{Addr: addr, MaxRetries: -1})})
	go h.Run()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := h.Shutdown(ctx); err != nil {
			t.Error(err)
		}
	})
	return h
}

func socketServer(t *testing.T, hub *Hub) string {
	t.Helper()
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "test-editor")
		ServeWs(hub, staticWorkspaceAuthorizer{allowed: true}, c)
	})
	server := httptest.NewServer(r)
	t.Cleanup(server.Close)
	return "ws" + strings.TrimPrefix(server.URL, "http") + "/ws/colab/paper"
}

func TestCollaborationAcrossInstancesAndReconnectAfterShutdown(t *testing.T) {
	t.Setenv("SHARED_STATE_REQUIRED", "true")
	redisServer := miniredis.RunT(t)
	a, b := sharedHub(t, redisServer.Addr()), sharedHub(t, redisServer.Addr())
	urlA, urlB := socketServer(t, a), socketServer(t, b)
	connA, connB := dial(t, urlA, ""), dial(t, urlB, "")
	if err := connA.WriteMessage(gorillaws.TextMessage, []byte("across-instances")); err != nil {
		t.Fatal(err)
	}
	if got, ok := receives(connB, time.Second); !ok || got != "across-instances" {
		t.Fatalf("cross-instance delivery: %q %v", got, ok)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := a.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if code := closeCode(t, connA); code != gorillaws.CloseServiceRestart {
		t.Fatalf("close code = %d, want restart", code)
	}
	// The client obtains a fresh connection on the surviving instance.
	reconnected := dial(t, urlB, "")
	if err := reconnected.WriteMessage(gorillaws.TextMessage, []byte("after-failover")); err != nil {
		t.Fatal(err)
	}
	if got, ok := receives(connB, time.Second); !ok || got != "after-failover" {
		t.Fatalf("surviving instance: %q %v", got, ok)
	}
}

func TestRequiredBroadcastsDoNotFallBackLocallyDuringRedisOutage(t *testing.T) {
	t.Setenv("SHARED_STATE_REQUIRED", "true")
	server := miniredis.RunT(t)
	h := sharedHub(t, server.Addr())
	server.Close()
	if err := h.Publish("paper", []byte("must-not-diverge")); err == nil {
		t.Fatal("Redis outage must be reported to the socket")
	}
	if err := h.Ready(context.Background()); err == nil {
		t.Fatal("Redis outage must fail readiness")
	}
}
