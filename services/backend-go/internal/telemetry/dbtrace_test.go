package telemetry

import (
	"context"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5"
	"go.opentelemetry.io/otel/codes"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func TestDBTracerRecordsOneSpanPerStatementWithoutArguments(t *testing.T) {
	spans := tracetest.NewSpanRecorder()
	tel, err := newWithProviders(sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(spans)), sdkmetric.NewMeterProvider())
	if err != nil {
		t.Fatal(err)
	}
	tracer := tel.DBTracer()
	ctx := context.Background()

	end := tracer.TraceQueryStart(ctx, nil, pgx.TraceQueryStartData{
		SQL:  "\n\t SELECT role\n FROM workspace_members WHERE user_id = $1",
		Args: []any{"secret-user-id"},
	})
	tracer.TraceQueryEnd(end, nil, pgx.TraceQueryEndData{Err: pgx.ErrNoRows})
	end = tracer.TraceQueryStart(ctx, nil, pgx.TraceQueryStartData{SQL: "insert into x values ($1)"})
	tracer.TraceQueryEnd(end, nil, pgx.TraceQueryEndData{Err: errors.New("boom")})

	got := spans.Ended()
	if len(got) != 2 || got[0].Name() != "SELECT" || got[1].Name() != "INSERT" {
		t.Fatalf("spans = %v", got)
	}
	for _, attr := range got[0].Attributes() {
		if attr.Value.AsString() == "secret-user-id" {
			t.Fatal("argument values must never be recorded")
		}
		if attr.Key == "db.query.text" && attr.Value.AsString() != "SELECT role FROM workspace_members WHERE user_id = $1" {
			t.Fatalf("statement = %q", attr.Value.AsString())
		}
	}
	if got[0].Status().Code == codes.Error {
		t.Fatal("no rows is a normal outcome, not an error")
	}
	if got[1].Status().Code != codes.Error {
		t.Fatal("a failed statement must mark its span as an error")
	}
}
