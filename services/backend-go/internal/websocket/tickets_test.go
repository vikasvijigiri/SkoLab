package websocket

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

type staticTicketStore struct {
	issuedTicket string
	issueErr     error
	consumeUser  string
	consumeErr   error
	issuedFor    string
	consumed     string
}

type sessionTicketFake struct {
	staticTicketStore
	authTime int64
}

func (s *sessionTicketFake) IssueSession(ctx context.Context, w, u string, at int64) (string, error) {
	s.authTime = at
	return s.Issue(ctx, w, u)
}
func (s *sessionTicketFake) ConsumeSession(ctx context.Context, w, t string) (string, int64, error) {
	u, err := s.Consume(ctx, w, t)
	return u, s.authTime, err
}

func TestTicketPreservesOriginalSessionSignInTime(t *testing.T) {
	gin.SetMode(gin.TestMode)
	store := &sessionTicketFake{staticTicketStore: staticTicketStore{issuedTicket: "opaque", consumeUser: "member"}}
	r := gin.New()
	r.POST("/ws/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "member")
		c.Set("session_auth_time", int64(123))
		IssueTicket(staticWorkspaceAuthorizer{allowed: true}, store)(c)
	})
	r.GET("/ws/:workspace_id", VerifyTicket(store), func(c *gin.Context) { c.JSON(200, gin.H{"auth_time": c.GetInt64("session_auth_time")}) })
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("POST", "/ws/paper", nil))
	if w.Code != 201 || store.authTime != 123 {
		t.Fatalf("sign-in time lost on issue: %d %d", w.Code, store.authTime)
	}
	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", "/ws/paper?ticket=opaque", nil))
	if w.Code != 200 || w.Body.String() != `{"auth_time":123}` {
		t.Fatalf("sign-in time lost on consume: %s", w.Body.String())
	}
}

func (s *staticTicketStore) Issue(_ context.Context, workspaceID, userID string) (string, error) {
	s.issuedFor = workspaceID + ":" + userID
	return s.issuedTicket, s.issueErr
}

func (s *staticTicketStore) Consume(_ context.Context, workspaceID, ticket string) (string, error) {
	s.consumed = workspaceID + ":" + ticket
	return s.consumeUser, s.consumeErr
}

func TestIssueTicket_OnlyIssuesForAnAuthorizedMember(t *testing.T) {
	gin.SetMode(gin.TestMode)
	tickets := &staticTicketStore{issuedTicket: "opaque-ticket"}
	r := gin.New()
	r.POST("/api/v1/ws/colab/:workspace_id/tickets", func(c *gin.Context) {
		c.Set("user_id", "researcher-ada")
		IssueTicket(staticWorkspaceAuthorizer{allowed: true}, tickets)(c)
	})

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/ws/colab/w-1/tickets", nil))

	if w.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d: %s", w.Code, http.StatusCreated, w.Body.String())
	}
	if got := w.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", got)
	}
	if got := tickets.issuedFor; got != "w-1:researcher-ada" {
		t.Fatalf("ticket issued for %q, want workspace and verified user", got)
	}
}

func TestIssueTicket_DeniesNonMember(t *testing.T) {
	gin.SetMode(gin.TestMode)
	tickets := &staticTicketStore{issuedTicket: "must-not-be-issued"}
	r := gin.New()
	r.POST("/api/v1/ws/colab/:workspace_id/tickets", func(c *gin.Context) {
		c.Set("user_id", "researcher-without-access")
		IssueTicket(staticWorkspaceAuthorizer{allowed: false}, tickets)(c)
	})

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/ws/colab/w-1/tickets", nil))

	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusNotFound)
	}
	if tickets.issuedFor != "" {
		t.Fatal("a non-member must never receive a WebSocket ticket")
	}
}

func TestVerifyTicket_ConsumesOpaqueTicketAndRestoresIdentity(t *testing.T) {
	gin.SetMode(gin.TestMode)
	tickets := &staticTicketStore{consumeUser: "researcher-ada"}
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", VerifyTicket(tickets), func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"user_id": c.GetString("user_id")})
	})

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/ws/colab/w-1?ticket=opaque-ticket", nil))

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d: %s", w.Code, http.StatusOK, w.Body.String())
	}
	if got := tickets.consumed; got != "w-1:opaque-ticket" {
		t.Fatalf("ticket consume input = %q", got)
	}
	if got := w.Body.String(); got != `{"user_id":"researcher-ada"}` {
		t.Fatalf("body = %s", got)
	}
}

func TestVerifyTicket_RejectsMissingAndInvalidTickets(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", VerifyTicket(&staticTicketStore{consumeErr: ErrInvalidWorkspaceTicket}), func(c *gin.Context) {
		c.Status(http.StatusNoContent)
	})

	for _, path := range []string{"/ws/colab/w-1", "/ws/colab/w-1?ticket=expired"} {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("path %q status = %d, want %d", path, w.Code, http.StatusUnauthorized)
		}
	}
}

func TestPostgresWorkspaceTicketStore_FailsClosedWithoutDatabase(t *testing.T) {
	store := NewPostgresWorkspaceTicketStore(nil)
	if _, err := store.Issue(context.Background(), "w-1", "researcher-ada"); !errors.Is(err, ErrWorkspaceTicketUnavailable) {
		t.Fatalf("Issue error = %v, want ErrWorkspaceTicketUnavailable", err)
	}
	if _, err := store.Consume(context.Background(), "w-1", "opaque-ticket"); !errors.Is(err, ErrWorkspaceTicketUnavailable) {
		t.Fatalf("Consume error = %v, want ErrWorkspaceTicketUnavailable", err)
	}
}
