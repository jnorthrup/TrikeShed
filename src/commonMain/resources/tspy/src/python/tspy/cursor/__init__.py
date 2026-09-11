"""
tspy.cursor — TrikeShed cursor algebra in Python (tuple-native)

Cursor = Series<RowVec>
RowVec = Series2<Any?, ColumnMeta↻> = Series<Join<Any?, () -> ColumnMeta>>

All types are tuples/type-aliases for immutability and wire-protocol compatibility.
"""

from __future__ import annotations

from typing import Any, Callable, Generic, Iterable, Iterator, TypeVar, Tuple, Protocol, TypeAlias
from dataclasses import dataclass

from ..algebra import Series, Join, j as _j, twin as _twin, s_ as _s_

# Python 3.9 compat: slots=True only in 3.10+
import sys
if sys.version_info >= (3, 10):
    _DC_SLOTS = {'frozen': True, 'slots': True}
else:
    _DC_SLOTS = {'frozen': True}

T = TypeVar('T')
K = TypeVar('K')

# Re-export core algebra
j = _j
twin = _twin
s_ = _s_

# =============================================================================
# TypeMemento / IOMemento
# =============================================================================

class TypeMemento(Protocol):
    """Type evidence carried by column metadata"""
    network_size: int | None

@dataclass(**_DC_SLOTS)
class IOMemento:
    """Standard IO mementos — fixed-width types enable O(1) random access"""
    network_size: int | None
    
    @property
    def kind(self) -> int:
        if self.network_size is None:
            return 2  # variable width
        if self.network_size <= 8:
            return 0  # primitive scalar
        return 1  # container

# Singleton instances
IoBoolean = IOMemento(1)
IoInt = IOMemento(4)
IoLong = IOMemento(8)
IoFloat = IOMemento(4)
IoDouble = IOMemento(8)
IoString = IOMemento(None)
IoLocalDate = IOMemento(10)
IoInstant = IOMemento(None)
IoNothing = IOMemento(0)
IoObject = IOMemento(None)
IoArray = IOMemento(None)
IoBytes = IOMemento(None)


@dataclass(**_DC_SLOTS)
class ColumnMeta:
    """ColumnMeta = Join<CharSequence, Join<TypeMemento, ColumnMeta?>> — name × type × child"""
    name: str
    type: TypeMemento
    child: 'ColumnMeta | None' = None
    
    def __iter__(self):
        return iter((self.name, (self.type, self.child)))

ColumnMetaRef = ColumnMeta  # Alias — the interface IS the implementation

# Lazy column metadata supplier — exact match to Kotlin `ColumnMeta↻ = () -> ColumnMeta`
ColumnMetaThunk = Callable[[], ColumnMeta]

# =============================================================================
# Type Aliases — exact match to Kotlin cursor typealiases
# =============================================================================

# RowVec = Series2<Any?, ColumnMeta↻> = Series<Join<Any?, () -> ColumnMeta>>
_RowVec: TypeAlias = Series[Join[Any, ColumnMetaThunk]]

# Cursor = Series<RowVec>
_Cursor: TypeAlias = Series[_RowVec]

# =============================================================================
# Constructors — callable functions matching the type alias names
# =============================================================================

def row_cell(value: Any, meta: ColumnMeta) -> Join[Any, ColumnMetaThunk]:
    """Construct a cell: Join<value, () -> meta>"""
    return (value, lambda: meta)


def _row_vec(*cells: Join[Any, ColumnMetaThunk]) -> _RowVec:
    """Internal: Construct RowVec from cells — returns Series<Join<Any, ColumnMetaThunk>>"""
    # Handle both row_vec(cell1, cell2, ...) and row_vec((cell1, cell2, ...))
    # A cell is Join<value, thunk> = tuple of length 2
    # A collection of cells would be tuple/list of length != 2
    if len(cells) == 1 and isinstance(cells[0], (tuple, list)):
        # If it's a tuple of length 2, it's a single cell, not a collection
        if not (isinstance(cells[0], tuple) and len(cells[0]) == 2 and callable(cells[0][1])):
            cells = tuple(cells[0])
    return _s_(*cells)


def _cursor(*rows: _RowVec) -> _Cursor:
    """Internal: Construct Cursor from rows — returns Series<RowVec>"""
    return _s_(*rows)


# Public constructors with type alias names — callable for backward compatibility
def RowVec(*cells: Join[Any, ColumnMetaThunk]) -> _RowVec:
    """Construct RowVec = Series<Join<Any, ColumnMetaThunk>>"""
    return _row_vec(*cells)


def Cursor(*rows: _RowVec) -> _Cursor:
    """Construct Cursor = Series<RowVec>"""
    return _cursor(*rows)


