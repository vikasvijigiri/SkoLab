"""Generate SkoLab's SLO recording + burn-rate alert rules.

The SLO table below is the source of truth; ``skolab-slo.rules.yml`` is
generated from it so the ~50 near-identical PromQL expressions cannot drift
apart. Stdlib only.

    python generate.py          # rewrite the rules file
    python generate.py --check  # CI: fail if the rules file is stale

Alerting follows the multi-window, multi-burn-rate scheme from the Google SRE
Workbook ("Alerting on SLOs", table 5-8), against a 30-day SLO window:

    severity  budget spent  long window  short window  burn rate
    page      2%            1h           5m            14.4
    page      5%            6h           30m           6
    ticket    10%           3d           6h            1

The long window proves the burn is significant; the short window proves it is
still happening, so an alert resolves within minutes of a fix. Every alert is
also gated on a minimum request count in its long window: at SkoLab's traffic
a single failed request out of five would otherwise read as a 20% error rate.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

OUT = Path(__file__).with_name("skolab-slo.rules.yml")
RUNBOOK = (
    "https://github.com/vikasvijigiri/SkoLab/blob/master/"
    "services/observability/slo/RUNBOOK.md"
)

COMPILE = "/api/v1/colab/compile"
# Long-lived or separately-budgeted routes. WebSocket upgrades are excluded
# because their "duration" is the socket's lifetime, not a response time.
GATEWAY_EXCLUDED = f"{COMPILE}|/ws/.*"

# name, job, routes (PromQL label matcher), objective %, latency threshold (s)
SLOS = [
    {
        "slo": "api-availability",
        "job": "skolab-backend-py",
        "routes": f'http_route!="{COMPILE}"',
        "objective": 99.5,
        "summary": "Python API requests that do not fail with a 5xx",
    },
    {
        "slo": "api-latency",
        "job": "skolab-backend-py",
        "routes": f'http_route!="{COMPILE}"',
        "objective": 95.0,
        "threshold": 1,
        "summary": "Python API requests that complete within 1s",
    },
    {
        "slo": "gateway-availability",
        "job": "skolab-gateway",
        "routes": f'http_route!~"{GATEWAY_EXCLUDED}"',
        "objective": 99.5,
        "summary": "Gateway requests that do not fail with a 5xx",
    },
    {
        "slo": "gateway-latency",
        "job": "skolab-gateway",
        "routes": f'http_route!~"{GATEWAY_EXCLUDED}"',
        "objective": 95.0,
        "threshold": 1,
        "summary": "Gateway requests that complete within 1s",
    },
    {
        "slo": "compile-availability",
        "job": "skolab-gateway",
        "routes": f'http_route="{COMPILE}"',
        "objective": 99.0,
        "summary": "LaTeX compiles that do not fail with a 5xx",
    },
    # The gateway abandons a compile at 25s (colab.sandboxCallTimeout) and
    # answers 503, so slower compiles surface as availability errors; 10s is
    # the latency users should expect for a typical document.
    {
        "slo": "compile-latency",
        "job": "skolab-gateway",
        "routes": f'http_route="{COMPILE}"',
        "objective": 95.0,
        "threshold": 10,
        "summary": "LaTeX compiles that complete within 10s",
    },
]

ENV = 'deployment_environment_name="production"'
WINDOWS = ["5m", "30m", "1h", "6h", "3d"]
# (severity, long window, short window, burn rate, budget share for the text)
ALERTS = [
    ("page", "1h", "5m", 14.4, "2%"),
    ("page", "6h", "30m", 6, "5%"),
    ("ticket", "3d", "6h", 1, "10%"),
]
MIN_REQUESTS = 50  # per long window; below this, ratios are noise


def selector(slo: dict, extra: str = "") -> str:
    parts = [f'job="{slo["job"]}"', ENV, slo["routes"]]
    if extra:
        parts.append(extra)
    return "{" + ",".join(parts) + "}"


def error_ratio(slo: dict, window: str) -> str:
    total = f"sum by (job) (rate(skolab_http_requests_total{selector(slo)}[{window}]))"
    if "threshold" not in slo:
        server_error = 'http_response_status_code=~"5.."'
        bad = (
            "sum by (job) (rate(skolab_http_requests_total"
            f"{selector(slo, server_error)}[{window}]))"
        )
        return f"{bad}\n/\n{total}"
    # OTLP translation may spell the bucket bound "1" or "1.0"; match both.
    # The regex needs a literal dot, written \\. inside a PromQL string.
    le = str(slo["threshold"])
    bucket = 'le=~"' + le + "|" + le + r'\\.0"'
    good = (
        "sum by (job) (rate(skolab_http_request_duration_seconds_bucket"
        f"{selector(slo, bucket)}[{window}]))"
    )
    count = (
        "sum by (job) (rate(skolab_http_request_duration_seconds_count"
        f"{selector(slo)}[{window}]))"
    )
    return f"1 - (\n  {good}\n/\n  {count}\n)"


def block(text: str, indent: int) -> str:
    pad = " " * indent
    return "|-\n" + "\n".join(pad + line for line in text.splitlines())


def alert_name(slo: str) -> str:
    return "SLOBurnRate" + "".join(part.title() for part in slo.split("-"))


def groups() -> list[dict]:
    """Every rule group as data: the rules file and provision.py share it."""
    result = []
    for slo in SLOS:
        name, budget = slo["slo"], round(1 - slo["objective"] / 100, 6)
        rules = [
            {
                "record": "slo:objective:ratio",
                "expr": f"vector({slo['objective'] / 100:g})",
                "labels": {"slo": name, "job": slo["job"]},
            }
        ]
        for window in WINDOWS:
            rules.append(
                {
                    "record": f"slo:sli_error:ratio_rate{window}",
                    "expr": error_ratio(slo, window),
                    "labels": {"slo": name},
                }
            )
        for window in sorted({a[1] for a in ALERTS}, key=WINDOWS.index):
            rules.append(
                {
                    "record": f"slo:sli_requests:increase{window}",
                    "expr": "sum by (job) (increase(skolab_http_requests_total"
                    f"{selector(slo)}[{window}]))",
                    "labels": {"slo": name},
                }
            )
        for severity, long, short, burn, share in ALERTS:
            threshold = round(burn * budget, 6)
            rules.append(
                {
                    "alert": alert_name(name),
                    "expr": (
                        f'slo:sli_error:ratio_rate{long}{{slo="{name}"}} > {threshold:g}\n'
                        f'and slo:sli_error:ratio_rate{short}{{slo="{name}"}} > {threshold:g}\n'
                        f'and slo:sli_requests:increase{long}{{slo="{name}"}} >= {MIN_REQUESTS}'
                    ),
                    "for": "2m" if severity == "page" else "30m",
                    "labels": {"severity": severity, "slo": name, "window": long},
                    "annotations": {
                        "summary": f"{name}: burning error budget {burn:g}x too fast "
                        f"({share} of the 30d budget in {long})",
                        "description": f"SLO: {slo['objective']:g}% of {slo['summary']}. "
                        f"Error ratio over {long} is {{{{ $value | humanizePercentage }}}} "
                        f"(budget allows {budget:.2%}).",
                        "runbook_url": f"{RUNBOOK}#{name}",
                    },
                }
            )
        result.append({"name": f"slo-{name}", "interval": "1m", "rules": rules})
    return result


def flow(mapping: dict) -> str:
    return "{" + ", ".join(f"{k}: {v}" for k, v in mapping.items()) + "}"


def render() -> str:
    out = [
        "# GENERATED by generate.py from its SLO table -- do not edit by hand.",
        "# Load into Grafana Cloud with provision.py (see ../README.md).",
        "groups:",
    ]
    for group in groups():
        out += [
            f"  - name: {group['name']}",
            f"    interval: {group['interval']}",
            "    rules:",
        ]
        for rule in group["rules"]:
            kind = "record" if "record" in rule else "alert"
            expr = rule["expr"]
            out += [
                f"      - {kind}: {rule[kind]}",
                f"        expr: {block(expr, 10) if chr(10) in expr else expr}",
            ]
            if kind == "record":
                out.append(f"        labels: {flow(rule['labels'])}")
                continue
            out.append(f"        for: {rule['for']}")
            out.append("        labels:")
            out += [f"          {k}: {v}" for k, v in rule["labels"].items()]
            out.append("        annotations:")
            out += [
                f"          {k}: {json.dumps(v)}" for k, v in rule["annotations"].items()
            ]
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text = render()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8").replace("\r\n", "\n") != text:
            print(f"{OUT.name} is stale: run python {Path(__file__).name}", file=sys.stderr)
            return 1
        return 0
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
