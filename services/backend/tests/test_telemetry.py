"""Exercise actual SDK collection: single recording, privacy, W3C propagation."""

import httpx
import pytest
from fastapi import FastAPI
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from starlette.responses import StreamingResponse

from app.core import telemetry as otel


@pytest.fixture
def runtime(monkeypatch):
    monkeypatch.setenv("OTEL_SDK_DISABLED", "false")
    for key in (
        "OTEL_EXPORTER_OTLP_ENDPOINT",
        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    ):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("OTEL_TRACES_SAMPLER_ARG", "1")
    monkeypatch.setattr(otel, "_provider", otel._provider)
    reader, exporter = InMemoryMetricReader(), InMemorySpanExporter()
    tel = otel.Telemetry(
        metric_readers=[reader], span_processors=[SimpleSpanProcessor(exporter)]
    )
    yield tel, reader, exporter
    tel.shutdown()


def points(reader):
    result = {}
    for resource in reader.get_metrics_data().resource_metrics:
        for scope in resource.scope_metrics:
            for metric in scope.metrics:
                result[metric.name] = metric.data.data_points
    return result


@pytest.mark.asyncio
async def test_one_recording_templates_errors_streaming_and_probes(runtime):
    tel, reader, exporter = runtime
    app = FastAPI()

    @app.get("/widgets/{widget_id}")
    async def widget(widget_id: str):
        return {"ok": True}

    @app.get("/boom")
    async def boom():
        raise RuntimeError("private token")

    @app.get("/stream")
    async def stream():
        async def chunks():
            yield b"hello"
            yield b"world"

        return StreamingResponse(chunks())

    app.add_middleware(otel.TelemetryMiddleware, telemetry=tel)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        for path in (
            "/widgets/private-one",
            "/widgets/private-two?token=secret",
            "/unknown/a",
            "/unknown/b",
            "/boom",
            "/stream",
            "/health",
            "/metrics",
        ):
            await client.get(path)
    data = points(reader)
    requests = data["skolab.http.requests"]
    assert sum(p.value for p in requests) == 6
    assert len(requests) == 4
    assert sum(p.count for p in data["skolab.http.request.duration"]) == 6
    assert sum(p.value for p in data["skolab.http.active_requests"]) == 0
    assert {p.attributes["http.route"] for p in requests} == {
        "/widgets/{widget_id}",
        "unmatched",
        "/boom",
        "/stream",
    }
    assert any(p.attributes["http.response.status_code"] == 500 for p in requests)
    spans = exporter.get_finished_spans()
    assert len(spans) == 6
    assert "private" not in str([(s.name, dict(s.attributes), s.events) for s in spans])


@pytest.mark.asyncio
async def test_client_propagation_and_no_sensitive_attributes(runtime):
    _, _, exporter = runtime
    otel.instrument_httpx()
    original = httpx.AsyncClient.send
    otel.instrument_httpx()
    assert httpx.AsyncClient.send is original
    seen = []

    async def upstream(request):
        seen.append(request.headers["traceparent"])
        return httpx.Response(503)

    parent = otel.propagator.extract(
        {"traceparent": "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"}
    )
    with otel.tracer.start_as_current_span("parent", context=parent):
        async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as client:
            await client.get("https://example.test/private?token=secret")
    assert "1234567890abcdef1234567890abcdef" in seen[0]
    spans = exporter.get_finished_spans()
    assert len(spans) == 2
    assert spans[0].parent.span_id == spans[1].context.span_id
    assert spans[0].status.status_code.name == "ERROR"
    assert "secret" not in str([(s.name, dict(s.attributes), s.events) for s in spans])
    assert otel.trace_id_var.get() == ""


def test_sync_client_and_failure_cleanup(runtime):
    _, _, exporter = runtime

    def fail(request):
        raise httpx.ConnectError("secret URL", request=request)

    with httpx.Client(transport=httpx.MockTransport(fail)) as client:
        with pytest.raises(httpx.ConnectError):
            client.get("https://example.test/private")
    assert exporter.get_finished_spans()[0].status.status_code.name == "ERROR"
    assert not exporter.get_finished_spans()[0].events
    assert otel.trace_id_var.get() == ""


