"""Task 9 — /support/metrics is typed."""

from app.schemas.support import SupportMetricsResponse


async def test_support_metrics_parses(client):
    r = await client.get("/api/v1/support/metrics")
    assert r.status_code == 200, r.text
    model = SupportMetricsResponse(**r.json())
    assert "vip_first_response_minutes" in model.sla_targets
