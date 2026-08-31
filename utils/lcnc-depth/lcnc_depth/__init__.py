"""lcnc-depth — model LCNC connections below the five-string kind layer.

The existing checker matches ports by exact string equality over
{json, text, id, trigger, num}. That layer is nominal over `Any?`: the executor
never reads a kind, and `json` alone carries String, Boolean, Int, Map and List.
This package derives what the kinds do not say — the Kotlin type actually on the
cable, the CoroutineContext elements a node needs to run, and what a supervision
boundary genuinely discounts — and then judges programs against it.

Exports are LAZY on purpose. `lcnc_depth.modules.kotlin_scan` is stdlib-only and
is mounted into the RLM sandbox, where dspy and predict_rlm do not exist; an
eager re-export here would make importing the scanner require the whole agent
stack. Attribute access below pulls the agent in only when it is actually asked
for, so the scanners stay importable — and testable — on their own.
"""

from typing import TYPE_CHECKING

__all__ = [
    "LcncDepth",
    "DepthModeler",
    "DepthAdjudicator",
    "ModelDepth",
    "AdjudicateDepth",
    "kotlin_source",
    "DepthModel",
    "DepthReport",
    "KindBinding",
    "PortType",
    "ContextDemand",
    "ScopeProvision",
    "SupervisionBoundary",
    "Violation",
    "MateProposal",
]

# Which submodule each public name comes from. `schema` is pure pydantic and
# cheap; `service`/`signature`/`skills` need dspy + predict_rlm.
_SOURCES = {
    "LcncDepth": ".agent.service",
    "DepthModeler": ".agent.service",
    "DepthAdjudicator": ".agent.service",
    "ModelDepth": ".agent.signature",
    "AdjudicateDepth": ".agent.signature",
    "kotlin_source": ".agent.skills",
    "DepthModel": ".agent.schema",
    "DepthReport": ".agent.schema",
    "KindBinding": ".agent.schema",
    "PortType": ".agent.schema",
    "ContextDemand": ".agent.schema",
    "ScopeProvision": ".agent.schema",
    "SupervisionBoundary": ".agent.schema",
    "Violation": ".agent.schema",
    "MateProposal": ".agent.schema",
}

if TYPE_CHECKING:  # pragma: no cover - for type checkers and editors only
    from .agent.schema import (
        ContextDemand,
        DepthModel,
        DepthReport,
        KindBinding,
        MateProposal,
        PortType,
        ScopeProvision,
        SupervisionBoundary,
        Violation,
    )
    from .agent.service import DepthAdjudicator, DepthModeler, LcncDepth
    from .agent.signature import AdjudicateDepth, ModelDepth
    from .agent.skills import kotlin_source


def __getattr__(name: str):
    """PEP 562 lazy export — see the module docstring for why."""
    module_path = _SOURCES.get(name)
    if module_path is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from importlib import import_module

    return getattr(import_module(module_path, __name__), name)


def __dir__() -> list[str]:
    return sorted(__all__)
