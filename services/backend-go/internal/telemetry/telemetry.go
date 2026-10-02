// Package telemetry owns the gateway's single OTLP metrics and tracing pipeline.
package telemetry

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

var boundaries = []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120}
var propagator = propagation.TraceContext{}

type Telemetry struct {
	Traces   *sdktrace.TracerProvider
	Metrics  *sdkmetric.MeterProvider
	requests metric.Int64Counter
	duration metric.Float64Histogram
	active   metric.Int64UpDownCounter
}

func ExportEnabled() bool {
	return !strings.EqualFold(os.Getenv("OTEL_SDK_DISABLED"), "true") && (os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != "" || os.Getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT") != "" || os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") != "")
}

// New uses standard OTLP endpoint/header variables. No endpoint means no network.
func New(ctx context.Context) (*Telemetry, error) {
	service := os.Getenv("OTEL_SERVICE_NAME")
	if service == "" {
		service = "skolab-gateway"
	}
	env := os.Getenv("APP_ENV")
	if env == "" {
		env = "development"
	}
	res := resource.NewWithAttributes("", attribute.String("service.name", service),
		attribute.String("service.instance.id", uuid.NewString()),
		attribute.String("deployment.environment.name", env),
		attribute.String("service.version", os.Getenv("RENDER_GIT_COMMIT")))
	ratio := 0.1
	if raw := os.Getenv("OTEL_TRACES_SAMPLER_ARG"); raw != "" {
		value, err := strconv.ParseFloat(raw, 64)
		if err != nil || math.IsNaN(value) || math.IsInf(value, 0) || value < 0 || value > 1 {
			return nil, fmt.Errorf("OTEL_TRACES_SAMPLER_ARG must be between 0 and 1")
		}
		ratio = value
	}
	sampler := sdktrace.ParentBased(sdktrace.TraceIDRatioBased(ratio))
	if strings.EqualFold(os.Getenv("OTEL_SDK_DISABLED"), "true") {
		sampler = sdktrace.NeverSample()
	}
	traceOpts := []sdktrace.TracerProviderOption{sdktrace.WithResource(res), sdktrace.WithSampler(sampler)}
	metricOpts := []sdkmetric.Option{sdkmetric.WithResource(res)}
	if ExportEnabled() {
		if protocol := os.Getenv("OTEL_EXPORTER_OTLP_PROTOCOL"); protocol != "" && protocol != "http/protobuf" {
			return nil, fmt.Errorf("SkoLab telemetry requires OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf")
		}
		if os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != "" || os.Getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT") != "" {
			exporter, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithTimeout(5*time.Second))
			if err != nil {
				return nil, err
			}
			metricOpts = append(metricOpts, sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(30*time.Second), sdkmetric.WithTimeout(5*time.Second))))
		}
		if os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != "" || os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") != "" {
			exporter, err := otlptracehttp.New(ctx, otlptracehttp.WithTimeout(5*time.Second))
			if err != nil {
				return nil, err
			}
			traceOpts = append(traceOpts, sdktrace.WithBatcher(exporter, sdktrace.WithMaxQueueSize(2048)))
		}
	}
	return newWithProviders(sdktrace.NewTracerProvider(traceOpts...), sdkmetric.NewMeterProvider(metricOpts...))
}

func newWithProviders(tp *sdktrace.TracerProvider, mp *sdkmetric.MeterProvider) (*Telemetry, error) {
	t := &Telemetry{Traces: tp, Metrics: mp}
	meter := mp.Meter("skolab.http")
	var err error
	if t.requests, err = meter.Int64Counter("skolab.http.requests", metric.WithUnit("{request}")); err != nil {
		return nil, err
	}
	if t.duration, err = meter.Float64Histogram("skolab.http.request.duration", metric.WithUnit("s"), metric.WithExplicitBucketBoundaries(boundaries...)); err != nil {
		return nil, err
	}
	if t.active, err = meter.Int64UpDownCounter("skolab.http.active_requests", metric.WithUnit("{request}")); err != nil {
		return nil, err
	}
	return t, nil
}

func (t *Telemetry) Shutdown(ctx context.Context) error {
	traceErr := t.Traces.Shutdown(ctx)
	metricErr := t.Metrics.Shutdown(ctx)
	if traceErr != nil {
		return traceErr
	}
	return metricErr
}

func methodLabel(method string) string {
	switch method {
	case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE":
		return method
	}
	return "_OTHER"
}

// Middleware precedes recovery so recovered panics are recorded as 500s.
// Health checks and retired scrape endpoints never generate telemetry.
func (t *Telemetry) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		switch c.Request.URL.Path {
		case "/gateway-health", "/readyz", "/observability", "/metrics":
			c.Next()
			return
		}
		ctx := propagator.Extract(c.Request.Context(), propagation.HeaderCarrier(c.Request.Header))
		method := methodLabel(c.Request.Method)
		ctx, span := t.Traces.Tracer("skolab.http").Start(ctx, "HTTP request", trace.WithSpanKind(trace.SpanKindServer))
		c.Request = c.Request.WithContext(ctx)
		attrs := []attribute.KeyValue{attribute.String("http.request.method", method)}
		t.active.Add(ctx, 1, metric.WithAttributes(attrs...))
		started := time.Now()
		defer func() {
			path := c.FullPath()
			if path == "" {
				path = "unmatched"
			}
			status := c.Writer.Status()
			labels := append(attrs, attribute.String("http.route", path), attribute.Int("http.response.status_code", status))
			span.SetName(method + " " + path)
			span.SetAttributes(labels...)
			if status >= 500 {
				span.SetStatus(codes.Error, "")
			}
			t.requests.Add(ctx, 1, metric.WithAttributes(labels...))
			t.duration.Record(ctx, time.Since(started).Seconds(), metric.WithAttributes(labels...))
			t.active.Add(ctx, -1, metric.WithAttributes(attrs...))
			span.End()
		}()
		propagator.Inject(ctx, propagation.HeaderCarrier(c.Writer.Header()))
		c.Next()
	}
}

type transport struct {
	base   http.RoundTripper
	tracer trace.Tracer
}

// Transport creates client spans and injects W3C context without exporting URLs,
// payloads, credentials, user identifiers or a second set of request metrics.
func (t *Telemetry) Transport(base http.RoundTripper) http.RoundTripper {
	if base == nil {
		base = http.DefaultTransport
	}
	return &transport{base, t.Traces.Tracer("skolab.http.client")}
}

func (t *transport) RoundTrip(req *http.Request) (*http.Response, error) {
	ctx, span := t.tracer.Start(req.Context(), "HTTP "+methodLabel(req.Method), trace.WithSpanKind(trace.SpanKindClient))
	defer span.End()
	req = req.Clone(ctx)
	propagator.Inject(ctx, propagation.HeaderCarrier(req.Header))
	span.SetAttributes(attribute.String("server.address", req.URL.Hostname()))
	resp, err := t.base.RoundTrip(req)
	if err != nil {
		span.SetStatus(codes.Error, "")
	} else {
		span.SetAttributes(attribute.Int("http.response.status_code", resp.StatusCode))
		if resp.StatusCode >= 500 {
			span.SetStatus(codes.Error, "")
		}
	}
	return resp, err
}
