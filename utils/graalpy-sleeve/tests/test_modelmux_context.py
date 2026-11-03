"""The host owns breadcrumb identity; donor session ids stay in the donor."""

import importlib.util
import json
from pathlib import Path
import types
import unittest
from unittest.mock import patch

import httpx


BRIDGE = Path(__file__).resolve().parents[1] / "bridges" / "trikeshed_modelmux.py"


class ModelMuxContextTest(unittest.TestCase):
    def test_provider_reads_valid_context_from_current_host_catalog(self):
        spec = importlib.util.spec_from_file_location("modelmux_context_catalog_bridge", BRIDGE)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        cards = [{"id": "fixture", "contextWindow": 131072}]
        bridge.install(lambda *args: json.dumps({"verdict": "ok", "models": cards}))

        class ProviderProfile:
            def __init__(self, **kwargs):
                pass

        providers = types.ModuleType("providers")
        providers.register_provider = lambda profile: None
        base = types.ModuleType("providers.base")
        base.ProviderProfile = ProviderProfile
        with patch.dict("sys.modules", {"providers": providers, "providers.base": base}):
            profile = bridge.register()
            self.assertEqual(profile.get_model_context_length("fixture"), 131072)
            cards[0]["contextWindow"] = 2048
            self.assertEqual(profile.get_model_context_length("fixture"), 2048)
            for invalid in (None, 0, -1, True, "4096"):
                cards[0]["contextWindow"] = invalid
                self.assertIsNone(profile.get_model_context_length("fixture"))
            self.assertIsNone(profile.get_model_context_length("unknown"))

    def test_buffered_stream_respects_usage_option(self):
        spec = importlib.util.spec_from_file_location("modelmux_stream_test_bridge", BRIDGE)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        completion = {"id": "call", "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                      "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}}
        bridge.install(lambda *args: json.dumps({"verdict": "ok", "completion": completion}))
        session = types.ModuleType("gateway.session_context")
        session.get_session_env = lambda name: "session"
        with patch.dict("sys.modules", {"gateway.session_context": session}):
            for option in (None, False, True):
                body = {"model": "test/model", "messages": [], "stream": True}
                if option is not None:
                    body["stream_options"] = {"include_usage": option}
                request = httpx.Request("POST", bridge.BASE_URL + "/chat/completions", json=body)
                response = bridge.ModelMuxTransport().handle_request(request)
                chunks = [json.loads(line[6:]) for line in response.text.splitlines()
                          if line.startswith("data: {")]
                self.assertEqual(len(chunks), 2 if option else 1)
                if option:
                    self.assertEqual(chunks[-1]["usage"], completion["usage"])

            for invalid in (0, 1, "true", None, [], {}):
                request = httpx.Request("POST", bridge.BASE_URL + "/chat/completions", json={
                    "model": "test/model", "messages": [], "stream": True,
                    "stream_options": {"include_usage": invalid},
                })
                with self.assertRaises(ValueError):
                    bridge.ModelMuxTransport().handle_request(request)

    def test_donor_session_cannot_select_a_breadcrumb_context(self):
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

        self.assertTrue(all(len(call) == 4 for call in calls))
        for verb, model, payload, timeout in calls:
            self.assertEqual((verb, model, timeout), ("mux_completion", "test/model", 17500))
            self.assertEqual(json.loads(payload), {"messages": body["messages"]})


if __name__ == "__main__":
    unittest.main()
