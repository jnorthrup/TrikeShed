# tspy — TrikeShed Python Algebra

Tuple-native implementation of the TrikeShed kernel algebra for CPython 3.10+.

## Core Algebra

```python
from tspy import Join, Series, j, s_, alpha

# Join = tuple of 2
Join[int, str]  # (1, "hello")

# Series = size + index function
Series(3, lambda i: i * 2)  # 0, 2, 4

# Literals
s_(1, 2, 3)              # Series[int]
_ = s_(*range(10)).alpha(lambda x: x * 2)  # Lazy projection

# Wire protocol (24-byte)
from tspy import FieldSynapse
synapse = FieldSynapse(
    phase=0, opcode=0xA5, method_idx=1, addr=42,
    seq=1, nano=1234567890, callsite_hash=999, template_idx=0
)
synapse.encode()  # 24-byte wire frame
```

## Cursor

```python
from tspy import ColumnMeta, IoInt, RowVec, Cursor, row_cell, cursor, select

meta = ColumnMeta("price", IoInt)
rv = RowVec((row_cell(100, meta), row_cell(200, meta)))
c = cursor(rv, rv)  # Series<RowVec>
select(c, 0, 1)     # Column projection
```

## Pointcut Emission (CPython peripheral)

```python
from tspy import PyenvEmitter, install_pointcut_hooks

emitter = PyenvEmitter()

# Hook specific classes
class MyClass:
    def __init__(self): self.value = 42

install_pointcut_hooks(emitter, MyClass)

obj = MyClass()
x = obj.value  # Emits L_GET BEFORE + AFTER
obj.value = 10  # Emits L_SET BEFORE + AFTER

records = emitter.get_records()
```

## Chronicle (Event Log)

```python
from tspy import CHRONICLE, TransitionSplat, emit

emit(TransitionSplat(
    element_key="dispatcher",
    from_state="ACTIVE",
    splat=None,
    actual_state="DRAINING",
    composition=("FanoutDispatcher", s_("sub1", "sub2"))
))

CHRONICLE.flush_to_json()  # Series[str]
```

## Installation

```bash
pip install -e /Users/jim/work/TrikeShed/libs/tspy
```

## Running Tests

```bash
cd /Users/jim/work/TrikeShed/libs/tspy
pytest tests/
```