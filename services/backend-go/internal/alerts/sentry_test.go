package alerts

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

const secret = "integration-client-secret"

func sign(body string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(body))
	return hex.EncodeToString(mac.Sum(nil))
}

// slack records what reaches the webhook and answers with status.
func slack(t *testing.T, status int) (string, *[]string) {
	t.Helper()
	var got []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var msg struct{ Text string }
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &msg)
		got = append(got, msg.Text)
		w.WriteHeader(status)
	}))
	t.Cleanup(server.Close)
	return server.URL, &got
}

func send(r Relay, resource, body, signature string) *httptest.ResponseRecorder {
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	engine.POST("/hooks/sentry", r.Handler())
	req := httptest.NewRequest(http.MethodPost, "/hooks/sentry", strings.NewReader(body))
	req.Header.Set("Sentry-Hook-Resource", resource)
	if signature != "" {
		req.Header.Set("Sentry-Hook-Signature", signature)
	}
	w := httptest.NewRecorder()
	engine.ServeHTTP(w, req)
	return w
}

const issueAlert = `{"action":"triggered","data":{"triggered_rule":"New issue","event":{
  "title":"ZeroDivisionError: division by zero","level":"error",
  "web_url":"https://vikas-1k.sentry.io/issues/42/events/abc/",
  "request":{"headers":[["Authorization","Bearer secret-token"]]},
  "user":{"email":"ada@example.com"}}}}`

func TestIssueAlertReachesSlackWithoutDetails(t *testing.T) {
	url, got := slack(t, http.StatusOK)
	w := send(Relay{Secret: secret, Slack: url}, "event_alert", issueAlert, sign(issueAlert))
	if w.Code != http.StatusNoContent {
		t.Fatalf("got %d %s, want 204", w.Code, w.Body)
	}
	if len(*got) != 1 {
		t.Fatalf("Slack got %d messages, want 1", len(*got))
	}
	text := (*got)[0]
	if !strings.Contains(text, "[error] ZeroDivisionError") || !strings.Contains(text, "https://vikas-1k.sentry.io/issues/42/") {
		t.Fatalf("message %q lacks the title or link", text)
	}
	if strings.Contains(text, "secret-token") || strings.Contains(text, "ada@example.com") {
		t.Fatalf("message %q leaks request or user data", text)
	}
}

func TestMetricAlertStates(t *testing.T) {
	url, got := slack(t, http.StatusOK)
	for _, action := range []string{"critical", "resolved"} {
		body := `{"action":"` + action + `","data":{"description_title":"Error rate above 5%","web_url":"https://sentry.io/organizations/o/alerts/1/"}}`
		if w := send(Relay{Secret: secret, Slack: url}, "metric_alert", body, sign(body)); w.Code != http.StatusNoContent {
			t.Fatalf("%s: %d", action, w.Code)
		}
	}
	if !strings.HasPrefix((*got)[0], "Sentry critical: Error rate") || !strings.HasPrefix((*got)[1], "Sentry resolved: Error rate") {
		t.Fatalf("messages = %q", *got)
	}
}

func TestRejectsUnsignedOrForgedRequests(t *testing.T) {
	url, got := slack(t, http.StatusOK)
	r := Relay{Secret: secret, Slack: url}
	for name, signature := range map[string]string{
		"missing":       "",
		"not hex":       "zz",
		"wrong secret":  hex.EncodeToString(hmac.New(sha256.New, []byte("other")).Sum(nil)),
		"other payload": sign(`{"action":"triggered"}`),
	} {
		w := send(r, "event_alert", issueAlert, signature)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("%s: got %d, want 401", name, w.Code)
		}
	}
	if len(*got) != 0 {
		t.Fatal("an unverified request reached Slack")
	}
}

func TestLifecycleEventsAreAcknowledgedSilently(t *testing.T) {
	url, got := slack(t, http.StatusOK)
	for _, tc := range [][2]string{
		{"installation", `{"action":"created","data":{"installation":{}}}`},
		{"event_alert", `{"action":"triggered","data":{}}`},
	} {
		if w := send(Relay{Secret: secret, Slack: url}, tc[0], tc[1], sign(tc[1])); w.Code != http.StatusNoContent {
			t.Fatalf("%s: %d", tc[0], w.Code)
		}
	}
	if len(*got) != 0 {
		t.Fatalf("Slack got %q, want nothing", *got)
	}
}

func TestErrors(t *testing.T) {
	if w := send(Relay{}, "event_alert", issueAlert, sign(issueAlert)); w.Code != http.StatusServiceUnavailable {
		t.Fatalf("unconfigured: %d, want 503", w.Code)
	}
	url, _ := slack(t, http.StatusOK)
	if w := send(Relay{Secret: secret, Slack: url}, "event_alert", "not json", sign("not json")); w.Code != http.StatusBadRequest {
		t.Fatalf("malformed: %d, want 400", w.Code)
	}
	down, _ := slack(t, http.StatusForbidden)
	if w := send(Relay{Secret: secret, Slack: down}, "event_alert", issueAlert, sign(issueAlert)); w.Code != http.StatusBadGateway {
		t.Fatalf("Slack refusing: %d, want 502", w.Code)
	}
	if w := send(Relay{Secret: secret, Slack: "http://127.0.0.1:1"}, "event_alert", issueAlert, sign(issueAlert)); w.Code != http.StatusBadGateway {
		t.Fatalf("Slack unreachable: %d, want 502", w.Code)
	}
}

func TestMessageHygiene(t *testing.T) {
	long := strings.Repeat("x", 400) + "\n\t"
	if got := clip(long); strings.ContainsAny(got, "\n\t") || len([]rune(got)) != maxTitle+1 {
		t.Fatalf("clip produced %q", got)
	}
	for url, want := range map[string]string{
		"https://vikas-1k.sentry.io/issues/1/": "https://vikas-1k.sentry.io/issues/1/",
		"https://sentry.io/organizations/o/":   "https://sentry.io/organizations/o/",
		"https://evil.example/sentry.io/":      "(no issue link)",
		"https://evil.example/x.sentry.io/":    "(no issue link)",
		"https://sentry.io.evil.example/":      "(no issue link)",
		"javascript:alert(1)":                  "(no issue link)",
	} {
		if got := safeURL(url); got != want {
			t.Fatalf("safeURL(%q) = %q", url, got)
		}
	}
	if SlackWebhook("https://hooks.slack.com/services/T/B/x") == "" || SlackWebhook("https://example.com/hook") != "" {
		t.Fatal("SlackWebhook must accept only Slack incoming webhooks")
	}
}
