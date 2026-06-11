"""
Composition literals — dense inline constructors.

_l[...]  — List/Tuple
_a[...]  — Array (list with type hint)
_s[...]  — Set
s_[...]  — Series
"""

from __future__ import annotations
from typing import TypeVar, Iterable, Callable, Any
from .series import series_of, Series
from .join import Join

T = TypeVar("T")


class _Literal:
    """Base class for composition literals."""
    def __getitem__(self, items):
        if not isinstance(items, tuple):
            items = (items,)
        return self._construct(items)
    
    def _construct(self, items: tuple) -> Any:
        raise NotImplementedError


class _ListLiteral(_Literal):
    """_l[...] — List literal."""
    def _construct(self, items: tuple) -> list:
        return list(items)


class _ArrayLiteral(_Literal):
    """_a[...] — Array literal (typed list)."""
    def _construct(self, items: tuple) -> list:
        return list(items)  # In Python, list is the array type


class _SetLiteral(_Literal):
    """_s[...] — Set literal."""
    def _construct(self, items: tuple) -> set:
        return set(items)


class _SeriesLiteral(_Literal):
    """s_[...] — Series literal."""
    def _construct(self, items: tuple) -> Series:
        return series_of(*items)


# Singleton instances
_l = _ListLiteral()
_a = _ArrayLiteral()
_s = _SetLiteral()
s_ = _SeriesLiteral()


# --- Infix j operator for int ---

# We can't really extend int, but we can provide a helper
# The pattern is: count.j(lambda i: ...) -> Series
# In Python, we use j(count, fn) from operators.py

def _int_j(count: int, fn: Callable[[int], T]) -> Series[T]:
    """Helper: count.j(fn) -> Series. Mirrors Kotlin `count.j { ... }`."""
    return Join(count, fn)


# Monkey-patch int for convenience (not recommended but matches Kotlin style)
# int.j = staticmethod(_int_j)  # Can't do this in Python


# Alternative: provide a callable that enables `count | j | fn` syntax
# This is done via the _InfixJ sentinel in operators.py