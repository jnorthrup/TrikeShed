import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from typing import Any
import unittest

os.environ.setdefault("JULES_API_KEY", "test-key")
os.environ.setdefault("JULES_SOURCE", "sources/github/example/repo")

HERE = Path(__file__).resolve().parent


def load_module(filename: str, name: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


flywheel = load_module("flywheel.py", "flywheel_under_test")
land = load_module("land.py", "land_under_test")


class WorkQueueTest(unittest.TestCase):
    def test_popped_work_can_be_requeued(self):
        queue = flywheel.WorkPQ()
        work = flywheel.WorkItem(title="retry me", spec="test then implement")

        queue.push(work)
        self.assertIs(queue.pop(), work)
        queue.push(work)

        self.assertEqual(1, len(queue))

    def test_snapshot_budget_keeps_the_complete_work_pool(self):
        pool = "## doc/ work pool\n- [ ] first\n- [ ] final\n"
        fitted = flywheel.fit_snapshot("x" * 16000 + pool, 16000)
        self.assertIn("- [ ] first", fitted)
        self.assertIn("- [ ] final", fitted)

    def test_researcher_task_wrapper_is_unwrapped(self):
        tasks = [{"title": "one"}]
        self.assertEqual(tasks, flywheel.proposal_list({"tasks": tasks}))

    def test_active_tasks_are_removed_before_triage(self):
        queue = flywheel.WorkPQ()
        live = {"sessions/1": {"work": {"title": "already active"}}}
        proposals = [{"title": "already active"}, {"title": "new work"}]
        self.assertEqual(
            [{"title": "new work"}],
            flywheel.unseen_proposals(proposals, queue, live),
        )

    def test_activity_cursor_is_filtered_client_side(self):
        requested = []
        old_http = flywheel.http
        try:
            def fake_http(method, url, body=None):
                requested.append(url)
                return {"activities": [
                    {"name": "old", "createTime": "2026-01-01T00:00:00Z"},
                    {"name": "new", "createTime": "2026-01-01T00:00:02Z"},
                ]}

            flywheel.http = fake_http
            activities = flywheel.Jules.activities(
                "sessions/1", after_ts="2026-01-01T00:00:01Z"
            )
        finally:
            flywheel.http = old_http

        self.assertNotIn("createTime=", requested[0])
        self.assertEqual(["new"], [activity["name"] for activity in activities])

    def test_nested_jules_git_patch_extracts_unidiff(self):
        change_set = {
            "gitPatch": {
                "baseCommitId": "abc123",
                "unidiffPatch": "diff --git a/Foo b/Foo\n",
            }
        }
        self.assertEqual(
            "diff --git a/Foo b/Foo\n",
            flywheel.change_set_patch(change_set),
        )

    def test_activity_poll_drains_all_pages(self):
        requested = []
        old_http = flywheel.http
        try:
            def fake_http(method, url, body=None):
                requested.append(url)
                if "pageToken=" not in url:
                    return {
                        "activities": [{"name": "first", "createTime": "1"}],
                        "nextPageToken": "next page",
                    }
                return {"activities": [{"name": "second", "createTime": "2"}]}

            flywheel.http = fake_http
            activities = flywheel.Jules.activities("sessions/1")
        finally:
            flywheel.http = old_http

        self.assertEqual(["first", "second"], [a["name"] for a in activities])
        self.assertIn("pageToken=next%20page", requested[1])


class ReconciliationTest(unittest.TestCase):
    def test_completed_session_without_patch_is_removed_and_requeued(self):
        with tempfile.TemporaryDirectory() as tmp:
            state_path = Path(tmp) / "state.json"
            state_path.write_text(json.dumps({
                "queue": [],
                "live": {
                    "sessions/123": {
                        "work": {
                            "tier": "task",
                            "score": 0.9,
                            "title": "retry terminal work",
                            "spec": "write a failing test first",
                            "parent": None,
                            "fingerprint": "ignored",
                        },
                        "state": "QUEUED",
                        "last_poll": None,
                        "patches": [],
                        "last_question": None,
                    }
                },
                "landed": 0,
                "outcomes": [],
            }))

            deleted = []
            old_state_path = flywheel.STATE_PATH
            old_argv = sys.argv
            old_main = flywheel._main
            old_snapshot = flywheel.snapshot
            old_brain_json = flywheel.brain_json
            old_get = flywheel.Jules.get
            old_activities = flywheel.Jules.activities
            old_delete = flywheel.Jules.delete
            old_max_live = flywheel.MAX_LIVE
            try:
                flywheel.STATE_PATH = str(state_path)
                flywheel.MAX_LIVE = 0
                flywheel._main = lambda: "master"
                flywheel.snapshot = lambda: ""
                flywheel.brain_json = lambda system, user, default: default
                flywheel.Jules.get = staticmethod(
                    lambda name: {"name": name, "state": "COMPLETED"}
                )
                flywheel.Jules.activities = staticmethod(
                    lambda name, after_ts=None: []
                )
                flywheel.Jules.delete = staticmethod(deleted.append)
                sys.argv = ["flywheel.py", "--once"]

                self.assertEqual(0, flywheel.main())
            finally:
                flywheel.STATE_PATH = old_state_path
                flywheel._main = old_main
                flywheel.snapshot = old_snapshot
                flywheel.brain_json = old_brain_json
                flywheel.Jules.get = old_get
                flywheel.Jules.activities = old_activities
                flywheel.Jules.delete = old_delete
                flywheel.MAX_LIVE = old_max_live
                sys.argv = old_argv

            state = json.loads(state_path.read_text())
            self.assertEqual({}, state["live"])
            self.assertEqual(["retry terminal work"], [w["title"] for w in state["queue"]])
            self.assertEqual(["sessions/123"], deleted)

    def test_rejected_patch_is_removed_and_requeued_with_gate_feedback(self):
        with tempfile.TemporaryDirectory() as tmp:
            state_path = Path(tmp) / "state.json"
            state_path.write_text(json.dumps({
                "queue": [],
                "live": {
                    "sessions/456": {
                        "work": {
                            "tier": "feature",
                            "score": 0.8,
                            "title": "resolve conflict continuously",
                            "spec": "write a failing test first",
                            "parent": None,
                            "fingerprint": "ignored",
                            "attempt": 0,
                            "feedback": "",
                        },
                        "state": "QUEUED",
                        "last_poll": None,
                        "patches": [{"changeSet": {"gitPatch": "broken patch"}}],
                        "last_question": None,
                    }
                },
                "landed": 0,
                "outcomes": [],
            }))

            deleted = []
            old_state_path = flywheel.STATE_PATH
            old_argv = sys.argv
            old_main = flywheel._main
            old_snapshot = flywheel.snapshot
            old_brain_json = flywheel.brain_json
            old_get = flywheel.Jules.get
            old_activities = flywheel.Jules.activities
            old_delete = flywheel.Jules.delete
            old_land = flywheel.land
            old_max_live = flywheel.MAX_LIVE
            try:
                flywheel.STATE_PATH = str(state_path)
                flywheel.MAX_LIVE = 0
                flywheel._main = lambda: "master"
                flywheel.snapshot = lambda: ""
                flywheel.brain_json = lambda system, user, default: default
                flywheel.Jules.get = staticmethod(
                    lambda name: {"name": name, "state": "COMPLETED"}
                )
                flywheel.Jules.activities = staticmethod(
                    lambda name, after_ts=None: []
                )
                flywheel.Jules.delete = staticmethod(deleted.append)
                flywheel.land = lambda patch, branch, title: (
                    False,
                    "git apply conflict in src/commonMain/Foo.kt",
                )
                sys.argv = ["flywheel.py", "--once"]

                self.assertEqual(0, flywheel.main())
            finally:
                flywheel.STATE_PATH = old_state_path
                flywheel._main = old_main
                flywheel.snapshot = old_snapshot
                flywheel.brain_json = old_brain_json
                flywheel.Jules.get = old_get
                flywheel.Jules.activities = old_activities
                flywheel.Jules.delete = old_delete
                flywheel.land = old_land
                flywheel.MAX_LIVE = old_max_live
                sys.argv = old_argv

            state = json.loads(state_path.read_text())
            self.assertEqual({}, state["live"])
            self.assertEqual(1, len(state["queue"]))
            retry = state["queue"][0]
            self.assertEqual("resolve conflict continuously", retry["title"])
            self.assertEqual(1, retry["attempt"])
            self.assertIn("git apply conflict", retry["feedback"])
            self.assertEqual(["sessions/456"], deleted)


class TestCommandDetectionTest(unittest.TestCase):
    def test_gradle_wrapper_selects_gradle_test_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "gradlew").touch()
            self.assertEqual("./gradlew test", land.detect_test_cmd(tmp))


class BaselineGateTest(unittest.TestCase):
    def test_red_baseline_allows_same_errors(self):
        baseline = "e: file:///repo/Foo.kt:1 Unresolved reference 'x'.\n"
        candidate = "e: file:///repo/Foo.kt:1 Unresolved reference 'x'.\n"
        self.assertTrue(land.is_nonregression(1, baseline, 1, candidate))

    def test_red_baseline_rejects_new_errors(self):
        baseline = "e: file:///repo/Foo.kt:1 Unresolved reference 'x'.\n"
        candidate = baseline + "e: file:///repo/Bar.kt:2 Unresolved reference 'y'.\n"
        self.assertFalse(land.is_nonregression(1, baseline, 1, candidate))

    def test_green_candidate_is_always_accepted(self):
        self.assertTrue(land.is_nonregression(1, "baseline failed", 0, "BUILD SUCCESS"))

    def test_green_baseline_rejects_red_candidate(self):
        self.assertFalse(land.is_nonregression(0, "BUILD SUCCESS", 1, "failed"))


if __name__ == "__main__":
    unittest.main()
