"""python -m unittest discover -s services/observability/slo -p "test_*.py" """

import unittest

import generate
import provision


class ProvisionTest(unittest.TestCase):
    def test_recording_groups_carry_no_alerts(self):
        groups = provision.recording_groups()
        self.assertEqual(len(groups), len(generate.SLOS))
        for group in groups:
            self.assertTrue(group["rules"])
            self.assertTrue(all("record" in rule for rule in group["rules"]))

    def test_alert_rules_mirror_the_tested_rules_file(self):
        rules = provision.alert_rules("prom-uid", "oncall")
        self.assertEqual(len(rules), len(generate.SLOS) * len(generate.ALERTS))
        self.assertEqual(len({r["uid"] for r in rules}), len(rules))
        self.assertEqual(len({r["title"] for r in rules}), len(rules))
        expected = {
            (r["alert"], r["labels"]["window"]): r
            for g in generate.groups()
            for r in g["rules"]
            if "alert" in r
        }
        for rule in rules:
            self.assertLessEqual(len(rule["uid"]), 40)  # Grafana's UID limit
            source = expected[(rule["title"].split(" ")[0], rule["labels"]["window"])]
            self.assertEqual(rule["data"][0]["model"]["expr"], source["expr"])
            self.assertEqual(rule["data"][0]["datasourceUid"], "prom-uid")
            self.assertEqual(rule["for"], source["for"])
            self.assertEqual(rule["noDataState"], "OK")
            self.assertEqual(rule["notification_settings"]["receiver"], "oncall")
            self.assertIn("slo", rule["notification_settings"]["group_by"])
            self.assertIn("$values.A.Value", rule["annotations"]["description"])
            self.assertNotIn("{{ $value ", rule["annotations"]["description"])


if __name__ == "__main__":
    unittest.main()
