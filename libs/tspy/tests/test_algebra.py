"""
Tests for tspy — TrikeShed Python kernel algebra.
"""

import pytest
from tspy import (
    Join, Twin, Series, RowVec, Cursor,
    j, alpha, α, left_identity,
    _l, _a, _s, s_,
    series_of, series_range, series_repeat,
    cursor_from_columns,
)


class TestJoin:
    """Tests for Join algebra."""
    
    def test_join_creation(self):
        j = Join(1, "hello")
        assert j.a == 1
        assert j.b == "hello"
        assert j.pair == (1, "hello")
    
    def test_join_unpacking(self):
        j = Join(1, "hello")
        a, b = j
        assert a == 1
        assert b == "hello"
    
    def test_join_component_access(self):
        j = Join(1, "hello")
        assert j.component1() == 1
        assert j.component2() == "hello"
    
    def test_twin(self):
        t = Twin(42)
        assert t.a == 42
        assert t.b == 42
        assert isinstance(t, Join)
    
    def test_join_equality(self):
        assert Join(1, 2) == Join(1, 2)
        assert Join(1, 2) != Join(2, 1)
        assert Join(1, 2) != (1, 2)  # Different types
    
    def test_join_repr(self):
        j = Join(1, "hello")
        assert repr(j) == "Join(1, 'hello')"


class TestInfixJ:
    """Tests for infix j operator."""
    
    def test_j_function(self):
        result = j(1, "hello")
        assert isinstance(result, Join)
        assert result.a == 1
        assert result.b == "hello"
    
    def test_j_infix_syntax(self):
        # Using | j | syntax
        from tspy.operators import _j_infix
        result = (1, "hello") | _j_infix
        assert isinstance(result, Join)
        assert result.a == 1
        assert result.b == "hello"
        
        result = _j_infix | (1, "hello")
        assert result.a == 1
        assert result.b == "hello"


class TestSeries:
    """Tests for Series algebra."""
    
    def test_series_creation(self):
        s = series_of(1, 2, 3, 4, 5)
        assert s.size == 5
        assert s[0] == 1
        assert s[4] == 5
    
    def test_series_index_access(self):
        s = series_of("a", "b", "c")
        assert s[0] == "a"
        assert s[1] == "b"
        assert s[2] == "c"
    
    def test_series_view(self):
        s = series_of(1, 2, 3)
        view = s.view
        assert list(view) == [1, 2, 3]
        assert len(view) == 3
    
    def test_series_iteration(self):
        s = series_of(1, 2, 3)
        result = []
        for x in s.view:
            result.append(x)
        assert result == [1, 2, 3]
    
    def test_series_alpha_projection(self):
        s = series_of(1, 2, 3, 4)
        doubled = s @ (lambda x: x * 2)  # Using @ for α
        assert doubled.size == 4
        assert list(doubled.view) == [2, 4, 6, 8]
    
    def test_series_alpha_unicode(self):
        s = series_of(1, 2, 3)
        doubled = α(s, lambda x: x * 2)
        assert list(doubled.view) == [2, 4, 6]
    
    def test_series_range(self):
        s = series_range(0, 5)
        assert s.size == 5
        assert list(s.view) == [0, 1, 2, 3, 4]
    
    def test_series_range_with_step(self):
        s = series_range(0, 10, 2)
        assert s.size == 5
        assert list(s.view) == [0, 2, 4, 6, 8]
    
    def test_series_repeat(self):
        s = series_repeat("x", 3)
        assert s.size == 3
        assert list(s.view) == ["x", "x", "x"]
    
    def test_series_from_iterable(self):
        from tspy.series import series_from_iterable
        s = series_from_iterable(x for x in range(3))
        assert s.size == 3
        assert list(s.view) == [0, 1, 2]


class TestSeriesView:
    """Tests for IterableSeries view methods."""
    
    def test_view_map(self):
        s = series_of(1, 2, 3)
        mapped = s.view.map(lambda x: x * 10)
        assert list(mapped) == [10, 20, 30]
    
    def test_view_filter(self):
        s = series_of(1, 2, 3, 4, 5)
        filtered = s.view.filter(lambda x: x % 2 == 0)
        assert filtered == [2, 4]
    
    def test_view_all(self):
        s = series_of(2, 4, 6)
        assert s.view.all(lambda x: x % 2 == 0) is True
        assert s.view.all(lambda x: x > 5) is False
    
    def test_view_any(self):
        s = series_of(1, 3, 5)
        assert s.view.any(lambda x: x % 2 == 0) is False
        s2 = series_of(1, 2, 3)
        assert s2.view.any(lambda x: x % 2 == 0) is True
    
    def test_view_to_list(self):
        s = series_of(1, 2, 3)
        assert s.view.to_list() == [1, 2, 3]


