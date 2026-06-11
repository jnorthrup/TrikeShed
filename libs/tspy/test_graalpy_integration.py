#!/usr/bin/env python3
"""
GraalPy Integration Test for tspy

This test runs the tspy Python tests on GraalPy via the polyglot harness.
It validates that the tspy algebra works correctly on GraalPy runtime.

Run via: ./gradlew :libs:polyglot:jvmTest --tests "GraalPointcutTddTest.graalpy tspy integration*"
"""

import sys

# This test expects to be run via the GraalPointcutHarness which binds pointcutEmitter
# and makes tspy available via the JVM classpath

def test_tspy_import():
    """Test that tspy can be imported on GraalPy"""
    import tspy
    print(f"tspy version: {getattr(tspy, '__version__', 'unknown')}")
    print(f"sys.implementation.name: {sys.implementation.name}")
    assert sys.implementation.name == 'graalpy', "Should run on GraalPy"
    print("✓ tspy imports successfully on GraalPy")

def test_tspy_algebra_basic():
    """Test basic tspy algebra operations on GraalPy"""
    from tspy import Join, Series, j, s_, alpha
    
    # Join
    jn = j(1, "hello")
    assert jn == (1, "hello")
    print("✓ Join works")
    
    # Series literal
    series = s_(1, 2, 3)
    assert series.size == 3
    assert series[0] == 1
    print("✓ Series literal works")
    
    # Series alpha projection
    doubled = series.alpha(lambda x: x * 2)
    assert doubled[0] == 2
    assert doubled[2] == 6
    print("✓ Series alpha works")
    
    # constant anchor
    c = alpha(lambda: 42)
    assert c() == 42
    print("✓ Alpha constant works")

def test_tspy_wire_protocol():
    """Test FieldSynapse wire protocol on GraalPy"""
    from tspy import FieldSynapse
    
    fs = FieldSynapse(
        phase=0, opcode=0xA5, method_idx=1, addr=42,
        seq=1, nano=1234567890123456789,
        callsite_hash=999, template_idx=5
    )
    encoded = fs.encode()
    assert len(encoded) in (30, 32)
    decoded = FieldSynapse.decode(encoded)
    assert decoded.phase == fs.phase
    assert decoded.opcode == fs.opcode
    assert decoded.method_idx == fs.method_idx
    print("✓ FieldSynapse encode/decode works")

def test_tspy_cursor():
    """Test cursor algebra on GraalPy"""
    from tspy import ColumnMeta, IoInt, IoString, RowVec, row_cell, cursor as make_cursor, select
    
    meta1 = ColumnMeta("price", IoInt)
    meta2 = ColumnMeta("name", IoString)
    rv = RowVec((
        row_cell(100, meta1),
        row_cell("widget", meta2),
    ))
    c = make_cursor(rv)
    assert c.size == 1
    print("✓ Cursor construction works")
    
    projected = select(c, 0)
    assert projected.size == 1
    print("✓ Cursor select works")

def test_tspy_pointcut_emitter():
    """Test PyenvEmitter on GraalPy"""
    from tspy import PyenvEmitter
    
    emitter = PyenvEmitter()
    record = emitter.emit_field_access(
        phase=0, is_static=False, is_write=False,
        class_name="TestTarget", field_name="instanceInt",
        source_location="test.py:1", seq=1
    )
    assert record.opcode == 0xA5  # L_GET
    assert record.phase == 0
    print("✓ PyenvEmitter works")

def test_tspy_chronicle():
    """Test Chronicle on GraalPy"""
    from tspy import CHRONICLE, TransitionSplat, emit
    
    emit(TransitionSplat(
        element_key="test",
        from_state="A",
        splat=None,
        actual_state="B",
        composition=("Test",)
    ))
    # Flush to JSON
    json_output = CHRONICLE.flush_to_json()
    assert isinstance(json_output, str)
    print("✓ Chronicle works")

# Run all tests if executed directly
if __name__ == "__main__":
    test_tspy_import()
    test_tspy_algebra_basic()
    test_tspy_wire_protocol()
    test_tspy_cursor()
    test_tspy_pointcut_emitter()
    test_tspy_chronicle()
    print("\nAll tspy GraalPy integration tests passed!")
