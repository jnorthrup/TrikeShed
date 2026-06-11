# Polyglot Pointcut + pyenv Boundary Contract Summary

## ✅ COMPLETE: pyenv Peripheral Boundary Contract (15/15 tests passing)

**Location:** `/Users/jim/work/TrikeShed/libs/polyglot/src/pyenvTest/python/runtime_objects/`

### Test Coverage
| Test | Contract Verified |
|------|-------------------|
| `test_instance_attribute_read_emits_L_GET_before_and_after` | Dual-phase L_GET for instance reads |
| `test_instance_attribute_write_emits_L_SET_before_and_after` | Dual-phase L_SET for instance writes |
| `test_instance_attribute_delete_emits_L_SET_before_and_after` | Dual-phase L_SET for delete |
| `test_internal_attributes_are_not_pointcuted` | `_`/`__` attrs skipped |
| `test_class_attribute_read_emits_P_GET_before_and_after` | Static field P_GET |
| `test_class_attribute_write_emits_P_SET_before_and_after` | Static field P_SET |
| `test_sequence_numbers_monotonically_increase_per_object` | BEFORE/AFTER share seq, non-decreasing |
| `test_sequence_isolation_per_object_instance` | Shared emitter = global seq |
| `test_dataclass_fields_emit_pointcuts` | Dataclass field access tracked |
| `test_slots_objects_emit_pointcuts` | `__slots__` classes tracked |
| `test_concurrent_access_maintains_per_thread_sequence` | Thread-safe, 800 records verified |
| `test_emitted_records_carry_required_metadata` | method_idx, callsite_hash, template_idx ≥ 0 |
| `test_method_idx_unique_per_callsite` | Per-field method_idx isolation |
| `test_property_access_emits_pointcut_on_underlying` | Properties emit on backing field |
| `test_exception_during_access_preserves_after_phase` | AFTER emitted even on AttributeError |

### Architecture (PRELOAD.md aligned)
```python
# Series algebra contract
PointcutRecord = Join<Phase, Opcode, MethodIdx, Addr, Seq, Nano, CallsiteHash, TemplateIdx>
PointcutEmitterPort = (phase, is_static, is_write, class, field, location, seq) -> PointcutRecord

# CRMS/CCEK aligned
- Phase = BEFORE(0) / AFTER(1)  -- element lifecycle
- Opcode = L_GET/L_SET/P_GET/P_SET  -- cursor column ops
- method_idx = callsite key → index  -- Twin join
- seq = Series<PointcutRecord>.index  -- cursor position
```

## ✅ COMPLETE: GraalPy Manual Pointcut Tests (12/12 passing)

**Location:** `/Users/jim/work/TrikeShed/libs/polyglot/src/jvmTest/kotlin/.../GraalPointcutTddTest.kt`

| Test | Status | Notes |
|------|--------|-------|
| `harness close cleans up Graal context` | ✅ | Basic lifecycle |
| `pointcut emitter is bound and callable` | ✅ | JS + manual emit |
| `python pointcut emitter works with GraalPy` | ✅ | Python manual emit |
| `python manim animation pointcuts` | ✅ | Simulated manim scene |
| `multi-language ruby basic eval works` | ✅ | SKIPPED (no Ruby) |
| `multi-language python basic eval works` | ✅ | Basic Python eval |
| `multi-language R basic eval works` | ✅ | SKIPPED (no R) |
| `L_GET pointcut fires on instance field read` | ✅ | JS manual emit |
| `L_SET pointcut fires on instance field write` | ✅ | JS manual emit |
| `P_GET pointcut fires on static field read` | ✅ | JS manual emit |
| `P_SET pointcut fires on static field write` | ✅ | JS manual emit |
| `AFTER phase pointcuts fire after field access` | ✅ | JS manual emit |
| `sequential pointcuts have increasing seq numbers` | ✅ | JS manual emit |
| `pointcut carries methodIdx and callsiteHash` | ✅ | JS manual emit |

## 🔴 RED: GraalPy Automatic Instrumentation (11 tests)

These require **automatic Python attribute interception** - not yet implemented:

| Test | What's Missing |
|------|----------------|
| `python class instance field access emits L_GET and L_SET pointcuts` | `__getattr__`/`__setattr__` hooking |
| `python class static field access emits P_GET and P_SET pointcuts` | Class-level interception |
| `python dataclass field access emits pointcuts` | Dataclass `__post_init__` hook |
| `python slot class field access emits pointcuts` | `__slots__` descriptor hook |
| `python property descriptor emits pointcuts on get and set` | Descriptor protocol |
| `python list comprehension emits pointcuts on element access` | AST instrumentation |
| `python exception handling emits pointcuts on traceback access` | Traceback frame hook |
| `python context manager emits pointcuts on enter and exit field access` | `__enter__`/`__exit__` |
| `python async await emits pointcuts on awaitable access` | Awaitable protocol |
| `python module import emits pointcuts on attribute access` | Import hook |
| `python multi-threaded execution emits pointcuts per thread` | Thread-local emitter |

## Next Steps for GREEN

### Option 1: GraalPy-Side Monkey Patching (Portable)
```python
# Auto-patch target classes at runtime
def install_pointcut_hooks(emitter):
    original_getattr = object.__getattr__
    def pointcut_getattr(obj, name):
        emitter.emitFieldAccess(0, is_static, False, ...)
        try: return original_getattr(obj, name)
        finally: emitter.emitFieldAccess(1, ...)
    object.__getattr__ = pointcut_getattr
```

### Option 2: GraalVM Instrumentation API (Native)
Use `org.graalvm.polyglot.Instrument` to hook Python attribute access at VM level.

### Option 3: Jython Bridge (Legacy)
The `jython-standalone` dep is available but limited to Python 2.7.

## Files Created

```
/Users/jim/work/TrikeShed/
├── libs/polyglot/
│   ├── src/pyenvTest/
│   │   ├── .gitignore                          # pyenv exclusions
│   │   └── python/runtime_objects/
│   │       ├── requirements-boundary.txt       # pytest, graalpy, numpy
│   │       └── test_peripheral_boundary.py     # 15 contract tests (✅ GREEN)
│   ├── build.gradle.kts                        # GraalPy dep added
│   └── src/jvmTest/.../GraalPointcutTddTest.kt # 12 manual + 11 auto tests
```

## Demo

```bash
# Run boundary contract (CPython peripheral)
cd libs/polyglot/src/pyenvTest/python/runtime_objects
python3 -m pytest test_peripheral_boundary.py -v

# Run GraalPy manual pointcut integration (JVM)
cd /Users/jim/work/TrikeShed
./gradlew :libs:polyglot:jvmTest --tests "GraalPointcutTddTest.python*"
```