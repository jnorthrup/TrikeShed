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
from .series import Series

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


# --- Cursor operations ---

def cursor_get(cursor: Cursor[V, M], i: int) -> RowVec[V, M]:
    """cursor[i] — select a row/view by index."""
    return cursor[i]


def cursor_range(cursor: Cursor[V, M], start: int, stop: int) -> Cursor[V, M]:
    """cursor[start:stop] — range view."""
    # Return a lazy slice
    def slice_fn(idx: int) -> RowVec[V, M]:
        return cursor[start + idx]
    return j(stop - start, slice_fn)


def cursor_project(cursor: Cursor[V, M], *indices: int) -> Cursor[V, M]:
    """cursor[1, 3, 2] — reorder/project columns by index."""
    # This projects rows by selecting specific columns
    # For now, a simplified implementation
    def project_fn(idx: int) -> RowVec[V, M]:
        row = cursor[idx]
        selected = tuple(row[j] for j in indices)
        return j(len(selected), lambda j: selected[j])
    return j(cursor.size, project_fn)


def cursor_project_names(cursor: Cursor[V, M], *names: str) -> Cursor[V, M]:
    """cursor['name', 'age'] — project by column name."""
    # Requires metadata to have column names
    def project_fn(idx: int) -> RowVec[V, M]:
        row = cursor[idx]
        # Find columns by name in metadata
        selected = []
        for j in range(row.size):
            meta = row[j].b()  # metadata supplier
            if meta.get("name") in names:
                selected.append(row[j])
        return j(len(selected), lambda j: selected[j])
    return j(cursor.size, project_fn)


def cursor_exclude(cursor: Cursor[V, M], *names: str) -> Cursor[V, M]:
    """cursor[-\"debug\"] — exclude columns by name."""
    def project_fn(idx: int) -> RowVec[V, M]:
        row = cursor[idx]
        selected = []
        for j in range(row.size):
            meta = row[j].b()
            if meta.get("name") not in names:
                selected.append(row[j])
        return j(len(selected), lambda j: selected[j])
    return j(cursor.size, project_fn)


def join_cursors(c1: Cursor[V, M], c2: Cursor[V, M]) -> Cursor[V, M]:
    """join(c1, c2) — widen along columns (concat rows horizontally)."""
    assert c1.size == c2.size, "Cursors must have same row count for join"
    def join_fn(idx: int) -> RowVec[V, M]:
        row1 = c1[idx]
        row2 = c2[idx]
        # Combine the row vectors
        combined_size = row1.size + row2.size
        def combined_get(j: int) -> Join[V, Callable[[], M]]:
            if j < row1.size:
                return row1[j]
            return row2[j - row1.size]
        return j(combined_size, combined_get)
    return j(c1.size, join_fn)


def combine_cursors(c1: Cursor[V, M], c2: Cursor[V, M]) -> Cursor[V, M]:
    """combine(c1, c2) — concatenate along rows (vertical concat)."""
    total_size = c1.size + c2.size
    def combine_fn(idx: int) -> RowVec[V, M]:
        if idx < c1.size:
            return c1[idx]
        return c2[idx - c1.size]
    return j(total_size, combine_fn)


# --- Construction helpers ---

def cursor_from_rows(*rows: RowVec[V, M]) -> Cursor[V, M]:
    """Create a Cursor from RowVec instances."""
    lst = list(rows)
    return j(len(lst), lambda i: lst[i])


def cursor_from_columns(
    column_names: list[str],
    *columns: Series[V]
) -> Cursor[V, M]:
    """
    Create a Cursor from column series.
    Each column must have the same size.
    """
    if not columns:
        return j(0, lambda _: (_ for _ in ()).throw(StopIteration))
    
    size = len(columns[0])
    for col in columns:
        assert len(col) == size, "All columns must have same size"
    
    def row_fn(idx: int) -> RowVec[V, M]:
        row_data = []
        for col_idx, col in enumerate(columns):
            value = col[idx]
            name = column_names[col_idx] if col_idx < len(column_names) else f"col_{col_idx}"
            metadata = lambda n=name: {"name": n, "index": col_idx}
            row_data.append(Join(value, metadata))
        return j(len(row_data), lambda j: row_data[j])
    
    return j(size, row_fn)


# --- Cursor view (materialized iterable) ---

class CursorView(Generic[V, M]):
    """Materialized iterable view of a Cursor."""
    def __init__(self, cursor: Cursor[V, M]):
        self._cursor = cursor
    
    def __iter__(self) -> Iterator[RowVec[V, M]]:
        for i in range(self._cursor.size):
            yield self._cursor[i]
    
    def __len__(self) -> int:
        return self._cursor.size
    
    def __getitem__(self, i: int) -> RowVec[V, M]:
        return self._cursor[i]

    def to_list(self) -> list[RowVec[V, M]]:
        return list(self)


Cursor.view = property(lambda self: CursorView(self))  # type: ignore[attr-defined]