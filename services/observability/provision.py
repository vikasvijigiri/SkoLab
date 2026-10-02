"""Reconcile SkoLab checks and Grafana alerts without storing credentials in Git.

Uses the documented Grafana provisioning and Synthetic Monitoring REST APIs.
Dry run is the default. Only resources with our stable IDs/jobs are updated.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FOLDER = "skolab-monitoring"
JOURNEY_SECRETS = {
    "skolab-firebase-api-key": "SKOLAB_FIREBASE_API_KEY",
    "skolab-monitor-email": "SKOLAB_SYNTHETIC_EMAIL",
    "skolab-monitor-password": "SKOLAB_SYNTHETIC_PASSWORD",
    "skolab-monitor-workspace": "SKOLAB_SYNTHETIC_WORKSPACE_ID",
}


def load_environment(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


class API:
    def __init__(self, url: str, token: str):
        if not url.startswith("https://"):
            raise ValueError("Monitoring API URLs must use HTTPS")
        self.url, self.token = url.rstrip("/"), token

    def call(self, method: str, path: str, body=None, allow_missing=False):
        request = urllib.request.Request(
            self.url + path, method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json", "X-Disable-Provenance": "true"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as exc:
            if allow_missing and exc.code == 404:
                return None
            # Never print server response bodies: they can echo scripts/secrets.
            raise RuntimeError(f"{method} {path}: HTTP {exc.code}") from None


def check_definitions(probes: list[int], journey_probe: int | None = None):
    config = json.loads((ROOT / "checks.json").read_text())
    definitions = []
    for item in config["availability"]:
        definitions.append({
            "job": item["job"], "target": item["target"], "enabled": True,
            "frequency": config["availability_frequency_ms"], "timeout": config["availability_timeout_ms"],
            "probes": probes, "alertSensitivity": "none", "basicMetricsOnly": True,
            "labels": [{"name": "environment", "value": "production"}, {"name": "service", "value": item["service"]}],
            "settings": {"http": {"method": "GET", "ipVersion": "V4", "validStatusCodes": [200],
                "noFollowRedirects": True, "failIfNotSSL": True,
                "failIfBodyNotMatchesRegexp": item["body_patterns"]}},
        })
    if journey_probe is not None:
        definitions.append({
            "job": config["journey_job"], "target": "https://skolab-gateway.onrender.com",
            "description": "Fresh login, identity sync, compile/PDF validation, tickets and two-peer collaboration delivery.",
            "enabled": True, "frequency": config["journey_frequency_ms"], "timeout": config["journey_timeout_ms"],
            "probes": [journey_probe], "alertSensitivity": "none", "basicMetricsOnly": True,
            "labels": [{"name": "environment", "value": "production"}],
            "settings": {"scripted": {"script": base64.b64encode((ROOT / "journey.js").read_bytes()).decode()}},
        })
    return definitions


def alert_rule(uid, title, expr, datasource, receiver, duration="3m", severity="critical", no_data="Alerting"):
    return {
        "uid": uid, "title": title, "folderUID": FOLDER, "ruleGroup": "SkoLab backend",
        "condition": "B", "for": duration, "noDataState": no_data, "execErrState": "Error",
        "labels": {"application": "skolab", "environment": "production", "severity": severity},
        "annotations": {"summary": title, "description": "Open the SkoLab availability dashboard, compare probe locations, then inspect Render deployment logs and Go/Python traces.",
            "runbook_url": "https://github.com/vikasvijigiri/SkoLab/blob/master/services/observability/RUNBOOK.md"},
        "notification_settings": {"receiver": receiver, "group_by": ["grafana_folder", "alertname"],
            "group_wait": "30s", "group_interval": "5m", "repeat_interval": "4h"},
        "data": [
            {"refId": "A", "relativeTimeRange": {"from": 3600, "to": 0}, "datasourceUid": datasource,
                "model": {"refId": "A", "expr": expr, "instant": True, "range": False, "intervalMs": 1000, "maxDataPoints": 43200}},
            {"refId": "B", "relativeTimeRange": {"from": 0, "to": 0}, "datasourceUid": "__expr__",
                "model": {"refId": "B", "type": "threshold", "expression": "A", "conditions": [
                    {"evaluator": {"type": "gt", "params": [0]}, "operator": {"type": "and"},
                        "query": {"params": ["A"]}, "reducer": {"type": "last", "params": []}, "type": "query"}]}},
        ],
    }


def availability_expression(job: str) -> str:
    # Alert only after neither location has succeeded for three minutes.
    # Missing data gets its own signal rather than masquerading as healthy.
    return (f'max(max_over_time(probe_success{{job="{job}"}}[3m])) < bool 1 '
            f'or absent_over_time(probe_success{{job="{job}"}}[5m])')


def reconcile_check(api: API, definition: dict, existing: list[dict]):
    matches = [item for item in existing if item["job"] == definition["job"]]
    if len(matches) > 1:
        raise RuntimeError(f"Duplicate check job {definition['job']}; resolve duplicates before applying")
    body = dict(definition)
    if matches:
        body["id"] = matches[0]["id"]
    validation = api.call("POST", "/api/v1/check/validate", body)
    if not validation["valid"]:
        fields = [item.get("field", "check") for item in validation.get("findings", [])]
        raise RuntimeError(f"Check validation failed: {definition['job']}: {fields}")
    path = f"/api/v1/check/{body['id']}" if matches else "/api/v1/check"
    result = api.call("POST", path, body)
    print(f"Reconciled check: {definition['job']} (id={result['id']})")


def choose_probes(items: list[dict], names: list[str]) -> list[int]:
    result = []
    for name in names:
        matches = [item for item in items if item["name"].casefold() == name.casefold() and item.get("online") and item.get("public")]
        if len(matches) != 1:
            raise RuntimeError(f"Public probe unavailable: {name}; use --probes with two available names")
        result.append(matches[0]["id"])
    if len(result) != 2 or len(set(result)) != 2:
        raise ValueError("Availability requires two distinct public probes")
    return result


MONITOR_WORKSPACE_TITLE = "SkoLab monitoring (automated, do not use)"
# A fixed key makes creation idempotent across deploys: every run gets the
# same workspace back from POST /api/v1/workspaces instead of a new one.
MONITOR_WORKSPACE_KEY = "skolab-synthetic-journey-workspace"
FIREBASE_SIGN_IN = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"


def _configured(value: str) -> bool:
    return bool(value) and not value.startswith(("the_", "your_"))


def request_json(label: str, method: str, url: str, body=None, token=None, headers=None):
    """HTTPS JSON call whose errors name only `label` -- never the URL (the
    Firebase URL carries the API key) or the response body."""
    if not url.startswith("https://"):
        raise ValueError(f"{label}: HTTPS is required")
    request = urllib.request.Request(
        url, method=method, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {}), **(headers or {})})
    try:
        # Generous timeout: a sleeping free-tier Render service cold-starts.
        with urllib.request.urlopen(request, timeout=120) as response:
            content = response.read()
            return json.loads(content) if content else None
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"{label}: HTTP {exc.code}") from None
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{label}: {exc.reason}") from None


def ensure_monitoring_workspace(api_key: str, email: str, password: str) -> str:
    """Sign in as the monitoring account and create (or, on every later run,
    get back) its dedicated workspace through the public workspace API --
    the same path a real user takes, so no manual database setup."""
    login = request_json("Firebase sign-in", "POST", f"{FIREBASE_SIGN_IN}?key={urllib.parse.quote(api_key)}",
                         {"email": email, "password": password, "returnSecureToken": True})
    token, uid = login["idToken"], login["localId"]
    gateway = os.environ.get("SKOLAB_GATEWAY_URL", "https://skolab-gateway.onrender.com").rstrip("/")
    request_json("Profile sync", "POST", f"{gateway}/api/v1/users/profile/sync",
                 {"uid": uid, "name": "SkoLab monitoring"}, token)
    workspace = request_json("Workspace create", "POST", f"{gateway}/api/v1/workspaces",
                             {"title": MONITOR_WORKSPACE_TITLE}, token, {"Idempotency-Key": MONITOR_WORKSPACE_KEY})
    return workspace["id"]


def journey_secrets(resolve_workspace=ensure_monitoring_workspace):
    values = {name: os.environ.get(key, "") for name, key in JOURNEY_SECRETS.items()}
    workspace = "skolab-monitor-workspace"
    invalid = [JOURNEY_SECRETS[name] for name, value in values.items() if name != workspace and not _configured(value)]
    if invalid:
        raise ValueError("Missing journey fixture configuration: " + ", ".join(invalid))
    if not _configured(values[workspace]):
        # Optional: created through the workspace API when not supplied.
        values[workspace] = resolve_workspace(values["skolab-firebase-api-key"], values["skolab-monitor-email"],
                                              values["skolab-monitor-password"])
        print("Monitoring workspace ready")
    return values


def _redact(text: str) -> str:
    text = re.sub(r"ticket=[^\s\"&]+", "ticket=[REDACTED]", text)
    return re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[REDACTED_TOKEN]", text)


def probe_results(lines: list[dict]) -> dict[str, bool]:
    """probe name -> probe_success, from ad-hoc result lines (one per probe)."""
    results = {}
    for line in lines:
        for series in line.get("timeseries") or []:
            if series.get("name") == "probe_success" and series.get("metric"):
                results[line.get("probe", "?")] = series["metric"][0]["gauge"]["value"] == 1
    return results


def verify_adhoc(grafana: API, sm: API, settings: dict, definition: dict, probe: int) -> bool:
    """Run one check once on a Grafana probe, without saving it, and wait for
    its result. Nothing runs locally: the probe executes the check (k6 for the
    journey) and reports to the stack's Loki, which is read back here."""
    run = sm.call("POST", "/api/v1/check/adhoc", {
        "target": definition["target"], "timeout": definition["timeout"],
        "probes": [probe], "settings": definition["settings"]})
    logs = [d["uid"] for d in grafana.call("GET", "/api/datasources") if d["name"] == settings["logs"]["grafanaName"]]
    if len(logs) != 1:
        raise RuntimeError("Synthetic Monitoring logs datasource not found")
    started = int(time.time() - 60) * 10**9
    deadline = time.monotonic() + definition["timeout"] / 1000 + 120
    while time.monotonic() < deadline:
        time.sleep(5)
        selector = '{type="adhoc"} |= "' + run["id"] + '"'
        query = urllib.parse.urlencode({"query": selector, "start": str(started), "limit": "20"})
        streams = grafana.call("GET", f"/api/datasources/proxy/uid/{logs[0]}/loki/api/v1/query_range?{query}")["data"]["result"]
        lines = [json.loads(raw) for stream in streams for _, raw in stream["values"]]
        results = probe_results(lines)
        if results:
            for line in lines:
                for entry in line.get("logs") or []:
                    if str(entry.get("level", "")).lower() == "error":
                        print("  " + _redact(str(entry.get("msg") or entry.get("error") or ""))[:200])
            for name, ok in results.items():
                print(f"Ad-hoc {definition['job']} from {name}: {'passed' if ok else 'FAILED'}")
            return all(results.values())
    raise RuntimeError(f"No ad-hoc result for {definition['job']} within its timeout")


