package health

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type pinger struct{ err error }

func (p pinger) Ping(context.Context) error { return p.err }

func python(status int) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/readyz" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(status)
	}))
}

func TestReadiness(t *testing.T) {
	up, down := python(http.StatusOK), python(http.StatusServiceUnavailable)
	defer up.Close()
	defer down.Close()

	cases := []struct {
		name      string
		db        Pinger
		pythonURL string
		want      map[string]string
		ready     bool
	}{
		{"all healthy", pinger{}, up.URL, map[string]string{"database": "healthy", "python": "healthy"}, true},
		{"database down", pinger{errors.New("no route")}, up.URL, map[string]string{"database": "unhealthy", "python": "healthy"}, false},
		{"no pool", nil, up.URL, map[string]string{"database": "unhealthy", "python": "healthy"}, false},
		{"python not ready", pinger{}, down.URL, map[string]string{"database": "healthy", "python": "unhealthy"}, false},
		{"python unreachable", pinger{}, "http://127.0.0.1:1", map[string]string{"database": "healthy", "python": "unhealthy"}, false},
	}
	for _, tc := range cases {
		state, ready := Readiness(context.Background(), tc.db, http.DefaultClient, tc.pythonURL)
		if ready != tc.ready || state["database"] != tc.want["database"] || state["python"] != tc.want["python"] {
			t.Fatalf("%s: %v ready=%v", tc.name, state, ready)
		}
	}
}
