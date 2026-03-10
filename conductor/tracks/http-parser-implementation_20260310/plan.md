# Track: HTTP Parser Implementation

**Track ID:** `http-parser-implementation_20260310`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Implement the RFC 7230 HTTP request-line and header parsing contracts established in arrange-05
(`HttpParserContractTest.kt`). The failing tests define the exact API surface; this track
delivers the implementation that makes them green.

## Bounded Corpus

- `src/commonMain/kotlin/borg/trikeshed/net/http/` (create)
- `src/jvmTest/kotlin/borg/trikeshed/net/HttpParserContractTest.kt` (read-only — do not modify)

## Invariants

- Implementation lives in `commonMain` (no JVM-only imports).
- API matches the stubs in `HttpParserContractTest.kt` exactly:
  - `fun parseHttpRequestLine(line: String): HttpRequestLineSpec?`
  - `fun parseHttpHeaders(lines: List<String>): List<Pair<String, String>>`
  - `interface HttpRequestLineSpec { val method: String; val requestTarget: String; val httpVersion: String }`
- Do not modify the test file.
- The stubs in the test file (`TODO()`) must be replaced by real imports once the implementation exists
  — move the stubs out of the test and import from `borg.trikeshed.net.http`.

## Contract (from failing tests)

- `parseHttpRequestLine("GET /index.html HTTP/1.1")` → method=GET, target=/index.html, version=HTTP/1.1
- `parseHttpRequestLine("POST /api/v1/data HTTP/1.1")` → method=POST, target=/api/v1/data, version=HTTP/1.1
- `parseHttpRequestLine("HEAD / HTTP/1.0")` → method=HEAD, target=/, version=HTTP/1.0
- `parseHttpRequestLine("")` → null
- `parseHttpRequestLine("BADLINE")` → null (fewer than 3 space-delimited parts)
- `parseHttpHeaders(listOf("Content-Type: text/html"))` → [("Content-Type", "text/html")]
- `parseHttpHeaders(listOf("Content-Type: application/json", "Content-Length: 42", "X-Custom: value"))` → 3 pairs
- Header values have leading/trailing whitespace trimmed
- Header lines without `:` are skipped

## Slice Schema

### http-01 — HttpRequestLine implementation
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/net/http/HttpRequestLine.kt` (create)

**Deliverables:**
- `interface HttpRequestLineSpec` (or move stub from test)
- `data class HttpRequestLine : HttpRequestLineSpec`
- `fun parseHttpRequestLine(line: String): HttpRequestLineSpec?`
- `fun parseHttpHeaders(lines: List<String>): List<Pair<String, String>>`

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.HttpParserContractTest'`
→ all 9 tests pass

**Delivered:**
- `src/commonMain/kotlin/borg/trikeshed/net/http/HttpRequestLine.kt` created with `HttpRequestLineSpec`, `HttpRequestLine`, `parseHttpRequestLine()`, `parseHttpHeaders()`
- Test file stubs removed; real imports added
- `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.HttpParserContractTest'` → BUILD SUCCESSFUL (all 9 pass)

---

## Evidence Log

- 2026-03-10: Track created from arrange-05 triage output. 9 failing contract tests in HttpParserContractTest.kt.
- 2026-03-10: http-01 closed — HttpRequestLine.kt implemented in commonMain; all 9 contract tests green.
