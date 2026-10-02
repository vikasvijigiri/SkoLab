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
