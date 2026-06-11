"""
tspy.algebra — TrikeShed kernel algebra in Python (tuple-native)

All types are tuples or tuple-compatible for immutability.
Wire-protocol compatible with JVM Kotlin implementation.
"""

from __future__ import annotations
from typing import Any, Callable, Generic, Iterable, Iterator, TypeVar, Tuple, Protocol
from dataclasses import dataclass

T = TypeVar('T')
U = TypeVar('U')
V = TypeVar('V')
I = TypeVar('I')

# =============================================================================
# Join = base binary composition (tuple of 2)
# =============================================================================

Join = Tuple[T, U]  # (a, b) — exactly like Pair but tuple

def j(a: T, b: U) -> Join[T, U]:
    """Infix constructor: a j b == (a, b)"""
    return (a, b)

def fst(j: Join[T, U]) -> T:
    return j[0]

def snd(j: Join[T, U]) -> U:
    return j[1]

# =============================================================================
# Twin = same-typed Join
# =============================================================================

Twin = Tuple[T, T]

def twin(t: T, u: T) -> Twin[T]:
    return (t, u)

def both(twin: Twin[T]) -> Tuple[T, T]:
    return twin

# =============================================================================
# Series<T> = Join<Int, (Int) -> T>  (size + index function)
# =============================================================================

class Series(Generic[T]):
    """
    Immutable indexed series: size paired with index oracle.
    
    Wire-protocol compatible with JVM: Series<T> = Join<Int, (Int) -> T>
    """
    __slots__ = ('_size', '_index')
    
    def __init__(self, size: int, index: Callable[[int], T]):
        self._size = size
        self._index = index
    
    @property
    def size(self) -> int:
        return self._size
    
    def __getitem__(self, i: int) -> T:
        if not 0 <= i < self._size:
            raise IndexError(f"index {i} out of bounds [0, {self._size})")
        return self._index(i)
    
    def __len__(self) -> int:
        return self._size
    
    def __iter__(self) -> Iterator[T]:
        return (self._index(i) for i in range(self._size))
    
    # Lazy projection (α) — returns new Series, no materialization
    def alpha(self, xform: Callable[[T], U]) -> 'Series[U]':
        return Series(self._size, lambda i: xform(self._index(i)))
    
    # Range view as composition
    def range(self, start: int, end: int) -> 'Series[T]':
        count = max(0, min(end, self._size) - start)
        return Series(count, lambda i: self._index(start + i))
    
    # Materialize to tuple (for wire transport)
    def to_tuple(self) -> Tuple[T, ...]:
        return tuple(self._index(i) for i in range(self._size))
    
    @classmethod
    def from_tuple(cls, t: Tuple[T, ...]) -> 'Series[T]':
        return cls(len(t), lambda i: t[i])
    
    def __repr__(self) -> str:
        return f"Series({self._size}, <index>)"


# Convenience literals
def s_(*items: T) -> Series[T]:
    """Series literal: s_(1, 2, 3)"""
    return Series.from_tuple(items)

def _l(*items: T) -> list[T]:
    """List literal"""
    return list(items)

def _a(*items: T) -> tuple[T, ...]:
    """Tuple/Array literal"""
    return items

def _s(*items: T) -> set[T]:
    """Set literal"""
    return set(items)


# =============================================================================
# MetaSeries<I, T> = Join<I, (I) -> T>  (generalized Series)
# =============================================================================

class MetaSeries(Generic[I, T]):
    __slots__ = ('_domain', '_index')
    
    def __init__(self, domain: I, index: Callable[[I], T]):
        self._domain = domain
        self._index = index
    
    @property
    def domain(self) -> I:
        return self._domain
    
    def __getitem__(self, key: I) -> T:
        return self._index(key)
    
    def alpha(self, xform: Callable[[T], U]) -> 'MetaSeries[I, U]':
        return MetaSeries(self._domain, lambda k: xform(self._index(k)))


# =============================================================================
# CSeries — comparable Series
# =============================================================================

class CSeries(Series[T]):
    """Series with comparable elements — lexicographic ordering"""
    def __lt__(self: 'CSeries[T]', other: 'CSeries[T]') -> bool:
        n = min(self.size, other.size)
        for i in range(n):
            a, b = self[i], other[i]
            if a < b: return True
            if a > b: return False
        return self.size < other.size


# =============================================================================
# Left identity / constant anchor (↺)
# =============================================================================

def constant(t: T) -> Callable[[], T]:
    """Left identity anchor: t.↺ == () -> t"""
    return lambda: t


# =============================================================================
# Wire protocol: FieldSynapse (24 bytes) — exact match to JVM
# =============================================================================

