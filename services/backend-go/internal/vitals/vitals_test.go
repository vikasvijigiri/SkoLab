package vitals

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"
)

type memory struct {
	mu      sync.Mutex
	samples []Sample
}

func (m *memory) RecordVital(_ context.Context, s Sample) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.samples = append(m.samples, s)
}

func post(t *testing.T, rec Recorder, contentType, body string) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.POST("/api/v1/vitals", Handler(rec))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/vitals", strings.NewReader(body))
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestReportIsRecordedInSecondsWithItsPage(t *testing.T) {
	m := &memory{}
	body := `{"page":"/sign-in","metrics":[{"name":"LCP","value":1234},{"name":"CLS","value":0.04},{"name":"INP","value":88},{"name":"FCP","value":900},{"name":"TTFB","value":120}]}`
	for _, contentType := range []string{"text/plain;charset=UTF-8", "application/json"} {
		m.samples = nil
		if w := post(t, m, contentType, body); w.Code != http.StatusNoContent {
			t.Fatalf("%s: status %d, body %s", contentType, w.Code, w.Body)
		}
		want := []Sample{{"LCP", "/sign-in", 1.234}, {"CLS", "/sign-in", 0.04}, {"INP", "/sign-in", 0.088}, {"FCP", "/sign-in", 0.9}, {"TTFB", "/sign-in", 0.12}}
		if len(m.samples) != len(want) {
			t.Fatalf("got %v", m.samples)
		}
		for i, s := range want {
			got := m.samples[i]
			if got.Name != s.Name || got.Page != s.Page || got.Value < s.Value-1e-9 || got.Value > s.Value+1e-9 {
				t.Fatalf("sample %d: got %+v, want %+v", i, got, s)
			}
		}
	}
}

func TestUnknownPagesShareOneLabel(t *testing.T) {
	m := &memory{}
	for _, page := range []string{"/admin/secret-123", "", "/editor/9f1c", strings.Repeat("a", maxPage)} {
		if w := post(t, m, "text/plain", `{"page":"`+page+`","metrics":[{"name":"LCP","value":1}]}`); w.Code != http.StatusNoContent {
			t.Fatalf("page %q: status %d", page, w.Code)
		}
	}
	for _, s := range m.samples {
		if s.Page != "other" {
			t.Fatalf("unlisted page kept its own label: %+v", s)
		}
	}
}

func TestInvalidReportsAreRejectedAndRecordNothing(t *testing.T) {
	cases := map[string]string{
		"not json":         `nope`,
		"no metrics":       `{"page":"/","metrics":[]}`,
		"unknown metric":   `{"page":"/","metrics":[{"name":"FID","value":1}]}`,
		"missing value":    `{"page":"/","metrics":[{"name":"LCP"}]}`,
		"string value":     `{"page":"/","metrics":[{"name":"LCP","value":"1"}]}`,
		"negative":         `{"page":"/","metrics":[{"name":"LCP","value":-1}]}`,
		"too slow":         `{"page":"/","metrics":[{"name":"INP","value":60001}]}`,
		"huge shift":       `{"page":"/","metrics":[{"name":"CLS","value":101}]}`,
		"lowercase name":   `{"page":"/","metrics":[{"name":"lcp","value":1}]}`,
		"good then bad":    `{"page":"/","metrics":[{"name":"LCP","value":1},{"name":"LCP","value":-5}]}`,
		"too many samples": `{"page":"/","metrics":[` + strings.Repeat(`{"name":"CLS","value":0},`, maxSamples) + `{"name":"CLS","value":0}]}`,
		"array":            `[]`,
		"no page":          `{"metrics":[{"name":"LCP","value":1}]}`,
		"null page":        `{"page":null,"metrics":[{"name":"LCP","value":1}]}`,
		"number page":      `{"page":7,"metrics":[{"name":"LCP","value":1}]}`,
		"null metric":      `{"page":"/","metrics":[null]}`,
		"null value":       `{"page":"/","metrics":[{"name":"LCP","value":null}]}`,
		"long page":        `{"page":"` + strings.Repeat("a", maxPage+1) + `","metrics":[{"name":"LCP","value":1}]}`,
		"extra field":      `{"page":"/","user":"x","metrics":[{"name":"LCP","value":1}]}`,
		"extra in metric":  `{"page":"/","metrics":[{"name":"LCP","value":1,"id":"v4-1"}]}`,
		"trailing data":    `{"page":"/","metrics":[{"name":"LCP","value":1}]} {}`,
		"null":             `null`,
	}
	for name, body := range cases {
		m := &memory{}
		w := post(t, m, "text/plain", body)
		if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), `"invalid_vitals"`) {
			t.Errorf("%s: status %d body %s", name, w.Code, w.Body)
		}
		if len(m.samples) != 0 {
			t.Errorf("%s: recorded %v", name, m.samples)
		}
	}
}

func TestWrongContentTypes(t *testing.T) {
	m := &memory{}
	for _, contentType := range []string{"", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", ";;"} {
		if w := post(t, m, contentType, `{"page":"/","metrics":[{"name":"LCP","value":1}]}`); w.Code != http.StatusUnsupportedMediaType {
			t.Fatalf("content type %q: status %d", contentType, w.Code)
		}
	}
	if len(m.samples) != 0 {
		t.Fatalf("recorded %v", m.samples)
	}
}

func FuzzParse(f *testing.F) {
	f.Add([]byte(`{"page":"/","metrics":[{"name":"LCP","value":1}]}`))
	f.Add([]byte(`{"page":"/x","metrics":[{"name":"CLS","value":1e308}]}`))
	f.Fuzz(func(t *testing.T, body []byte) {
		samples, err := parse(body)
		if err != nil {
			return
		}
		if len(samples) == 0 || len(samples) > maxSamples {
			t.Fatalf("accepted %d samples", len(samples))
		}
		for _, s := range samples {
			if _, ok := limits[s.Name]; !ok || (!pages[s.Page] && s.Page != "other") || s.Value < 0 || s.Value > 100 {
				t.Fatalf("accepted %+v", s)
			}
		}
	})
}

func TestPageLengthCountsCharactersNotBytes(t *testing.T) {
	m := &memory{}
	page := strings.Repeat("𝑻", maxPage) // 4 bytes each
	if w := post(t, m, "application/json", `{"page":"`+page+`","metrics":[{"name":"CLS","value":0}]}`); w.Code != http.StatusNoContent {
		t.Fatalf("status %d", w.Code)
	}
}
