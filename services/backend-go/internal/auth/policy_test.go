package auth

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func requestFrom(t *testing.T, ip, header string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("CF-Connecting-IP", ip)
	if header != "" {
		req.Header.Set("Authorization", header)
	}
	w := httptest.NewRecorder()
	newTestRouter().ServeHTTP(w, req)
	return w
}

func code(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var body struct{ Code string }
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	return body.Code
}

func TestAccountPolicy(t *testing.T) {
	cases := []struct {
		name       string
		provider   string
		unverified bool
		disable    bool // AUTH_REQUIRE_VERIFIED_EMAIL=false
		status     int
		code       string
	}{
		{"verified password account", "password", false, false, 200, ""},
		{"unverified password account", "password", true, false, 403, "email_unverified"},
		{"unverified, rollout switch off", "password", true, true, 200, ""},
		{"anonymous session", "anonymous", false, false, 403, "anonymous_not_allowed"},
		{"federated provider vouches for itself", "google.com", true, false, 200, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fake := &fakeFirebase{authTime: 1000, provider: tc.provider, unverified: tc.unverified}
			withFirebase(t, fake)
			if tc.disable {
				t.Setenv("AUTH_REQUIRE_VERIFIED_EMAIL", "false")
			}
			w := requestFrom(t, "198.51.100.1", "Bearer token")
			if w.Code != tc.status || code(t, w) != tc.code {
				t.Fatalf("status = %d code = %q, want %d %q", w.Code, code(t, w), tc.status, tc.code)
			}
			if tc.status == 403 && fake.getUsers != 0 {
				t.Fatal("policy refusals must not cost a Firebase account lookup")
			}
		})
	}
}

func TestFailedLoginsThrottleTheIPBeforeVerification(t *testing.T) {
	fake := &fakeFirebase{authTime: 1000, badToken: true}
	withFirebase(t, fake)
	for i := 0; i < 20; i++ {
		if w := requestFrom(t, "203.0.113.9", "Bearer forged"); w.Code != http.StatusUnauthorized {
			t.Fatalf("failure %d: status %d, want 401", i+1, w.Code)
		}
	}
	// The bucket is empty: even a valid token from this IP waits.
	fake.badToken = false
	w := requestFrom(t, "203.0.113.9", "Bearer token")
	if w.Code != http.StatusTooManyRequests || code(t, w) != "auth_throttled" || w.Header().Get("Retry-After") == "" {
		t.Fatalf("status = %d code = %q retry-after = %q", w.Code, code(t, w), w.Header().Get("Retry-After"))
	}
	// Other callers are unaffected.
	if w := requestFrom(t, "203.0.113.10", "Bearer token"); w.Code != http.StatusOK {
		t.Fatalf("other IP: status %d, want 200", w.Code)
	}
}

func TestSuccessfulAndMissingCredentialsDoNotCountAsFailures(t *testing.T) {
	withFirebase(t, &fakeFirebase{authTime: 1000})
	for i := 0; i < 30; i++ {
		if w := requestFrom(t, "203.0.113.20", "Bearer token"); w.Code != http.StatusOK {
			t.Fatalf("request %d: status %d", i, w.Code)
		}
		if w := requestFrom(t, "203.0.113.20", ""); w.Code != http.StatusUnauthorized {
			t.Fatalf("missing header: status %d", w.Code)
		}
	}
}
