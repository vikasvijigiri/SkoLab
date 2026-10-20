// Package ai is SkoLab's writing analytics: the AI improver and the
// originality check.
//
// The improver sends a passage the reader selected to a language model (the
// Python service, which only calls Groq) after masking everything the model
// must not touch (math, citations, references, commands: see Mask), then
// shows only the edits Verify proves safe. The originality check (Check)
// compares the document's prose with OpenAlex in the background and keeps
// the report.
//
// Both are bounded twice: a daily allowance per person and per feature, and
// a global daily budget per provider set below the provider's free tier, so
// SkoLab never runs past what it can use for free.
//
// Routes (authenticated):
//
//	GET  /api/v1/ai/status                               what is available and left today
//	POST /api/v1/workspaces/:id/ai/improve               suggestions for a passage (owner, editor)
//	POST /api/v1/workspaces/:id/ai/originality           start a check (owner, editor)
//	GET  /api/v1/workspaces/:id/ai/originality           recent reports (any member)
//	GET  /api/v1/workspaces/:id/ai/originality/:report   one report (any member)
package ai

import (
	"context"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"

	"github.com/skolab/backend-go/internal/apierror"
)

// Config is read from the environment once at startup (see FromEnv).
type Config struct {
	// The improver: on when the Python service has a model key (GROQ_API).
	ImproveEnabled bool
	// The originality check: on when OPENALEX_API_KEY is set. Without a key
	// OpenAlex allows a tenth of the searches, too few to share.
	OriginalityEnabled bool
	OpenAlexKey        string

	ImprovePerUser     int // AI_IMPROVE_DAILY_PER_USER, default 30
	OriginalityPerUser int // AI_ORIGINALITY_DAILY_PER_USER, default 5
	ModelDaily         int // AI_MODEL_DAILY_LIMIT, default 800 (Groq's free tier: 1,000)
	SearchDaily        int // AI_SEARCH_DAILY_LIMIT, default 800 (OpenAlex's free key: 1,000)
	MaxProbes          int // sentences searched per originality check, default 20

	// More sources for the originality check (sources.go). Each needs a
	// free key except Europe PMC and arXiv, on unless switched off.
	SemanticScholarKey string // SEMANTIC_SCHOLAR_API_KEY
	COREKey            string // CORE_API_KEY
	TavilyKey          string // TAVILY_API_KEY (the web)
	EuropePMC          bool   // AI_SOURCE_EUROPE_PMC, default on
	ArXiv              bool   // AI_SOURCE_ARXIV, default on

	// Daily search budgets per source, all checks together.
	SemanticScholarDaily int // AI_SEARCH_DAILY_SEMANTIC_SCHOLAR, default 2000
	COREDaily            int // AI_SEARCH_DAILY_CORE, default 500
	EuropePMCDaily       int // AI_SEARCH_DAILY_EUROPE_PMC, default 2000
	ArXivDaily           int // AI_SEARCH_DAILY_ARXIV, default 500
	// Tavily's free plan is 1,000 searches a month.
	WebDaily    int // AI_SEARCH_DAILY_WEB, default 30
	WebPerCheck int // AI_SEARCH_WEB_PER_CHECK, default 6

	// The AI-writing estimate (Python's /api/v1/ai/detect, which calls the
	// detector service): AI_WRITING_ESTIMATE=on once that is deployed; off by default.
	WritingEstimate bool
}

// FromEnv reads Config.
func FromEnv() Config {
	key := strings.TrimSpace(os.Getenv("OPENALEX_API_KEY"))
	return Config{
		ImproveEnabled:     strings.TrimSpace(os.Getenv("GROQ_API")+os.Getenv("GROQ_API_KEY")) != "",
		OriginalityEnabled: key != "",
		OpenAlexKey:        key,
		ImprovePerUser:     envInt("AI_IMPROVE_DAILY_PER_USER", 30),
		OriginalityPerUser: envInt("AI_ORIGINALITY_DAILY_PER_USER", 5),
		ModelDaily:         envInt("AI_MODEL_DAILY_LIMIT", 800),
		SearchDaily:        envInt("AI_SEARCH_DAILY_LIMIT", 800),
		MaxProbes:          envInt("AI_ORIGINALITY_SEARCHES", 20),

		SemanticScholarKey: strings.TrimSpace(os.Getenv("SEMANTIC_SCHOLAR_API_KEY")),
		COREKey:            strings.TrimSpace(os.Getenv("CORE_API_KEY")),
		TavilyKey:          strings.TrimSpace(os.Getenv("TAVILY_API_KEY")),
		EuropePMC:          envOn("AI_SOURCE_EUROPE_PMC"),
		ArXiv:              envOn("AI_SOURCE_ARXIV"),

		SemanticScholarDaily: envInt("AI_SEARCH_DAILY_SEMANTIC_SCHOLAR", 2000),
		COREDaily:            envInt("AI_SEARCH_DAILY_CORE", 500),
		EuropePMCDaily:       envInt("AI_SEARCH_DAILY_EUROPE_PMC", 2000),
		ArXivDaily:           envInt("AI_SEARCH_DAILY_ARXIV", 500),
		WebDaily:             envInt("AI_SEARCH_DAILY_WEB", 30),
		WebPerCheck:          envInt("AI_SEARCH_WEB_PER_CHECK", 6),

		WritingEstimate: strings.EqualFold(strings.TrimSpace(os.Getenv("AI_WRITING_ESTIMATE")), "on"),
	}
}

