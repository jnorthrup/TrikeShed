"""
Series algebra — indexed sequence with size and index function.

Series<T> = Join<int, Callable[[int], T]>
- size: int
- get(i): T
- view: IterableSeries (materialized iterable view)
- α: lazy projection
- cpb: comparable series (for comparable elements)
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Generic, TypeVar, Iterable, Iterator, Any, Protocol
from collections.abc import Sequence

from .join import Join, j

X = TypeVar("X")
C = TypeVar("C")
T = TypeVar("T")


# --- Type alias: Series<T> = Join<int, Callable[[int], T]> ---
# We use a protocol for structural typing

class SeriesProtocol(Protocol[X]):
    """Structural protocol for Series."""
    a: int  # size
    b: Callable[[int], X]  # index function
    
    @property
    def size(self) -> int: ...
    
    def __getitem__(self, i: int) -> X: ...
    
    @property
    def view(self) -> Iterable[X]: ...


# The actual Series type is Join<int, Callable[[int], X]>
Series = Join[int, Callable[[int], X]]


# --- Attach extension methods to Series (Join) objects ---
# These are added dynamically to instances created via series_of, etc.

def _series_size(self: Series[X]) -> int:
    """Series.size — the length of the series."""
    return self.a


def _series_getitem(self: Series[X], i: int) -> X:
    """Series[i] — index access."""
    return self.b(i)


def _series_view(self: Series[X]) -> Iterable[X]:
    """Series.view — materialized iterable view."""
    return IterableSeries(self)


def _series_matmul(self: Series[X], xform: Callable[[X], C]) -> Series[C]:
    """series @ xform — lazy map/projection (α operator)."""
    return j(self.a, lambda i: xform(self[i]))


def _series_mul(self: Series[X], xform: Callable[[X], C]) -> Series[C]:
    """series * xform — alternative lazy map/projection."""
    return j(self.a, lambda i: xform(self[i]))


# Attach as properties/methods to the Join class (used as Series)
# We do this at module load time
Series.size = property(_series_size)  # type: ignore[attr-defined]
Series.__getitem__ = _series_getitem  # type: ignore[attr-defined]
Series.view = property(_series_view)  # type: ignore[attr-defined]
Series.__matmul__ = _series_matmul  # type: ignore[attr-defined]
Series.__mul__ = _series_mul  # type: ignore[attr-defined]


# --- Lazy projection α (alpha) ---

def alpha_projection(series: SeriesProtocol[X], xform: Callable[[X], C]) -> Series[C]:
    """series α xform — lazy map/projection. Mirrors Kotlin `series α { ... }`."""
    return j(series.size, lambda i: xform(series[i]))


# --- Iterable materialization ---

@dataclass(frozen=True, repr=False)
class IterableSeries(Generic[X]):
    """Materialized iterable view of a Series."""
    source: SeriesProtocol[X]

    def __iter__(self) -> Iterator[X]:
        for i in range(self.source.size):
            yield self.source[i]

    def __len__(self) -> int:
        return self.source.size

    def __getitem__(self, i: int) -> X:
        return self.source[i]

    def map(self, fn: Callable[[X], C]) -> "IterableSeries[C]":
        # Return a new IterableSeries wrapping the projected Series
        return IterableSeries(alpha_projection(self.source, fn))

    def filter(self, pred: Callable[[X], bool]) -> list[X]:
        return [x for x in self if pred(x)]

    def all(self, pred: Callable[[X], bool]) -> bool:
        return all(pred(x) for x in self)

    def any(self, pred: Callable[[X], bool]) -> bool:
        return any(pred(x) for x in self)

    def to_list(self) -> list[X]:
        return list(self)


# --- CSeries for comparable elements ---

class CSeries(Generic[T]):
    """Comparable Series — Series with ordering."""
    def __init__(self, size: int, index_fn: Callable[[int], T]):
        self.size = size
        self._index_fn = index_fn

    def __getitem__(self, i: int) -> T:
        return self._index_fn(i)

    def __lt__(self, other: "CSeries[T]") -> bool:
        # Lexicographic comparison
        n = min(self.size, other.size)
        for i in range(n):
            if self[i] < other[i]:
                return True
            if self[i] > other[i]:
                return False
        return self.size < other.size

    def __le__(self, other: "CSeries[T]") -> bool:
        return self < other or self == other

    def __gt__(self, other: "CSeries[T]") -> bool:
        return not (self <= other)

    def __ge__(self, other: "CSeries[T]") -> bool:
        return not (self < other)


def cpb(series: Series[T]) -> CSeries[T]:
    """series.cpb — comparable series view."""
    return CSeries(series.a, series.b)


# Attach cpb property
Series.cpb = property(cpb)  # type: ignore[attr-defined]


# --- Construction helpers ---

def series_of(*items: X) -> Series[X]:
    """Create a Series from items: s_(1, 2, 3)."""
    lst = list(items)
    return j(len(lst), lambda i: lst[i])


def series_from_iterable(it: Iterable[X]) -> Series[X]:
    """Create a Series from any iterable."""
    lst = list(it)
    return j(len(lst), lambda i: lst[i])


def series_range(start: int, stop: int, step: int = 1) -> Series[int]:
    """Create a Series from range."""
    rng = range(start, stop, step)
    return j(len(rng), lambda i: rng[i])


def series_repeat(value: X, count: int) -> Series[X]:
    """Create a Series repeating a value."""
    return j(count, lambda _: value)