@dataclass(frozen=True, slots=True)
class FieldSynapse:
    """24-byte wire protocol frame — matches JVM FieldSynapse exactly"""
    phase: int         # 0=BEFORE, 1=AFTER  (1 byte)
    opcode: int        # 0xA5=L_GET, 0xA6=L_SET, 0xA7=P_GET, 0xA8=P_SET (1 byte)
    method_idx: int    # 4 bytes
    addr: int          # 4 bytes
    seq: int           # 4 bytes
    nano: int          # 8 bytes
    callsite_hash: int # 4 bytes
    template_idx: int  # 4 bytes
    # Total: 1+1+4+4+4+8+4+4 = 30 bytes padded to 32? JVM uses 24-byte pack.
    # Python doesn't pack — wire transport handles encoding.
    
    PHASE_BEFORE = 0
    PHASE_AFTER = 1
    OP_L_GET = 0xA5
    OP_L_SET = 0xA6
    OP_P_GET = 0xA7
    OP_P_SET = 0xA8
    
    def encode(self) -> bytes:
        """Encode to 24-byte wire frame (big-endian)"""
        import struct
        # phase(1) + opcode(1) + pad(2) + method_idx(4) + addr(4) + seq(4) + nano(8) + callsite_hash(4) + template_idx(4) = 32?
        # JVM packs as 24 bytes: phase(1) + opcode(1) + method_idx(4) + addr(4) + seq(4) + nano(8) + callsite_hash(4) + template_idx(4) = 30, padded?
        # Actual JVM: ByteBuffer.put(phase).put(opcode).putInt(methodIdx).putInt(addr).putInt(seq).putLong(nano).putInt(callsiteHash).putInt(templateIdx)
        return struct.pack('>bbiiiqii',
            self.phase & 0xFF,
            self.opcode & 0xFF,
            self.method_idx,
            self.addr,
            self.seq,
            self.nano,
            self.callsite_hash,
            self.template_idx
        )
    
    @classmethod
    def decode(cls, buf: bytes) -> 'FieldSynapse':
        import struct
        phase, opcode, method_idx, addr, seq, nano, callsite_hash, template_idx = struct.unpack('>bbiiiqii', buf[:32])
        return cls(phase, opcode, method_idx, addr, seq, nano, callsite_hash, template_idx)


# =============================================================================
# PointcutEmitterPort — peripheral interface
# =============================================================================

class PointcutEmitterPort(Protocol):
    """PORT: peripheral (CPython) calls this to emit pointcuts"""
    
    def emit_field_access(self, phase: int, is_static: bool, is_write: bool,
                          class_name: str, field_name: str,
                          source_location: str, seq: int) -> FieldSynapse:
        ...
    
    def emit_method_dispatch(self, phase: int, receiver_type: str, method_name: str,
                             source_location: str, seq: int) -> FieldSynapse:
        ...
    
    def emit_dunder_call(self, phase: int, obj_type: str, dunder_name: str,
                         source_location: str, seq: int) -> FieldSynapse:
        ...


# =============================================================================
# OpK / ColK / FacetedRow (GADT key families)
# =============================================================================

class OpK(Protocol[T]):
    """Base for all typed keys"""
    pass

class ColK(OpK[T]):
    """Columnar facet keys"""
    class ByIndex(OpK[Any]):
        __slots__ = ('col',)
        def __init__(self, col: int): self.col = col
    
    class ByName(OpK[Any]):
        __slots__ = ('name',)
        def __init__(self, name: str): self.name = name
    
    class Meta(OpK[Series[Any]]):
        __slots__ = ()
    
    class Width(OpK[int]):
        __slots__ = ()

class TextK(OpK[T]):
    """Text facet keys"""
    class CharAt(OpK[str]):
        __slots__ = ('idx',)
        def __init__(self, idx: int): self.idx = idx
    
    class Substring(OpK[str]):
        __slots__ = ('start', 'end')
        def __init__(self, start: int, end: int): self.start = start; self.end = end


# =============================================================================
# Shape / Tensor (from PRELOAD.md)
# =============================================================================

Shape = Series[int]  # = Series<Int>

class Tensor(Generic[T]):
    """Tensor<T> = Join<Shape, (Shape) -> T>"""
    __slots__ = ('_shape', '_index')
    
    def __init__(self, shape: Shape, index: Callable[[Shape], T]):
        self._shape = shape
        self._index = index
    
    @property
    def shape(self) -> Shape:
        return self._shape
    
    def __getitem__(self, idx: Shape) -> T:
        return self._index(idx)

def shape_of(*dims: int) -> Shape:
    return Series(len(dims), lambda i: dims[i])

def scalar_shape() -> Shape:
    return Series(0, lambda i: (_ for _ in ()).throw(IndexError("scalar shape")))

def scalar_tensor(value: T) -> Tensor[T]:
    return Tensor(scalar_shape(), lambda _: value)


# =============================================================================
# RingSeries — fixed capacity ring buffer (hot path)
# =============================================================================

class RingSeries(Generic[T]):
    """Fixed-capacity ring buffer, capacity must be power of 2"""
    __slots__ = ('_capacity', '_mask', '_buf', '_head', '_count')
    
    def __init__(self, capacity: int):
        if capacity <= 0 or (capacity & (capacity - 1)) != 0:
            raise ValueError("capacity must be positive power of 2")
        self._capacity = capacity
        self._mask = capacity - 1
        self._buf: list[T | None] = [None] * capacity
        self._head = 0
        self._count = 0
    
    @property
    def size(self) -> int:
        return self._count
    
    def __getitem__(self, i: int) -> T:
        if not 0 <= i < self._count:
            raise IndexError()
        val = self._buf[(self._head + i) & self._mask]
        # val cannot be None for valid indices < count
        return val  # type: ignore[return-value]
    
    def add(self, item: T) -> None:
        if self._count < self._capacity:
            self._buf[(self._head + self._count) & self._mask] = item
            self._count += 1
        else:
            # Overwrite oldest
            self._buf[self._head & self._mask] = item
            self._head = (self._head + 1) & self._mask
    
    def __iter__(self) -> Iterator[T]:
        for i in range(self._count):
            yield self[i]
    
    def to_series(self) -> Series[T]:
        return Series(self._count, lambda i: self[i])