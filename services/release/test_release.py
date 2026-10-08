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

    groups = ()

    def env_groups(self):
        return [dict(g) for g in self.groups]

    def link_env_group(self, group, service):
        self.writes.append(f"link {group}")
        for g in self.groups:
            if g["id"] == group:
                g["services"].append(service)

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

    def test_links_the_env_groups_the_replaced_services_used(self):
        render = FakeRender(services=LEGACY, env={"api": {}, "py": {}, "go": {}})
        render.groups = [{"id": "grp-1", "name": "skolab", "services": ["py"]},
                         {"id": "grp-2", "name": "other-app", "services": ["srv-other"]}]
        self.assertEqual(release.adopt_settings(render, "api"), ["env group skolab"])
        self.assertEqual(render.writes, ["link grp-1"])
        # Already linked: nothing to do the second time.
        self.assertEqual(release.adopt_settings(render, "api"), [])

    def test_empty_placeholders_count_as_missing(self):
        render = FakeRender(services=LEGACY,
                            env={"api": {"DATABASE_ENCRYPTION_KEY": "", "SET": "kept"},
                                 "py": {"DATABASE_ENCRYPTION_KEY": "k", "SET": "other", "EMPTY": ""}, "go": {}},
                            files={"api": {"service-account.json": ""}, "go": {"service-account.json": "{}"}})
        copied = release.adopt_settings(render, "api")
        self.assertEqual(copied, ["DATABASE_ENCRYPTION_KEY", "secret file service-account.json"])
        self.assertEqual(render.env["api"], {"DATABASE_ENCRYPTION_KEY": "k", "SET": "kept"})

    def test_failed_deploy_prints_the_service_logs(self):
        render = FakeRender([d("a", "old", "live")], ["build_failed"])
        render.recent_logs = lambda owner, service: ["RuntimeError: DATABASE_ENCRYPTION_KEY is unset"]
        with self.assertLogsPrinted() as out, self.assertRaisesRegex(RuntimeError, "build_failed"):
            release.ship(render, "svc", "new-sha", owner="own", sleep=lambda _: None, clock=ticks(),
                         changed=lambda *_: True)
        self.assertIn("DATABASE_ENCRYPTION_KEY is unset", out.getvalue())

    def assertLogsPrinted(self):
        import contextlib
        import io

        buffer = io.StringIO()

        @contextlib.contextmanager
        def capture():
            with contextlib.redirect_stdout(buffer):
                yield buffer
        return capture()

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



class RollbackRender(FakeRender):
    def __init__(self, script=None, rollback_error=None):
        super().__init__(script=script)
        self.rolled_back, self.rollback_error = [], rollback_error

    def rollback(self, service, deploy):
        if self.rollback_error:
            raise RuntimeError(self.rollback_error)
        self.rolled_back.append(deploy)
        return {"id": "restored"}


def failing(times):
    calls = []

    def check():
        calls.append(1)
        if len(calls) <= times:
            raise RuntimeError(f"smoke failure {len(calls)}")
    return check, calls


def verify(render, check, previous=None, healthy=None):
    return release.verify_or_roll_back(render, "svc", previous, check, healthy=healthy,
                                       sleep=lambda _: None, clock=ticks())


class VerifyOrRollBack(unittest.TestCase):
    previous = d("old", "old-sha", "live")

    def test_passing_smoke_never_rolls_back(self):
        render, (check, calls) = RollbackRender(), failing(0)
        verify(render, check, self.previous)
        self.assertEqual((len(calls), render.rolled_back), (1, []))

    def test_one_blip_is_retried_not_rolled_back(self):
        render, (check, calls) = RollbackRender(), failing(1)
        verify(render, check, self.previous)
        self.assertEqual((len(calls), render.rolled_back), (2, []))

    def test_repeated_failure_rolls_back_to_the_replaced_deploy_and_fails(self):
        render, (check, _) = RollbackRender(script=["update_in_progress", "live"]), failing(2)
        checked = []
        with self.assertRaisesRegex(RuntimeError, "rolled back to old-sha"):
            verify(render, check, self.previous, healthy=lambda: checked.append(1))
        self.assertEqual((render.rolled_back, checked), (["old"], [1]))

    def test_without_a_replaced_deploy_the_failure_is_raised_as_is(self):
        render, (check, _) = RollbackRender(), failing(2)
        with self.assertRaisesRegex(RuntimeError, "smoke failure 2"):
            verify(render, check, None)
        self.assertEqual(render.rolled_back, [])

    def test_a_failed_rollback_says_production_needs_a_manual_one(self):
        render, (check, _) = RollbackRender(rollback_error="api down"), failing(2)
        with self.assertRaisesRegex(RuntimeError, "manual rollback"):
            verify(render, check, self.previous)

    def test_a_rollback_that_never_goes_live_is_reported(self):
        render, (check, _) = RollbackRender(script=["build_failed"]), failing(2)
        with self.assertRaisesRegex(RuntimeError, "manual rollback"):
            verify(render, check, self.previous)


class LiveDeploy(unittest.TestCase):
    def test_picks_the_live_deploy(self):
        rows = [d("a", "x", "build_in_progress"), d("b", "y", "live"), d("c", "z", "deactivated")]
        self.assertEqual(release.live_deploy(rows)["id"], "b")
        self.assertIsNone(release.live_deploy([d("a", "x", "deactivated")]))


if __name__ == "__main__":
    unittest.main()
