"""TrikeShed-backed memory provider for a pen-hosted Hermes.

Implements the hermes-agent ``agent/memory_provider.py`` ABC surface over the
pen's host.call verbs. The pen is the ONLY tool alphabet this provider speaks:
no sockets, no files outside the guest world, every call receipted host-side.

Lifecycle mapping:
  initialize          -> nothing to open; the render is fetched lazily
  system_prompt_block -> the frozen MEMORY render + selected-skill block
  prefetch(query)     -> crumb_walk warm pass (bounded backchain)
  sync_turn(u, a)     -> turn-close landing; the host's TurnReview induces
  handle_tool_call    -> verb dispatch straight onto host.call
  on_session_end      -> no-op (host owns persistence)

Refusals come back as data ({"verdict": ...}); this provider treats them as
empty results, never as exceptions — AIKR at the call site.
"""

import json


def _call(name, *args):
    try:
        raw = host.call(name, *args)  # noqa: F821  (host is the pen binding)
    except Exception:
        return {"verdict": "unavailable"}
    if raw is None:
        return {"verdict": "none"}
    try:
        return json.loads(raw)
    except Exception:
        return {"verdict": "opaque", "raw": str(raw)}


class TrikeShedMemoryProvider:
    name = "trikeshed"

    def __init__(self):
        self._snapshot_block = None

    # ── lifecycle ────────────────────────────────────────────────

    def initialize(self, config=None):
        return True

    def system_prompt_block(self):
        # Frozen per session: first read wins, mid-session belief changes
        # stay host-side until the next session (prompt-prefix economics).
        if self._snapshot_block is None:
            recall = _call("bag_recall", "session memory", 24)
            beliefs = recall.get("beliefs", []) if recall.get("verdict") == "ok" else []
            lines = ["## Memory (belief render)"]
            for b in beliefs[:16]:
                lines.append("- %s (e=%.2f)" % (b.get("subjectCid", "?")[:19], b.get("expectation", 0.0)))
            self._snapshot_block = "\n".join(lines)
        return self._snapshot_block

    def prefetch(self, query):
        walk = _call("crumb_walk", str(query), None, 5)
        return walk.get("picks", []) if walk.get("verdict") == "ok" else []

    def sync_turn(self, user_message, assistant_message, success=True):
        _call("bag_assert", "turn:%s" % str(user_message)[:96], None, bool(success))

    def on_session_end(self):
        return None

    # ── tools ────────────────────────────────────────────────────

    def get_tool_schemas(self):
        # TrikeShed-native vocabulary; nothing aliases a familiar tool.
        return [
            {
                "name": "crumb_walk",
                "description": "Walk the ontological breadcrumb planes from a goal; returns skill picks with their trails.",
                "input_schema": {"type": "object", "properties": {
                    "goal": {"type": "string"}, "category": {"type": "string"}, "k": {"type": "integer"}},
                    "required": ["goal"]},
            },
            {
                "name": "bag_recall",
                "description": "Hamming-recall beliefs near a goal coordinate from the belief bag.",
                "input_schema": {"type": "object", "properties": {
                    "goal": {"type": "string"}, "maxDistance": {"type": "integer"}}, "required": ["goal"]},
            },
            {
                "name": "bag_assert",
                "description": "Assert one observed outcome as unit evidence (clamped; the host audits).",
                "input_schema": {"type": "object", "properties": {
                    "subject": {"type": "string"}, "object": {"type": "string"}, "success": {"type": "boolean"}},
                    "required": ["subject"]},
            },
            {
                "name": "skill_scribe",
                "description": "Create, patch, or archive a skill in the confined skills plane. There is no delete.",
                "input_schema": {"type": "object", "properties": {
                    "action": {"type": "string", "enum": ["create", "patch", "archive"]},
                    "category": {"type": "string"}, "name": {"type": "string"}, "content": {"type": "string"}},
                    "required": ["action", "category", "name"]},
            },
            {
                "name": "mux_converse",
                "description": "One lease-gated model exchange through the provider-neutral mux.",
                "input_schema": {"type": "object", "properties": {
                    "modelId": {"type": "string"}, "prompt": {"type": "string"}},
                    "required": ["modelId", "prompt"]},
            },
        ]

    def handle_tool_call(self, tool_name, tool_input):
        i = tool_input or {}
        if tool_name == "crumb_walk":
            return _call("crumb_walk", i.get("goal", ""), i.get("category"), i.get("k", 5))
        if tool_name == "bag_recall":
            return _call("bag_recall", i.get("goal", ""), i.get("maxDistance", 16))
        if tool_name == "bag_assert":
            return _call("bag_assert", i.get("subject", ""), i.get("object"), bool(i.get("success", True)))
        if tool_name == "skill_scribe":
            return _call("skill_scribe", i.get("action", ""), i.get("category", ""), i.get("name", ""), i.get("content"))
        if tool_name == "mux_converse":
            return _call("mux_converse", i.get("modelId", ""), i.get("prompt", ""))
        return {"verdict": "no-such-verb", "tool": tool_name}
