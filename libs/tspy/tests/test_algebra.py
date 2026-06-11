"""Tests for tspy algebra"""

import pytest
from tspy import (
    Join, Series, j, twin, s_, _l, _a, _s, constant,
    FieldSynapse,
    ColumnMeta, IoInt, IoString, RowVec, Cursor,
    row_cell, cursor as make_cursor, select, select_names, exclude,
    meta as cursor_meta, column_names, width,
    PyenvEmitter,
)


# =============================================================================
# Core Algebra Tests
# =============================================================================

def test_join_construction():
    jn = j(1, "hello")
    assert jn == (1, "hello")
    assert jn[0] == 1
    assert jn[1] == "hello"


def test_twin_construction():
    tw = twin(1, 2)
    assert tw == (1, 2)


def test_series_literal():
    series = s_(1, 2, 3)
    assert series.size == 3
    assert series[0] == 1
    assert series[1] == 2
    assert series[2] == 3


def test_series_alpha():
    series = s_(1, 2, 3)
    doubled = series.alpha(lambda x: x * 2)
    assert doubled.size == 3
    assert doubled[0] == 2
    assert doubled[1] == 4
    assert doubled[2] == 6


def test_series_range():
    series = s_(0, 1, 2, 3, 4)
    sub = series.range(1, 4)
    assert sub.size == 3
    assert sub[0] == 1
    assert sub[1] == 2
    assert sub[2] == 3


def test_series_to_tuple():
    series = s_('a', 'b', 'c')
    tup = series.to_tuple()
    assert tup == ('a', 'b', 'c')


def test_series_from_tuple():
    series = Series.from_tuple((10, 20, 30))
    assert series.size == 3
    assert series[0] == 10


def test_constant_anchor():
    c = constant(42)
    assert c() == 42
    assert c() == 42  # Left identity: same result every call


# =============================================================================
# Wire Protocol Tests
# =============================================================================

def test_fieldsynapse_encode_decode():
    fs = FieldSynapse(
        phase=0, opcode=0xA5, method_idx=1, addr=42,
        seq=1, nano=1234567890123456789,
        callsite_hash=999, template_idx=5
    )
    encoded = fs.encode()
    assert len(encoded) == 24
    decoded = FieldSynapse.decode(encoded)
    assert decoded.phase == fs.phase
    assert decoded.opcode == fs.opcode
    assert decoded.method_idx == fs.method_idx
    assert decoded.addr == fs.addr
    assert decoded.seq == fs.seq
    assert decoded.nano == fs.nano
    assert decoded.callsite_hash == fs.callsite_hash
    assert decoded.template_idx == fs.template_idx


def test_fieldsynapse_opcodes():
    assert FieldSynapse.OP_L_GET == 0xA5
    assert FieldSynapse.OP_L_SET == 0xA6
    assert FieldSynapse.OP_P_GET == 0xA7
    assert FieldSynapse.OP_P_SET == 0xA8


# =============================================================================
# ColumnMeta / IOMemento Tests
# =============================================================================

def test_iomemento_singletons():
    assert IoInt.network_size == 4
    assert IoLong.network_size == 8
    assert IoString.network_size is None
    assert IoObject.network_size is None
    assert IoBoolean.network_size == 1


def test_columnmeta_construction():
    meta = ColumnMeta("price", IoInt, None)
    assert meta.name == "price"
    assert meta.type is IoInt
    assert meta.child is None


def test_columnmeta_with_child():
    parent = ColumnMeta("order", IoObject)
    child = ColumnMeta("price", IoInt, None)
    meta = ColumnMeta("order", IoObject, child)
    assert meta.child is child
    assert meta.child.name == "price"


# =============================================================================
# RowVec / Cursor Tests
# =============================================================================

def test_row_cell():
    meta = ColumnMeta("price", IoInt)
    cell = row_cell(100, meta)
    assert cell[0] == 100
    assert cell[1]() is meta  # thunk returns same meta


def test_row_vec():
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
    ))
    assert rv.size == 2
    assert rv[0] == (100, meta1)
    assert rv[1] == ("widget", meta2)


def test_cursor_construction():
    meta = ColumnMeta("price", IoInt)
    rv1 = RowVec((row_cell(100, meta),))
    rv2 = RowVec((row_cell(200, meta),))
    c = make_cursor(rv1, rv2)
    assert c.size == 2
    assert c[0] == rv1
    assert c[1] == rv2


