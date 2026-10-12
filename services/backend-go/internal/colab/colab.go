// Package colab is the gateway's side of CoLab compile: authentication,
// per-user cost quota, and per-user single-flight all live here, in front
// of whichever backend actually runs the compile.
//
// Two backends, selected at boot by whether COLAB_SANDBOX_URL is set:
//   - Set: proxy to the standalone colab-sandbox worker (cmd/colab-sandbox),
//     deployed separately from the API. Workers can reuse containers; per-job
//     container isolation requires additional infrastructure.
//   - Unset: fall back to the existing Python /api/v1/colab/compile route
//     (still hardened, just sharing the API's own container) so nothing
//     breaks before the sandbox service is actually deployed.
package colab

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/quota"
	"github.com/skolab/backend-go/internal/shared"
	"github.com/skolab/backend-go/internal/texsandbox"
)

const (
	compileQuotaCost   = 4
	retryAfterSeconds  = 5
	sandboxCallTimeout = 25 * time.Second
	// maxSourceRunes matches the compile backends' own limit.
	maxSourceRunes = 100_000
)

type activeUsers struct {
	mu   sync.Mutex
	uids map[string]struct{}
}

var inFlight = &activeUsers{uids: map[string]struct{}{}}

// locks, when set, holds each user's compile slot in Redis so the
// one-compile-per-user rule spans gateway instances (see ShareLocks).
var locks *shared.Store

// ShareLocks keeps compile slots in store (Redis). A nil store keeps them
// in process.
func ShareLocks(store *shared.Store) { locks = store }

// start takes uid's compile slot and returns its release, or ok=false when
// a compile is already running for uid. Redis errors refuse the compile in
// required shared-state mode; optional deployments can use an in-process slot.
func start(ctx context.Context, uid string) (release func(), ok bool, err error) {
	if shared.Required() && locks == nil {
		return nil, false, fmt.Errorf("shared compile lock unavailable")
	}
	if locks != nil {
		release, ok, err := locks.Lock(ctx, "compile:"+uid, sandboxCallTimeout+5*time.Second)
		if err == nil {
			return release, ok, nil
		}
		if shared.Required() {
			return nil, false, err
		}
		slog.Warn("colab: shared compile lock unavailable; using in-process slot", "err", err)
	}
	if !inFlight.tryStart(uid) {
		return nil, false, nil
	}
	return func() { inFlight.finish(uid) }, true, nil
}

func ValidateConfiguration() error {
	if strings.EqualFold(os.Getenv("COLAB_REQUIRE_SANDBOX"), "true") && SandboxURL() == "" {
		return fmt.Errorf("COLAB_REQUIRE_SANDBOX=true requires COLAB_SANDBOX_URL")
	}
	if worker := SandboxURL(); worker != "" {
		u, err := url.Parse(worker)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return fmt.Errorf("compile worker must have an HTTP(S) URL without credentials, query, or fragment")
		}
	}
	return nil
}

// Render supplies private addresses as host:port, without a URL scheme.
func SandboxURL() string {
	if hostport := strings.TrimSpace(os.Getenv("COLAB_SANDBOX_HOSTPORT")); hostport != "" {
		return "http://" + hostport
	}
	return strings.TrimRight(os.Getenv("COLAB_SANDBOX_URL"), "/")
}

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

// Service runs gateway-side compiles: request validation, the per-user
// single-flight slot, the quota charge (refunded when the backend never
// compiled) and the call to whichever backend is configured. The
// /colab/compile handler and the project compile route share it.
type Service struct {
	pool          *pgxpool.Pool
	httpClient    *http.Client
	pythonURL     string
	sandboxURL    string
	internalToken string
}

// NewService reads the backend configuration once. pythonURL is the
// existing Python backend base URL, used only as the fallback path when
// COLAB_SANDBOX_URL is unset.
func NewService(pool *pgxpool.Pool, httpClient *http.Client, pythonURL string) *Service {
	return &Service{
		pool:          pool,
		httpClient:    httpClient,
		pythonURL:     pythonURL,
		sandboxURL:    SandboxURL(),
		internalToken: os.Getenv("INTERNAL_API_TOKEN"),
	}
}

