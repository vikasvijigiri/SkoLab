"""Per-URL metrics report from Prometheus, for the endpoint metrics run.

    python services/observability/endpoint_report.py --prometheus http://127.0.0.1:9090 \
        --start <unix> --end <unix> --k6 k6-summary.json --out qa-reports/metrics

Reads what the API exported over OpenTelemetry (through the Collector into
Prometheus) for the run's window: per service and URL, the request count,
rate, 4xx and 5xx shares, and p50/p95/p99 latency from the
skolab.http.request.duration histogram; per span, the same from the
spanmetrics connector (traces). Adds k6's client-side view per URL. Writes
report.json and report.md to --out, appends the Markdown to the job
summary, and prints the JSON between markers so it can be read from the log.
"""
from __future__ import annotations

import argparse
import base64
import json
import math
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path


def query(prom: str, expr: str, at: float) -> list[dict]:
    url = f"{prom}/api/v1/query?" + urllib.parse.urlencode({"query": expr, "time": at})
    with urllib.request.urlopen(url, timeout=30) as response:
        body = json.load(response)
    if body.get("status") != "success":
        raise RuntimeError(f"{expr}: {body}")
    return body["data"]["result"]


def by_key(rows: list[dict], *labels: str) -> dict[tuple, float]:
    out = {}
    for row in rows:
        value = float(row["value"][1])
        if not math.isnan(value):  # NaN: no samples
            out[tuple(row["metric"].get(label, "") for label in labels)] = value
    return out


def gateway_table(prom: str, start: float, end: float) -> list[dict]:
    window = f"{max(int(end - start), 1)}s"
    keys = ("service_name", "http_request_method", "http_route")
    group = "sum by (service_name, http_request_method, http_route)"
    total = by_key(query(prom, f"{group} (increase(skolab_http_requests_total[{window}]))", end), *keys)
    c4 = by_key(query(prom, f'{group} (increase(skolab_http_requests_total{{http_response_status_code=~"4.."}}[{window}]))', end), *keys)
    c5 = by_key(query(prom, f'{group} (increase(skolab_http_requests_total{{http_response_status_code=~"5.."}}[{window}]))', end), *keys)
    quantiles = {}
    for q in (0.5, 0.95, 0.99):
        expr = (f"histogram_quantile({q}, sum by (le, service_name, http_request_method, http_route) "
                f"(increase(skolab_http_request_duration_seconds_bucket[{window}])))")
        quantiles[q] = by_key(query(prom, expr, end), *keys)
    statuses = query(prom, f"sum by (service_name, http_request_method, http_route, http_response_status_code) "
                           f"(increase(skolab_http_requests_total[{window}]))", end)
    codes: dict[tuple, dict[str, int]] = {}
    for row in statuses:
        m = row["metric"]
        key = tuple(m.get(label, "") for label in keys)
        count = round(float(row["value"][1]))
        if count:
            codes.setdefault(key, {})[m.get("http_response_status_code", "?")] = count
    rows = []
    for key, count in sorted(total.items(), key=lambda kv: (kv[0][0], kv[0][2], kv[0][1])):
        if count < 0.5:
            continue
        service, method, route = key
        rows.append({
            "service": service, "method": method, "url": route,
            "requests": round(count), "rps": round(count / max(end - start, 1), 2),
            "pct_4xx": round(100 * c4.get(key, 0) / count, 2), "pct_5xx": round(100 * c5.get(key, 0) / count, 2),
            "p50_ms": round(1000 * quantiles[0.5].get(key, 0), 1),
            "p95_ms": round(1000 * quantiles[0.95].get(key, 0), 1),
            "p99_ms": round(1000 * quantiles[0.99].get(key, 0), 1),
            "status_codes": codes.get(key, {}),
        })
    return rows