def provision_secrets(grafana: API, stack_id: int):
    values = journey_secrets()
    path = f"/apis/secret.grafana.app/v1beta1/namespaces/stacks-{stack_id}/securevalues"
    existing = {item["metadata"]["name"]: item for item in grafana.call("GET", path)["items"]}
    for name, value in values.items():
        old = existing.get(name)
        metadata = {"name": name, "namespace": f"stacks-{stack_id}"}
        if old:
            metadata["resourceVersion"] = old["metadata"]["resourceVersion"]
        body = {"apiVersion": "secret.grafana.app/v1beta1", "kind": "SecureValue", "metadata": metadata,
            "spec": {"description": name[:25], "value": value, "decrypters": ["synthetic-monitoring"]}}
        grafana.call("PUT" if old else "POST", path + "/" + name if old else path, body)
        print("Reconciled secure value: " + name)


def apply(journey: bool, names: list[str], existing_secrets: bool = False):
    required = ["GRAFANA_URL", "GRAFANA_TOKEN", "GRAFANA_SM_TOKEN", "GRAFANA_CONTACT_POINT"]
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        raise ValueError("Missing local configuration: " + ", ".join(missing))
    grafana = API(os.environ["GRAFANA_URL"], os.environ["GRAFANA_TOKEN"])
    settings = grafana.call("GET", "/api/plugins/grafana-synthetic-monitoring-app/settings")["jsonData"]
    sm = API(settings["apiHost"], os.environ["GRAFANA_SM_TOKEN"])
    contacts = grafana.call("GET", "/api/v1/provisioning/contact-points")
    receiver = os.environ["GRAFANA_CONTACT_POINT"]
    if not any(item["name"] == receiver for item in contacts):
        raise ValueError("Named contact point does not exist; configure and test it before enabling alerts")
    datasources = grafana.call("GET", "/api/datasources")
    datasource = os.environ.get("GRAFANA_PROMETHEUS_UID")
    if not datasource:
        candidates = [item for item in datasources if item["type"] == "prometheus" and item["name"] == settings["metrics"]["grafanaName"]]
        if len(candidates) != 1:
            raise ValueError("Set GRAFANA_PROMETHEUS_UID to the Synthetic Monitoring metrics datasource UID")
        datasource = candidates[0]["uid"]
    if not any(folder["uid"] == FOLDER for folder in grafana.call("GET", "/api/folders")):
        grafana.call("POST", "/api/folders", {"uid": FOLDER, "title": "SkoLab monitoring"})
    probes = choose_probes(sm.call("GET", "/api/v1/probe"), names)
    if journey and not existing_secrets:
        provision_secrets(grafana, settings["stackId"])
    existing = sm.call("GET", "/api/v1/check")
    definitions = check_definitions(probes, probes[0] if journey else None)
    if journey and not verify_adhoc(grafana, sm, settings, definitions[-1], probes[0]):
        # Never schedule a journey that fails: it would page every 30 minutes.
        raise RuntimeError("Journey failed its ad-hoc run on a Grafana probe; not scheduling it")
    for definition in definitions:
        reconcile_check(sm, definition, existing)
    config = json.loads((ROOT / "checks.json").read_text())
    rules = [alert_rule(item["job"], item["service"] + " unavailable", availability_expression(item["job"]), datasource, receiver)
             for item in config["availability"]]
    if journey:
        rules.append(alert_rule("skolab-journey-failed", "SkoLab complete journey failed",
            'min(last_over_time(probe_success{job="skolab-complete-journey"}[35m])) < bool 1', datasource, receiver, duration="2m"))
    existing_rules = grafana.call("GET", "/api/v1/provisioning/alert-rules")
    for rule in rules:
        path = "/api/v1/provisioning/alert-rules"
        old = next((item for item in existing_rules if item["uid"] == rule["uid"]), None)
        grafana.call("PUT" if old else "POST", path + "/" + rule["uid"] if old else path, rule)
        print("Reconciled alert: " + rule["title"])
    dashboard = json.loads((ROOT / "availability-dashboard.json").read_text())
    serialized = json.dumps(dashboard).replace("${DS_PROMETHEUS}", datasource)
    grafana.call("POST", "/api/dashboards/db", {"dashboard": json.loads(serialized), "folderUid": FOLDER, "overwrite": True})
    print("Reconciled availability dashboard")