// envOn is true unless the variable is set to off, false, no or 0.
func envOn(key string) bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(key))) {
	case "off", "false", "no", "0":
		return false
	}
	return true
}

func envInt(key string, fallback int) int {
	if n, err := strconv.Atoi(strings.TrimSpace(os.Getenv(key))); err == nil && n >= 0 {
		return n
	}
	return fallback
}

// Deps is what the routes need.
type Deps struct {
	Pool      *pgxpool.Pool
	Client    *http.Client // for the Python service and OpenAlex
	PythonURL string
	Token     string // INTERNAL_API_TOKEN, sent to the Python service
	Config    Config
	Reports   ReportStore
	Runner    *Runner
	// Budgets charges daily allowances; Allow by default (quota.Allowance).
	Budgets Budgets
}

// Register mounts the routes on an authenticated group (user_id set).
func Register(r gin.IRoutes, d Deps) {
	h := &handlers{d}
	r.GET("/ai/status", h.status)
	r.POST("/workspaces/:id/ai/improve", h.improve)
	r.POST("/workspaces/:id/ai/originality", h.startCheck)
	r.GET("/workspaces/:id/ai/originality", h.listChecks)
	r.GET("/workspaces/:id/ai/originality/:report", h.getCheck)
}

type handlers struct{ Deps }

func fail(c *gin.Context, status int, code, message string) {
	apierror.Abort(c, status, code, message)
}

func caller(c *gin.Context) (string, bool) {
	c.Header("Cache-Control", "no-store")
	uid := c.GetString("user_id")
	if uid == "" {
		fail(c, http.StatusUnauthorized, "unauthenticated", "Authentication is required")
		return "", false
	}
	return uid, true
}

// ── Metrics ────────────────────────────────────────────────────────────────

type meters struct {
	requests metric.Int64Counter
	provider metric.Int64Counter
	latency  metric.Float64Histogram
	edits    metric.Int64Counter
}

var instruments atomic.Pointer[meters]

// UseMeter enables the package's metrics:
//
//	skolab.ai.requests{feature, outcome}      every improve and originality request
//	skolab.ai.provider.calls{provider, outcome} every model and search call
//	skolab.ai.provider.duration{provider}      seconds per provider call
//	skolab.ai.edits{result}                    model edits shown, or refused and why
func UseMeter(meter metric.Meter) error {
	var m meters
	var err error
	if m.requests, err = meter.Int64Counter("skolab.ai.requests", metric.WithUnit("{request}"), metric.WithDescription("AI requests by feature and outcome")); err != nil {
		return err
	}
	if m.provider, err = meter.Int64Counter("skolab.ai.provider.calls", metric.WithUnit("{call}"), metric.WithDescription("Calls to the model and search providers by outcome")); err != nil {
		return err
	}
	if m.latency, err = meter.Float64Histogram("skolab.ai.provider.duration", metric.WithUnit("s"), metric.WithDescription("Provider call latency"),
		metric.WithExplicitBucketBoundaries(0.1, 0.25, 0.5, 1, 2, 4, 8, 15, 30)); err != nil {
		return err
	}
	if m.edits, err = meter.Int64Counter("skolab.ai.edits", metric.WithUnit("{edit}"), metric.WithDescription("Model edits shown (verified) or refused, by reason")); err != nil {
		return err
	}
	instruments.Store(&m)
	return nil
}

func countRequest(ctx context.Context, feature, outcome string) {
	if m := instruments.Load(); m != nil {
		m.requests.Add(ctx, 1, metric.WithAttributes(attribute.String("feature", feature), attribute.String("outcome", outcome)))
	}
}

func countProvider(ctx context.Context, provider, outcome string, took time.Duration) {
	if m := instruments.Load(); m != nil {
		m.provider.Add(ctx, 1, metric.WithAttributes(attribute.String("provider", provider), attribute.String("outcome", outcome)))
		m.latency.Record(ctx, took.Seconds(), metric.WithAttributes(attribute.String("provider", provider)))
	}
}

func countEdits(ctx context.Context, shown int, refused []string) {
	m := instruments.Load()
	if m == nil {
		return
	}
	if shown > 0 {
		m.edits.Add(ctx, int64(shown), metric.WithAttributes(attribute.String("result", "shown")))
	}
	for _, reason := range refused {
		m.edits.Add(ctx, 1, metric.WithAttributes(attribute.String("result", reason)))
	}
}
