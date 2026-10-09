import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("telemetry_lookup", Path(__file__).resolve().parents[1] / "telemetry_lookup.py")
lookup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lookup)

START = lookup.parse_time("2026-10-09T05:00:00Z")


def fake(path, params):
    if path.endswith("/api/search"):
        if "skolab-gateway" not in params["q"]:
            return {"traces": []}
        return {"traces": [
            {"traceID": "b", "rootTraceName": "POST /api/v1/users/profile/sync", "durationMs": 293,
             "startTimeUnixNano": str((START + 642) * 10**9)},
            {"traceID": "a", "rootTraceName": "OPTIONS unmatched", "startTimeUnixNano": str((START + 641) * 10**9)},
            {"traceID": "c", "rootTraceName": "DELETE", "durationMs": 2, "startTimeUnixNano": str((START + 624) * 10**9)},
        ]}
    return {"data": {"result": [{"metric": {"service_name": "skolab-gateway", "http_request_method": "POST",
                                            "http_route": "/api/v1/users/profile/sync", "http_response_status_code": "200"},
                                 "values": [[START + 600, "0"], [START + 900, "1"]]}]}}


class TelemetryLookupTests(unittest.TestCase):
    def test_report_lists_user_requests_in_time_order_and_hides_background(self):
        text = lookup.report(fake, START, START + 1800)
        self.assertIn("## skolab-gateway: 3 traces (1 background hidden)", text)
        self.assertLess(text.index("05:10:41 OPTIONS unmatched"), text.index("05:10:42 POST /api/v1/users/profile/sync 293 ms trace b"))
        self.assertNotIn("DELETE", text)
        self.assertIn("## skolab-backend-py: 0 traces (0 background hidden)\n\n- none", text)
        self.assertIn("POST /api/v1/users/profile/sync 200: 05:15=1", text)
        self.assertNotIn("05:10=0", text)

    def test_show_noise_keeps_background(self):
        self.assertIn("05:10:24 DELETE 2 ms trace c", lookup.report(fake, START, START + 1800, show_noise=True))

    def test_query_targets_the_stack_data_sources(self):
        seen = []
        lookup.report(lambda path, params: seen.append(path) or fake(path, params), START, START + 60)
        self.assertIn("/api/datasources/proxy/uid/grafanacloud-traces/api/search", seen)
        self.assertIn("/api/datasources/proxy/uid/grafanacloud-prom/api/v1/query_range", seen)


if __name__ == "__main__":
    unittest.main()
