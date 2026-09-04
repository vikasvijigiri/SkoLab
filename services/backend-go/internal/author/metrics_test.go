package author

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/services/openalex"
)

// A missing author_id must 400 before any cache / OpenAlex / Python call, so
// this path is safe with no DB and no network.
func TestGetAuthorMetrics_RequiresAuthorID(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/author_metrics", nil)

	GetAuthorMetrics(c)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestBuildAuthorMetricsContext(t *testing.T) {
	tests := []struct {
		name  string
		works []openalex.Work
		want  string
	}{
		{
			name:  "no works",
			works: nil,
			want:  "",
		},
		{
			name: "title with concepts",
			works: []openalex.Work{
				{Title: "On Widgets", Concepts: []openalex.Concept{
					{DisplayName: "Widget theory"}, {DisplayName: "Gadgets"},
				}},
			},
			want: "Title: On Widgets. Concepts: Widget theory, Gadgets",
		},
		{
			name: "blank concept names are dropped, multiple works joined by newline",
			works: []openalex.Work{
				{Title: "First", Concepts: []openalex.Concept{{DisplayName: "A"}, {DisplayName: ""}}},
				{Title: "Second", Concepts: nil},
			},
			want: "Title: First. Concepts: A\nTitle: Second. Concepts: ",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := buildAuthorMetricsContext(tc.works); got != tc.want {
				t.Errorf("buildAuthorMetricsContext() = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestEnrichAuthorMetrics(t *testing.T) {
	const okBody = `{"overall_score":61,"topic_toughness":70,"velocity":52,"skills":["x"],"tools":["y"],"analysis":"z"}`

	tests := []struct {
		name       string
		status     int
		body       string
		wantErr    bool
		wantVerbat string // expected passthrough body when no error
	}{
		{name: "200 passthrough verbatim", status: 200, body: okBody, wantVerbat: okBody},
		{name: "503 is an error", status: 503, body: `{"code":"ai_unavailable"}`, wantErr: true},
		{name: "200 non-json is an error", status: 200, body: `<html>nope</html>`, wantErr: true},
		{name: "200 json array is an error", status: 200, body: `[1,2,3]`, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != metricsEnrichPath {
					t.Errorf("path = %q, want %q", r.URL.Path, metricsEnrichPath)
				}
				var got map[string]string
				_ = json.NewDecoder(r.Body).Decode(&got)
				if _, ok := got["context"]; !ok {
					t.Errorf("request body missing context field: %v", got)
				}
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()

			old := metricsPythonBackendURL
			metricsPythonBackendURL = srv.URL
			defer func() { metricsPythonBackendURL = old }()

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			out, err := enrichAuthorMetrics(ctx, "Title: T. Concepts: C")
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error, got body %s", out)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if string(out) != tc.wantVerbat {
				t.Errorf("body = %s, want %s", out, tc.wantVerbat)
			}
		})
	}
}

func TestEnrichAuthorMetrics_TransportErrorIsReturned(t *testing.T) {
	old := metricsPythonBackendURL
	metricsPythonBackendURL = "http://127.0.0.1:1" // nothing listening
	defer func() { metricsPythonBackendURL = old }()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if _, err := enrichAuthorMetrics(ctx, "x"); err == nil {
		t.Fatal("expected a transport error, got nil")
	}
}

// The degrade-to-empty response must be the exact shape the Android AuthorMetrics
// model expects, so a client never NPEs on a missing field.
func TestEmptyMetricsBundleShape(t *testing.T) {
	var m map[string]any
	if err := json.Unmarshal(emptyMetricsBundle, &m); err != nil {
		t.Fatalf("emptyMetricsBundle is not valid JSON: %v", err)
	}
	for _, k := range []string{"overall_score", "topic_toughness", "velocity", "skills", "tools", "analysis"} {
		if _, ok := m[k]; !ok {
			t.Errorf("emptyMetricsBundle missing key %q", k)
		}
	}
	if s, ok := m["skills"].([]any); !ok || len(s) != 0 {
		t.Errorf("skills = %v, want empty array", m["skills"])
	}
}
