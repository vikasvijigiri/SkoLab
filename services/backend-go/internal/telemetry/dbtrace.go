package telemetry

import (
	"context"
	"strings"

	"github.com/jackc/pgx/v5"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

// DBTracer returns a pgx query tracer: one client span per SQL statement,
// so a slow request's trace shows each database round trip and its time.
// Spans carry the operation and the statement text -- the gateway's SQL is
// constant with $n placeholders -- never the argument values.
func (t *Telemetry) DBTracer() pgx.QueryTracer {
	return queryTracer{t.Traces.Tracer("skolab.db")}
}

type queryTracer struct{ tracer trace.Tracer }

func (q queryTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, data pgx.TraceQueryStartData) context.Context {
	operation := sqlOperation(data.SQL)
	ctx, _ = q.tracer.Start(ctx, operation, trace.WithSpanKind(trace.SpanKindClient), trace.WithAttributes(
		attribute.String("db.system.name", "postgresql"),
		attribute.String("db.operation.name", operation),
		attribute.String("db.query.text", truncate(strings.Join(strings.Fields(data.SQL), " "), 2000)),
	))
	return ctx
}

func (q queryTracer) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, data pgx.TraceQueryEndData) {
	span := trace.SpanFromContext(ctx)
	if data.Err != nil && data.Err != pgx.ErrNoRows {
		span.SetStatus(codes.Error, "")
	}
	span.End()
}

// sqlOperation is the statement's first keyword (SELECT, INSERT, WITH...).
func sqlOperation(sql string) string {
	fields := strings.Fields(sql)
	if len(fields) == 0 {
		return "SQL"
	}
	return strings.ToUpper(fields[0])
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
