import unittest

from verify_ci import REQUIRED, passed, verified


class ManualReleaseGateTests(unittest.TestCase):
    def jobs(self):
        return [
            {"name": name, "conclusion": "success"}
            for name in [*REQUIRED, "security / gitleaks · semgrep · trivy"]
        ]

    def test_security_failure_and_missing_tests_block_release(self):
        jobs = self.jobs()
        self.assertTrue(passed(jobs))
        jobs[-1]["conclusion"] = "failure"
        self.assertFalse(passed(jobs))
        self.assertFalse(passed(self.jobs()[1:]))

    def test_other_commits_and_pull_request_runs_do_not_authorize_release(self):
        def fetch(_):
            return {
                "workflow_runs": [
                    {"head_sha": "old", "event": "push"},
                    {"head_sha": "wanted", "event": "pull_request"},
                ]
            }

        self.assertFalse(verified("owner/repo", "wanted", fetch))

    def test_failed_deploy_can_be_retried_after_successful_checks(self):
        def fetch(path):
            if "/jobs?" in path:
                jobs = self.jobs() + [
                    {"name": "release / release", "conclusion": "failure"}
                ]
                return {"jobs": jobs, "total_count": len(jobs)}
            return {
                "workflow_runs": [{"id": 42, "head_sha": "wanted", "event": "push"}]
            }

        self.assertTrue(verified("owner/repo", "wanted", fetch))
