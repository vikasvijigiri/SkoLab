"""Load the SLO rules and backend dashboard into Grafana Cloud.

Recording rules run in Mimir, next to the data, as data-source-managed rules
(written through Grafana's ruler proxy, so the Grafana service-account token
is the only credential). The burn-rate alerts are Grafana-managed rules in the
"SkoLab monitoring" folder, routed to GRAFANA_CONTACT_POINT, the same way
../provision.py routes the availability alerts. Both come from generate.py's
groups(), so they cannot disagree with the promtool-tested rules file.

    python provision.py                        # dry run: print the plan
    python provision.py --apply                # reconcile rules + dashboard
    python provision.py --apply --contact-email you@example.com
                                               # also create the contact point
    python provision.py --verify               # recorded series exist?

Re-running is safe: groups are replaced whole, and groups or rules that no
longer exist in generate.py are deleted. Stdlib only. Never prints secrets.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import generate

HERE = Path(__file__).resolve().parent
NAMESPACE = "skolab-slo"
FOLDER = "skolab-monitoring"  # shared with ../provision.py
RULE_GROUP = "SkoLab SLOs"
CONTACT_POINT = "skolab-oncall"
REPEAT = {"page": "1h", "ticket": "24h"}


def load_environment(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


class API:
    def __init__(self, base: str, token: str):
        self.base, self.token = base.rstrip("/"), token

    def call(self, method: str, path: str, body=None):
        request = urllib.request.Request(
            self.base + path,
            data=None if body is None else json.dumps(body).encode(),
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "X-Disable-Provenance": "true",  # stay editable in the UI
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode(errors="replace")[:300]
            raise RuntimeError(f"{method} {path} -> HTTP {exc.code}: {detail}") from None
        return json.loads(raw) if raw.strip() else None


def prometheus_uid(grafana: API) -> str:
    if os.environ.get("GRAFANA_PROMETHEUS_UID"):
        return os.environ["GRAFANA_PROMETHEUS_UID"]
    candidates = [
        d["uid"]
        for d in grafana.call("GET", "/api/datasources")
        if d["type"] == "prometheus" and d["name"].endswith("-prom")
    ]
    if len(candidates) != 1:
        raise ValueError("Set GRAFANA_PROMETHEUS_UID to the stack's Prometheus datasource")
    return candidates[0]


def recording_groups() -> list[dict]:
    return [
        {
            "name": g["name"],
            "interval": g["interval"],
            "rules": [r for r in g["rules"] if "record" in r],
        }
        for g in generate.groups()
    ]


def alert_rules(datasource: str, receiver: str) -> list[dict]:
    rules = []
    for group in generate.groups():
        for rule in (r for r in group["rules"] if "alert" in r):
            window, severity = rule["labels"]["window"], rule["labels"]["severity"]
            slo = rule["labels"]["slo"]
            annotations = dict(rule["annotations"])
            # Grafana exposes query results as $values.<refId>, not $value.
            annotations["description"] = annotations["description"].replace(
                "$value |", "$values.A.Value |"
            )
            rules.append(
                {
                    "uid": f"slo-{slo}-{window}",
                    "title": f"{rule['alert']} ({window})",
                    "folderUID": FOLDER,
                    "ruleGroup": RULE_GROUP,
                    "condition": "B",
                    "for": rule["for"],
                    # The expression returns nothing while the SLO is healthy.
                    "noDataState": "OK",
                    "execErrState": "Error",
                    "labels": {
                        **rule["labels"],
                        "application": "skolab",
                        "environment": "production",
                    },
                    "annotations": annotations,
                    "notification_settings": {
                        "receiver": receiver,
                        "group_by": ["grafana_folder", "alertname", "slo"],
                        "group_wait": "30s",
                        "group_interval": "5m",
                        "repeat_interval": REPEAT[severity],
                    },
                    "data": [
                        {
                            "refId": "A",
                            "relativeTimeRange": {"from": 600, "to": 0},
                            "datasourceUid": datasource,
                            "model": {
                                "refId": "A",
                                "expr": rule["expr"],
                                "instant": True,
                                "range": False,
                            },
                        },
                        {
                            "refId": "B",
                            "relativeTimeRange": {"from": 0, "to": 0},
                            "datasourceUid": "__expr__",
                            "model": {
                                "refId": "B",
                                "type": "threshold",
                                "expression": "A",
                                "conditions": [{"evaluator": {"type": "gt", "params": [0]}}],
                            },
                        },
                    ],
                }
            )
    return rules


def ensure_contact_point(grafana: API, email: str | None) -> str:
    name = os.environ.get("GRAFANA_CONTACT_POINT") or CONTACT_POINT
    existing = grafana.call("GET", "/api/v1/provisioning/contact-points")
    if any(c["name"] == name for c in existing):
        return name
    if not email:
        raise ValueError(
            f"Contact point '{name}' does not exist; pass --contact-email to create it"
        )
    grafana.call(
        "POST",
        "/api/v1/provisioning/contact-points",
        {"name": name, "type": "email", "settings": {"addresses": email}},
    )
    print(f"Created contact point '{name}' (email)")
    return name


def apply(grafana: API, email: str | None) -> None:
    datasource = prometheus_uid(grafana)
    receiver = ensure_contact_point(grafana, email)

    # 1. Recording rules in Mimir: replace each group, drop retired ones.
    ruler = f"/api/ruler/{datasource}/api/v1/rules"
    wanted = recording_groups()
    for group in wanted:
        grafana.call("POST", f"{ruler}/{NAMESPACE}", group)
        print(f"Recording rules: {group['name']} ({len(group['rules'])} rules)")
    current = (grafana.call("GET", f"{ruler}?subtype=mimir") or {}).get(NAMESPACE, [])
    for group in current:
        if group["name"] not in {g["name"] for g in wanted}:
            grafana.call("DELETE", f"{ruler}/{NAMESPACE}/{urllib.parse.quote(group['name'])}")
            print(f"Deleted retired recording group {group['name']}")

    # 2. Burn-rate alerts as one Grafana-managed group, replaced atomically.
    if not any(f["uid"] == FOLDER for f in grafana.call("GET", "/api/folders")):
        grafana.call("POST", "/api/folders", {"uid": FOLDER, "title": "SkoLab monitoring"})
    rules = alert_rules(datasource, receiver)
    grafana.call(
        "PUT",
        f"/api/v1/provisioning/folder/{FOLDER}/rule-groups/{urllib.parse.quote(RULE_GROUP)}",
        {"title": RULE_GROUP, "folderUid": FOLDER, "interval": 60, "rules": rules},
    )
    print(f"Alert rules: {len(rules)} burn-rate alerts -> contact point '{receiver}'")

    # 3. The backend metrics dashboard, bound to the real datasource.
    text = (HERE.parent / "grafana-dashboard.json").read_text(encoding="utf-8")
    dashboard = json.loads(text.replace("${DS_PROMETHEUS}", datasource))
    dashboard.pop("__inputs", None)
    dashboard.pop("id", None)
    result = grafana.call(
        "POST",
        "/api/dashboards/db",
        {"dashboard": dashboard, "folderUid": FOLDER, "overwrite": True},
    )
    print(f"Dashboard: {os.environ['GRAFANA_URL'].rstrip('/')}{result['url']}")


def verify(grafana: API) -> int:
    datasource = prometheus_uid(grafana)
    query = "count by (slo) (slo:objective:ratio)"
    url = (
        f"/api/datasources/proxy/uid/{datasource}/api/v1/query?"
        + urllib.parse.urlencode({"query": query})
    )
    found = {r["metric"]["slo"] for r in grafana.call("GET", url)["data"]["result"]}
    expected = {s["slo"] for s in generate.SLOS}
    print(f"Recorded SLOs present: {len(found & expected)}/{len(expected)}")
    rules = grafana.call("GET", "/api/v1/provisioning/alert-rules")
    ours = [r for r in rules if r["uid"].startswith("slo-") and r["folderUID"] == FOLDER]
    print(f"Grafana burn-rate alert rules: {len(ours)}")
    return 0 if found >= expected and len(ours) == len(expected) * len(generate.ALERTS) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--contact-email", help="create the contact point if missing")
    parser.add_argument("--env-file", type=Path, default=HERE.parents[2] / ".env")
    args = parser.parse_args()
    load_environment(args.env_file)

    if not (args.apply or args.verify):
        groups = recording_groups()
        print(f"Would load {sum(len(g['rules']) for g in groups)} recording rules "
              f"in {len(groups)} groups into Mimir namespace '{NAMESPACE}'")
        for rule in alert_rules("<prometheus>", "<contact point>"):
            print(f"Would provision alert {rule['title']} [{rule['labels']['severity']}]")
        return 0
    missing = [k for k in ("GRAFANA_URL", "GRAFANA_TOKEN") if not os.environ.get(k)]
    if missing:
        print("Missing configuration: " + ", ".join(missing), file=sys.stderr)
        return 2
    grafana = API(os.environ["GRAFANA_URL"], os.environ["GRAFANA_TOKEN"])
    try:
        if args.apply:
            apply(grafana, args.contact_email)
        return verify(grafana) if args.verify else 0
    except (ValueError, RuntimeError, urllib.error.URLError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
