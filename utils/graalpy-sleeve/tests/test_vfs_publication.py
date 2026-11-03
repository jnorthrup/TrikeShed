"""The active-session registry binds its host publication to its own lease."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


SLEEVE = Path(__file__).resolve().parents[1]


class VfsPublicationTest(unittest.TestCase):
    def test_registry_pairs_existing_lock_with_the_state_it_publishes(self):
        calls = []

        def host(*args):
            calls.append(args)
            if args[1] == "acquire":
                return "lease-1"
            return True

        host_module = types.ModuleType("trikeshed_host")
        host_module.call = host
        constants = types.ModuleType("hermes_constants")
        constants.get_default_hermes_root = lambda: "/unused"
        constants.get_hermes_home = lambda: "/unused"
        constants.named_profile_is_live = lambda *_: False
        utils = types.ModuleType("utils")
        utils.atomic_json_write = lambda *_, **__: self.fail("native publication was selected")
        cli = types.ModuleType("trikeshed_cli")
        cli.LINE_MODE = True

        with tempfile.TemporaryDirectory() as directory, patch.dict(sys.modules, {
            "trikeshed_host": host_module, "hermes_constants": constants, "utils": utils, "trikeshed_cli": cli,
        }):
            spec = importlib.util.spec_from_file_location("trikeshed_vfs", SLEEVE / "python" / "trikeshed_vfs.py")
            vfs = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(vfs)
            with patch.dict(sys.modules, {"trikeshed_vfs": vfs}):
                spec = importlib.util.spec_from_file_location(
                    "active_sessions_vfs_test", SLEEVE / "hermes" / "hermes_cli" / "active_sessions.py"
                )
                sessions = importlib.util.module_from_spec(spec)
                with patch.dict(sys.modules, {spec.name: sessions}):
                    spec.loader.exec_module(sessions)
                    lock = Path(directory) / "runtime" / "active_sessions.lock"
                    state = lock.with_suffix(".json")
                    with sessions._FileLock(lock, state):
                        sessions._write_entries(state, [])

        self.assertEqual(calls[0], ("vfs_lease", "acquire", str(lock), str(state)))
        self.assertEqual(calls[1][:3], ("vfs_lease", "publish", str(state)))
        self.assertEqual(json.loads(calls[1][3]), {"entries": []})
        self.assertEqual(calls[2], ("vfs_lease", "release", "lease-1"))


if __name__ == "__main__":
    unittest.main()