class TestLeftIdentity:
    """Tests for ↺ (left_identity) operator."""
    
    def test_left_identity_callable(self):
        thunk = left_identity(42)
        assert callable(thunk)
        assert thunk() == 42
    
    def test_left_identity_descriptor(self):
        class Container:
            value = 100
            # This would need the class to have left_identity as descriptor
    
    def test_unicode_alias(self):
        # ↺ is available as tspy.↺ via getattr
        import tspy
        thunk = getattr(tspy, '↺')(99)
        assert thunk() == 99


class TestLiterals:
    """Tests for composition literals."""
    
    def test_list_literal(self):
        result = _l[1, 2, 3]
        assert result == [1, 2, 3]
        assert isinstance(result, list)
    
    def test_array_literal(self):
        result = _a[1, 2, 3]
        assert result == [1, 2, 3]
        assert isinstance(result, list)
    
    def test_set_literal(self):
        result = _s[1, 2, 2, 3]
        assert result == {1, 2, 3}
        assert isinstance(result, set)
    
    def test_series_literal(self):
        result = s_[1, 2, 3]
        assert isinstance(result, Series)
        assert result.size == 3
        assert list(result.view) == [1, 2, 3]


class TestCursor:
    """Tests for Cursor algebra."""
    
    def test_cursor_from_columns(self):
        cursor = cursor_from_columns(
            ["name", "age"],
            series_of("Alice", "Bob", "Carol"),
            series_of(30, 25, 35)
        )
        assert cursor.size == 3
        
        row0 = cursor[0]
        assert row0.size == 2
        assert row0[0].a == "Alice"
        assert row0[0].b()["name"] == "name"
        assert row0[1].a == 30
        assert row0[1].b()["name"] == "age"
    
    def test_cursor_range(self):
        from tspy.cursor import cursor_range
        cursor = cursor_from_columns(
            ["a", "b"],
            series_of(1, 2, 3, 4),
            series_of(10, 20, 30, 40)
        )
        sliced = cursor_range(cursor, 1, 3)
        assert sliced.size == 2
        assert sliced[0][0].a == 2
        assert sliced[1][0].a == 3
    
    def test_cursor_join(self):
        from tspy.cursor import join_cursors
        c1 = cursor_from_columns(["a"], series_of(1, 2))
        c2 = cursor_from_columns(["b"], series_of(10, 20))
        joined = join_cursors(c1, c2)
        assert joined.size == 2
        assert joined[0].size == 2
        assert joined[0][0].a == 1
        assert joined[0][1].a == 10
    
    def test_cursor_combine(self):
        from tspy.cursor import combine_cursors
        c1 = cursor_from_columns(["x"], series_of(1, 2))
        c2 = cursor_from_columns(["x"], series_of(3, 4))
        combined = combine_cursors(c1, c2)
        assert combined.size == 4
        assert combined[0][0].a == 1
        assert combined[2][0].a == 3
    
    def test_cursor_view(self):
        cursor = cursor_from_columns(
            ["name", "age"],
            series_of("Alice", "Bob"),
            series_of(30, 25)
        )
        view = cursor.view
        assert len(view) == 2
        rows = list(view)
        assert rows[0][0].a == "Alice"


class TestInfixOperators:
    """Tests for various infix operator patterns."""
    
    def test_alpha_chaining(self):
        s = series_of(1, 2, 3, 4)
        # Chain projections: s @ f @ g
        result = s @ (lambda x: x + 1) @ (lambda x: x * 2)
        assert list(result.view) == [4, 6, 8, 10]
    
    def test_alpha_with_unicode(self):
        s = series_of(1, 2, 3)
        result = α(s, lambda x: x * 3)
        assert list(result.view) == [3, 6, 9]


if __name__ == "__main__":
    pytest.main([__file__, "-v"])