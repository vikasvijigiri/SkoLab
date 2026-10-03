// Package colab is the gateway's side of CoLab compile: authentication,
// per-user cost quota, and per-user single-flight all live here, in front
// of whichever backend actually runs the compile.
//
// Two backends, selected at boot by whether COLAB_SANDBOX_URL is set:
//   - Set: proxy to the standalone colab-sandbox worker (cmd/colab-sandbox),
//     deployed as its own per-request-isolated container (Cloud Run/
//     Fargate) — the stronger isolation boundary.
//   - Unset: fall back to the existing Python /api/v1/colab/compile route
//     (still hardened, just sharing the API's own container) so nothing
//     breaks before the sandbox service is actually deployed.
package colab

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/quota"
)

const (
	compileQuotaCost   = 4
	retryAfterSeconds  = 5
	sandboxCallTimeout = 25 * time.Second
)

type activeUsers struct {
	mu   sync.Mutex
	uids map[string]struct{}
}

var inFlight = &activeUsers{uids: map[string]struct{}{}}

func (a *activeUsers) tryStart(uid string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, busy := a.uids[uid]; busy {
		return false
	}
	a.uids[uid] = struct{}{}
	return true
}

func (a *activeUsers) finish(uid string) {
	a.mu.Lock()
	delete(a.uids, uid)
	a.mu.Unlock()
}

// Handler returns the gin handler for POST /api/v1/colab/compile. pythonURL
// is the existing Python backend base URL, used only as the fallback path
// when COLAB_SANDBOX_URL is unset.
func Handler(pool *pgxpool.Pool, httpClient *http.Client, pythonURL string) gin.HandlerFunc {
	sandboxURL := os.Getenv("COLAB_SANDBOX_URL")
	internalToken := os.Getenv("INTERNAL_API_TOKEN")

	return func(c *gin.Context) {
		uid := c.GetString("user_id")
		if uid == "" {
			apierror.Abort(c, http.StatusUnauthorized, "unauthenticated", "Authentication is required")
			return
		}

		if !inFlight.tryStart(uid) {
			c.Header("Retry-After", strconv.Itoa(retryAfterSeconds))
			apierror.Abort(c, http.StatusTooManyRequests, "compile_in_progress", "You already have a compile in progress")
			return
		}
		defer inFlight.finish(uid)

		_, _, err := quota.Consume(c.Request.Context(), pool, uid, compileQuotaCost)
		if err != nil {
			if exc, ok := quota.AsExceeded(err); ok {
				c.Header("Retry-After", strconv.Itoa(int(exc.RetryAfter.Seconds())+1))
				apierror.Abort(c, http.StatusTooManyRequests, "quota_exceeded", "Your "+exc.Window+" usage budget is spent. Try again later")
				return
			}
			slog.Error("colab: quota check failed", "err", err)
			// Availability over strict accounting (quota.Consume itself
			// already falls back to a local counter on a Postgres error) —
			// reaching this branch means even that failed, so degrade to
			// "allow" rather than lock every user out on an infra fault.
		}

		body, readErr := io.ReadAll(c.Request.Body)
		if readErr != nil {
			apierror.Abort(c, http.StatusBadRequest, "invalid_body", "Could not read the request body")
			return
		}

		var status int
		var respBody []byte
		var upstreamErr error
		if sandboxURL != "" {
			status, respBody, upstreamErr = callSandbox(c.Request.Context(), httpClient, sandboxURL, internalToken, body)
		} else {
			status, respBody, upstreamErr = callPython(c.Request.Context(), httpClient, pythonURL, internalToken, c.Request.Header, body)
		}
		if upstreamErr != nil {
			slog.Error("colab: compile backend unreachable", "err", upstreamErr, "sandbox", sandboxURL != "")
			apierror.Abort(c, http.StatusServiceUnavailable, "compile_unavailable", "The compile service is temporarily unavailable")
			return
		}
		c.Data(status, "application/json", respBody)
	}
}

func callSandbox(ctx context.Context, client *http.Client, baseURL, token string, body []byte) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/compile", bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Token", token)
	return doWithTimeout(client, req)
}

// callPython forwards the caller's Firebase token plus the gateway's
// X-Internal-Token: Python serves compiles only for the gateway, which has
// already charged the quota and holds the per-user single-flight slot.
func callPython(ctx context.Context, client *http.Client, baseURL, token string, inHeaders http.Header, body []byte) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/api/v1/colab/compile", bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Token", token)
	if auth := inHeaders.Get("Authorization"); auth != "" {
		req.Header.Set("Authorization", auth)
	}
	return doWithTimeout(client, req)
}

func doWithTimeout(client *http.Client, req *http.Request) (int, []byte, error) {
	ctx, cancel := context.WithTimeout(req.Context(), sandboxCallTimeout)
	defer cancel()
	resp, err := client.Do(req.WithContext(ctx))
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, nil, err
	}
	return resp.StatusCode, b, nil
}
