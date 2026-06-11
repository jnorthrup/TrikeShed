"""
tspy.polyglot — Python peripheral pointcut emission (CPython runtime)

Provides PointcutEmitterPort implementation for CPython (pyenv) runtime.
Hooks __getattr__/__setattr__/__delattr__ to emit FieldSynapse events.
"""

from __future__ import annotations
from typing import Any, Callable, Dict, Set
from dataclasses import dataclass, field
import threading
import time
import sys

from ..algebra import FieldSynapse, PointcutEmitterPort
from ..cursor import ColumnMeta, IoString


# =============================================================================
# CPython Pointcut Emitter — monkey-patches object.__getattr__ etc.
# =============================================================================

# Global sequence counter (thread-local for isolation)
_sequence_local = threading.local()

def _next_seq() -> int:
    if not hasattr(_sequence_local, 'seq'):
        _sequence_local.seq = 0
    _sequence_local.seq += 1
    return _sequence_local.seq


@dataclass(slots=True)
class _PointcutState:
    """Per-object pointcut tracking state"""
    emitter: PointcutEmitterPort
    tracked_types: Set[type] = field(default_factory=set)
    original_getattr: Callable | None = None
    original_setattr: Callable | None = None
    original_delattr: Callable | None = None


# Registry of active pointcut installations
_installations: Dict[str, _PointcutState] = {}


def install_pointcut_hooks(
    emitter: PointcutEmitterPort,
    *types: type,
    track_all: bool = False
) -> str:
    """
    Install pointcut hooks on given types (or all __dict__ objects if track_all).
    Returns installation ID for later removal.
    """
    install_id = f"pointcut_{len(_installations)}"
    
    # Monkey-patch builtin object if track_all
    if track_all:
        state = _PointcutState(emitter=emitter, tracked_types=set())
        state.original_getattr = object.__getattribute__
        state.original_setattr = object.__setattr__
        state.original_delattr = object.__delattr__
        
        def patched_getattr(obj, name):
            if name.startswith('_') or name in ('__class__', '__dict__', '__slots__'):
                return state.original_getattr(obj, name)  # type: ignore[misc]
            state.emitter.emit_field_access(
                phase=FieldSynapse.PHASE_BEFORE,
                is_static=False,
                is_write=False,
                class_name=type(obj).__name__,
                field_name=name,
                source_location=f"{obj.__class__.__module__}:{getattr(obj, '__source_location__', '?')}",
                seq=_next_seq()
            )
            try:
                return state.original_getattr(obj, name)  # type: ignore[misc]
            finally:
                state.emitter.emit_field_access(
                    phase=FieldSynapse.PHASE_AFTER,
                    is_static=False,
                    is_write=False,
                    class_name=type(obj).__name__,
                    field_name=name,
                    source_location=f"{obj.__class__.__module__}:{getattr(obj, '__source_location__', '?')}",
                    seq=_next_seq()
                )
        
        def patched_setattr(obj, name, value):
            if name.startswith('__'):
                return state.original_setattr(obj, name, value)  # type: ignore[misc]
            state.emitter.emit_field_access(
                phase=FieldSynapse.PHASE_BEFORE,
                is_static=False,
                is_write=True,
                class_name=type(obj).__name__,
                field_name=name,
                source_location=f"{obj.__class__.__module__}:{getattr(obj, '__source_location__', '?')}",
                seq=_next_seq()
            )
            try:
                return state.original_setattr(obj, name, value)  # type: ignore[misc]
            finally:
                state.emitter.emit_field_access(
                    phase=FieldSynapse.PHASE_AFTER,
                    is_static=False,
                    is_write=True,
                    class_name=type(obj).__name__,
                    field_name=name,
                    source_location=f"{obj.__class__.__module__}:{getattr(obj, '__source_location__', '?')}",
                    seq=_next_seq()
                )
        
        # NOTE: Can't easily patch object.__getattr__/__setattr__ globally in CPython
        # This is a demonstration — real impl uses sys.settrace or C extension
        _installations[install_id] = state
        return install_id
    
    # Track specific types
    state = _PointcutState(emitter=emitter, tracked_types=set(types))
    
    for cls in types:
        if cls in _installations:
            continue
        
        orig_getattr = cls.__getattribute__ if '__getattribute__' in cls.__dict__ else object.__getattribute__
        orig_setattr = cls.__setattr__ if '__setattr__' in cls.__dict__ else object.__setattr__
        orig_delattr = cls.__delattr__ if '__delattr__' in cls.__dict__ else object.__delattr__
        
        def make_getattr(orig, class_name):
            def getattr_hook(obj, name):
                if name.startswith('_') and not name.endswith('_'):
                    return orig(obj, name)
                seq = _next_seq()
                state.emitter.emit_field_access(
                    phase=FieldSynapse.PHASE_BEFORE, is_static=False, is_write=False,
                    class_name=class_name, field_name=name,
                    source_location=f"{obj.__class__.__module__}:?",
                    seq=seq
                )
                try:
                    return orig(obj, name)
                finally:
                    state.emitter.emit_field_access(
                        phase=FieldSynapse.PHASE_AFTER, is_static=False, is_write=False,
                        class_name=class_name, field_name=name,
                        source_location=f"{obj.__class__.__module__}:?",
                        seq=seq
                    )
            return getattr_hook
        
        def make_setattr(orig, class_name):
            def setattr_hook(obj, name, value):
                if name.startswith('__') and not name.endswith('_'):
                    return orig(obj, name, value)
                seq = _next_seq()
                state.emitter.emit_field_access(
                    phase=FieldSynapse.PHASE_BEFORE, is_static=False, is_write=True,
                    class_name=class_name, field_name=name,
                    source_location=f"{obj.__class__.__module__}:?",
                    seq=seq
                )
                try:
                    return orig(obj, name, value)
                finally:
                    state.emitter.emit_field_access(
                        phase=FieldSynapse.PHASE_AFTER, is_static=False, is_write=True,
                        class_name=class_name, field_name=name,
                        source_location=f"{obj.__class__.__module__}:?",
                        seq=seq
                    )
            return setattr_hook
        
        cls.__getattribute__ = make_getattr(orig_getattr, cls.__name__)  # type: ignore[assignment]
        cls.__setattr__ = make_setattr(orig_setattr, cls.__name__)  # type: ignore[assignment]
        state.tracked_types.add(cls)
    
    _installations[install_id] = state
    return install_id