def test_cursor_select():
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    meta3 = ColumnMeta("qty", IoInt)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
        row_cell(10, meta3),
    ))
    c = make_cursor(rv)
    projected = select(c, 0, 2)
    assert projected.size == 1
    assert projected[0][0][0] == 100  # price
    assert projected[0][1][0] == 10   # qty


def test_cursor_select_names():
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    meta3 = ColumnMeta("qty", IoInt)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
        row_cell(10, meta3),
    ))
    c = make_cursor(rv)
    projected = select_names(c, "price", "qty")
    assert projected.size == 1
    assert projected[0].size == 2


def test_cursor_exclude():
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    meta3 = ColumnMeta("qty", IoInt)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
        row_cell(10, meta3),
    ))
    c = make_cursor(rv)
    excluded = exclude(c, "name")
    assert excluded.size == 1
    assert excluded[0].size == 2  # price, qty


def test_cursor_meta():
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
    ))
    c = make_cursor(rv)
    meta_series = cursor_meta(c)
    assert meta_series.size == 2
    assert meta_series[0].name == "price"
    assert meta_series[1].name == "name"


def test_cursor_column_names():
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
    ))
    c = make_cursor(rv)
    names = column_names(c)
    assert names.size == 2
    assert names[0] == "price"
    assert names[1] == "name"


def test_cursor_width():
    meta = ColumnMeta("price", IoInt)
    rv = RowVec((row_cell(100, meta),))
    c = make_cursor(rv)
    assert width(c) == 1


# =============================================================================
# PyenvEmitter Tests
# =============================================================================

def test_pyenv_emitter_field_access():
    emitter = PyenvEmitter()
    
    record = emitter.emit_field_access(
        phase=0, is_static=False, is_write=False,
        class_name="TestTarget", field_name="instanceInt",
        source_location="test.py:1", seq=1
    )
    
    assert record.opcode == FieldSynapse.OP_L_GET
    assert record.phase == 0
    assert record.seq == 1
    assert record.method_idx >= 0


def test_pyenv_emitter_static_field():
    emitter = PyenvEmitter()
    
    record = emitter.emit_field_access(
        phase=0, is_static=True, is_write=False,
        class_name="TestTarget", field_name="staticInt",
        source_location="test.py:1", seq=2
    )
    
    assert record.opcode == FieldSynapse.OP_P_GET


def test_pyenv_emitter_write():
    emitter = PyenvEmitter()
    
    record = emitter.emit_field_access(
        phase=0, is_static=False, is_write=True,
        class_name="TestTarget", field_name="instanceInt",
        source_location="test.py:1", seq=3
    )
    
    assert record.opcode == FieldSynapse.OP_L_SET


def test_pyenv_emitter_dual_phase():
    emitter = PyenvEmitter()
    
    # BEFORE
    emitter.emit_field_access(0, False, False, "Test", "field", "loc", 1)
    # AFTER
    emitter.emit_field_access(1, False, False, "Test", "field", "loc", 1)
    
    records = emitter.get_records()
    assert len(records) == 2
    assert records[0].phase == FieldSynapse.PHASE_BEFORE
    assert records[1].phase == FieldSynapse.PHASE_AFTER
    assert records[0].seq == records[1].seq  # Same seq for BEFORE/AFTER pair


# =============================================================================
# RingSeries Tests
# =============================================================================

def test_ring_series_basic():
    from tspy import RingSeries
    
    rs = RingSeries(4)
    assert rs.size == 0
    
    rs.add(1)
    rs.add(2)
    rs.add(3)
    assert rs.size == 3
    assert rs[0] == 1
    assert rs[1] == 2
    assert rs[2] == 3


def test_ring_series_wrap():
    from tspy import RingSeries
    
    rs = RingSeries(4)
    for i in range(6):  # Exceeds capacity
        rs.add(i)
    assert rs.size == 4
    # Should have 2, 3, 4, 5
    assert rs.to_series().to_tuple() == (2, 3, 4, 5)


def test_ring_series_to_series():
    from tspy import RingSeries
    
    rs = RingSeries(8)
    for i in range(3):
        rs.add(i * 10)
    series = rs.to_series()
    assert series.size == 3
    assert series.to_tuple() == (0, 10, 20)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])