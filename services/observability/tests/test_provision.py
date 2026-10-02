import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("provision", Path(__file__).resolve().parents[1] / "provision.py")
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)


class FakeAPI:
    def __init__(self, valid=True):
        self.calls = []
        self.valid = valid

    def call(self, method, path, body=None):
        self.calls.append((method, path, body))
        return {"valid": self.valid, "findings": [{"field": "settings"}], "id": 123}


class ProvisionTests(unittest.TestCase):
    def test_existing_check_is_updated_instead_of_duplicated(self):
        api = FakeAPI()
        definition = provision.check_definitions([1, 2])[0]
        provision.reconcile_check(api, definition, [{"job": definition["job"], "id": 123}])
        self.assertEqual(api.calls[-1][1], "/api/v1/check/123")
        self.assertEqual(api.calls[0][2]["id"], 123)

    def test_invalid_check_does_not_mutate_resources(self):
        api = FakeAPI(valid=False)
        with self.assertRaises(RuntimeError):
            provision.reconcile_check(api, provision.check_definitions([1, 2])[0], [])
        self.assertEqual(len(api.calls), 1)

    def test_duplicate_jobs_fail_closed(self):
        definition = provision.check_definitions([1, 2])[0]
        with self.assertRaises(RuntimeError):
            provision.reconcile_check(FakeAPI(), definition, [{"job": definition["job"], "id": i} for i in (1, 2)])

    def test_offline_and_identical_probes_rejected(self):
        probes = [{"name": "Mumbai", "id": 1, "public": True, "online": True},
                  {"name": "Oregon", "id": 2, "public": True, "online": False}]
        with self.assertRaises(RuntimeError):
            provision.choose_probes(probes, ["Mumbai", "Oregon"])
        with self.assertRaises(ValueError):
            provision.choose_probes(probes, ["Mumbai", "Mumbai"])

    def test_journey_does_not_exhaust_default_daily_quota(self):
        journey = provision.check_definitions([1, 2], 1)[-1]
        daily_runs = 86400000 / journey["frequency"] * len(journey["probes"])
        self.assertLess(daily_runs * 8, 600)  # Go and Python each consume four units.

    def test_local_env_does_not_override_injected_secrets(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"GRAFANA_TOKEN": "injected"}):
            path = Path(directory) / ".env"
            path.write_text("GRAFANA_TOKEN=local\n")
            provision.load_environment(path)
            self.assertEqual(os.environ["GRAFANA_TOKEN"], "injected")

    def test_monitoring_api_rejects_plaintext_transport(self):
        with self.assertRaises(ValueError):
            provision.API("http://example.com", "secret")

    def test_alert_test_fires_recovers_and_cleans_up(self):
        class AlertAPI:
            def __init__(self):
                self.calls = []
                self.states = iter(["firing", "inactive"])

            def call(self, method, path, body=None):
                self.calls.append((method, path))
                if method == "GET" and path.endswith("alert-rules"):
                    return []
                if path.endswith("/rules"):
                    return {"data": {"groups": [{"rules": [{"name": "TEST: SkoLab monitoring notification", "state": next(self.states)}]}]}}

        api = AlertAPI()
        with patch.object(provision, "API", return_value=api), patch.dict(os.environ,
                {"GRAFANA_URL": "https://test.example", "GRAFANA_TOKEN": "mock", "GRAFANA_CONTACT_POINT": "test-email"}):
            provision.test_alert()
        self.assertEqual(api.calls[-1], ("DELETE", "/api/v1/provisioning/alert-rules/skolab-notification-test"))
        self.assertTrue(any(method == "PUT" for method, _ in api.calls))

    def test_alert_test_cleans_up_after_evaluation_failure(self):
        api = unittest.mock.Mock()
        api.call.side_effect = [[], None, RuntimeError("evaluation unavailable"), None]
        with patch.object(provision, "API", return_value=api), patch.dict(os.environ,
                {"GRAFANA_URL": "https://test.example", "GRAFANA_TOKEN": "mock", "GRAFANA_CONTACT_POINT": "test-email"}), self.assertRaises(RuntimeError):
            provision.test_alert()
        self.assertEqual(api.call.call_args.args, ("DELETE", "/api/v1/provisioning/alert-rules/skolab-notification-test"))


if __name__ == "__main__":
    unittest.main()


def adhoc_line(probe, success, logs=()):
    return {"id": "run-1", "probe": probe, "logs": list(logs),
            "timeseries": [{"name": "probe_success", "metric": [{"gauge": {"value": 1 if success else 0}}]}]}


class FakeGrafanaLoki:
    """Answers the logs-datasource lookup, then Loki polls with fixed lines."""

    def __init__(self, lines, empty_polls=0):
        self.lines, self.empty_polls, self.queries = lines, empty_polls, []

    def call(self, method, path, body=None):
        if path == "/api/datasources":
            return [{"name": "stack-logs", "uid": "logs-uid"}]
        self.queries.append(path)
        if self.empty_polls:
            self.empty_polls -= 1
            return {"data": {"result": []}}
        return {"data": {"result": [{"values": [["1", provision.json.dumps(line)] for line in self.lines]}]}}


SM_SETTINGS = {"logs": {"grafanaName": "stack-logs"}}


