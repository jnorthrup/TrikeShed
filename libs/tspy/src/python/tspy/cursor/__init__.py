"""
tspy.cursor — TrikeShed cursor algebra in Python (tuple-native)

Cursor = Series<RowVec>
RowVec = Series2<Any, ColumnMeta↻> = Series<Join<Any, () -> ColumnMeta>>

All types use tuples for immutability.
"""

from __future__ import annotations
from typing import Any, Callable, Generic, Iterable, Iterator, TypeVar, Tuple, Protocol
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


# Lazy column metadata supplier
ColumnMetaThunk = Callable[[], ColumnMeta]


# =============================================================================
# RowVec = Series2<Any, ColumnMeta↻> = Series<Join<Any, () -> ColumnMeta>>
# =============================================================================

# RowVec as a dataclass wrapper around Series
@dataclass(**_DC_SLOTS)
class RowVec:
    """RowVec = Series<Join<Any, ColumnMetaThunk>>"""
    cells: Series[Join[Any, ColumnMetaThunk]]
    
    def __init__(self, *cells: Join[Any, ColumnMetaThunk]):
        from ..algebra import s_ as _s_
        # Handle both RowVec(cell1, cell2, ...) and RowVec((cell1, cell2, ...))
        if len(cells) == 1 and isinstance(cells[0], (tuple, list)):
            cells = tuple(cells[0])
        object.__setattr__(self, 'cells', _s_(*cells))
    
    @property
    def size(self) -> int:
        return self.cells.size
    
    def __getitem__(self, i: int) -> Join[Any, ColumnMetaThunk]:
        return self.cells[i]
    
    def __iter__(self):
        return iter(self.cells)
    
    def __len__(self) -> int:
        return self.size


def row_cell(value: Any, meta: ColumnMeta) -> Join[Any, ColumnMetaThunk]:
    """Construct a cell: Join<value, () -> meta>"""
    return (value, lambda: meta)


def row_vec(*cells: Join[Any, ColumnMetaThunk]) -> RowVec:
    """Construct RowVec from cells"""
    return RowVec(*cells)


# =============================================================================
# Cursor = Series<RowVec>
# =============================================================================

# =============================================================================
# Cursor = Series<RowVec>
# =============================================================================

@dataclass(**_DC_SLOTS)
class Cursor:
    """Cursor = Series<RowVec>"""
    rows: Series[RowVec]
    
    def __init__(self, *rows: RowVec):
        from ..algebra import s_ as _s_
        object.__setattr__(self, 'rows', _s_(*rows))
    
    @property
    def size(self) -> int:
        return self.rows.size
    
    def __getitem__(self, i: int) -> RowVec:
        return self.rows[i]
    
    def range(self, start: int, end: int) -> 'Cursor':
        count = max(0, min(end, self.size) - start)
        return Cursor(*(self.rows[start + i] for i in range(count)))
    
    def __iter__(self):
        return iter(self.rows)
    
    def __len__(self) -> int:
        return self.size
    
    def alpha(self, xform):
        return self.rows.alpha(xform)


def cursor(*rows: RowVec) -> Cursor:
    """Construct Cursor from rows"""
    return Cursor(*rows)


# =============================================================================
# Cursor combinators (pure projections)
# =============================================================================

def select(cursor: Cursor, *cols: int) -> Cursor:
    """Column projection by ordinal indices — reorders / projects columns"""
    return Cursor(*(
        RowVec(*(cursor[row][c] for c in cols))
        for row in range(cursor.size)
    ))


def select_names(cursor: Cursor, *names: str) -> Cursor:
    """Column projection by name"""
    first_row = cursor[0]
    name_to_idx = {}
    for c in range(first_row.size):
        meta = first_row[c][1]()  # thunk
        name_to_idx[meta.name] = c
    indices = tuple(name_to_idx[n] for n in names)
    return select(cursor, *indices)


def exclude(cursor: Cursor, *names: str) -> Cursor:
    """Column exclusion by name"""
    first_row = cursor[0]
    indices = [c for c in range(first_row.size) 
               if first_row[c][1]().name not in names]
    return select(cursor, *indices)


def join(left: Cursor, right: Cursor) -> Cursor:
    """Widen along columns — side-by-side join"""
    rows = min(left.size, right.size)
    return Cursor(*(
        RowVec(*(
            left[row][c] if c < left[row].size else right[row][c - left[row].size]
            for c in range(left[row].size + right[row].size)
        ))
        for row in range(rows)
    ))


def combine(top: Cursor, bottom: Cursor) -> Cursor:
    """Concatenate along rows — top-to-bottom"""
    return Cursor(*(
        top[row] if row < top.size else bottom[row - top.size]
        for row in range(top.size + bottom.size)
    ))


# Cursor α — lazy map over rows
def cursor_alpha(cursor: Cursor, xform: Callable[[RowVec], T]) -> Series[T]:
    return cursor.alpha(xform)


# Head / Tail
def head(cursor: Cursor) -> RowVec:
    return cursor[0]


def tail(cursor: Cursor) -> Cursor:
    return cursor.range(1, cursor.size)


# Meta access
def meta(cursor: Cursor) -> Series[ColumnMeta]:
    """Column metadata series from first row"""
    row = cursor[0]
    return Series(row.size, lambda c: row[c][1]())


def column_names(cursor: Cursor) -> Series[str]:
    return meta(cursor).alpha(lambda m: m.name)


def width(cursor: Cursor) -> int:
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


def as_faceted(rv: RowVec):
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


def _colk_get(rv: RowVec, op: Any) -> Any:
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


def as_rowvec(fr: Any) -> RowVec:
    """Lower: FacetedRow<ColK<*>> → RowVec"""
    # Simplified — requires full protocol implementation
    raise NotImplementedError("Full lowering requires FacetedRow protocol")