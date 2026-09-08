# Metered Agents / VM Substrate Guide

Spawn, drive, and revoke GraalVM guest VMs with a metered sheet view.

> **Launch prerequisite:** the daemon must be running. See [guide-daemon-launch.md](guide-daemon-launch.md).

## The VM Sheet

```http
GET /api/vm
```

Returns a sheet (dataframe) of all live VMs. Columns (from `VM_COLUMNS`):

| Column | Type | Description |
|--------|------|-------------|
| `id` | String | VM identifier |
| `facet` | String | Language runtime (`java`, `js`, `python`, `ruby`, `clojure`, `llvm`) |
| `trust` | String | `OWN` or `UNTRUSTED` |
| `tier` | String | Resource tier |
| `phase` | String | Current lifecycle phase |
| `statements` | Long | Budget: max eval statements |
| `wallMs` | Long | Budget: max wall-clock ms |
| `calls` | Long | Actual eval calls so far |
| `heat` | Long | Aggregate compute units |
| `receipts` | Long | Receipt count |

> **Status:** verified-live — column shape matches `VM_COLUMNS` at `src/commonMain/kotlin/borg/trikeshed/vm/VmRow.kt:10-21`.

## Spawn

```http
POST /api/vm/spawn
Content-Type: application/json

{
  "id": "agent-1",
  "facet": "js",
  "trust": "OWN",
  "statements": 1000,
  "wallMillis": 30000
}
```

Response:
```json
{
  "id": "agent-1",
  "facet": "js",
  "tier": "<tier-label>",
  "terminal": "/vm-terminal?id=agent-1"
}
```

> **Status:** verified-live — shape matches `VmWire.kt:107-124`.

## Eval

```http
POST /api/vm/{id}/eval
Content-Type: application/json

{"source": "1 + 1", "name": "<eval>"}
```

Response:
```json
{
  "id": "agent-1",
  "cid": "<sha256-hex>",
  "value": {"$num": 2}
}
```

> **Status:** verified-live — shape matches `VmWire.kt:127-143`.

## Revoke

```http
POST /api/vm/{id}/revoke
Content-Type: application/json

{"reason": "done"}
```

Response:
```json
{"ok": true, "id": "agent-1"}
```

> **Status:** verified-live — shape matches `VmWire.kt:145-151`.

## Events SSE

```http
GET /api/vm/events
```

Server-Sent Events stream. Each event is:
```
data: {"kind":"spawn","id":"agent-1","facet":"js",...}
```

Event kinds: `spawn`, `eval`, `revoke`, `land`. The stream replays the host's recent buffer first, then streams live events.

> **Status:** verified-live — SSE wiring matches `VmWire.kt:153-164`.

## Terminal Page

Open `/vm-terminal?id=<vmId>` in a browser. The VT220 web terminal provides one tab per VM/process.

Terminal input modes (from `VmWire.kt:171-187`):
- **`eval`** (default): text is evaluated as an expression in the VM. The result is captured and shown.
- **`stdin`**: text is pushed as raw stdin to the VM process. Use `{text, mode: "stdin"}` in the POST body.

> **Status:** unverified — terminal input mode (eval vs stdin) is documented from source, not traced by a validator.

## The Metering Story (Honest)

Budget fields (`statements`, `wallMillis`) are **declared and carried** but **not yet enforced** by the VM host. A VM with `statements: 1000` can execute unlimited eval calls. Metering is the commercial promise of this surface; enforcement is wave-2 scope.

> **Status:** degraded — budget fields present in spawn request but not enforced. This is the honest marker for the metering gap.

## Worked Walkthrough

1. **Spawn** a VM:
```bash
curl -s -X POST http://localhost:8888/api/vm/spawn \
  -H 'Content-Type: application/json' \
  -d '{"id":"demo","facet":"js"}'
```

2. **Eval** an expression:
```bash
curl -s -X POST http://localhost:8888/api/vm/demo/eval \
  -H 'Content-Type: application/json' \
  -d '{"source":"Math.PI * 2"}'
```

3. **Read** the sheet:
```bash
curl -s http://localhost:8888/api/vm
```

4. **Revoke** when done:
```bash
curl -s -X POST http://localhost:8888/api/vm/demo/revoke \
  -H 'Content-Type: application/json' \
  -d '{"reason":"demo done"}'
```
