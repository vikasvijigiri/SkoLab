"""Answer "did this request reach production?" from Grafana Cloud.

Lists every gateway and Python trace in a time window and the per-route
request counts, so a sign-in or any other user action can be matched by
time. Spans carry no user identifiers by design, so matching is by time and
shape (a browser call starts with an OPTIONS preflight; the synthetic journey
does not).

  GRAFANA_URL=... GRAFANA_TOKEN=... python telemetry_lookup.py --start 2026-10-09T05:00:00Z --end 2026-10-09T05:30:00Z
"""
import argparse
import datetime as dt
import json
import math
import os
import urllib.error
import urllib.parse
import urllib.request

SERVICES = ("skolab-gateway", "skolab-backend-py")
TRACES_UID = "grafanacloud-traces"
METRICS_UID = "grafanacloud-prom"
LATENCY = ("histogram_quantile({q}, sum by (le, service_name, http_route, http_request_method) "
           "(increase(skolab_http_request_duration_seconds_bucket[{w}s])))")
TOTALS = ("sum by (service_name, http_route, http_request_method, http_response_status_code) "
          "(increase(skolab_http_requests_total[{w}s]))")
COUNTS = ("sum by (service_name, http_route, http_request_method, http_response_status_code) "
          "(increase(skolab_http_requests_total[5m])) > 0")
# Background traffic that would otherwise bury user requests.
NOISE = {"DELETE", "DB SELECT"}


def parse_time(value: str) -> int:
    return int(dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())


def clock(seconds: float) -> str:
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).strftime("%H:%M:%S")


class Grafana:
    def __init__(self, url: str, token: str):
        self.url, self.token = url.rstrip("/"), token

    def get(self, path: str, params: dict) -> dict:
        request = urllib.request.Request(f"{self.url}{path}?{urllib.parse.urlencode(params)}",
                                         headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)


def traces(fetch, service: str, start: int, end: int) -> list[tuple[str, str, str, str]]:
    result = fetch(f"/api/datasources/proxy/uid/{TRACES_UID}/api/search",
                   {"q": f'{{ resource.service.name = "{service}" }}', "start": start, "end": end, "limit": 500})
    rows = [(clock(int(t["startTimeUnixNano"]) / 1e9), t.get("rootTraceName", "?"),
             str(t.get("durationMs", "?")), t["traceID"]) for t in result.get("traces", [])]
    return sorted(rows)


def counts(fetch, start: int, end: int) -> list[str]:
    result = fetch(f"/api/datasources/proxy/uid/{METRICS_UID}/api/v1/query_range",
                   {"query": COUNTS, "start": start, "end": end, "step": 300})
    lines = []
    for series in result.get("data", {}).get("result", []):
        m = series["metric"]
        points = ", ".join(f"{clock(float(t))[:5]}={float(v):.0f}" for t, v in series["values"] if float(v) > 0)
        lines.append(f"- {m.get('service_name', '?')} {m.get('http_request_method', '?')} "
                     f"{m.get('http_route', '?')} {m.get('http_response_status_code', '?')}: {points}")
    return sorted(lines)


def instant(fetch, query: str, end: int) -> list[dict]:
    return fetch(f"/api/datasources/proxy/uid/{METRICS_UID}/api/v1/query",
                 {"query": query, "time": end}).get("data", {}).get("result", [])


def health(fetch, start: int, end: int) -> list[str]:
    """One row per route over the whole window: requests, 4xx, 5xx and latency."""
    window = end - start
    routes: dict[tuple, dict] = {}
    for series in instant(fetch, TOTALS.format(w=window), end):
        m, n = series["metric"], round(float(series["value"][1]))
        row = routes.setdefault((m.get("service_name", "?"), m.get("http_request_method", "?"), m.get("http_route", "?")),
                                {"requests": 0, "4xx": 0, "5xx": 0})
        row["requests"] += n
        code = m.get("http_response_status_code", "")
        if code[:1] in ("4", "5"):
            row[code[0] + "xx"] += n
    for q in ("0.5", "0.95", "0.99"):
        for series in instant(fetch, LATENCY.format(q=q, w=window), end):
            m, v = series["metric"], float(series["value"][1])
            key = (m.get("service_name", "?"), m.get("http_request_method", "?"), m.get("http_route", "?"))
            if key in routes and not math.isnan(v):  # NaN when a route had no requests
                routes[key]["p" + q[2:].ljust(2, "0")] = v * 1000
    lines = ["| Service | Route | Requests | 4xx | 5xx | p50 ms | p95 ms | p99 ms |", "|---|---|---|---|---|---|---|---|"]
    for (service, method, route), r in sorted(routes.items(), key=lambda kv: -kv[1]["requests"]):
        if r["requests"]:
            ms = [f"{r[k]:.0f}" if k in r else "-" for k in ("p50", "p95", "p99")]
            lines.append(f"| {service} | {method} {route} | {r['requests']} | {r['4xx']} | {r['5xx']} | {' | '.join(ms)} |")
    return lines if len(lines) > 2 else ["- no requests"]


def section(build) -> list[str]:
    """Runs one part of the report; a failing data source is reported, not fatal."""
    try:
        return build()
    except urllib.error.HTTPError as error:
        source = error.url.split("?")[0].split("/uid/")[-1]
        return [f"- unavailable: Grafana answered {error.code} for {source}"]


def service_traces(fetch, service: str, start: int, end: int, show_noise: bool) -> list[str]:
    rows = traces(fetch, service, start, end)
    hidden = [r for r in rows if r[1] in NOISE]
    shown = rows if show_noise else [r for r in rows if r[1] not in NOISE]
    out = [f"{len(rows)} traces ({len(hidden)} background hidden)" if not show_noise else f"{len(rows)} traces", ""]
    return out + ([f"- {t} {name} {ms} ms trace {trace_id}" for t, name, ms, trace_id in shown] or ["- none"])


def report(fetch, start: int, end: int, show_noise: bool = False) -> str:
    out = [f"# Production telemetry {clock(start)} to {clock(end)} UTC", ""]
    out += ["## Health per route", ""] + section(lambda: health(fetch, start, end)) + [""]
    for service in SERVICES:
        out += [f"## {service} traces", ""] + section(lambda s=service: service_traces(fetch, s, start, end, show_noise)) + [""]
    out += ["## Requests per 5 minutes", ""] + (section(lambda: counts(fetch, start, end)) or ["- none"])
    return "\n".join(out) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--start", required=True, help="ISO 8601, e.g. 2026-10-09T05:00:00Z")
    parser.add_argument("--end", required=True)
    parser.add_argument("--show-noise", action="store_true", help="include per-minute background traces")
    args = parser.parse_args()
    start, end = parse_time(args.start), parse_time(args.end)
    if not 0 < end - start <= 24 * 3600:
        raise SystemExit("--end must be after --start, at most 24 hours later")
    grafana = Grafana(os.environ["GRAFANA_URL"], os.environ["GRAFANA_TOKEN"])
    text = report(grafana.get, start, end, args.show_noise)
    print(text, end="")
    if "- unavailable:" in text:
        raise SystemExit("Some Grafana data sources did not answer; see the report above")


if __name__ == "__main__":
    main()
