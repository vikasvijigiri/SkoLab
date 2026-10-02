package telemetry

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	metricspb "go.opentelemetry.io/proto/otlp/collector/metrics/v1"
	tracespb "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/protobuf/proto"
)

func setup(t *testing.T) (*Telemetry, *metric.ManualReader, *tracetest.InMemoryExporter) {
	t.Helper()
	reader := metric.NewManualReader()
	exporter := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSampler(sdktrace.AlwaysSample()), sdktrace.WithSyncer(exporter))
	tel, err := newWithProviders(tp, metric.NewMeterProvider(metric.WithReader(reader)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = tel.Shutdown(context.Background()) })
	return tel, reader, exporter
}

func TestRequestsCountOnceWithSafeRoutesAndRecoveredErrors(t *testing.T) {
	tel, reader, exporter := setup(t)
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(tel.Middleware(), gin.CustomRecovery(func(c *gin.Context, _ any) { c.AbortWithStatus(500) }))
	r.GET("/widgets/:id", func(c *gin.Context) { c.Status(200) })
	r.GET("/boom", func(c *gin.Context) { panic("private payload") })
	r.GET("/gateway-health", func(c *gin.Context) { c.Status(200) })
	for _, path := range []string{"/widgets/private-one", "/widgets/private-two?token=secret", "/unknown/a", "/unknown/b", "/boom", "/gateway-health", "/observability"} {
		r.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", path, nil))
	}
	var data metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &data); err != nil {
		t.Fatal(err)
	}
	var total, active, count int64
	for _, scope := range data.ScopeMetrics {
		for _, m := range scope.Metrics {
			switch m.Name {
			case "skolab.http.requests":
				points := m.Data.(metricdata.Sum[int64]).DataPoints
				if len(points) != 3 {
					t.Fatalf("want 3 bounded route series, got %d", len(points))
				}
				for _, p := range points {
					total += p.Value
					if strings.Contains(p.Attributes.Encoded(attribute.DefaultEncoder()), "private") {
						t.Fatal("raw path leaked")
					}
				}
			case "skolab.http.active_requests":
				for _, p := range m.Data.(metricdata.Sum[int64]).DataPoints {
					active += p.Value
				}
			case "skolab.http.request.duration":
				for _, p := range m.Data.(metricdata.Histogram[float64]).DataPoints {
					count += int64(p.Count)
				}
			}
		}
	}
	if total != 5 || count != 5 || active != 0 {
		t.Fatalf("requests=%d duration count=%d active=%d", total, count, active)
	}
	spans := exporter.GetSpans()
	if len(spans) != 5 {
		t.Fatalf("want one server span per request, got %d", len(spans))
	}
	if spans[4].Status.Code.String() != "Error" {
		t.Fatal("panic was not recorded as error")
	}
}

func TestTraceContextCrossesClientBoundaryWithoutSecrets(t *testing.T) {
	tel, _, exporter := setup(t)
	var header string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header = r.Header.Get("traceparent")
		w.WriteHeader(503)
	}))
	defer upstream.Close()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(tel.Middleware())
	r.GET("/compile", func(c *gin.Context) {
		req, _ := http.NewRequestWithContext(c.Request.Context(), "GET", upstream.URL+"/private?token=secret", nil)
		resp, err := (&http.Client{Transport: tel.Transport(nil)}).Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		_ = resp.Body.Close()
		c.Status(200)
	})
	request := httptest.NewRequest("GET", "/compile", nil)
	request.Header.Set("traceparent", "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01")
	r.ServeHTTP(httptest.NewRecorder(), request)
	if !strings.Contains(header, "1234567890abcdef1234567890abcdef") {
		t.Fatalf("lost parent trace: %s", header)
	}
	spans := exporter.GetSpans()
	if len(spans) != 2 || spans[0].Parent.SpanID() != spans[1].SpanContext.SpanID() {
		t.Fatal("client span is not child of server span")
	}
	for _, span := range spans {
		if strings.Contains(fmtAttributes(span.Attributes), "secret") {
			t.Fatal("secret exported")
		}
	}
}

func fmtAttributes(attrs []attribute.KeyValue) string {
	set := attribute.NewSet(attrs...)
	return set.Encoded(attribute.DefaultEncoder())
}

func TestDisabledConfigurationAndInstanceIdentity(t *testing.T) {
	t.Setenv("OTEL_SDK_DISABLED", "true")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "https://unreachable.invalid")
	tel, err := New(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if ExportEnabled() {
		t.Fatal("SDK disable ignored")
	}
	_, span := tel.Traces.Tracer("test").Start(context.Background(), "disabled")
	if span.IsRecording() {
		t.Fatal("disabled SDK recorded a span")
	}
	span.End()
	_ = tel.Shutdown(context.Background())
	t.Setenv("OTEL_TRACES_SAMPLER_ARG", "2")
	if _, err := New(context.Background()); err == nil {
		t.Fatal("invalid sampler accepted")
	}
	if methodLabel("user-controlled") != "_OTHER" {
		t.Fatal("unbounded method label")
	}
}

func TestRealOTLPDelivery(t *testing.T) {
	var mu sync.Mutex
	paths := map[string][]byte{}
	receiver := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Basic test-only" {
			t.Error("OTLP auth header not decoded")
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		mu.Lock()
		paths[r.URL.Path] = body
		mu.Unlock()
		w.Header().Set("Content-Type", "application/x-protobuf")
		w.WriteHeader(200)
	}))
	defer receiver.Close()
	t.Setenv("OTEL_SDK_DISABLED", "false")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", receiver.URL+"/otlp")
	t.Setenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "")
	t.Setenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "")
	t.Setenv("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Basic%20test-only")
	t.Setenv("OTEL_TRACES_SAMPLER_ARG", "1")
	t.Setenv("OTEL_SERVICE_NAME", "gateway-test")
	tel, err := New(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tel.Shutdown(context.Background()) }()
	tel.requests.Add(context.Background(), 1)
	_, span := tel.Traces.Tracer("test").Start(context.Background(), "compile")
	span.End()
	if err := tel.Metrics.ForceFlush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := tel.Traces.ForceFlush(context.Background()); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	metrics := &metricspb.ExportMetricsServiceRequest{}
	if err := proto.Unmarshal(paths["/otlp/v1/metrics"], metrics); err != nil {
		t.Fatal(err)
	}
	if len(metrics.ResourceMetrics) != 1 {
		t.Fatal("expected one resource payload")
	}
	metric := metrics.ResourceMetrics[0].ScopeMetrics[0].Metrics[0]
	if metric.Name != "skolab.http.requests" || metric.GetSum().DataPoints[0].GetAsInt() != 1 {
		t.Fatal("metric delivery duplicated or lost")
	}
	traces := &tracespb.ExportTraceServiceRequest{}
	if err := proto.Unmarshal(paths["/otlp/v1/traces"], traces); err != nil {
		t.Fatal(err)
	}
	if len(traces.ResourceSpans) != 1 || len(traces.ResourceSpans[0].ScopeSpans[0].Spans) != 1 {
		t.Fatal("trace delivery duplicated or lost")
	}
}
