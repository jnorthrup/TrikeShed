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


# --- Type alias for type hinting ---
# Series<T> = Join<int, Callable[[int], T]>
Series = Join[int, Callable[[int], X]]


# --- Proper Series class with all methods ---

class SeriesMixin:
    """Mixin providing Series methods for Join objects."""
    
    @property
    def size(self) -> int:
        return self.a
    
    def __getitem__(self, i: int) -> X:
        return self.b(i)
    
    @property
    def view(self) -> Iterable[X]:
        return IterableSeries(self)
    
    def __matmul__(self, xform: Callable[[X], C]) -> Series[C]:
        """series @ xform — lazy map/projection (α operator)."""
        return j(self.a, lambda i: xform(self[i]))
    
    def __mul__(self, xform: Callable[[X], C]) -> Series[C]:
        """series * xform — alternative lazy map/projection."""
        return j(self.a, lambda i: xform(self[i]))
    
    @property
    def cpb(self):
        """series.cpb — comparable series view."""
        # Import here to avoid circular import
        from .series import CSeries
        return CSeries(self.a, self.b)


# We can't easily mix this into Join objects created by j()
# So we create a factory that returns objects with the mixin


def _make_series(size: int, index_fn: Callable[[int], X]) -> Series[X]:
    """Create a Series (Join with Series methods attached)."""
    series_obj = Join(size, index_fn)
    # Attach mixin methods
    series_obj.size = property(lambda self: self.a).__get__(series_obj, Series)
    series_obj.__getitem__ = lambda self, i: self.b(i).__get__(series_obj, Series)
    series_obj.view = property(lambda self: IterableSeries(self)).__get__(series_obj, Series)
    series_obj.__matmul__ = lambda self, xform: j(self.a, lambda i: xform(self[i])).__get__(series_obj, Series)
    series_obj.__mul__ = lambda self, xform: j(self.a, lambda i: xform(self[i])).__get__(series_obj, Series)
    return series_obj


# Simpler approach: just use a class


class SeriesClass(Generic[X]):
    """Series class implementing the full algebra."""
    
    def __init__(self, size: int, index_fn: Callable[[int], X]):
        self._size = size
        self._index_fn = index_fn
    
    @property
    def size(self) -> int:
        return self._size
    
    @property
    def a(self) -> int:
        return self._size
    
    @property
    def b(self) -> Callable[[int], X]:
        return self._index_fn
    
    def __getitem__(self, i: int) -> X:
        return self._index_fn(i)
    
    @property
    def view(self) -> Iterable[X]:
        return IterableSeries(self)
    
    def __matmul__(self, xform: Callable[[X], C]) -> Series[C]:
        """series @ xform — lazy map/projection (α operator)."""
        return SeriesClass(self._size, lambda i: xform(self._index_fn(i)))
    
    def __mul__(self, xform: Callable[[X], C]) -> Series[C]:
        return self.__matmul__(xform)
    
    def __iter__(self) -> Iterator[X]:
        for i in range(self._size):
            yield self._index_fn(i)
    
    def __repr__(self) -> str:
        return f"Series({self._size}, {self._index_fn})"
    
    @property
    def cpb(self):
        from .series import CSeries
        return CSeries(self._size, self._index_fn)


# Update Series type alias to use our class for construction
# But keep the type hint as Join for compatibility
def _series_from_join(join: Join[int, Callable[[int], X]]) -> SeriesClass[X]:
    """Convert a Join to SeriesClass."""
    return SeriesClass(join.a, join.b)


# --- Lazy projection α (alpha) ---

def alpha_projection(series: SeriesClass[X], xform: Callable[[X], C]) -> SeriesClass[C]:
    """series α xform — lazy map/projection. Mirrors Kotlin `series α { ... }`."""
    return series @ xform


# --- Iterable materialization ---

@dataclass(frozen=True, repr=False)
class IterableSeries(Generic[X]):
    """Materialized iterable view of a Series."""
    source: SeriesClass[X]

    def __iter__(self) -> Iterator[X]:
        for i in range(self.source.size):
            yield self.source[i]

    def __len__(self) -> int:
        return self.source.size

    def __getitem__(self, i: int) -> X:
        return self.source[i]

    def map(self, fn: Callable[[X], C]) -> "IterableSeries[C]":
        return IterableSeries(self.source @ fn)

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


# --- Construction helpers ---

def series_of(*items: X) -> SeriesClass[X]:
    """Create a Series from items: s_(1, 2, 3)."""
    lst = list(items)
    return SeriesClass(len(lst), lambda i: lst[i])


def series_from_iterable(it: Iterable[X]) -> SeriesClass[X]:
    """Create a Series from any iterable."""
    lst = list(it)
    return SeriesClass(len(lst), lambda i: lst[i])


def series_range(start: int, stop: int, step: int = 1) -> SeriesClass[int]:
    """Create a Series from range."""
    rng = range(start, stop, step)
    return SeriesClass(len(rng), lambda i: rng[i])


def series_repeat(value: X, count: int) -> SeriesClass[X]:
    """Create a Series repeating a value."""
    return SeriesClass(count, lambda _: value)


# For backward compatibility with type hints
# Series = Join[int, Callable[[int], X]]  # type alias