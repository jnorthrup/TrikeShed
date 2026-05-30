# CouchDB 1.7.2 → TrikeShed/couch — TODO

## Status: EARLY PORT — CANNOT BREAK BUILD

This is a historical-reimplementation project. CouchDB 1.7.2 is a deployed Apache project
installed at `~/.couchdb`. The goal is a clean KMP port using TrikeShed/lib (no stdlib
primitives, Series/Join/RingSeries only, CharSeries over String, SPI/Impl over expect/actual).

---

## Architecture

```
couch (KMP lib)
├── src/commonMain/kotlin/borg/trikeshed/couch/
│   ├── ConfigParser/        ← INI → JSON/YAML/CBOR via Confix
│   ├── ViewServer/         ← map/reduce view engine
│   ├── CouchDB/            ← HTTP API, replication, database ops
│   └── drivers/             ← couch_icu_driver, couch_ejson_compare (NATIVE)
├── src/jvmMain/            ← Java NIO SeekFileBuffer, io_uring (Linux)
└── src/nativeMain/         ← POSIX mmap, eventfd
```

---

## Donor: zTrike/confix

`zTrike/src/commonMain/kotlin/borg/trikeshed/parse/confix/Confix.kt`

Key ideas to移植:
- `Syntax` enum with `JSON | CBOR | YAML` variants — single dispatch surface
- `recognize(first: Byte): Bool` — sniff format from first byte
- `scan(src: Series<Byte>): ConfixIndex` — produces `ConfixIndex = FacetedRow<ConfixIndexK<*>>`
- `scan0` for text formats (JSON/YAML) uses `CharSequence` witness over `Series<Byte>`
- CBOR has its own `scanCbor` scanner
- `IOMemento` tag system: `IoString | IoBoolean | IoDouble | IoNothing | IoObject`
- Column meta: `[open, close, tag, children]` — 4 fixed columns

**NOT porting** (TrikeShed already has equivalents):
- `cursor.*` — use `borg.trikeshed.cursor.*`
- `lib.*` — use `borg.trikeshed.lib.*`

---

## 1. ConfigParser — INI → Confix-compatible AST

### Why INI first

CouchDB 1.7.2 uses INI files (default.ini, local.ini). These are the current pain point:
`re:split("", Pattern)` returns `[]` in Erlang 28 instead of `[""]`. The INI parser
was the reason we started this port — the Erlang 1.7.2 source is already in hand.

### Goals

- [ ] Parse INI format into a typed AST using Confix `IOMemento` tags
- [ ] Make the AST queryable: `getSection("httpd").get("port")` 
- [ ] Emit JSON, YAML, CBOR from the same AST (Confix multi-format output)
- [ ] Handle CouchDB INI quirks: `[section.key]` interpolation, `;` comments, multi-line values

### Subtasks

- [ ] Define `IniNode = SectionNode | KeyValueNode | CommentNode` in `ConfigParser.kt`
- [ ] Write `IniParser` using `CharSeries` — no String, no stdlib regex
- [ ] Hook into `Confix.Syntax` dispatch: `IniParser` as a 4th `Syntax` variant?
      Or keep INI separate and use Confix for JSON/YAML/CBOR output only?
- [ ] Compatibility layer: current Erlang INI at `~/.couchdb/etc/couchdb/` must remain usable
- [ ] Write tests: round-trip INI → AST → INI, INI → JSON, INI → CBOR

### Erlang reference (DO NOT PORT — STUDY ONLY)

```erlang
% couch_config.erl — parse_ini_file/1 (broken in Erlang 28)
% The crash: re:split("", "\\r\\n|\\n|\\r|\\032", [{return,list}]) → []
% Fix candidate: binary:split(IniBin, <<"\n">>, [global]) replaces re:split line split
Lines0 = binary:split(IniBin, <<"\n>>, [global]),
Lines1 = lists:map(fun(<<>>) -> <<>>; (L) -> binary:replace(L, <<"\r">>, <<>>, [global]) end, Lines0),
```

---

## 2. ViewServer — map/reduce engine

### What it does

CouchDB views are JavaScript map/reduce functions evaluated by a ViewServer process.
The Erlang side spawns a `couch_js` process and communicates via JSON commands:

```
Erlang → ViewServer: ["reset"], ["add_fun", "function(doc) { ... }"], ["map_doc", doc]
ViewServer → Erlang: [true, [[key, value], ...]] or [false, error]
```

