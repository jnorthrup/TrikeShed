"""
Operators — infix constructors and projection operators.

α       — lazy projection: series α transform (alias: alpha)
left_identity (↺) — constant anchor: x.↺ == lambda: x
"""

from __future__ import annotations
from typing import Callable, TypeVar, Generic, Any

from .join import j
from .series import Series, SeriesClass

X = TypeVar("X")
C = TypeVar("C")
T = TypeVar("T")


def alpha(series: SeriesClass[X], xform: Callable[[X], C]) -> SeriesClass[C]:
    """
    Lazy projection: series α xform.
    Mirrors Kotlin `series α { it.transform() }`.
    Returns a new Series with size preserved, elements transformed lazily.
    """
    return SeriesClass(series.size, lambda i: xform(series[i]))


# Unicode alias
α = alpha


class _LeftIdentity:
    """Callable that returns its argument as a thunk: x.↺ == lambda: x."""
    
    def __call__(self, x: T) -> Callable[[], T]:
        return lambda: x
    
    def __get__(self, obj: Any, objtype: Any = None) -> Callable[[], T]:
        # Descriptor protocol for x.↺ access
        if obj is None:
            return self
        return lambda: obj


left_identity = _LeftIdentity()

# The ↺ and α aliases are injected into module namespace in __init__.py