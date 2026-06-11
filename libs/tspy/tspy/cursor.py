"""
Cursor algebra — columnar data abstraction.

RowVec = Series2<Any, () -> RecordMeta>
Cursor = Series<RowVec>

Cursors are a columnar abstraction composed of Series of joined value+meta pairs.
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Generic, TypeVar, Any, Iterable, Iterator, Protocol
from .join import Join, j
from .series import Series, SeriesClass

# Type variables
V = TypeVar("V")  # value type
M = TypeVar("M")  # metadata type


# Record metadata supplier
RecordMeta = dict[str, Any]


# RowVec = Series2<Any, () -> RecordMeta>
# In our algebra: Series<Join<V, Callable[[], M]>]
# i.e., a Series of (value, metadata-supplier) pairs
RowVec = Series[Join[V, Callable[[], M]]]


# Cursor = Series<RowVec>
Cursor = Series[RowVec[V, M]]


# --- Helper to create SeriesClass-based cursors ---
def _make_cursor(size: int, index_fn: Callable[[int], RowVec[V, M]]) -> Cursor[V, M]:
    """Create a Cursor as SeriesClass."""
    return SeriesClass(size, index_fn)


# --- Helper to get size/index from Cursor (works with SeriesClass) ---
def _cursor_size(cursor: Cursor[V, M]) -> int:
    """Get size from cursor."""
    return cursor.size


def _cursor_get(cursor: Cursor[V, M], i: int) -> RowVec[V, M]:
    """Get row from cursor by index."""
    return cursor[i]


# --- Cursor operations ---

def cursor_get(cursor: Cursor[V, M], i: int) -> RowVec[V, M]:
    """cursor[i] — select a row/view by index."""
    return _cursor_get(cursor, i)


def cursor_range(cursor: Cursor[V, M], start: int, stop: int) -> Cursor[V, M]:
    """cursor[start:stop] — range view."""
    def slice_fn(idx: int) -> RowVec[V, M]:
        return _cursor_get(cursor, start + idx)
    return _make_cursor(stop - start, slice_fn)


def cursor_project(cursor: Cursor[V, M], *indices: int) -> Cursor[V, M]:
    """cursor[1, 3, 2] — reorder/project columns by index."""
    def project_fn(idx: int) -> RowVec[V, M]:
        row = _cursor_get(cursor, idx)
        selected = tuple(row[j] for j in indices)
        return _make_cursor(len(selected), lambda j: selected[j])
    return _make_cursor(_cursor_size(cursor), project_fn)


def cursor_project_names(cursor: Cursor[V, M], *names: str) -> Cursor[V, M]:
    """cursor['name', 'age'] — project by column name."""
    def project_fn(idx: int) -> RowVec[V, M]:
        row = _cursor_get(cursor, idx)
        selected = []
        for j in range(row.size):
            meta = row[j].b()
            if meta.get("name") in names:
                selected.append(row[j])
        return _make_cursor(len(selected), lambda j: selected[j])
    return _make_cursor(_cursor_size(cursor), project_fn)


def cursor_exclude(cursor: Cursor[V, M], *names: str) -> Cursor[V, M]:
    """cursor[-\"debug\"] — exclude columns by name."""
    def project_fn(idx: int) -> RowVec[V, M]:
        row = _cursor_get(cursor, idx)
        selected = []
        for j in range(row.size):
            meta = row[j].b()
            if meta.get("name") not in names:
                selected.append(row[j])
        return _make_cursor(len(selected), lambda j: selected[j])
    return _make_cursor(_cursor_size(cursor), project_fn)


def join_cursors(c1: Cursor[V, M], c2: Cursor[V, M]) -> Cursor[V, M]:
    """join(c1, c2) — widen along columns (concat rows horizontally)."""
    assert _cursor_size(c1) == _cursor_size(c2), "Cursors must have same row count for join"
    def join_fn(idx: int) -> RowVec[V, M]:
        row1 = _cursor_get(c1, idx)
        row2 = _cursor_get(c2, idx)
        combined_size = row1.size + row2.size
        def combined_get(j: int) -> Join[V, Callable[[], M]]:
            if j < row1.size:
                return row1[j]
            return row2[j - row1.size]
        return _make_cursor(combined_size, combined_get)
    return _make_cursor(_cursor_size(c1), join_fn)


def combine_cursors(c1: Cursor[V, M], c2: Cursor[V, M]) -> Cursor[V, M]:
    """combine(c1, c2) — concatenate along rows (vertical concat)."""
    total_size = _cursor_size(c1) + _cursor_size(c2)
    def combine_fn(idx: int) -> RowVec[V, M]:
        if idx < _cursor_size(c1):
            return _cursor_get(c1, idx)
        return _cursor_get(c2, idx - _cursor_size(c1))
    return _make_cursor(total_size, combine_fn)


# --- Construction helpers ---

def cursor_from_rows(*rows: RowVec[V, M]) -> Cursor[V, M]:
    """Create a Cursor from RowVec instances."""
    lst = list(rows)
    return _make_cursor(len(lst), lambda i: lst[i])


def cursor_from_columns(
    column_names: list[str],
    *columns: Series[V]
) -> Cursor[V, M]:
    """
    Create a Cursor from column series.
    Each column must have the same size.
    """
    if not columns:
        return _make_cursor(0, lambda _: (_ for _ in ()).throw(StopIteration))
    
    size = columns[0].size
    for col in columns:
        assert col.size == size, "All columns must have same size"
    
    def row_fn(idx: int) -> RowVec[V, M]:
        row_data = []
        for col_idx, col in enumerate(columns):
            value = col[idx]
            name = column_names[col_idx] if col_idx < len(column_names) else f"col_{col_idx}"
            metadata = lambda n=name: {"name": n, "index": col_idx}
            row_data.append(Join(value, metadata))
        return _make_cursor(len(row_data), lambda j: row_data[j])
    
    return _make_cursor(size, row_fn)


# --- Cursor view (materialized iterable) ---

class CursorView(Generic[V, M]):
    """Materialized iterable view of a Cursor."""
    def __init__(self, cursor: Cursor[V, M]):
        self._cursor = cursor
    
    def __iter__(self) -> Iterator[RowVec[V, M]]:
        for i in range(_cursor_size(self._cursor)):
            yield _cursor_get(self._cursor, i)
    
    def __len__(self) -> int:
        return _cursor_size(self._cursor)
    
    def __getitem__(self, i: int) -> RowVec[V, M]:
        return _cursor_get(self._cursor, i)

    def to_list(self) -> list[RowVec[V, M]]:
        return list(self)


Cursor.view = property(lambda self: CursorView(self))  # type: ignore[attr-defined]