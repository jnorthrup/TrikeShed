"""
tspy.chronicle — Event chronicle (event log + reification)

Pure Python implementation of TrikeShed Chronicle:
- CircularQueue for hot-path event buffering
- Multiple reification flavors (compact binary, columnar, classfile)
- CCEK fanout integration
"""

from __future__ import annotations
from typing import Any, Callable, ClassVar, Generic, Iterator, TypeVar
from dataclasses import dataclass, field
from collections.abc import Sequence

from ..algebra import Series, RingSeries, FieldSynapse
from ..cursor import Cursor

T = TypeVar('T')

# Python 3.9 compat: slots=True only in 3.10+
import sys
if sys.version_info >= (3, 10):
    _DC_SLOTS = {'frozen': True, 'slots': True}
else:
    _DC_SLOTS = {'frozen': True}


# =============================================================================
# CircularQueue — fixed capacity ring buffer (same as RingSeries but generic)
# =============================================================================

class CircularQueue(Generic[T]):
    """Fixed-capacity ring buffer for hot-path event collection"""
    
    __slots__ = ('_capacity', '_mask', '_buf', '_head', '_count')
    
    def __init__(self, capacity: int):
        if capacity <= 0 or (capacity & (capacity - 1)) != 0:
            raise ValueError("capacity must be positive power of 2")
        self._capacity = capacity
        self._mask = capacity - 1
        self._buf = [None] * capacity
        self._head = 0
        self._count = 0
    
    @property
    def size(self) -> int:
        return self._count
    
    def __len__(self) -> int:
        return self._count
    
    def __getitem__(self, i: int) -> T:
        if not 0 <= i < self._count:
            raise IndexError()
        val = self._buf[(self._head + i) & self._mask]
        return val  # type: ignore[return-value]
    
    def __iter__(self) -> Iterator[T]:
        for i in range(self._count):
            yield self[i]
    
    def enqueue(self, item: T) -> None:
        if self._count < self._capacity:
            self._buf[(self._head + self._count) & self._mask] = item
            self._count += 1
        else:
            # Overwrite oldest
            self._buf[self._head & self._mask] = item
            self._head = (self._head + 1) & self._mask
    
    def to_series(self) -> Series[T]:
        return Series(self._count, lambda i: self[i])
    
    def clear(self) -> None:
        for i in range(self._count):
            self._buf[(self._head + i) & self._mask] = None
        self._head = 0
        self._count = 0


# =============================================================================
# ChronicleEvent — sealed hierarchy using type discriminant
# =============================================================================

class ChronicleEvent:
    """Base chronicle event — includes type discriminant for reification"""
    # event_type derived from class name
    
    def to_json(self) -> str:
        raise NotImplementedError
    
    def to_compact_binary(self) -> bytes:
        """Reification flavor 1: compact binary (arena-backed, cache-friendly)"""
        raise NotImplementedError
    
    def to_columnar(self) -> dict[str, Any]:
        """Reification flavor 2: columnar (query-friendly, indexed)"""
        raise NotImplementedError
    
    def to_classfile(self) -> dict[str, Any]:
        """Reification flavor 3: classfile (metadata-rich, provenance)"""
        raise NotImplementedError


@dataclass(**_DC_SLOTS)
class TransitionSplat(ChronicleEvent):
    """CCEK element state transition with splat prediction"""
    event_type: ClassVar[str] = 'TransitionSplat'
    element_key: str
    from_state: str
    splat: Any | None  # Splat<ElementState>
    actual_state: str
    composition: tuple[str, Series[str]]  # Join<String, Series<String>>
    
    def to_json(self) -> str:
        import json
        return json.dumps({
            'type': self.event_type,
            'element_key': self.element_key,
            'from': self.from_state,
            'splat': str(self.splat) if self.splat else None,
            'actual': self.actual_state,
            'composition': {'key': self.composition[0], 'values': list(self.composition[1])}
        }, separators=(',', ':'))
    
    def to_compact_binary(self) -> bytes:
        # Compact: element_key_len + element_key + from_state_len + from_state + ...
        # Simplified for Python — real impl uses arena-backed allocation
        return self.to_json().encode('utf-8')
    
    def to_columnar(self) -> dict[str, Any]:
        return {
            'type': self.event_type,
            'element_key': self.element_key,
            'from_state': self.from_state,
            'actual_state': self.actual_state,
            'composition_key': self.composition[0],
            'composition_values': list(self.composition[1]),
        }
    
    def to_classfile(self) -> dict[str, Any]:
        return {
            'event_type': self.event_type,
            'element_key': self.element_key,
            'from': self.from_state,
            'actual': self.actual_state,
            'splat': str(self.splat) if self.splat else None,
            'composition': self.composition,
        }


