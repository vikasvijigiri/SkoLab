"""Generate the small availability dashboard from its explicit query definitions."""
import json
from pathlib import Path

DS = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
AVAILABILITY = 'job=~"skolab-gateway-availability|skolab-api-readiness"'
ALL = 'job=~"skolab-gateway-availability|skolab-api-readiness|skolab-complete-journey"'
SPECS = [
    ("Availability by location", f"probe_success{{{AVAILABILITY}}}", "{{job}} / {{probe}}", "timeseries", "percentunit"),
    ("Availability over 30 days (target 99.9%)", f"avg by (job) (avg_over_time(probe_success{{{AVAILABILITY}}}[30d]))", "{{job}}", "stat", "percentunit"),
    ("External response time", f"probe_duration_seconds{{{AVAILABILITY}}}", "{{job}} / {{probe}}", "timeseries", "s"),
    ("Complete journey result", 'probe_success{job="skolab-complete-journey"}', "{{probe}}", "timeseries", "percentunit"),
    ("Complete journey duration", 'probe_duration_seconds{job="skolab-complete-journey"}', "{{probe}}", "timeseries", "s"),
    ("Time since most recent check", f"time() - max by (job) (timestamp(probe_success{{{ALL}}}))", "{{job}}", "stat", "s"),
]


def dashboard():
    panels = []
    for index, (title, expr, legend, kind, unit) in enumerate(SPECS):
        panels.append({
            "id": index + 1, "title": title, "type": kind, "datasource": DS,
            "gridPos": {"h": 8, "w": 12, "x": 12 * (index % 2), "y": 8 * (index // 2)},
            "targets": [{"refId": "A", "expr": expr, "legendFormat": legend, "datasource": DS}],
            "fieldConfig": {"defaults": {"unit": unit}, "overrides": []}, "options": {},
        })
    return {
        "uid": "skolab-availability", "title": "SkoLab availability and complete journey",
        "schemaVersion": 39, "version": 1, "tags": ["skolab", "synthetic-monitoring"],
        "time": {"from": "now-1h", "to": "now"}, "refresh": "30s",
        "__inputs": [{"name": "DS_PROMETHEUS", "label": "Prometheus", "type": "datasource", "pluginId": "prometheus", "pluginName": "Prometheus"}],
        "panels": panels,
    }


if __name__ == "__main__":
    Path(__file__).with_name("availability-dashboard.json").write_text(json.dumps(dashboard(), indent=2) + "\n")
