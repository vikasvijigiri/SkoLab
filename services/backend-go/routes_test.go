package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"sort"
	"strings"
	"testing"
)

// These tests read the router itself, so a route added anywhere is held to
// the same rules as the rest without anyone remembering to list it: it needs
// a token, it is in the API contract, and the endpoint-metrics load test
// drives it (or says here why not).

// public are the routes that answer without a Firebase token, each for a reason.
var public = map[string]string{
	"GET /gateway-health":         "liveness probe",
	"HEAD /gateway-health":        "liveness probe",
	"GET /readyz":                 "readiness probe",
	"POST /api/v1/vitals":         "browsers report page speed before sign-in",
	"POST /hooks/sentry":          "Sentry's HMAC signature, not a Firebase token",
	"GET /ws/colab/:workspace_id": "a single-use ticket, not a bearer token, opens the socket",
}

// unloaded are the routes the endpoint-metrics load test does not drive.
var unloaded = map[string]string{
	"DELETE /api/v1/users/:userId":               "deletes the test account; the Hurl scenarios cover it",
	"POST /hooks/sentry":                         "only Sentry can sign a request",
	"GET /ws/colab/:workspace_id":                "a socket's lifetime is not a latency; the e2e and Hurl suites cover it",
	"HEAD /gateway-health":                       "the GET is driven",
	"POST /api/v1/workspaces/:id/ai/originality": "would spend the shared OpenAlex allowance; ai_live.py checks it weekly",
}

func routeKeys(t *testing.T) []string {
	t.Helper()
	r, _ := testRouter(t, gateway{})
	var keys []string
	for _, route := range r.Routes() {
		keys = append(keys, route.Method+" "+route.Path)
	}
	sort.Strings(keys)
	if len(keys) < 40 {
		t.Fatalf("only %d routes registered; the router did not build", len(keys))
	}
	return keys
}

var param = regexp.MustCompile(`:[A-Za-z_]+`)

func TestEveryAPIRouteRequiresAToken(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	for n, key := range routeKeys(t) {
		if _, ok := public[key]; ok {
			continue
		}
		method, path, _ := strings.Cut(key, " ")
		req := httptest.NewRequest(method, param.ReplaceAllString(path, "00000000-0000-4000-8000-000000000001"), nil)
		// Each from its own address, so the per-address limit never answers first.
		req.RemoteAddr = fmt.Sprintf("198.51.100.%d:1234", n+1)
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != http.StatusUnauthorized || w.Header().Get("WWW-Authenticate") != "Bearer" {
			t.Errorf("%s: got %d, want 401 with a Bearer challenge", key, w.Code)
		}
	}
	for key := range public {
		if !contains(routeKeys(t), key) {
			t.Errorf("%s is listed as public but no longer exists", key)
		}
	}
}

func TestEveryRouteIsInTheContract(t *testing.T) {
	spec, err := os.ReadFile("api/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	documented := map[string]bool{}
	path := regexp.MustCompile(`(?m)^  (/[^:\s]*):\s*$`)
	method := regexp.MustCompile(`^    (get|post|put|patch|delete|head):`)
	current := ""
	for _, line := range strings.Split(strings.ReplaceAll(string(spec), "\r\n", "\n"), "\n") {
		if m := path.FindStringSubmatch(line); m != nil {
			current = regexp.MustCompile(`\{[^}]+\}`).ReplaceAllString(m[1], "{}")
		} else if m := method.FindStringSubmatch(line); m != nil && current != "" {
			documented[strings.ToUpper(m[1])+" "+current] = true
		}
	}
	for _, key := range routeKeys(t) {
		if strings.HasPrefix(key, "HEAD ") || key == "GET /ws/colab/:workspace_id" {
			// gin answers HEAD where GET is documented; the socket upgrade is
			// not an HTTP operation OpenAPI can describe (its ticket route is).
			continue
		}
		if !documented[param.ReplaceAllString(key, "{}")] {
			t.Errorf("%s is not in api/openapi.yaml", key)
		}
	}
}

func TestTheLoadTestDrivesEveryRoute(t *testing.T) {
	script, err := os.ReadFile("../loadtest/endpoints.js")
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range routeKeys(t) {
		if _, ok := unloaded[key]; ok {
			continue
		}
		if !strings.Contains(string(script), "'"+key+"'") {
			t.Errorf("%s is not driven by services/loadtest/endpoints.js (add it to URLS and endpoints, or to unloaded with a reason)", key)
		}
	}
	for key := range unloaded {
		if !contains(routeKeys(t), key) {
			t.Errorf("%s is listed as unloaded but no longer exists", key)
		}
	}
}

func contains(list []string, item string) bool {
	for _, entry := range list {
		if entry == item {
			return true
		}
	}
	return false
}
