import unittest

import release


class FakeRender:
    """Service 'svc'; get_deploy walks each deploy through `script`."""

    def __init__(self, deploys, script=None):
        self.listed, self.script, self.started = deploys, list(script or []), []

    def service_id(self, name):
        return "svc"

    def deploys(self, service):
        return self.listed

    def deploy(self, service, commit):
        self.started.append(commit)
        return {"id": "new"}

    def get_deploy(self, service, deploy):
        status = self.script.pop(0)
        if isinstance(status, tuple):  # (status, deploys listed afterwards)
            status, self.listed = status
        return {"id": deploy, "status": status}


def d(id, commit, status):
    return {"id": id, "commit": {"id": commit}, "status": status}


def ship(render, commit="new-sha", changed=True, timeout=1000):
    ticks = iter(range(0, 10_000, 15))
    return release.ship(render, "svc", "services/x", commit, timeout=timeout, sleep=lambda _: None,
                        clock=lambda: next(ticks), changed=lambda *_: changed)


class Ship(unittest.TestCase):
    def test_deploys_and_waits_until_live(self):
        render = FakeRender([d("a", "old", "live")], ["build_in_progress", "update_in_progress", "live"])
        self.assertEqual(ship(render), "deployed")
        self.assertEqual(render.started, ["new-sha"])

    def test_already_live_starts_nothing(self):
        render = FakeRender([d("a", "new-sha", "live")])
        self.assertEqual(ship(render), "already live")
        self.assertEqual(render.started, [])

    def test_unchanged_source_is_not_rebuilt(self):
        render = FakeRender([d("a", "old-sha1", "live")])
        self.assertIn("not rebuilt", ship(render, changed=False))
        self.assertEqual(render.started, [])

    def test_reuses_a_running_deploy_of_the_same_commit(self):
        render = FakeRender([d("auto", "new-sha", "build_in_progress"), d("a", "old", "live")], ["live"])
        self.assertEqual(ship(render), "deployed")
        self.assertEqual(render.started, [])

    def test_build_failure_fails_the_release(self):
        render = FakeRender([d("a", "old", "live")], ["build_in_progress", "build_failed"])
        with self.assertRaisesRegex(RuntimeError, "build_failed"):
            ship(render)

    def test_follows_a_replacement_deploy_of_the_same_commit(self):
        replaced = [d("other", "new-sha", "update_in_progress"), d("new", "new-sha", "canceled")]
        render = FakeRender([d("a", "old", "live")], [("canceled", replaced), "live"])
        self.assertEqual(ship(render), "deployed")

    def test_superseded_by_another_commit_fails(self):
        render = FakeRender([d("a", "old", "live")], [("canceled", [d("x", "newer", "build_in_progress")])])
        with self.assertRaisesRegex(RuntimeError, "another commit"):
            ship(render)

    def test_times_out(self):
        render = FakeRender([d("a", "old", "live")], ["build_in_progress"] * 100)
        with self.assertRaisesRegex(RuntimeError, "not live"):
            ship(render, timeout=60)


class SourceChanged(unittest.TestCase):
    def test_unknown_live_commit_means_deploy(self):
        self.assertTrue(release.source_changed(None, "abc", "services/x"))

    def test_unknown_git_revision_means_deploy(self):
        self.assertTrue(release.source_changed("0" * 40, "HEAD", "services"))


if __name__ == "__main__":
    unittest.main()
