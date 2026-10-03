import unittest

from check_connection_budget import connection_budget


class ConnectionBudgetTests(unittest.TestCase):
    def test_rollout_overlap_and_python_workers_count(self):
        env = {
            "DB_MAX_CONNS": "5",
            "DB_MIN_CONNS": "1",
            "DB_POOL_SIZE": "3",
            "DB_MAX_OVERFLOW": "2",
            "WEB_CONCURRENCY": "2",
            "APP_MAX_INSTANCES": "4",
            "DB_RESERVED_CONNECTIONS": "10",
            "DB_CONNECTION_BUDGET": "70",
        }
        self.assertEqual(connection_budget(env), (70, 70))
        env["DB_CONNECTION_BUDGET"] = "69"
        with self.assertRaisesRegex(ValueError, "exceeds"):
            connection_budget(env)

    def test_scaled_deployment_requires_a_measured_budget(self):
        with self.assertRaisesRegex(ValueError, "verified"):
            connection_budget({"SHARED_STATE_REQUIRED": "true"})

    def test_unbounded_or_invalid_pool_settings_are_refused(self):
        for env in (
            {"DB_MAX_OVERFLOW": "-1"},
            {"DB_MAX_CONNS": "0"},
            {"DB_MIN_CONNS": "20"},
            {"WEB_CONCURRENCY": "0"},
        ):
            with self.subTest(env=env), self.assertRaises(ValueError):
                connection_budget(env)