### Goals

- [ ] Port `couch_query_servers.erl` — manages ViewServer lifecycle
- [ ] Port `couch_view.erl` — B-tree index management for views
- [ ] Support JavaScript views (via `couch_js` process) AND native Erlang views
- [ ] Confix integration: view definition stored as JSON, parsed via `Confix.Syntax.JSON`

### Subtasks

- [ ] `ViewServerProtocol` — JSON command/response codec using Confix
- [ ] `CouchJsProcess` — manages OS subprocess for `couch_js`
- [ ] `MapReduceEngine` — orchestrates map phase, then reduce phase
- [ ] `ViewIndex` — B-tree over view output, using TrikeShed ISAM
- [ ] ` couch_view_group` — manages group of views (design document)
- [ ] Write tests: map-only view, map+reduce view, stale=ok

### Erlang reference (DO NOT PORT — STUDY ONLY)

```erlang
% couch_query_servers.erl — handles JSON protocol with ViewServer
start_link(IniFiles) ->
    {ok, ConfigPid} = couch_config:start_link(IniFiles),
    ... % spawns couch_js process
```

---

## 3. .so Driver Libraries — NATIVE CODE

CouchDB 1.7.2 uses libtool to build `.so` drivers. These must remain native:

### Goals

- [ ] `couch_icu_driver.so` — Unicode normalization via ICU (NIF or port driver)
- [ ] `couch_ejson_compare.so` — JSON comparison for view collation (NIF)
- [ ] Build system: `src/couchdb/priv/Makefile` — currently stub (`all-am: @:`)

### Subtasks

- [ ] Write proper `priv/Makefile.am` / `priv/Makefile` using libtool
- [ ] `couch_icu_driver.c` — ICU `u_strCompare` wrapper as NIF
- [ ] `couch_ejson_compare.c` — memcmp-based JSON value comparison
- [ ] Erlang NIF registration: `couch_btree:cmp_fun/2` → calls `couch_ejson_compare`
- [ ] CI: ensure `.so` files are built and installed to `~/.couchdb/lib/couchdb/`

### Current block

`make` from source root silently skips `src/couchdb/priv/` (no Makefile).
Run `make` from `src/couchdb/priv/` directly after fixing Makefile.

---

## 4. Erlang 28 Compatibility (historical context)

The reason we're here: Erlang 28 changed `re:split("", Pattern)` behavior.

```erlang
% Erlang ≤27
re:split("", "\\n", [{return,list}]) → [""]
% Erlang 28
re:split("", "\\n", [{return,list}]) → []
```

This broke `couch_config:parse_ini_file/1` in CouchDB 1.7.2.
The fix (DO NOT APPLY — for reference only):
- Replace `re:split(IniBin, "\\r\\n|\\n|\\r|\\032")` with `binary:split(IniBin, <<"\\n">>, [global])`
- Add `[] →` catch clause to foldl case expressions

---

## 5. Non-Goals (explicitly out of scope)

- CouchDB 2.x/3.x features (clustering, `/_scheduler`)
- CouchDB Fauxton web UI
- Replication protocol v2 (only v1 for now)
- Security: admin passwords, cookie auth (stub only)
- `couch_peruser` plugin
- Any Java code in `src/javax/` (excluded from TrikeShed port)

---

## Reading List

| File | Purpose |
|------|---------|
| `zTrike/src/commonMain/kotlin/borg/trikeshed/parse/confix/Confix.kt` | Donor: multi-format parser |
| `zTrike/src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixIndexK.kt` | ConfixIndex = FacetedRow<ConfixIndexK<*>> |
| `zTrike/src/commonTest/kotlin/borg/trikeshed/confix/ConfixTest.kt` | Tests showing Syntax dispatch |
| `zTrike/confix-architecture.html` | Architecture diagram (view in browser) |
| `apache-couchdb-1.7.2/src/couchdb/couch_config.erl` | Reference: broken INI parser |
| `apache-couchdb-1.7.2/src/couchdb/couch_server_sup.erl` | Reference: startup |
| `apache-couchdb-1.7.2/src/couchdb/couch_query_servers.erl` | Reference: ViewServer protocol |
| `apache-couchdb-1.7.2/src/couchdb/couch_view.erl` | Reference: view B-tree |
