// Package health answers the gateway's readiness question: can this
// instance serve every route right now? The gateway and the Python service
// run in one container, so readiness covers both: the database the gateway
// queries and the Python process it forwards compiles to.
package health

import (
	"context"
	"net/http"
	"time"
)

// Pinger is the part of *pgxpool.Pool readiness needs.
type Pinger interface {
	Ping(ctx context.Context) error
}

const probeTimeout = 2 * time.Second

// Readiness checks the database and the Python service's own /readyz
// (which in turn checks its database and cache connections). It returns
// each dependency's state and whether all are healthy.
func Readiness(ctx context.Context, db Pinger, client *http.Client, pythonURL string) (map[string]string, bool) {
	state := map[string]string{"database": "unhealthy", "python": "unhealthy"}
	if db != nil {
		pingCtx, cancel := context.WithTimeout(ctx, probeTimeout)
		if db.Ping(pingCtx) == nil {
			state["database"] = "healthy"
		}
		cancel()
	}
	if pythonReady(ctx, client, pythonURL) {
		state["python"] = "healthy"
	}
	return state, state["database"] == "healthy" && state["python"] == "healthy"
}

func pythonReady(ctx context.Context, client *http.Client, baseURL string) bool {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, baseURL+"/readyz", nil)
	if err != nil {
		return false
	}
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}
