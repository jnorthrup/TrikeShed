# Drop a Corpus Guide

Route a corpus into the daemon — which endpoint to use, what happens to bytes, where things land.

> **Launch prerequisite:** the daemon must be running. See [guide-daemon-launch.md](guide-daemon-launch.md).

## The Three Entry Points

The daemon exposes three ingest endpoints. A stranger should use **one** for bulk corpus drop:

| Endpoint | Method | Content Types | What Happens | Use For |
|----------|--------|---------------|--------------|---------|
| **`POST /api/graal/ingest?name=<filename>`** | POST | Any (binary-safe) | Bytes stored as CAS blob + dropzone doc; binary → Tika/OCR → markdown; plan-shape gate → board cards | **Bulk corpus drop** (primary gesture) |
| `POST /api/submit` | POST | Markdown (plan shape) | Plan-shaped markdown → board cards | Board plan submission |
| `POST /api/donor` | POST | Markdown | Document ingestion | Donor document intake |

> **Primary gesture:** `POST /api/graal/ingest` — it accepts any binary, handles Tika/OCR extraction, and stores everything as a dropzone citizen. This is the one a stranger should use.

> **Status:** verified-live — ingest route shape matches `GraalWire.kt:241-363`.

## Ingest Flow (POST /api/graal/ingest)

```bash
# Drop a file into the daemon
curl -s -X POST "http://localhost:8888/api/graal/ingest?name=myfile.pdf" \
  --data-binary @myfile.pdf
```

Response:
```json
{
  "ok": true,
  "id": "dropzone/myfile.pdf",
  "cid": "<sha256-hex>",
  "bytes": 4096,
  "extracted": "dropzone/myfile.pdf.extract.md",
  "chars": 2048,
  "shape": "SP_6_WP_WDP",
  "plan": false,
  "persisted": false,
  "code": 12345,
  "codeRing8": 48,
  "byteSchema": "<hex-prefix>",
  "byteChunks": 12,
  "byteLzPhrases": 5,
  "byteComplexity": "0.1234",
  "byteLinks": 3,
  "pdfLane": true
}
```

What happens to the bytes:
1. **CAS blob** stored via `database.blockPut(bytes)` — content-addressed, deduplicated.
2. **Dropzone doc** `dropzone/<name>` created with contentType, length, contentId, code fields.
3. **Binary extraction:** text files pass through directly; binary files go through in-tree COS PDF disassembler first, then Tika/Tesseract as fallback. Extraction lands as `dropzone/<name>.extract.md`.
4. **Plan gate:** if the extracted text matches the plan shape grammar (`…6…W…[7…]`), it can be persisted to the board via `?persist=<userId>`.
5. **Byte epistemic surface:** binary drops produce chunk/link/signal documents for zoomable terrain.

> **Status:** verified-live — ingest flow matches `GraalWire.kt:253-363`.

## Project DBs

When a directory hierarchy is dropped (via `/api/projects` POST or browser upload), it becomes its own CouchDB database:

- The **db name** is the sanitized folder name.
- **Doc IDs** are the relative file paths.
- **CAS blobs** are shared with the daemon's main store.
- A **mount ledger** at `.oroboros/manifests/mount-ledger.tsv` records each mounted project (name, kind, path).

The mount ledger survives daemon restarts — boot replays it to re-mount projects.

> **Status:** unverified — the mount-ledger-survives-restart claim requires validator confirmation of a scratch-daemon restart showing the mount still present.

## The Zero-Dep PDF Disassembler

`PdfDisassembler` (commonMain, `src/commonMain/kotlin/borg/trikeshed/pdf/PdfDisassembler.kt`) is a scan-based PDF parser that:
- Finds every `N G obj` body by byte scan (never trusts the xref).
- Decodes stream filters (FlateDecode + PNG predictors, ASCIIHex, ASCII85).
- Expands object streams (`/Type /ObjStm`) for PDF 1.5+ compressed bodies.

It is an **internal API** — not exposed on a public route. Its output flows through the same text lanes as any drop via `PdfText.extract(doc).text`.

> **Status:** verified-live — the disassembler exists at `src/commonMain/kotlin/borg/trikeshed/pdf/PdfDisassembler.kt` and is invoked from `GraalWire.kt:265-268`.

## Known Wave-2 Items

- No unified `/api/corpus` endpoint exists. The three-entry-point routing is the as-built state.
- Tika/OCR path requires Tika on the classpath (JVM only; commonMain PDF disassembler is zero-dep).