def test_disabled_export_and_unique_workers(monkeypatch):
    monkeypatch.setenv("OTEL_SDK_DISABLED", "true")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "https://unreachable.invalid")
    monkeypatch.setattr(otel, "_provider", otel._provider)
    assert not otel.export_enabled()
    first, second = otel.Telemetry(), otel.Telemetry()
    try:
        assert (
            first.traces.resource.attributes["service.instance.id"]
            != second.traces.resource.attributes["service.instance.id"]
        )
    finally:
        first.shutdown()
        second.shutdown()


@pytest.mark.asyncio
async def test_another_sdk_wrapper_does_not_cause_double_instrumentation(
    runtime, monkeypatch
):
    _, _, exporter = runtime
    otel.instrument_httpx()
    original = httpx.AsyncClient.send

    async def another_sdk(self, request, **kwargs):
        return await original(self, request, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "send", another_sdk)
    otel.instrument_httpx()
    assert httpx.AsyncClient.send is another_sdk
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200))
    ) as client:
        await client.get("https://example.test/compile")
    assert len(exporter.get_finished_spans()) == 1


def test_real_otlp_delivery_auth_resource_and_single_metric(monkeypatch):
    from http.server import BaseHTTPRequestHandler, HTTPServer
    from threading import Thread
    from opentelemetry.proto.collector.metrics.v1.metrics_service_pb2 import (
        ExportMetricsServiceRequest,
    )
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import (
        ExportTraceServiceRequest,
    )

    received = []

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            payload = self.rfile.read(int(self.headers["Content-Length"]))
            received.append((self.path, self.headers.get("Authorization"), payload))
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.end_headers()

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Receiver)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    monkeypatch.setenv("OTEL_SDK_DISABLED", "false")
    monkeypatch.setenv("OTEL_TRACES_SAMPLER_ARG", "1")
    monkeypatch.setenv(
        "OTEL_EXPORTER_OTLP_ENDPOINT", f"http://127.0.0.1:{server.server_port}/otlp"
    )
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Basic%20test-only")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "python-test")
    monkeypatch.setattr(otel, "_provider", otel._provider)
    for key in (
        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    ):
        monkeypatch.delenv(key, raising=False)
    tel = otel.Telemetry(force=True)
    try:
        tel.requests.add(1, {"http.route": "/compile"})
        with otel.tracer.start_as_current_span("compile"):
            pass
        assert tel.metrics.force_flush(timeout_millis=5000)
        assert tel.traces.force_flush(timeout_millis=5000)
        assert {path for path, _, _ in received} == {
            "/otlp/v1/metrics",
            "/otlp/v1/traces",
        }
        assert all(auth == "Basic test-only" for _, auth, _ in received)
        metric_payload = next(
            payload for path, _, payload in received if path.endswith("metrics")
        )
        metrics = ExportMetricsServiceRequest.FromString(metric_payload)
        resource = metrics.resource_metrics[0]
        attrs = {a.key: a.value.string_value for a in resource.resource.attributes}
        assert attrs["service.name"] == "python-test"
        assert attrs["service.instance.id"]
        requests = [
            m
            for scope in resource.scope_metrics
            for m in scope.metrics
            if m.name == "skolab.http.requests"
        ]
        assert len(requests) == 1
        assert requests[0].sum.data_points[0].as_int == 1
        trace_payload = next(
            payload for path, _, payload in received if path.endswith("traces")
        )
        traces = ExportTraceServiceRequest.FromString(trace_payload)
        assert len(traces.resource_spans[0].scope_spans[0].spans) == 1
    finally:
        tel.shutdown()
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def test_invalid_export_protocol_fails_before_starting_readers(monkeypatch):
    monkeypatch.setenv("OTEL_SDK_DISABLED", "false")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc")
    with pytest.raises(ValueError, match="http/protobuf"):
        otel.Telemetry(force=True)