// Handler returns the gin handler for POST /api/v1/colab/compile.
func Handler(pool *pgxpool.Pool, httpClient *http.Client, pythonURL string) gin.HandlerFunc {
	s := NewService(pool, httpClient, pythonURL)
	return func(c *gin.Context) {
		uid := c.GetString("user_id")
		if uid == "" {
			apierror.Abort(c, http.StatusUnauthorized, "unauthenticated", "Authentication is required")
			return
		}
		body, readErr := io.ReadAll(c.Request.Body)
		if readErr != nil {
			apierror.Abort(c, http.StatusBadRequest, "invalid_body", "Could not read the request body")
			return
		}
		if respBody, ok := s.Run(c, uid, body); ok {
			c.Data(http.StatusOK, "application/json", respBody)
		}
	}
}

// Run validates body (the /colab/compile request: latex_source, engine,
// files), takes uid's compile slot, charges the quota and calls the compile
// backend. On a 200 from the backend it returns that JSON body and ok=true.
// Otherwise it has already written the gateway error response to c and
// returns ok=false.
func (s *Service) Run(c *gin.Context, uid string, body []byte) (respBody []byte, ok bool) {
	// Validate first: a malformed request is refused with 400 before it
	// takes the single-flight slot or spends any quota.
	if code, message := validateRequest(body); code != "" {
		apierror.Abort(c, http.StatusBadRequest, code, message)
		return nil, false
	}

	release, started, lockErr := start(c.Request.Context(), uid)
	if lockErr != nil {
		c.Header("Retry-After", strconv.Itoa(retryAfterSeconds))
		apierror.Abort(c, http.StatusServiceUnavailable, "compile_unavailable", "The compile service is temporarily unavailable")
		return nil, false
	}
	if !started {
		c.Header("Retry-After", strconv.Itoa(retryAfterSeconds))
		apierror.Abort(c, http.StatusTooManyRequests, "compile_in_progress", "You already have a compile in progress")
		return nil, false
	}
	defer release()

	receipt, _, _, err := quota.Charge(c.Request.Context(), s.pool, uid, compileQuotaCost)
	if err != nil {
		if exc, isExceeded := quota.AsExceeded(err); isExceeded {
			c.Header("Retry-After", strconv.Itoa(int(exc.RetryAfter.Seconds())+1))
			apierror.Abort(c, http.StatusTooManyRequests, "quota_exceeded", "Your "+exc.Window+" usage budget is spent. Try again later")
			return nil, false
		}
		slog.Error("colab: quota check failed", "err", err)
		if shared.Required() {
			c.Header("Retry-After", strconv.Itoa(retryAfterSeconds))
			apierror.Abort(c, http.StatusServiceUnavailable, "compile_unavailable", "The compile service is temporarily unavailable")
			return nil, false
		}
		// Availability over strict accounting (quota.Consume itself
		// already falls back to a local counter on a Postgres error) —
		// reaching this branch means even that failed, so degrade to
		// "allow" rather than lock every user out on an infra fault.
	}

	var status int
	var header http.Header
	var upstreamErr error
	if s.sandboxURL != "" {
		status, header, respBody, upstreamErr = callSandbox(c.Request.Context(), s.httpClient, s.sandboxURL, s.internalToken, body)
	} else {
		status, header, respBody, upstreamErr = callPython(c.Request.Context(), s.httpClient, s.pythonURL, s.internalToken, c.Request.Header, body)
	}
	if upstreamErr != nil || status != http.StatusOK {
		// The caller never got the compile they paid for: unreachable,
		// busy, or failing backend. A compile that ran (even one whose
		// LaTeX failed or timed out, answered 200) stays charged.
		// Detached context: the refund must survive a client disconnect.
		receipt.Refund(context.WithoutCancel(c.Request.Context()), s.pool)
	}
	if upstreamErr != nil {
		slog.Error("colab: compile backend unreachable", "err", upstreamErr, "sandbox", s.sandboxURL != "")
		c.Header("Retry-After", strconv.Itoa(retryAfterSeconds))
		apierror.Abort(c, http.StatusServiceUnavailable, "compile_unavailable", "The compile service is temporarily unavailable")
		return nil, false
	}
	if status != http.StatusOK {
		upstreamError(c, status, header)
		return nil, false
	}
	return respBody, true
}

type compileRequest struct {
	LatexSource *string               `json:"latex_source"`
	Engine      string                `json:"engine"`
	Files       []texsandbox.WireFile `json:"files"`
}

