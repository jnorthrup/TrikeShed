# Guest Worlds Guide

Spawn and drive GraalPy guest worlds — isolated sub-VMs seeded from a local VFS.

> **Launch prerequisite:** the daemon must be running. See [guide-daemon-launch.md](guide-daemon-launch.md).

## What Is a Guest World

A guest world is a GraalPy sub-VM hosted inside the daemon's `HypervisorVmHost`. Each world gets its own VFS-seeded directory tree, isolated from the host filesystem. Worlds are spawned via the VM API and evaluated through the same Teleported sealed-class boundary that all VM values cross.

The daemon currently ships GraalPy (Python 3.12) and GraalJS facets. A Python world can run pure-Python code (pytest, scripts) without pip/venv — only VmSpec.world seeding + sys.path prepend.

## Spawn Request

```http
POST /api/vm/spawn
Content-Type: application/json

{
  "id": "my-world",
  "facet": "python",
  "world": ["/path/to/seed/dir"]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Alphanumeric ID (pattern: `[A-Za-z0-9._:-]{1,128}`) |
| `facet` | yes | `"python"`, `"js"`, `"java"`, `"ruby"`, `"clojure"`, `"llvm"` |
| `trust` | no | `"UNTRUSTED"` or `"OWN"` (default) |
| `statements` | no | Budget: max eval statements (not enforced, see below) |
| `wallMillis` | no | Budget: max wall-clock ms (not enforced, see below) |
| `world` | no | List of directory paths to seed into the VFS |

Response:
```json
{
  "id": "my-world",
  "facet": "python",
  "tier": "<tier-label>",
  "terminal": "/vm-terminal?id=my-world"
}
```

> **Status:** verified-live — spawn shape matches `VmWire.kt:107-124`.

## The Teleported Result Form

Every value crossing the VM boundary projects into the `Teleported` sealed class:

| Variant | Shape |
|---------|-------|
| `Null` | `null` |
| `Bool` | `{"$bool": true/false}` |
| `Num` | `{"$num": 123}` (no decimal point) |
| `Real` | `{"$real": 1.5}` |
| `Str` | `"hello"` |
| `Bytes` | `{"$bytes": "<base64>"}` |
| `Arr` | `[...]` |
| `Obj` | `{...}` (sorted keys) |
| `Opaque` | `{"$opaque": "<toString>"}` |

The canonical encoding (sorted object keys, no whitespace) determines the CID. Hash-before-array: if any element is opaque, the containing structure becomes Opaque.

> **Status:** verified-live — schema derived from `Teleported.kt:24-36,46-49`.

## Eval

```http
POST /api/vm/{id}/eval
Content-Type: application/json

{"source": "2 + 2", "name": "<eval>"}
```

Response:
```json
{
  "id": "my-world",
  "cid": "<sha256-hex>",
  "value": {"$num": 4}
}
```

> **Status:** verified-live — shape matches `VmWire.kt:127-143`.

## Worked Example: Seed a Dir, Run pytest

1. Create a seed directory:
```bash
mkdir -p /tmp/test-world
cat > /tmp/test_world/test_basic.py << 'EOF'
def test_one_plus_one():
    assert 1 + 1 == 2

def test_string():
    assert "hello".upper() == "HELLO"
EOF
cp /tmp/test_world/test_basic.py /tmp/test-world/
```

2. Spawn the world:
```bash
curl -s -X POST http://localhost:8888/api/vm/spawn \
  -H 'Content-Type: application/json' \
  -d '{"id":"pytest-world","facet":"python","world":["/tmp/test-world"]}'
```

3. Run pytest via eval:
```bash
curl -s -X POST http://localhost:8888/api/vm/pytest-world/eval \
  -H 'Content-Type: application/json' \
  -d '{"source":"import subprocess; subprocess.run([\"python\",\"-m\",\"pytest\",\"/tmp/test-world\",\"-v\"])"}'
```

> **Status:** unverified — the worked example reproduces the documented API shape, but live pytest execution within the GraalPy sandbox has not been traced by a validator.

## Known Limits

### Guest-Write Durability
Root cause found and patched 2026-08-29: the VFS committed a file's bytes to the store only when the guest's channel was closed, and GraalPy has no refcounted close — a writer the guest never explicitly closed silently lost everything that reached the channel (re-reads showed the old content). Every write/truncate now commits immediately (write(2) visibility), and `O_CREAT`/`O_TRUNC` take effect at open, not at close. One residue stands: bytes still sitting in Python's own io buffer that never reach the VFS cannot be persisted by any host-side fix — guest code should still `flush()` or close its writers (after the patch, a flush alone is durable).

> **Status:** unverified — patched with regression tests (`TrikeShedGraalVfsTest.channelWritesAreDurableWithoutClose`, `graalPythonFlushWithoutCloseReachesTheStore`); not yet re-traced by a validator.

### GraalPy StatementLimit
`ResourceLimits.statementLimit` is unsafe on GraalPy (the trip dies inside the GIL bookkeeping — `GraalBoundsSmokeTest`), so the VM host never installs it for Python. Since 2026-08-29 a `statements` budget on a Python world degrades to the wall watchdog (the facet's default 5000ms when `wallMillis` is unset): a runaway eval ends as a clean typed `INTERRUPTED` failure and the world survives. An engine-internal crash (the GIL-assert shape) now fails closed as a typed `DEAD` failure with the isolate downed, instead of leaving a poisoned context serving later evals. Statement-precise counting on GraalPy remains unimplemented; `wallMillis` is enforced by the watchdog at the isolate tier.

> **Status:** unverified — patched with regression tests (`InProcessIsolateTest.pythonStatementsBudgetDegradesToTheWallProxyInterrupt`, `failureTaxonomyFailsClosedOnEngineInternalErrors`); not yet re-traced by a validator.

### No Network Access
Guest worlds have no network access. The GraalPy sandbox does not expose outbound sockets.
