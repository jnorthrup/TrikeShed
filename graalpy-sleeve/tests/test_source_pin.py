"""The CLI accepts only the clean, committed source selected by its fork lock."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


BRIDGE = Path(__file__).resolve().parents[1] / "bridges" / "trikeshed_cli_worker.py"
REPOSITORY = "https://github.com/jnorthrup/hermes-trikeshed-graal.git"


class SourcePinTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "source"
        self.root.mkdir()
        self.lock = Path(self.directory.name) / "hermes.lock.json"
        self.git("init", "--quiet")
        self.git("config", "user.name", "Source Pin Test")
        self.git("config", "user.email", "source-pin@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("remote", "add", "origin", REPOSITORY)
        (self.root / "cli.py").write_text("class HermesCLI: pass\n", encoding="utf-8")
        self.git("add", "cli.py")
        self.git("commit", "--quiet", "-m", "initial source")
        self.revision = self.git("rev-parse", "HEAD").strip()
        self.lock.write_text(json.dumps({"repository": REPOSITORY, "revision": self.revision}), encoding="utf-8")
        spec = importlib.util.spec_from_file_location("source_pin_test_bridge", BRIDGE)
        self.bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.bridge)

    def git(self, *arguments):
        environment = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        return subprocess.check_output(["git", "-C", str(self.root), *arguments],
                                       env=environment, text=True, stderr=subprocess.STDOUT)

    def test_clean_exact_fork_commit_is_accepted(self):
        source = self.bridge.verify_source(self.root, self.lock)
        self.assertEqual(source["repository"], REPOSITORY)
        self.assertEqual(source["revision"], self.revision)

    def test_changed_head_reports_actual_and_expected_revision(self):
        self.git("commit", "--allow-empty", "--quiet", "-m", "unreviewed update")
        actual = self.git("rev-parse", "HEAD").strip()
        with self.assertRaises(RuntimeError) as failure:
            self.bridge.verify_source(self.root, self.lock)
        self.assertIn(actual, str(failure.exception))
        self.assertIn(self.revision, str(failure.exception))

    def test_modified_tracked_source_is_refused(self):
        (self.root / "cli.py").write_text("raise RuntimeError('unreviewed source')\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "cli.py"):
            self.bridge.verify_source(self.root, self.lock)

    def test_staged_source_is_refused(self):
        (self.root / "cli.py").write_text("raise RuntimeError('staged source')\n", encoding="utf-8")
        self.git("add", "cli.py")
        with self.assertRaisesRegex(RuntimeError, "cli.py"):
            self.bridge.verify_source(self.root, self.lock)

    def test_unversioned_export_is_refused(self):
        export = Path(self.directory.name) / "export"
        export.mkdir()
        (export / "cli.py").write_text("class HermesCLI: pass\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "Git"):
            self.bridge.verify_source(export, self.lock)

    def test_parent_repository_cannot_authorize_nested_source(self):
        nested = self.root / "nested"
        nested.mkdir()
        (nested / "cli.py").write_text("class HermesCLI: pass\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "root"):
            self.bridge.verify_source(nested, self.lock)

    def test_wrong_origin_reports_actual_and_expected_repository(self):
        actual = "https://github.com/NousResearch/hermes-agent.git"
        self.git("remote", "set-url", "origin", actual)
        with self.assertRaises(RuntimeError) as failure:
            self.bridge.verify_source(self.root, self.lock)
        self.assertIn(actual, str(failure.exception))
        self.assertIn(REPOSITORY, str(failure.exception))

    def test_missing_lock_is_refused(self):
        self.lock.unlink()
        with self.assertRaisesRegex(RuntimeError, "lock"):
            self.bridge.verify_source(self.root, self.lock)

    def test_non_exact_revision_is_refused(self):
        self.lock.write_text(json.dumps({"repository": REPOSITORY, "revision": "main"}), encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "revision"):
            self.bridge.verify_source(self.root, self.lock)

    def test_cleanup_cannot_import_rejected_source(self):
        marker = Path(self.directory.name) / "executed"
        (self.root / "cli.py").write_text(
            f"from pathlib import Path\nPath({str(marker)!r}).touch()\n", encoding="utf-8")
        sys.path.insert(0, str(self.root))
        try:
            self.bridge.close()
            self.assertFalse(marker.exists(), "cleanup must not execute an unverified cli module")
        finally:
            sys.path.remove(str(self.root))
            sys.modules.pop("cli", None)


if __name__ == "__main__":
    unittest.main()
