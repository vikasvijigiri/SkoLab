package auth

// Tests for VerifyUser's behaviour when Firebase credentials are absent
// (authClient == nil). That is the state InitFirebase leaves the package in
// whenever credential discovery fails, and until 2026-08-09 it meant every
// protected route was served as the shared identity "dev_user" in any
// environment. These tests pin the mode split: refuse in release, fall back in
// dev/CI.
//
// The happy path (a real, verifiable token) is deliberately not covered here --
// it needs a live Firebase Admin credential, which a unit test must not carry.
// What is covered is every path that does NOT call VerifyIDToken.

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// newTestRouter builds a router with VerifyUser in front of a terminal handler
// that echoes whatever identity the middleware settled on, so a test can assert
// on the identity rather than only on the status code.
func newTestRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/protected", VerifyUser(), func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"user_id": c.GetString("user_id")})
	})
	return r
}

func do(t *testing.T, r *gin.Engine, header string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	if header != "" {
		req.Header.Set("Authorization", header)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// withNilClient guarantees the package-level authClient is nil for the duration
// of a test and restored afterwards, so these tests cannot leak state into each
// other or depend on ordering.
func withNilClient(t *testing.T) {
	t.Helper()
	saved := authClient
	authClient = nil
	t.Cleanup(func() { authClient = saved })
}

func TestVerifyUser_ReleaseRefusesWhenFirebaseUnavailable(t *testing.T) {
	withNilClient(t)
	t.Setenv("GIN_MODE", "release")

	// "Bearer anything" is the whole precondition: the header check only tests
	// for the prefix, so an attacker never needed a real token to reach the
	// fallback.
	w := do(t, newTestRouter(), "Bearer anything")

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusServiceUnavailable)
	}
	if body := w.Body.String(); contains(body, "dev_user") {
		t.Fatalf("response leaked the dev_user identity in release mode: %s", body)
	}
}

func TestVerifyUser_DevFallsBackToDevUser(t *testing.T) {
	withNilClient(t)
	t.Setenv("GIN_MODE", "") // explicitly not release

	w := do(t, newTestRouter(), "Bearer anything")

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d -- the dev/CI fallback must survive", w.Code, http.StatusOK)
	}
	if body := w.Body.String(); !contains(body, "dev_user") {
		t.Fatalf("dev/CI fallback did not set dev_user: %s", body)
	}
}

// A non-release value must not be treated as release by accident (e.g. by a
// prefix or case-insensitive match).
func TestVerifyUser_DebugModeIsNotRelease(t *testing.T) {
	withNilClient(t)
	t.Setenv("GIN_MODE", "debug")

	if w := do(t, newTestRouter(), "Bearer anything"); w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d for GIN_MODE=debug", w.Code, http.StatusOK)
	}
}

// The header check runs before the mode split and must be unchanged by it: a
// missing or malformed Authorization header is 401 in either mode.
func TestVerifyUser_RejectsMissingOrMalformedHeader(t *testing.T) {
	for _, mode := range []string{"release", ""} {
		for _, header := range []string{"", "Basic abc", "bearer lowercase", "Bearer"} {
			withNilClient(t)
			t.Setenv("GIN_MODE", mode)
			w := do(t, newTestRouter(), header)
			if w.Code != http.StatusUnauthorized {
				t.Errorf("GIN_MODE=%q header=%q: status = %d, want %d",
					mode, header, w.Code, http.StatusUnauthorized)
			}
		}
	}
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) &&
		(haystack == needle || indexOf(haystack, needle) >= 0)
}

func indexOf(haystack, needle string) int {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return i
		}
	}
	return -1
}
