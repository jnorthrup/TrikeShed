"""Real terminal-pipe failure and retirement behavior of the CLI transport."""

import importlib.util
import io
import os
from pathlib import Path
import select
import threading
import time
import types
import unittest
from contextlib import contextmanager

from prompt_toolkit.input import create_pipe_input


BRIDGE = Path(__file__).resolve().parents[1] / "bridges" / "trikeshed_cli_worker.py"


class CliInputTest(unittest.TestCase):
    @contextmanager
    def transport(self):
        spec = importlib.util.spec_from_file_location("cli_input_test_bridge", BRIDGE)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        read_fd, write_fd = os.pipe()
        stdin = os.fdopen(read_fd, "r", encoding="utf-8")
        callbacks = []
        bridge.sys = types.SimpleNamespace(stdin=stdin, stderr=io.StringIO())
        bridge._host_call = lambda *args: callbacks.append(args)
        with create_pipe_input() as pipe:
            bridge._pipe = pipe
            try:
                yield bridge, stdin, write_fd, pipe, callbacks
            finally:
                try:
                    os.close(write_fd)
                except OSError:
                    pass
                try:
                    stdin.close()
                except OSError:
                    pass

    def assert_input_failure(self, bridge, pipe, callbacks):
        self.assertIsInstance(bridge._failure, str)
        self.assertTrue(bridge._failure)
        self.assertEqual(len(callbacks), 1)
        self.assertEqual(callbacks[0][0], "sleeve_cli_closed")
        self.assertEqual(callbacks[0][-1], bridge._failure)
        pipe.read_keys()
        self.assertTrue(pipe.closed, "lost stdin must deliver EOF to prompt_toolkit")

    def test_unexpected_eof_reports_failure_and_closes_cli_input(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            os.close(writer)
            bridge._feed(pipe)
            self.assert_input_failure(bridge, pipe, callbacks)

    def test_invalid_descriptor_reports_failure_and_closes_cli_input(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            os.close(stdin.fileno())
            bridge._feed(pipe)
            self.assert_input_failure(bridge, pipe, callbacks)

    def test_closed_stdin_reports_failure_and_closes_cli_input(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            stdin.close()
            bridge._feed(pipe)
            self.assert_input_failure(bridge, pipe, callbacks)

    def test_retirement_eof_is_quiet(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            bridge._closing = True
            os.close(writer)
            bridge._feed(pipe)
            self.assertIsNone(bridge._failure)
            self.assertEqual(callbacks, [])
            self.assertEqual(bridge.sys.stderr.getvalue(), "")

    def test_retirement_invalid_descriptor_is_quiet(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            bridge._closing = True
            os.close(stdin.fileno())
            bridge._feed(pipe)
            self.assertIsNone(bridge._failure)
            self.assertEqual(callbacks, [])
            self.assertEqual(bridge.sys.stderr.getvalue(), "")

    def test_expected_stop_is_quiet(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            bridge._input_stop.set()
            bridge._feed(pipe)
            self.assertIsNone(bridge._failure)
            self.assertEqual(callbacks, [])
            self.assertEqual(bridge.sys.stderr.getvalue(), "")

    def test_stop_joins_feeder_blocked_on_full_terminal_pipe(self):
        with self.transport() as (bridge, stdin, writer, pipe, callbacks):
            # Fill to the actual OS limit, without assuming a pipe capacity.
            fd = pipe.pipe.write_fd
            os.set_blocking(fd, False)
            try:
                while True:
                    os.write(fd, b"x" * 4096)
            except BlockingIOError:
                pass
            finally:
                os.set_blocking(fd, True)
            os.write(writer, b"q")
            feeder = threading.Thread(target=bridge._feed, args=(pipe,), daemon=True)
            bridge._feeder = feeder
            feeder.start()
            try:
                deadline = time.monotonic() + 2
                while select.select([stdin.fileno()], [], [], 0)[0] and time.monotonic() < deadline:
                    feeder.join(0.01)
                self.assertFalse(select.select([stdin.fileno()], [], [], 0)[0], "feeder must consume the source byte")
                self.assertFalse(select.select([], [fd], [], 0)[1], "terminal pipe must remain full")
                bridge._input_stop.set()
                feeder.join(0.1)
                self.assertTrue(feeder.is_alive(), "full-pipe write must block despite the stop event")
                bridge._stop_input(pipe)
                self.assertFalse(feeder.is_alive())
                self.assertIsNone(bridge._failure)
                self.assertEqual(callbacks, [])
            finally:
                bridge._input_stop.set()
                pipe.read_keys()
                feeder.join(2)
                self.assertFalse(feeder.is_alive(), "test must retire its input feeder")


if __name__ == "__main__":
    unittest.main()