def uninstall_pointcut_hooks(install_id: str) -> bool:
    """Remove pointcut hooks by installation ID"""
    if install_id not in _installations:
        return False
    
    state = _installations[install_id]
    
    # Restore original methods for tracked types
    for cls in state.tracked_types:
        if state.original_getattr:
            cls.__getattribute__ = state.original_getattr
        if state.original_setattr:
            cls.__setattr__ = state.original_setattr
        if state.original_delattr:
            cls.__delattr__ = state.original_delattr
    
    del _installations[install_id]
    return True


# =============================================================================
# Pyenv Boundary Contract — test peripheral emission
# =============================================================================

class PyenvEmitter(PointcutEmitterPort):
    """
    Pyenv peripheral emitter — captures FieldSynapse records for boundary contract tests.
    Matches test_peripheral_boundary.py expectations exactly.
    """
    
    def __init__(self):
        self._records: list[FieldSynapse] = []
        self._lock = threading.Lock()
    
    def emit_field_access(self, phase: int, is_static: bool, is_write: bool,
                          class_name: str, field_name: str,
                          source_location: str, seq: int) -> FieldSynapse:
        opcode = (FieldSynapse.OP_P_SET if is_static and is_write else
                  FieldSynapse.OP_P_GET if is_static and not is_write else
                  FieldSynapse.OP_L_SET if not is_static and is_write else
                  FieldSynapse.OP_L_GET)
        
        record = FieldSynapse(
            phase=phase,
            opcode=opcode,
            method_idx=hash((class_name, field_name)) & 0x7FFFFFFF,
            addr=hash(source_location) & 0x7FFFFFFF,
            seq=seq,
            nano=time.time_ns(),
            callsite_hash=hash(f"{class_name}.{field_name}{' static' if is_static else ''}{' write' if is_write else ' read'}") & 0x7FFFFFFF,
            template_idx=hash((class_name, field_name)) & 0x7FFFFFFF
        )
        
        with self._lock:
            self._records.append(record)
        
        return record
    
    def emit_method_dispatch(self, phase: int, receiver_type: str, method_name: str,
                             source_location: str, seq: int) -> FieldSynapse:
        record = FieldSynapse(
            phase=phase,
            opcode=FieldSynapse.OP_L_GET,  # Method dispatch treated as L_GET
            method_idx=hash(method_name) & 0x7FFFFFFF,
            addr=hash(source_location) & 0x7FFFFFFF,
            seq=seq,
            nano=time.time_ns(),
            callsite_hash=hash(f"{receiver_type}.{method_name}") & 0x7FFFFFFF,
            template_idx=hash(method_name) & 0x7FFFFFFF
        )
        with self._lock:
            self._records.append(record)
        return record
    
    def emit_dunder_call(self, phase: int, obj_type: str, dunder_name: str,
                         source_location: str, seq: int) -> FieldSynapse:
        record = FieldSynapse(
            phase=phase,
            opcode=FieldSynapse.OP_L_GET,
            method_idx=hash(dunder_name) & 0x7FFFFFFF,
            addr=hash(source_location) & 0x7FFFFFFF,
            seq=seq,
            nano=time.time_ns(),
            callsite_hash=hash(f"{obj_type}.{dunder_name}") & 0x7FFFFFFF,
            template_idx=hash(dunder_name) & 0x7FFFFFFF
        )
        with self._lock:
            self._records.append(record)
        return record
    
    def get_records(self) -> tuple[FieldSynapse, ...]:
        with self._lock:
            return tuple(self._records)
    
    def clear(self) -> None:
        with self._lock:
            self._records.clear()


# =============================================================================
# GraalPy Integration — manual emitter binding
# =============================================================================

class GraalPyEmitter:
    """
    GraalPy manual emitter — for use when GraalPy provides pointcutEmitter polyglot binding.
    """
    
    def __init__(self, polyglot_emitter: Any):
        """polyglot_emitter = GraalPy's pointcutEmitter object"""
        self._emitter = polyglot_emitter
    
    def emit_field_access(self, phase: int, is_static: bool, is_write: bool,
                          class_name: str, field_name: str,
                          source_location: str, seq: int) -> None:
        self._emitter.emitFieldAccess(phase, is_static, is_write,
                                       class_name, field_name, source_location, seq)
    
    def emit_method_dispatch(self, phase: int, receiver_type: str, method_name: str,
                             source_location: str, seq: int) -> None:
        # GraalPy emitter doesn't have dedicated method dispatch — use field access
        self.emit_field_access(phase, False, False,
                               receiver_type, method_name, source_location, seq)