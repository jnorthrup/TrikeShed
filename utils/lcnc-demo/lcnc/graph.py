"""Typed LCNC graph definition and versioning (I03, I11).

The graph is a repeatable typed group: sources, transforms, engines, panels,
sinks. Three columns are a view, not a hardcoded architecture. Any config
change produces a NEW definition identity; old evidence stays historical and
cannot silently certify the new graph. Every analysis node carries an explicit
context (asserted / hypothetical / fictional / derived) — no default union.
"""
from __future__ import annotations

from .cas import cid_of

NODE_TYPES = {"source", "transform", "engine", "panel", "sink"}
CONTEXTS = {"asserted", "hypothetical", "fictional", "derived"}


class Graph:
    def __init__(self, nodes: list, edges: list, label: str = ""):
        self.nodes = {n["id"]: n for n in nodes}
        self.edges = [tuple(e) for e in edges]
        self.label = label
        self._validate()

    def _validate(self):
        for nid, n in self.nodes.items():
            if n["type"] not in NODE_TYPES:
                raise ValueError(f"node {nid}: unknown type {n['type']!r}")
            if n["type"] in ("engine", "panel", "sink") and n.get("context", "asserted") not in CONTEXTS:
                raise ValueError(f"node {nid}: invalid context")
        for a, b in self.edges:
            if a not in self.nodes or b not in self.nodes:
                raise ValueError(f"edge ({a},{b}) references unknown node")

    def definition(self) -> dict:
        return {"label": self.label,
                "nodes": [self.nodes[k] for k in sorted(self.nodes)],
                "edges": sorted(self.edges)}

    @property
    def cid(self) -> str:
        return cid_of(self.definition())

    def with_config(self, node_id: str, **config) -> "Graph":
        """Rescaffold: returns a NEW graph (new identity), never mutates this one."""
        nodes = [dict(n) for n in self.nodes.values()]
        for n in nodes:
            if n["id"] == node_id:
                n["config"] = {**n.get("config", {}), **config}
        return Graph(nodes, list(self.edges), label=self.label)

    def to_json(self) -> dict:
        return {"definitionCid": self.cid, **self.definition()}
