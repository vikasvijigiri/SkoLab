// Package security records security-relevant decisions in one shape.
//
// Every event becomes a structured log line (msg "security_event") and a
// count on the OpenTelemetry counter skolab.security.events{event,outcome},
// so spikes -- credential stuffing, a revoked-token storm, a role probe --
// are visible on dashboards and alertable. Account-level actions (account or
// workspace lifecycle) are also written to the durable security_audit_log
// table via Audit; high-volume denials are deliberately not, so an attack
// cannot turn into database write load.
package security

import (
	"context"
	"log/slog"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
)

// Outcomes.
const (
	Allowed   = "allowed"
	Denied    = "denied"
	Throttled = "throttled"
	Failed    = "failed" // the system could not decide (dependency down)
)

// Event names. Kept as constants so dashboards and alerts can rely on them.
const (
	AuthTokenInvalid     = "auth.token_invalid"
	AuthSessionRevoked   = "auth.session_revoked"
	AuthEmailUnverified  = "auth.email_unverified"
	AuthAnonymousRefused = "auth.anonymous_refused"
	AuthUnavailable      = "auth.unavailable"
	AuthThrottled        = "auth.failed_login_throttled"
	RateLimitUser        = "ratelimit.user"
	WorkspaceDenied      = "authz.workspace_denied"
	SocketTicketInvalid  = "authz.socket_ticket_invalid"
	SocketClosed         = "authz.socket_closed"
	AccountDeleted       = "account.deleted"
	WorkspaceCreated     = "workspace.created"
	WorkspaceRenamed     = "workspace.renamed"
	WorkspaceDeleted     = "workspace.deleted"
)

type Event struct {
	Name        string
	Outcome     string
	UserID      string
	WorkspaceID string
	Reason      string
	IP          string // filled from the request when recorded via gin
	RequestID   string
}

var (
	counter atomic.Pointer[metric.Int64Counter]
	store   atomic.Pointer[pgxpool.Pool]
)

// UseMeter enables the security event counter. Until called, events are
// still logged.
func UseMeter(meter metric.Meter) error {
	c, err := meter.Int64Counter("skolab.security.events", metric.WithUnit("{event}"),
		metric.WithDescription("Security decisions by event and outcome"))
	if err != nil {
		return err
	}
	counter.Store(&c)
	return nil
}

// UseStore enables durable audit rows. A nil pool disables them.
func UseStore(pool *pgxpool.Pool) {
	store.Store(pool)
}

// ClientIP is the caller's address as Cloudflare saw it (Render's edge sets
// CF-Connecting-IP from the TCP connection; clients cannot forge it), else
// the direct peer. Forwarding headers clients control are never trusted here.
func ClientIP(c *gin.Context) string {
	if ip := strings.TrimSpace(c.GetHeader("CF-Connecting-IP")); ip != "" {
		return ip
	}
	return c.RemoteIP()
}

// Record logs and counts an event observed while serving c.
func Record(c *gin.Context, e Event) {
	if e.IP == "" {
		e.IP = ClientIP(c)
	}
	if e.RequestID == "" {
		e.RequestID = c.GetString("request_id")
	}
	if e.UserID == "" {
		e.UserID = c.GetString("user_id")
	}
	RecordContext(c.Request.Context(), e)
}

// RecordContext logs and counts an event outside a request (e.g. a socket's
// background re-authorization).
func RecordContext(ctx context.Context, e Event) {
	attrs := []any{"event", e.Name, "outcome", e.Outcome}
	for _, kv := range [][2]string{{"user_id", e.UserID}, {"workspace_id", e.WorkspaceID},
		{"reason", e.Reason}, {"ip", e.IP}, {"request_id", e.RequestID}} {
		if kv[1] != "" {
			attrs = append(attrs, kv[0], kv[1])
		}
	}
	if sc := trace.SpanContextFromContext(ctx); sc.IsValid() {
		attrs = append(attrs, "trace_id", sc.TraceID().String())
	}
	level := slog.LevelInfo
	if e.Outcome != Allowed {
		level = slog.LevelWarn
	}
	slog.Log(ctx, level, "security_event", attrs...)
	if c := counter.Load(); c != nil {
		(*c).Add(ctx, 1, metric.WithAttributes(
			attribute.String("event", e.Name), attribute.String("outcome", e.Outcome)))
	}
}

// Audit records the event and appends a durable security_audit_log row.
// The action has already happened, so a failed write is logged (and counted
// as audit.write_failed), never turned into a failed request.
func Audit(c *gin.Context, e Event) {
	Record(c, e)
	pool := store.Load()
	if pool == nil {
		return
	}
	if e.IP == "" {
		e.IP = ClientIP(c)
	}
	if e.UserID == "" {
		e.UserID = c.GetString("user_id")
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(c.Request.Context()), 3*time.Second)
	defer cancel()
	_, err := pool.Exec(ctx, `
		INSERT INTO security_audit_log (occurred_at, event, outcome, actor_id, workspace_id, ip, request_id, reason)
		VALUES ($1, $2, $3, NULLIF($4, ''), NULLIF($5, ''), NULLIF($6, ''), NULLIF($7, ''), NULLIF($8, ''))`,
		time.Now().UTC(), e.Name, e.Outcome, e.UserID, e.WorkspaceID, e.IP,
		c.GetString("request_id"), e.Reason)
	if err != nil {
		RecordContext(ctx, Event{Name: "audit.write_failed", Outcome: Failed, Reason: e.Name})
	}
}