def span_table(prom: str, start: float, end: float) -> list[dict]:
    window = f"{max(int(end - start), 1)}s"
    keys = ("service_name", "span_name")
    total = by_key(query(prom, f"sum by (service_name, span_name) (increase(traces_span_metrics_calls_total[{window}]))", end), *keys)
    errors = by_key(query(prom, f'sum by (service_name, span_name) (increase(traces_span_metrics_calls_total{{status_code="STATUS_CODE_ERROR"}}[{window}]))', end), *keys)
    p95 = by_key(query(prom, f"histogram_quantile(0.95, sum by (le, service_name, span_name) (increase(traces_span_metrics_duration_milliseconds_bucket[{window}])))", end), *keys)
    rows = []
    for key, count in sorted(total.items()):
        if count < 0.5:
            continue
        rows.append({"service": key[0], "span": key[1], "spans": round(count),
                     "errors": round(errors.get(key, 0)), "p95_ms": round(p95.get(key, 0), 1)})
    return rows


def k6_table(path: Path | None) -> list[dict]:
    if not path or not path.exists():
        return []
    data = json.loads(path.read_text())
    rows = []
    for name, metric in data["metrics"].items():
        if not name.startswith("http_req_duration{name:"):
            continue
        v = metric["values"]
        if not v.get("count"):
            continue
        thresholds = metric.get("thresholds", {})
        rows.append({"url": name[len("http_req_duration{name:"):-1], "requests": int(v["count"]),
                     "p50_ms": round(v["med"], 1), "p95_ms": round(v["p(95)"], 1), "p99_ms": round(v["p(99)"], 1),
                     "max_ms": round(v["max"], 1),
                     "gate": ", ".join(f"{k} {'ok' if t['ok'] else 'FAILED'}" for k, t in thresholds.items())})
    return sorted(rows, key=lambda r: r["url"])


def markdown(report: dict) -> str:
    lines = [f"## Endpoint metrics ({report['window_seconds']} s of load, {report['commit'][:7]})", ""]
    lines += ["### Server side (OpenTelemetry → Collector → Prometheus)", "",
              "| Service | Method | URL | Requests | req/s | 4xx % | 5xx % | p50 ms | p95 ms | p99 ms |",
              "|---|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for r in report["server"]:
        lines.append(f"| {r['service']} | {r['method']} | `{r['url']}` | {r['requests']} | {r['rps']} | {r['pct_4xx']} | "
                     f"{r['pct_5xx']} | {r['p50_ms']} | {r['p95_ms']} | {r['p99_ms']} |")
    if report["spans"]:
        lines += ["", "### From traces (spanmetrics)", "", "| Service | Span | Spans | Errors | p95 ms |", "|---|---|---:|---:|---:|"]
        for r in report["spans"]:
            lines.append(f"| {r['service']} | `{r['span']}` | {r['spans']} | {r['errors']} | {r['p95_ms']} |")
    if report["client"]:
        lines += ["", "### Client side (k6)", "", "| URL | Requests | p50 ms | p95 ms | p99 ms | max ms | Gate |", "|---|---:|---:|---:|---:|---:|---|"]
        for r in report["client"]:
            lines.append(f"| `{r['url']}` | {r['requests']} | {r['p50_ms']} | {r['p95_ms']} | {r['p99_ms']} | {r['max_ms']} | {r['gate']} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--prometheus", required=True)
    parser.add_argument("--start", type=float, required=True)
    parser.add_argument("--end", type=float, required=True)
    parser.add_argument("--k6", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    prom = args.prometheus.rstrip("/")
    report = {
        "commit": os.environ.get("GITHUB_SHA", "local"),
        "window_seconds": int(args.end - args.start),
        "server": gateway_table(prom, args.start, args.end),
        "spans": span_table(prom, args.start, args.end),
        "client": k6_table(args.k6),
    }
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.json").write_text(json.dumps(report, indent=2))
    md = markdown(report)
    (args.out / "report.md").write_text(md)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(md)
    # Base64, because the runner masks every line of multi-line secrets, and
    # a service-account JSON's "{" and "}" lines would blank out the braces.
    print("ENDPOINT-REPORT-JSON-BEGIN")
    print(base64.b64encode(json.dumps(report, separators=(",", ":")).encode()).decode())
    print("ENDPOINT-REPORT-JSON-END")
    if not report["server"]:
        print("::error::Prometheus has no skolab_http_requests_total samples; did the API export?")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