class AdHocVerificationTests(unittest.TestCase):

    def journey(self):
        return provision.check_definitions([7, 8], 7)[-1]

    def run_verify(self, grafana):
        sm = FakeAPI()
        sm.call = lambda method, path, body=None: sm.calls.append((method, path, body)) or {"id": "run-1"}
        with patch.object(provision.time, "sleep"):
            result = provision.verify_adhoc(grafana, sm, SM_SETTINGS, self.journey(), 7)
        return result, sm

    def test_passing_run_submits_unsaved_check_to_one_probe(self):
        grafana = FakeGrafanaLoki([adhoc_line("Mumbai", True)], empty_polls=2)
        ok, sm = self.run_verify(grafana)
        self.assertTrue(ok)
        method, path, body = sm.calls[0]
        self.assertEqual((method, path, body["probes"]), ("POST", "/api/v1/check/adhoc", [7]))
        self.assertIn("scripted", body["settings"])
        self.assertTrue(all("run-1" in provision.urllib.parse.unquote_plus(q) for q in grafana.queries))

    def test_failing_probe_fails_and_redacts_secrets_in_errors(self):
        logs = [{"level": "error", "msg": "ws ticket=abc123 token eyJa.eyJb.sig failed"}]
        grafana = FakeGrafanaLoki([adhoc_line("Mumbai", False, logs)])
        with patch("builtins.print") as printed:
            ok, _ = self.run_verify(grafana)
        self.assertFalse(ok)
        output = " ".join(str(c.args[0]) for c in printed.call_args_list)
        self.assertNotIn("abc123", output)
        self.assertNotIn("eyJa.eyJb.sig", output)
        self.assertIn("FAILED", output)

    def test_missing_result_times_out_instead_of_passing(self):
        grafana = FakeGrafanaLoki([], empty_polls=10**6)
        clock = patch.object(provision.time, "monotonic", side_effect=[0, 1, 10**6])
        with clock, self.assertRaises(RuntimeError):
            self.run_verify(grafana)


FIXTURES = {"SKOLAB_FIREBASE_API_KEY": "AIza-test-key", "SKOLAB_SYNTHETIC_EMAIL": "monitor@example.com",
            "SKOLAB_SYNTHETIC_PASSWORD": "pw-secret", "SKOLAB_SYNTHETIC_WORKSPACE_ID": ""}


class MonitoringWorkspaceTests(unittest.TestCase):
    def test_workspace_is_created_through_the_api_with_a_fixed_idempotency_key(self):
        calls = []

        def fake(label, method, url, body=None, token=None, headers=None):
            calls.append((label, method, url, body, token, headers))
            return {"Firebase sign-in": {"idToken": "id-token", "localId": "uid-1"},
                    "Profile sync": {"status": "synced"},
                    "Workspace create": {"id": "ws-42"}}[label]

        with patch.object(provision, "request_json", fake):
            self.assertEqual(provision.ensure_monitoring_workspace("AIza-test-key", "monitor@example.com", "pw-secret"), "ws-42")
        self.assertEqual([c[0] for c in calls], ["Firebase sign-in", "Profile sync", "Workspace create"])
        sync, create = calls[1], calls[2]
        self.assertEqual((sync[3]["uid"], sync[4]), ("uid-1", "id-token"))
        self.assertTrue(create[2].endswith("/api/v1/workspaces"))
        self.assertEqual(create[5], {"Idempotency-Key": provision.MONITOR_WORKSPACE_KEY})
        self.assertEqual(create[4], "id-token")

    def test_missing_workspace_is_resolved_and_explicit_one_is_kept(self):
        resolved = []
        with patch.dict(os.environ, FIXTURES), patch("builtins.print"):
            values = provision.journey_secrets(lambda *args: resolved.append(args) or "ws-auto")
        self.assertEqual(values["skolab-monitor-workspace"], "ws-auto")
        self.assertEqual(resolved, [("AIza-test-key", "monitor@example.com", "pw-secret")])
        with patch.dict(os.environ, {**FIXTURES, "SKOLAB_SYNTHETIC_WORKSPACE_ID": "ws-manual"}):
            values = provision.journey_secrets(lambda *args: self.fail("must not call the API"))
        self.assertEqual(values["skolab-monitor-workspace"], "ws-manual")

    def test_login_fixtures_remain_required(self):
        with patch.dict(os.environ, {**FIXTURES, "SKOLAB_SYNTHETIC_PASSWORD": "your_password"}), \
                self.assertRaises(ValueError) as raised:
            provision.journey_secrets(lambda *args: "unused")
        self.assertIn("SKOLAB_SYNTHETIC_PASSWORD", str(raised.exception))

    def test_request_errors_never_reveal_url_or_key(self):
        error = provision.urllib.error.HTTPError(
            "https://identitytoolkit.googleapis.com/v1/x?key=AIza-test-key", 400, "bad", {}, None)
        with patch.object(provision.urllib.request, "urlopen", side_effect=error), \
                self.assertRaises(RuntimeError) as raised:
            provision.request_json("Firebase sign-in", "POST", "https://identitytoolkit.googleapis.com/v1/x?key=AIza-test-key", {})
        self.assertEqual(str(raised.exception), "Firebase sign-in: HTTP 400")
        with self.assertRaises(ValueError):
            provision.request_json("Plain", "GET", "http://example.com")