// validateRequest applies the compile contract (same limits as the
// backends) and returns an error code and message, or "" when valid.
func validateRequest(body []byte) (string, string) {
	var req compileRequest
	if err := json.Unmarshal(body, &req); err != nil {
		return "invalid_body", "Request body must be JSON with latex_source"
	}
	if req.LatexSource == nil {
		return "invalid_body", "latex_source is required"
	}
	switch n := utf8.RuneCountInString(*req.LatexSource); {
	case n == 0:
		return "invalid_source", "latex_source must not be empty"
	case n > maxSourceRunes:
		return "invalid_source", "latex_source must be at most 100,000 characters"
	case req.Engine != "" && req.Engine != "pdflatex":
		return "invalid_engine", "engine must be pdflatex"
	}
	if _, err := texsandbox.DecodeFiles(req.Files); err != nil {
		return "invalid_files", "files: " + err.Error()
	}
	return "", ""
}

// upstreamError answers a failed backend call in the gateway's own error
// contract. Backend bodies are not passed through: their shape differs
// from the gateway's and could carry internal detail.
func upstreamError(c *gin.Context, status int, header http.Header) {
	retryAfter := header.Get("Retry-After")
	if retryAfter == "" {
		retryAfter = strconv.Itoa(retryAfterSeconds)
	}
	switch {
	case status == http.StatusUnauthorized:
		apierror.Abort(c, http.StatusUnauthorized, "token_invalid", "Session is no longer valid; sign in again")
	case status == http.StatusTooManyRequests:
		c.Header("Retry-After", retryAfter)
		apierror.Abort(c, http.StatusTooManyRequests, "compile_in_progress", "You already have a compile in progress")
	case status == http.StatusServiceUnavailable:
		c.Header("Retry-After", retryAfter)
		apierror.Abort(c, http.StatusServiceUnavailable, "compile_busy", "All compile workers are busy; try again shortly")
	case status < 500 && status != http.StatusForbidden:
		// The gateway already validated the request, so a backend 4xx is a
		// contract mismatch between the two services, not the caller's fault.
		slog.Error("colab: compile backend refused a validated request", "status", status)
		apierror.Abort(c, http.StatusBadGateway, "compile_failed", "The compile service could not process the request")
	default:
		// 403 means the backend refused the gateway's own credential: a
		// deployment misconfiguration, never the caller's fault.
		slog.Error("colab: compile backend failed", "status", status)
		apierror.Abort(c, http.StatusBadGateway, "compile_failed", "The compile service failed; try again")
	}
}

func callSandbox(ctx context.Context, client *http.Client, baseURL, token string, body []byte) (int, http.Header, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/compile", bytes.NewReader(body))
	if err != nil {
		return 0, nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Token", token)
	return doWithTimeout(client, req)
}

// callPython forwards the caller's Firebase token plus the gateway's
// X-Internal-Token: Python serves compiles only for the gateway, which has
// already charged the quota and holds the per-user single-flight slot.
func callPython(ctx context.Context, client *http.Client, baseURL, token string, inHeaders http.Header, body []byte) (int, http.Header, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/api/v1/colab/compile", bytes.NewReader(body))
	if err != nil {
		return 0, nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Token", token)
	if auth := inHeaders.Get("Authorization"); auth != "" {
		req.Header.Set("Authorization", auth)
	}
	return doWithTimeout(client, req)
}

// doWithTimeout sends req (up to ~15 MiB: source plus base64 project files)
// and reads a bounded response.
func doWithTimeout(client *http.Client, req *http.Request) (int, http.Header, []byte, error) {
	ctx, cancel := context.WithTimeout(req.Context(), sandboxCallTimeout)
	defer cancel()
	resp, err := client.Do(req.WithContext(ctx))
	if err != nil {
		return 0, nil, nil, err
	}
	defer resp.Body.Close()
	// A worker response contains at most an 8 MiB PDF encoded as base64.
	const maxResponseBytes = 12 * 1024 * 1024
	b, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes+1))
	if len(b) > maxResponseBytes {
		return 0, nil, nil, fmt.Errorf("compile response exceeds limit")
	}
	if err != nil {
		return 0, nil, nil, err
	}
	return resp.StatusCode, resp.Header, b, nil
}
