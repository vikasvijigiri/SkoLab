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
import os
import urllib.error
import urllib.parse
import urllib.request

SERVICES = ("skolab-gateway", "skolab-backend-py")
TRACES_UID = "grafanacloud-traces"
METRICS_UID = "grafanacloud-prom"
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


def report(fetch, start: int, end: int, show_noise: bool = False) -> str:
    out = [f"# Production telemetry {clock(start)} to {clock(end)} UTC", ""]
    for service in SERVICES:
        rows = traces(fetch, service, start, end)
        hidden = [r for r in rows if r[1] in NOISE]
        shown = rows if show_noise else [r for r in rows if r[1] not in NOISE]
        out += [f"## {service}: {len(rows)} traces ({len(hidden)} background hidden)" if not show_noise
                else f"## {service}: {len(rows)} traces", ""]
        out += [f"- {t} {name} {ms} ms trace {trace_id}" for t, name, ms, trace_id in shown] or ["- none"]
        out.append("")
    out += ["## Requests per 5 minutes", ""] + (counts(fetch, start, end) or ["- none"])
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
    try:
        print(report(grafana.get, start, end, args.show_noise), end="")
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Grafana answered {error.code} for {error.url.split('?')[0]}") from None


if __name__ == "__main__":
    main()
