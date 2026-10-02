"""One OTLP metrics/traces pipeline per worker; no public metrics endpoint."""

from __future__ import annotations

import contextvars
import functools
import math
import os
import sys
import time
import uuid
from contextlib import contextmanager

import httpx
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from opentelemetry.trace import SpanKind, StatusCode

trace_id_var = contextvars.ContextVar("trace_id", default="")
span_id_var = contextvars.ContextVar("span_id", default="")
propagator = TraceContextTextMapPropagator()  # No user-controlled baggage.
BOUNDS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120)
PROBES = {"/health", "/livez", "/readyz", "/metrics", "/observability"}
METHODS = {
    "GET",
    "POST",
    "PUT",
    "PATCH",
    "DELETE",
    "HEAD",
    "OPTIONS",
    "CONNECT",
    "TRACE",
}
_provider: TracerProvider | None = None
_httpx_instrumented = False


def export_enabled() -> bool:
    return os.getenv("OTEL_SDK_DISABLED", "false").lower() != "true" and any(
        os.getenv(key)
        for key in (
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
        )
    )


class Telemetry:
    def __init__(self, *, metric_readers=None, span_processors=None, force=False):
        global _provider
        ratio = float(os.getenv("OTEL_TRACES_SAMPLER_ARG", "0.1"))
        if not math.isfinite(ratio) or not 0 <= ratio <= 1:
            raise ValueError("OTEL_TRACES_SAMPLER_ARG must be between 0 and 1")
        resource = Resource(
            {
                "service.name": os.getenv("OTEL_SERVICE_NAME") or "skolab-backend-py",
                "service.instance.id": str(uuid.uuid4()),
                "deployment.environment.name": os.getenv("APP_ENV", "development"),
                "service.version": os.getenv("RENDER_GIT_COMMIT", "local"),
            }
        )
        enabled = export_enabled() and (force or "pytest" not in sys.modules)
        readers, processors = list(metric_readers or []), list(span_processors or [])
        if enabled:
            if (
                os.getenv("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")
                != "http/protobuf"
            ):
                raise ValueError(
                    "SkoLab telemetry requires OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf"
                )
            if os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") or os.getenv(
                "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"
            ):
                readers.append(
                    PeriodicExportingMetricReader(
                        OTLPMetricExporter(timeout=5),
                        export_interval_millis=30000,
                        export_timeout_millis=5000,
                    )
                )
            if os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") or os.getenv(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
            ):
                processors.append(
                    BatchSpanProcessor(OTLPSpanExporter(timeout=5), max_queue_size=2048)
                )
        self.traces = TracerProvider(
            resource=resource, sampler=ParentBased(TraceIdRatioBased(ratio))
        )
        for processor in processors:
            self.traces.add_span_processor(processor)
        self.metrics = MeterProvider(
            resource=resource,
            metric_readers=readers,
            views=[
                View(
                    instrument_name="skolab.http.request.duration",
                    aggregation=ExplicitBucketHistogramAggregation(BOUNDS),
                ),
            ],
        )
        _provider = self.traces
        meter = self.metrics.get_meter("skolab.http")
        self.requests = meter.create_counter("skolab.http.requests", unit="{request}")
        self.duration = meter.create_histogram("skolab.http.request.duration", unit="s")
        self.active = meter.create_up_down_counter(
            "skolab.http.active_requests", unit="{request}"
        )

    def shutdown(self):
        self.metrics.shutdown(timeout_millis=5000)
        self.traces.shutdown()


class Tracer:
    """Existing DB spans now use the actual OTel SDK."""

    @contextmanager
    def start_as_current_span(self, name, **kwargs):
        sdk_tracer = (_provider or trace.get_tracer_provider()).get_tracer("skolab")
        # Exception text/stack frames can contain SQL, URLs or credentials.
        with sdk_tracer.start_as_current_span(
            name,
            record_exception=False,
            set_status_on_exception=False,
            **kwargs,
        ) as span:
            ctx = span.get_span_context()
            trace_token = trace_id_var.set(format(ctx.trace_id, "032x"))
            span_token = span_id_var.set(format(ctx.span_id, "016x"))
            try:
                yield span
            except BaseException:
                span.set_status(StatusCode.ERROR)
                raise
            finally:
                trace_id_var.reset(trace_token)
                span_id_var.reset(span_token)


tracer = Tracer()


class TelemetryMiddleware:
    """Measure the entire ASGI response, including streaming and failures."""

    def __init__(self, app, telemetry):
        self.app, self.telemetry = app, telemetry

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("path") in PROBES:
            return await self.app(scope, receive, send)
        headers = {k.decode("latin1"): v.decode("latin1") for k, v in scope["headers"]}
        method = scope["method"] if scope["method"] in METHODS else "_OTHER"
        attrs = {"http.request.method": method}
        started, status = time.perf_counter(), 500
        self.telemetry.active.add(1, attrs)
        with tracer.start_as_current_span(
            "HTTP request", context=propagator.extract(headers), kind=SpanKind.SERVER
        ) as span:

            async def traced_send(message):
                nonlocal status
                if message["type"] == "http.response.start":
                    status = message["status"]
                await send(message)

            try:
                await self.app(scope, receive, traced_send)
            except BaseException:
                status = 500
                raise
            finally:
                route = getattr(scope.get("route"), "path", "unmatched")
                labels = {
                    **attrs,
                    "http.route": route,
                    "http.response.status_code": status,
                }
                span.update_name(f"{method} {route}")
                span.set_attributes(labels)
                if status >= 500:
                    span.set_status(StatusCode.ERROR)
                self.telemetry.requests.add(1, labels)
                self.telemetry.duration.record(time.perf_counter() - started, labels)
                self.telemetry.active.add(-1, attrs)


def instrument_httpx():
    """Trace outgoing HTTP once; never export paths, queries or headers."""
    global _httpx_instrumented
    # Sentry can wrap send() without preserving function attributes.
    # Keep installation state independently so later calls cannot wrap twice.
    if _httpx_instrumented:
        return
    async_send, sync_send = httpx.AsyncClient.send, httpx.Client.send

    @functools.wraps(async_send)
    async def traced_async(self, request, *args, **kwargs):
        if request.url.path in PROBES or isinstance(
            self._transport, httpx.ASGITransport
        ):
            return await async_send(self, request, *args, **kwargs)
        with tracer.start_as_current_span(
            f"HTTP {request.method}", kind=SpanKind.CLIENT
        ) as span:
            propagator.inject(request.headers)
            span.set_attribute("server.address", request.url.host or "unknown")
            response = await async_send(self, request, *args, **kwargs)
            span.set_attribute("http.response.status_code", response.status_code)
            if response.status_code >= 500:
                span.set_status(StatusCode.ERROR)
            return response

    @functools.wraps(sync_send)
    def traced_sync(self, request, *args, **kwargs):
        if request.url.path in PROBES:
            return sync_send(self, request, *args, **kwargs)
        with tracer.start_as_current_span(
            f"HTTP {request.method}", kind=SpanKind.CLIENT
        ) as span:
            propagator.inject(request.headers)
            span.set_attribute("server.address", request.url.host or "unknown")
            response = sync_send(self, request, *args, **kwargs)
            span.set_attribute("http.response.status_code", response.status_code)
            if response.status_code >= 500:
                span.set_status(StatusCode.ERROR)
            return response

    httpx.AsyncClient.send, httpx.Client.send = traced_async, traced_sync
    _httpx_instrumented = True
