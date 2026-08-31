"""The callable agent: schemas, signatures, skill, and services.

Lazy for the same reason as the top-level package: `schema` is pure pydantic and
must stay importable where dspy and predict_rlm are absent (the sandbox, and any
environment that only wants to read a serialized DepthModel). Eagerly importing
`service` here would make every schema consumer depend on the whole agent stack.
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

_SOURCES = {
    "LcncDepth": ".service",
    "DepthModeler": ".service",
    "DepthAdjudicator": ".service",
    "ModelDepth": ".signature",
    "AdjudicateDepth": ".signature",
    "kotlin_source": ".skills",
    "DepthModel": ".schema",
    "DepthReport": ".schema",
    "KindBinding": ".schema",
    "PortType": ".schema",
    "ContextDemand": ".schema",
    "ScopeProvision": ".schema",
    "SupervisionBoundary": ".schema",
    "Violation": ".schema",
    "MateProposal": ".schema",
}

if TYPE_CHECKING:  # pragma: no cover
    from .schema import (
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
    from .service import DepthAdjudicator, DepthModeler, LcncDepth
    from .signature import AdjudicateDepth, ModelDepth
    from .skills import kotlin_source


def __getattr__(name: str):
    module_path = _SOURCES.get(name)
    if module_path is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from importlib import import_module

    return getattr(import_module(module_path, __name__), name)


def __dir__() -> list[str]:
    return sorted(__all__)
