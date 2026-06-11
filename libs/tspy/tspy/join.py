"""
Join algebra — base binary composition.

Join<A, B> = pair of (a: A, b: B) with component access and pair conversion.
Twin<T> = Join<T, T> — same-typed join.
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Generic, TypeVar, Any, Callable

A = TypeVar("A")
B = TypeVar("B")
T = TypeVar("T")


@dataclass(frozen=True, repr=False)
class Join(Generic[A, B]):
    """Base binary composition: a pair of (a: A, b: B)."""
    a: A
    b: B

    def __repr__(self) -> str:
        return f"Join({self.a!r}, {self.b!r})"

    # Component access (like Kotlin's component1/component2)
    def component1(self) -> A:
        return self.a

    def component2(self) -> B:
        return self.b

    # Pythonic unpacking
    def __iter__(self):
        yield self.a
        yield self.b

    # Pair conversion
    @property
    def pair(self) -> tuple[A, B]:
        return (self.a, self.b)

    # Equality is structural (dataclass default)


# Type alias for same-typed join
Twin = Join[T, T]


def twin(value: T) -> Twin[T]:
    """Create a Twin (Join<T, T>) from a single value."""
    return Join(value, value)


def j(a: A, b: B) -> Join[A, B]:
    """Infix Join constructor. Mirrors Kotlin `A.j(b)`."""
    return Join(a, b)


# Enable `a | j | b` syntax via a sentinel object
class _InfixJ:
    def __or__(self, other: tuple[A, B]) -> Join[A, B]:
        if isinstance(other, tuple) and len(other) == 2:
            return Join(other[0], other[1])
        return NotImplemented

    def __ror__(self, other: tuple[A, B]) -> Join[A, B]:
        if isinstance(other, tuple) and len(other) == 2:
            return Join(other[0], other[1])
        return NotImplemented


# The infix sentinel - exported as j for infix syntax
_j_infix = _InfixJ()


# Allow Join to be used in isinstance checks with structural matching
Join.__match_args__ = ("a", "b")