def verify_journey(names: list[str], existing_secrets: bool = False):
    grafana = API(os.environ["GRAFANA_URL"], os.environ["GRAFANA_TOKEN"])
    settings = grafana.call("GET", "/api/plugins/grafana-synthetic-monitoring-app/settings")["jsonData"]
    sm = API(settings["apiHost"], os.environ["GRAFANA_SM_TOKEN"])
    if not existing_secrets:
        provision_secrets(grafana, settings["stackId"])
    probes = choose_probes(sm.call("GET", "/api/v1/probe"), names)
    if not verify_adhoc(grafana, sm, settings, check_definitions(probes, probes[0])[-1], probes[0]):
        raise RuntimeError("Journey failed its ad-hoc run")


def test_alert():
    """Exercise evaluation -> firing -> recovery without breaking production.

    The test rule sends a notification to the explicitly selected contact point.
    It is deleted in finally, including on timeout/error. This is a real alert
    pipeline test, not merely a contact-point preview.
    """
    api = API(os.environ["GRAFANA_URL"], os.environ["GRAFANA_TOKEN"])
    receiver = os.environ["GRAFANA_CONTACT_POINT"]
    uid = "skolab-notification-test"
    path = "/api/v1/provisioning/alert-rules/" + uid
    if any(item["uid"] == uid for item in api.call("GET", "/api/v1/provisioning/alert-rules")):
        raise RuntimeError("A notification test already exists; refusing to overwrite it")
    rule = alert_rule(uid, "TEST: SkoLab monitoring notification", "", "__expr__", receiver, duration="0s", severity="test")
    rule["data"][0]["datasourceUid"] = "__expr__"
    rule["data"][0]["model"] = {"refId": "A", "type": "math", "expression": "1"}
    rule["notification_settings"]["group_wait"] = "0s"
    created = False
    try:
        api.call("POST", "/api/v1/provisioning/alert-rules", rule)
        created = True
        deadline = time.monotonic() + 240
        def wait_state(expected):
            while time.monotonic() < deadline:
                groups = api.call("GET", "/api/prometheus/grafana/api/v1/rules")["data"]["groups"]
                for group in groups:
                    for item in group.get("rules", []):
                        if item.get("name") == rule["title"] and item.get("state") == expected:
                            return
                time.sleep(5)
            raise RuntimeError("Timed out waiting for test alert state " + expected)
        wait_state("firing")
        print("Test alert evaluated and fired; confirm receipt at the configured contact point")
        rule["data"][0]["model"]["expression"] = "0"
        api.call("PUT", path, rule)
        deadline = time.monotonic() + 180
        wait_state("inactive")
        print("Test alert recovered")
    finally:
        if created:
            api.call("DELETE", path)
            print("Temporary test alert removed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--journey", action="store_true", help="Enable journey after configuring four Grafana secure values")
    parser.add_argument("--test-alert", action="store_true")
    parser.add_argument("--verify-journey", action="store_true",
                        help="Run the journey once on a Grafana probe (no local k6) and report the result")
    parser.add_argument("--existing-secrets", action="store_true", help="Use the four secure values already configured in Grafana instead of uploading local values")
    parser.add_argument("--probes", nargs=2, default=["Mumbai", "Oregon"])
    parser.add_argument("--env-file", type=Path, default=ROOT.parents[1] / ".env")
    args = parser.parse_args()
    load_environment(args.env_file)
    if args.test_alert:
        test_alert()
    elif args.verify_journey:
        verify_journey(args.probes, args.existing_secrets)
    elif args.apply:
        apply(args.journey, args.probes, args.existing_secrets)
    else:
        print(json.dumps(check_definitions([1, 2], 1 if args.journey else None), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, urllib.error.URLError, KeyError) as exc:
        print(f"Monitoring setup failed: {exc}", file=sys.stderr)
        sys.exit(1)