# Backward-compatible lowercase aliases
row_vec = RowVec
cursor = Cursor


# =============================================================================
# Cursor combinators (pure projections) — work on Series types directly
# =============================================================================

def select(cursor: _Cursor, *cols: int) -> _Cursor:
    """Column projection by ordinal indices — reorders / projects columns"""
    return _s_(*(
        _row_vec(*(cursor[row][c] for c in cols))
        for row in range(cursor.size)
    ))


def select_names(cursor: _Cursor, *names: str) -> _Cursor:
    """Column projection by name"""
    first_row = cursor[0]
    name_to_idx = {}
    for c in range(first_row.size):
        meta = first_row[c][1]()  # thunk
        name_to_idx[meta.name] = c
    indices = tuple(name_to_idx[n] for n in names)
    return select(cursor, *indices)


def exclude(cursor: _Cursor, *names: str) -> _Cursor:
    """Column exclusion by name"""
    first_row = cursor[0]
    indices = [c for c in range(first_row.size) 
               if first_row[c][1]().name not in names]
    return select(cursor, *indices)


def cursor_join(left: _Cursor, right: _Cursor) -> _Cursor:
    """Widen along columns — side-by-side join"""
    rows = min(left.size, right.size)
    return _s_(*(
        _row_vec(*(
            left[row][c] if c < left[row].size else right[row][c - left[row].size]
            for c in range(left[row].size + right[row].size)
        ))
        for row in range(rows)
    ))

# Alias for backward compatibility
join = cursor_join


def combine(top: _Cursor, bottom: _Cursor) -> _Cursor:
    """Concatenate along rows — top-to-bottom"""
    return _s_(*(
        top[row] if row < top.size else bottom[row - top.size]
        for row in range(top.size + bottom.size)
    ))


# Cursor α — lazy map over rows
def cursor_alpha(cursor: _Cursor, xform: Callable[[_RowVec], T]) -> Series[T]:
    return cursor.alpha(xform)


# Head / Tail
def head(cursor: _Cursor) -> _RowVec:
    return cursor[0]


def tail(cursor: _Cursor) -> _Cursor:
    return _s_(*(cursor[i] for i in range(1, cursor.size)))


# Meta access
def meta(cursor: _Cursor) -> Series[ColumnMeta]:
    """Column metadata series from first row"""
    row = cursor[0]
    return Series(row.size, lambda c: row[c][1]())


def column_names(cursor: _Cursor) -> Series[str]:
    return meta(cursor).alpha(lambda m: m.name)


def width(cursor: _Cursor) -> int:
    return cursor[0].size


# =============================================================================
# RowVec ↔ FacetedRow<ColK<*>> isomorphism
# =============================================================================

class ColK(Protocol[T]):
    """Columnar facet keys"""
    class ByIndex(Protocol):
        col: int
    class ByName(Protocol):
        name: str
    class Meta(Protocol): pass
    class Width(Protocol): pass


def as_faceted(rv: _RowVec):
    """Lift: RowVec → FacetedRow<ColK<*>>"""
    return FacetedRow(
        value_at=lambda op: _colk_get(rv, op),
        width=rv.size,
        meta_series=Series(rv.size, lambda c: rv[c][1]())
    )


class FacetedRow(Generic[K]):
    """FacetedRow<K> = MetaSeries<K, Any?>"""
    __slots__ = ('_value_at', '_width', '_meta_series')
    
    def __init__(self, value_at: Callable[[K], Any], width: int, meta_series: Series[ColumnMeta]):
        self._value_at = value_at
        self._width = width
        self._meta_series = meta_series
    
    def __getitem__(self, op: K) -> Any:
        return self._value_at(op)
    
    @property
    def width(self) -> int:
        return self._width
    
    @property
    def meta(self) -> Series[ColumnMeta]:
        return self._meta_series


def _colk_get(rv: _RowVec, op: Any) -> Any:
    if isinstance(op, ColK.ByIndex):
        return rv[op.col][0]
    elif isinstance(op, ColK.ByName):
        # Find by name in first row meta
        for c in range(rv.size):
            if rv[c][1]().name == op.name:
                return rv[c][0]
        raise KeyError(op.name)
    elif isinstance(op, ColK.Meta):
        return rv.size, lambda c: rv[c][1]()
    elif isinstance(op, ColK.Width):
        return rv.size
    raise TypeError(f"Unknown ColK: {op}")


def as_rowvec(fr: Any) -> _RowVec:
    """Lower: FacetedRow<ColK<*>> → RowVec"""
    # Simplified — requires full protocol implementation
    raise NotImplementedError("Full lowering requires FacetedRow protocol")