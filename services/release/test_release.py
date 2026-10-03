import unittest

import release


class FakeRender:
    """One service 'svc'; get_deploy walks each deploy through `script`."""

    def __init__(self, deploys=(), script=None, services=None, env=None, files=None):
        self.listed, self.script, self.started, self.cancelled = list(deploys), list(script or []), [], []
        self.services = services or {}
        self.env, self.files = env or {}, files or {}
        self.writes, self.suspended = [], []

    # services
    def find_service(self, name):
        return self.services.get(name)

    def env_vars(self, service):
        return dict(self.env.get(service, {}))

    def set_env_var(self, service, key, value):
        self.env.setdefault(service, {})[key] = value
        self.writes.append(key)

    def secret_files(self, service):
        return dict(self.files.get(service, {}))

    def set_secret_file(self, service, name, content):
        self.files.setdefault(service, {})[name] = content
        self.writes.append(name)

    def suspend(self, service):
        self.suspended.append(service)

    # deploys
    def deploys(self, service):
        return self.listed

    def deploy(self, service, commit):
        self.started.append(commit)
        return {"id": "new"}

    def cancel(self, service, deploy):
        self.cancelled.append(deploy)

    def get_deploy(self, service, deploy):
        status = self.script.pop(0)
        if isinstance(status, tuple):  # (status, deploys listed afterwards)
            status, self.listed = status
        return {"id": deploy, "status": status}


def d(id, commit, status):
    return {"id": id, "commit": {"id": commit}, "status": status}


def ticks():
    clock = iter(range(0, 10_000, 15))
    return lambda: next(clock)


def ship(render, commit="new-sha", changed=True, timeout=1000, fresh=False):
    return release.ship(render, "svc", commit, fresh=fresh, timeout=timeout, sleep=lambda _: None,
                        clock=ticks(), changed=lambda *_: changed)


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

    def test_a_service_with_nothing_live_is_always_deployed(self):
        render = FakeRender([d("a", "new-sha", "build_failed")], ["live"])
        self.assertEqual(ship(render, changed=False), "deployed")

    def test_reuses_a_running_deploy_of_the_same_commit(self):
        render = FakeRender([d("auto", "new-sha", "build_in_progress"), d("a", "old", "live")], ["live"])
        self.assertEqual(ship(render), "deployed")
        self.assertEqual(render.started, [])

    def test_fresh_cancels_running_deploys_and_starts_its_own(self):
        render = FakeRender([d("stale", "new-sha", "build_in_progress"), d("a", "new-sha", "live")], ["live"])
        self.assertEqual(ship(render, fresh=True), "deployed")
        self.assertEqual((render.cancelled, render.started), (["stale"], ["new-sha"]))

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


LEGACY = {"skolab-backend-py": {"id": "py"}, "skolab-gateway": {"id": "go"}}


class Migration(unittest.TestCase):

    def test_copies_missing_settings_without_overwriting(self):
        render = FakeRender(
            services=LEGACY,
            env={"api": {"GIN_MODE": "release"},
                 "py": {"DATABASE_URL": "postgresql+asyncpg://db", "GROQ_API": "g", "OTEL_SERVICE_NAME": "skolab-backend-py"},
                 "go": {"DATABASE_URL": "postgres://db", "GIN_MODE": "debug", "PORT": "8080", "INTERNAL_API_TOKEN": "t"}},
            files={"go": {"service-account.json": "{}"}})
        copied = release.adopt_settings(render, "api")
        self.assertEqual(copied, ["DATABASE_URL", "GROQ_API", "INTERNAL_API_TOKEN", "secret file service-account.json"])
        api = render.env["api"]
        self.assertEqual(api["GIN_MODE"], "release")          # the target's own value wins
        self.assertEqual(api["DATABASE_URL"], "postgres://db")  # the gateway's value wins a conflict
        self.assertNotIn("OTEL_SERVICE_NAME", api)              # per-process: set by the entrypoint
        self.assertNotIn("PORT", api)
        self.assertEqual(render.files["api"], {"service-account.json": "{}"})

    def test_nothing_to_copy_once_settings_exist_or_legacy_is_gone(self):
        render = FakeRender(services=LEGACY, env={"api": {"K": "1"}, "py": {"K": "2"}, "go": {}})
        self.assertEqual(release.adopt_settings(render, "api"), [])
        self.assertEqual(release.adopt_settings(FakeRender(), "api"), [])

    def test_retire_suspends_only_running_legacy_services(self):
        render = FakeRender(services={"skolab-backend-py": {"id": "py", "suspended": "suspended"},
                                      "skolab-gateway": {"id": "go", "suspended": "not_suspended"}})
        self.assertEqual(release.retire_legacy(render), ["skolab-gateway"])
        self.assertEqual(render.suspended, ["go"])
        self.assertEqual(release.retire_legacy(FakeRender()), [])

    def test_waits_for_the_blueprint_to_create_the_service(self):
        render = FakeRender()
        calls = iter([None, None, {"id": "api"}])
        render.find_service = lambda name: next(calls)
        self.assertEqual(release.wait_for_service(render, "skolab-api", sleep=lambda _: None, clock=ticks())["id"], "api")

    def test_explains_a_missing_service(self):
        with self.assertRaisesRegex(RuntimeError, "Sync the Blueprint"):
            release.wait_for_service(FakeRender(), "skolab-api", timeout=60, sleep=lambda _: None, clock=ticks())


class SourceChanged(unittest.TestCase):
    def test_unknown_live_commit_means_deploy(self):
        self.assertTrue(release.source_changed(None, "abc"))

    def test_unknown_git_revision_means_deploy(self):
        self.assertTrue(release.source_changed("0" * 40, "HEAD", ("services",)))


if __name__ == "__main__":
    unittest.main()
