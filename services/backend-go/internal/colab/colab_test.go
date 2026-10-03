package colab

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"github.com/skolab/backend-go/internal/shared"
)

func TestSharedCompileLockSpansStoresAndFailsClosedOnOutage(t *testing.T) {
	t.Setenv("SHARED_STATE_REQUIRED", "true")
	server := miniredis.RunT(t)
	a := shared.New(redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1}))
	b := shared.New(redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1}))
	ShareLocks(a)
	t.Cleanup(func() { ShareLocks(nil); a.Close(); b.Close() })
	release, ok, err := start(context.Background(), "same-user")
	if err != nil || !ok {
		t.Fatalf("first lock: %v %v", ok, err)
	}
	ShareLocks(b)
	if _, ok, err := start(context.Background(), "same-user"); err != nil || ok {
		t.Fatalf("second instance must see lock: %v %v", ok, err)
	}
	release()
	server.Close()
	if _, _, err := start(context.Background(), "same-user"); err == nil {
		t.Fatal("outage must not create a local compile slot")
	}
}

func TestRequiredSandboxCannotFallBackToPython(t *testing.T) {
	t.Setenv("COLAB_REQUIRE_SANDBOX", "true")
	t.Setenv("COLAB_SANDBOX_HOSTPORT", "")
	t.Setenv("COLAB_SANDBOX_URL", "")
	if ValidateConfiguration() == nil {
		t.Fatal("isolated compile mode requires a worker")
	}
}

func TestPrivateWorkerAddressOverridesLegacyURL(t *testing.T) {
	t.Setenv("COLAB_SANDBOX_HOSTPORT", "sandbox:8081")
	t.Setenv("COLAB_SANDBOX_URL", "https://legacy.example")
	if SandboxURL() != "http://sandbox:8081" {
		t.Fatal(SandboxURL())
	}
	if err := ValidateConfiguration(); err != nil {
		t.Fatal(err)
	}
}

func newTestRouter(t *testing.T, userID string, sandboxURL, pythonURL string) *gin.Engine {
	t.Setenv("USER_QUOTA_ENABLED", "true")
	t.Setenv("USER_QUOTA_HOURLY_UNITS", "8") // exactly 2 compiles (cost 4 each)
	t.Setenv("USER_QUOTA_DAILY_UNITS", "1000")
	if sandboxURL != "" {
		t.Setenv("COLAB_SANDBOX_URL", sandboxURL)
	}
	t.Setenv("INTERNAL_API_TOKEN", "shared-secret")

	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(func(c *gin.Context) {
		if userID != "" {
			c.Set("user_id", userID)
		}
		c.Next()
	})
	r.POST("/api/v1/colab/compile", Handler(nil, http.DefaultClient, pythonURL))
	return r
}

func doCompile(r *gin.Engine, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/colab/compile", strings.NewReader(body))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestMissingUserIsUnauthorized(t *testing.T) {
	r := newTestRouter(t, "", "", "http://unused")
	w := doCompile(r, `{"latex_source":"x"}`)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

func TestProxiesToSandboxWhenConfiguredAndChecksSharedSecret(t *testing.T) {
	var gotToken string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		gotToken = req.Header.Get("X-Internal-Token")
		body, _ := io.ReadAll(req.Body)
		if !strings.Contains(string(body), "latex_source") {
			t.Errorf("sandbox did not receive the request body: %s", body)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"compiled"}`))
	}))
	defer upstream.Close()

	r := newTestRouter(t, "user-1", upstream.URL, "http://unused-python")
	w := doCompile(r, `{"latex_source":"hi"}`)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", w.Code, w.Body.String())
	}
	if gotToken != "shared-secret" {
		t.Fatalf("sandbox saw token %q, want the shared secret", gotToken)
	}
	if !strings.Contains(w.Body.String(), "compiled") {
		t.Fatalf("body = %s", w.Body.String())
	}
}

func TestFallsBackToPythonWhenSandboxURLUnset(t *testing.T) {
	var hitPath, gotToken string
	pythonUpstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		hitPath = req.URL.Path
		gotToken = req.Header.Get("X-Internal-Token")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"compiled"}`))
	}))
	defer pythonUpstream.Close()

	r := newTestRouter(t, "user-2", "", pythonUpstream.URL)
	w := doCompile(r, `{"latex_source":"hi"}`)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", w.Code, w.Body.String())
	}
	if hitPath != "/api/v1/colab/compile" {
		t.Fatalf("python upstream got path %q", hitPath)
	}
	// Python serves compiles only for the gateway (it does not charge the
	// quota again), so the gateway must prove itself on the fallback too.
	if gotToken != "shared-secret" {
		t.Fatalf("python upstream got X-Internal-Token %q", gotToken)
	}
}

