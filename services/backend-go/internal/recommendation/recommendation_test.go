package recommendation

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// These tests exercise the db.Pool == nil paths (no database in unit test) plus
// the request-shape guards, which is where the security-relevant behaviour
// lives: the batch cap on check-registered and the "no cross-user access on a
// missing identifier" contract. The SQL itself is covered by the CI Postgres
// job's end-to-end curl checks in the plan.

func router() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/peers", GetPeerRecommendations)
	r.POST("/peers/invite", LogPeerInvite)
	r.POST("/peers/check-registered", CheckRegisteredPeers)
	return r
}

func post(r *gin.Engine, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestCheckRegistered_RejectsOversizeBatch(t *testing.T) {
	emails := make([]string, 201)
	for i := range emails {
		emails[i] = `"a@b.com"`
	}
	body := `{"emails":[` + strings.Join(emails, ",") + `],"phones":[]}`

	w := post(router(), "/peers/check-registered", body)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("201 identifiers: status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestCheckRegistered_AtCapIsAccepted(t *testing.T) {
	emails := make([]string, 200)
	for i := range emails {
		emails[i] = `"a@b.com"`
	}
	body := `{"emails":[` + strings.Join(emails, ",") + `],"phones":[]}`

	w := post(router(), "/peers/check-registered", body)
	// No DB in the test → 200 with empty arrays, but NOT a 400.
	if w.Code != http.StatusOK {
		t.Fatalf("200 identifiers: status = %d, want %d", w.Code, http.StatusOK)
	}
	var resp checkRegisteredResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body not a checkRegisteredResponse: %v (%s)", err, w.Body.String())
	}
	if resp.RegisteredEmails == nil || resp.RegisteredPhones == nil {
		t.Fatalf("nil slice in response, want empty arrays: %+v", resp)
	}
}

func TestCheckRegistered_MalformedBodyIs400(t *testing.T) {
	w := post(router(), "/peers/check-registered", `{"emails": "not-an-array"}`)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("malformed body: status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestLogPeerInvite_NoPeerUIDReturnsSuccessFalse(t *testing.T) {
	w := post(router(), "/peers/invite", `{"user_id":"u1","peer_email":"x@y.com"}`)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	var resp map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["success"] != false {
		t.Fatalf(`success = %v, want false (no peer_uid, email cannot resolve)`, resp["success"])
	}
}

func TestLogPeerInvite_MalformedBodyIs400(t *testing.T) {
	w := post(router(), "/peers/invite", `not json`)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestGetPeers_EmptyQueryReturnsEmptyArray(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/peers", nil)
	w := httptest.NewRecorder()
	router().ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if strings.TrimSpace(w.Body.String()) != "[]" {
		t.Fatalf("body = %q, want []", w.Body.String())
	}
}

func TestGetPeers_QueryWithoutDBReturnsEmptyArray(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/peers?query=ab&user_id=u1", nil)
	w := httptest.NewRecorder()
	router().ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if strings.TrimSpace(w.Body.String()) != "[]" {
		t.Fatalf("body = %q, want [] (db.Pool nil in unit test)", w.Body.String())
	}
}