@dataclass(**_DC_SLOTS)
class FanoutSplat(ChronicleEvent):
    """CCEK fanout dispatcher event with per-subscriber splats"""
    event_type: ClassVar[str] = 'FanoutSplat'
    dispatcher_id: int
    event_type_name: str
    splats: Series[tuple[str, Any]]  # Series<Join<String, Splat<DeliveryOutcome>>>
    actuals: Series[tuple[str, Any]]  # Series<Join<String, DeliveryOutcome>>
    subscriber_count: int
    
    def to_json(self) -> str:
        import json
        return json.dumps({
            'type': self.event_type,
            'dispatcher_id': self.dispatcher_id,
            'event_type': self.event_type_name,
            'splats': [{k: str(v) for k, v in self.splats}],
            'actuals': [{k: str(v) for k, v in self.actuals}],
            'subscriber_count': self.subscriber_count,
        }, separators=(',', ':'))
    
    def to_compact_binary(self) -> bytes:
        return self.to_json().encode('utf-8')
    
    def to_columnar(self) -> dict[str, Any]:
        return {
            'type': self.event_type,
            'dispatcher_id': self.dispatcher_id,
            'event_type': self.event_type_name,
            'subscriber_count': self.subscriber_count,
        }
    
    def to_classfile(self) -> dict[str, Any]:
        return {
            'event_type': self.event_type,
            'dispatcher_id': self.dispatcher_id,
            'event_type_name': self.event_type_name,
            'subscriber_count': self.subscriber_count,
        }


# =============================================================================
# Chronicle — global event buffer with CCEK fanout
# =============================================================================

class Chronicle:
    """
    Global chronicle buffer + CCEK fanout.
    
    Mirrors JVM `org.xvm.cursor.Chronicle` with Python conventions.
    """
    
    def __init__(self, capacity: int = 1_048_576):  # 2^20
        self._buffer = CircularQueue[ChronicleEvent](capacity)
        self._subscribers: list[Callable[[ChronicleEvent], None]] = []
    
    def emit(self, event: ChronicleEvent) -> None:
        """Emit event to buffer and fan out to subscribers"""
        self._buffer.enqueue(event)
        for sub in self._subscribers:
            try:
                sub(event)
            except Exception:
                pass  # Subscriber errors are isolated
    
    def subscribe(self, listener: Callable[[ChronicleEvent], None]) -> None:
        """Subscribe to all events (CCEK fanout)"""
        self._subscribers.append(listener)
    
    def unsubscribe(self, listener: Callable[[ChronicleEvent], None]) -> bool:
        try:
            self._subscribers.remove(listener)
            return True
        except ValueError:
            return False
    
    def flush_to_series(self) -> Series[dict[str, Any]]:
        """Reify buffer as columnar series"""
        return self._buffer.to_series().alpha(lambda e: e.to_columnar())
    
    def flush_to_json(self) -> Series[str]:
        return self._buffer.to_series().alpha(lambda e: e.to_json())
    
    def flush_to_compact(self) -> Series[bytes]:
        return self._buffer.to_series().alpha(lambda e: e.to_compact_binary())
    
    def clear(self) -> None:
        self._buffer.clear()
    
    @property
    def size(self) -> int:
        return self._buffer.size


# Global chronicle instance
CHRONICLE = Chronicle()


def emit(event: ChronicleEvent) -> None:
    """Convenience: emit to global chronicle"""
    CHRONICLE.emit(event)


# =============================================================================
# Splat → Chronology conversion (for Compact Binary flavor)
# =============================================================================

def to_chronology(splat: Any) -> str:
    """Convert Splat to chronology string representation"""
    if splat is None:
        return "∅"
    # Real impl: access splat.position, splat.covariance, splat.opacity, splat.attributes
    return f"Splat(id={getattr(splat, 'id', '?')}, pos={getattr(splat, 'position', '?')}, cov={getattr(splat, 'covariance', '?')}, α={getattr(splat, 'opacity', '?')})"


# =============================================================================
# DeliveryOutcome enum (matches JVM)
# =============================================================================

class DeliveryOutcome:
    DELIVERED = 'DELIVERED'
    BACKPRESSURE = 'BACKPRESSURE'
    DEFERRED = 'DEFERRED'
    DROPPED = 'DROPPED'
    ERROR = 'ERROR'