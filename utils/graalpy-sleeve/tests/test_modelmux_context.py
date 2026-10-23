"""Session identity crosses the host boundary without entering provider payloads."""

import importlib.util
import json
from pathlib import Path
import types
import unittest
from unittest.mock import patch

import httpx


BRIDGE = Path(__file__).resolve().parents[1] / "bridges" / "trikeshed_modelmux.py"


class ModelMuxContextTest(unittest.TestCase):
    def test_each_request_uses_current_session_without_rewriting_tool_content(self):
        spec = importlib.util.spec_from_file_location("modelmux_context_test_bridge", BRIDGE)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        calls = []

        def host(*args):
            calls.append(args)
            return json.dumps({"verdict": "ok", "completion": {"choices": []}})

        bridge.install(host)
        session = types.ModuleType("gateway.session_context")
        session_id = "hermes-first"
        session.get_session_env = lambda name: session_id if name == "HERMES_SESSION_ID" else ""
        body = {"model": "test/model", "messages": [
            {"role": "tool", "tool_call_id": "tool-1", "content": "exact tool evidence\n\u03bb"}
        ]}
        with patch.dict("sys.modules", {"gateway.session_context": session}):
            transport = bridge.ModelMuxTransport()
            for session_id in ("hermes-first", "hermes-after-new", ""):
                request = httpx.Request("POST", bridge.BASE_URL + "/chat/completions", json=body,
                                        extensions={"timeout": {"read": 17.5}})
                self.assertEqual(transport.handle_request(request).status_code, 200)

        self.assertEqual([call[4] for call in calls], ["hermes-first", "hermes-after-new", ""])
        for verb, model, payload, timeout, _ in calls:
            self.assertEqual((verb, model, timeout), ("mux_completion", "test/model", 17500))
            self.assertEqual(json.loads(payload), {"messages": body["messages"]})


if __name__ == "__main__":
    unittest.main()
