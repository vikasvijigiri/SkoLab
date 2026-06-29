import uuid
import time
import logging
import contextvars

logger = logging.getLogger("skolab.telemetry")

trace_id_var: contextvars.ContextVar[str] = contextvars.ContextVar(
    "trace_id", default=""
)
span_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("span_id", default="")


class Span:
    def __init__(self, name: str):
        self.name = name
        self.trace_id = trace_id_var.get() or str(uuid.uuid4()).replace("-", "")
        self.parent_span_id = span_id_var.get() or None
        self.span_id = str(uuid.uuid4())[:16]
        self.start_time = time.perf_counter()

    def __enter__(self):
        self.trace_token = trace_id_var.set(self.trace_id)
        self.span_token = span_id_var.set(self.span_id)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        latency_ms = int((time.perf_counter() - self.start_time) * 1000)
        logger.info(
            f"Span '{self.name}' completed in {latency_ms}ms",
            extra={
                "endpoint": self.name,
                "method": "SPAN",
                "status_code": 500 if exc_type else 200,
                "latency_ms": latency_ms,
            },
        )
        trace_id_var.reset(self.trace_token)
        span_id_var.reset(self.span_token)


class Tracer:
    def start_as_current_span(self, name: str) -> Span:
        return Span(name)


tracer = Tracer()

# Instrument httpx globally if available
try:
    import httpx

    _original_async_send = httpx.AsyncClient.send
    _original_sync_send = httpx.Client.send

    async def _traced_async_send(self, request: httpx.Request, *args, **kwargs):
        url_str = str(request.url)
        # Skip internal metrics/health calls to avoid infinite loops or noise
        if "health" in url_str or "metrics" in url_str:
            return await _original_async_send(self, request, *args, **kwargs)

        if "openalex.org" in url_str:
            try:
                from app.main import metrics_store

                metrics_store.increment_openalex_requests_sync()
            except Exception:
                pass

        span_name = f"HTTP {request.method} {request.url.host}"
        start_time = time.perf_counter()
        status_code = 500
        with tracer.start_as_current_span(span_name) as span:
            request.headers["traceparent"] = f"00-{span.trace_id}-{span.span_id}-01"
            try:
                response = await _original_async_send(self, request, *args, **kwargs)
                status_code = response.status_code
                return response
            except Exception as e:
                status_code = 500
                raise e
            finally:
                latency_ms = int((time.perf_counter() - start_time) * 1000)
                try:
                    from app.main import metrics_store

                    metrics_store.record_outbound_request(
                        request.url.host, status_code, latency_ms
                    )
                except Exception:
                    pass

    def _traced_sync_send(self, request: httpx.Request, *args, **kwargs):
        url_str = str(request.url)
        if "health" in url_str or "metrics" in url_str:
            return _original_sync_send(self, request, *args, **kwargs)

        if "openalex.org" in url_str:
            try:
                from app.main import metrics_store

                metrics_store.increment_openalex_requests_sync()
            except Exception:
                pass

        span_name = f"HTTP {request.method} {request.url.host}"
        start_time = time.perf_counter()
        status_code = 500
        with tracer.start_as_current_span(span_name) as span:
            request.headers["traceparent"] = f"00-{span.trace_id}-{span.span_id}-01"
            try:
                response = _original_sync_send(self, request, *args, **kwargs)
                status_code = response.status_code
                return response
            except Exception as e:
                status_code = 500
                raise e
            finally:
                latency_ms = int((time.perf_counter() - start_time) * 1000)
                try:
                    from app.main import metrics_store

                    metrics_store.record_outbound_request(
                        request.url.host, status_code, latency_ms
                    )
                except Exception:
                    pass

    httpx.AsyncClient.send = _traced_async_send
    httpx.Client.send = _traced_sync_send
    logger.info(
        "OpenTelemetry trace wrapper successfully registered for httpx HTTP clients."
    )
except ImportError:
    pass