func TestSecondConcurrentCompileBySameUserIsRejected(t *testing.T) {
	entered := make(chan struct{})
	release := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		close(entered) // deterministic signal: the first request has passed
		// the in-flight check and reached the upstream call — only now is it
		// safe for the test to fire the second request and expect a 429,
		// instead of guessing at timing with a busy-poll.
		<-release
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"compiled"}`))
	}))
	defer upstream.Close()

	r := newTestRouter(t, "busy-user", upstream.URL, "http://unused")

	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- doCompile(r, `{"latex_source":"hi"}`) }()

	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("first request never reached the upstream call")
	}

	w2 := doCompile(r, `{"latex_source":"hi"}`)
	if w2.Code != http.StatusTooManyRequests {
		t.Fatalf("second concurrent compile: status = %d, body=%s, want 429", w2.Code, w2.Body.String())
	}
	if w2.Header().Get("Retry-After") == "" {
		t.Fatalf("expected a Retry-After header")
	}

	close(release)
	first := <-done
	if first.Code != http.StatusOK {
		t.Fatalf("first compile: status = %d", first.Code)
	}
}

func TestQuotaIsEnforcedAcrossRequests(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"compiled"}`))
	}))
	defer upstream.Close()

	r := newTestRouter(t, "metered-user", upstream.URL, "http://unused")
	first := doCompile(r, `{"latex_source":"hi"}`)
	second := doCompile(r, `{"latex_source":"hi"}`)
	third := doCompile(r, `{"latex_source":"hi"}`)

	if first.Code != http.StatusOK || second.Code != http.StatusOK {
		t.Fatalf("first two compiles should be within budget: %d, %d", first.Code, second.Code)
	}
	if third.Code != http.StatusTooManyRequests {
		t.Fatalf("third compile: status = %d, want 429 (budget was 8, cost 4 each)", third.Code)
	}
}

func TestUpstreamUnreachableReturns503NotAnErrorLeak(t *testing.T) {
	r := newTestRouter(t, "user-3", "http://127.0.0.1:1", "http://unused")
	w := doCompile(r, `{"latex_source":"hi"}`)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", w.Code)
	}
}

func TestInvalidRequestsAre400AndSpendNoQuota(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		calls++
		_, _ = w.Write([]byte(`{"status":"compiled"}`))
	}))
	defer upstream.Close()
	r := newTestRouter(t, "careless-user", upstream.URL, "http://unused")

	for body, code := range map[string]string{
		`not json`:            "invalid_body",
		`{}`:                  "invalid_body",
		`{"latex_source":42}`: "invalid_body",
		`{"latex_source":""}`: "invalid_source",
		`{"latex_source":"` + strings.Repeat("é", 100_001) + `"}`: "invalid_source",
		`{"latex_source":"x","engine":"lualatex"}`:                "invalid_engine",
	} {
		w := doCompile(r, body)
		if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), `"`+code+`"`) {
			t.Fatalf("%.40s: %d %s, want 400 %s", body, w.Code, w.Body, code)
		}
	}
	if calls != 0 {
		t.Fatalf("invalid requests reached the backend %d times", calls)
	}
	// The budget (8 units) is untouched: two real compiles still fit.
	for i := 0; i < 2; i++ {
		if w := doCompile(r, `{"latex_source":"hi"}`); w.Code != http.StatusOK {
			t.Fatalf("compile %d after invalid requests: %d", i+1, w.Code)
		}
	}
}

func TestBackendErrorsUseTheGatewayContract(t *testing.T) {
	cases := []struct {
		upstream   int
		retryAfter string
		want       int
		code       string
	}{
		{http.StatusServiceUnavailable, "7", http.StatusServiceUnavailable, "compile_busy"},
		{http.StatusTooManyRequests, "", http.StatusTooManyRequests, "compile_in_progress"},
		{http.StatusUnauthorized, "", http.StatusUnauthorized, "token_invalid"},
		{http.StatusForbidden, "", http.StatusBadGateway, "compile_failed"},
		{http.StatusUnprocessableEntity, "", http.StatusBadGateway, "compile_failed"},
		{http.StatusInternalServerError, "", http.StatusBadGateway, "compile_failed"},
	}
	for i, tc := range cases {
		upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			if tc.retryAfter != "" {
				w.Header().Set("Retry-After", tc.retryAfter)
			}
			w.WriteHeader(tc.upstream)
			_, _ = w.Write([]byte(`{"detail":"internal detail that must not leak"}`))
		}))
		r := newTestRouter(t, "user-"+strconv.Itoa(i), upstream.URL, "http://unused")
		w := doCompile(r, `{"latex_source":"hi"}`)
		upstream.Close()
		if w.Code != tc.want || !strings.Contains(w.Body.String(), `"`+tc.code+`"`) || strings.Contains(w.Body.String(), "internal detail") {
			t.Fatalf("upstream %d: got %d %s, want %d %s", tc.upstream, w.Code, w.Body, tc.want, tc.code)
		}
		if tc.retryAfter != "" && w.Header().Get("Retry-After") != tc.retryAfter {
			t.Fatalf("upstream %d: Retry-After = %q, want %q", tc.upstream, w.Header().Get("Retry-After"), tc.retryAfter)
		}
	}
}
