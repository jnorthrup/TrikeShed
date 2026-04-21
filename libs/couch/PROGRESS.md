# couch RED parity backlog

Goal: lock down the RED use cases for a new couch module that is:
- RelaxFactory view-service protocol compatible where it matters
- CouchDB 1.1 counterpart compatible at the design-doc/view/query layer
- forced to reuse TrikeShed reactor + HTX transport instead of reintroducing raw socket request code
- allowed to expose a mini DSL only when that surface is infix-composed rather than procedural/builder style

Current state
- RED tests only for RelaxFactory/CouchDB/transport parity suites beyond the MiniDuck DSL slice
- First MiniDuck GREEN slice now exists in `src/commonMain/kotlin/borg/trikeshed/couch/miniduck/` and `src/commonMain/kotlin/borg/trikeshed/couch/minidsl/`
- No implementation has been added yet for the requested RelaxFactory/CouchDB/transport parity layers
- Existing exploratory files at repo root (`MiniSqlDsl.kt`, `couchdb_1_7_2_api.yaml`) remain untouched and are not part of the new module build yet
- Fresh design docs now live at:
  - `MINIDUCK_DESIGN.md`
  - `WAL_DESIGN.md`
  - `ROWVEC_FAMILIES_DESIGN.md`

RED test suites
- `src/commonTest/kotlin/borg/trikeshed/couch/relaxfactory/RelaxFactoryParityRedTest.kt`
  - interface-to-design-doc compilation
  - annotation-to-query-template parity
  - method-level override semantics
  - scalar/list/map return-shape decoding contracts
  - explicit `group_level` correctness guard
- `src/commonTest/kotlin/borg/trikeshed/couch/couchdb/CouchDb11CounterpartRedTest.kt`
  - Couch 1.1 design-doc JSON shape
  - view query parameter encoding
  - rowset decoding (`total_rows`, `offset`, `rows`, `doc`)
  - endpoint surface expectations
- `src/commonTest/kotlin/borg/trikeshed/couch/transport/ReactorHtxReuseRedTest.kt`
  - runtime must accept injected `Reactor`
  - transport must produce HTX requests
  - RequestFactory bridge expectations for `/gwtRequest`

Expected RED failure mode
- compile failures for missing production APIs such as:
  - `borg.trikeshed.couch.compat.relaxfactory.*`
  - `borg.trikeshed.couch.api.*`
  - `borg.trikeshed.couch.runtime.*`
  - `borg.trikeshed.couch.transport.htx.*`
- this is intentional until the parity layer is implemented from the tests

Next GREEN slices
1. Introduce serializable Couch 1.1 spec/data classes.
2. Introduce RelaxFactory-compatible annotations + service manifest compiler.
3. Introduce HTX request builder + reactor-backed transport interfaces.
4. Introduce RequestFactory bridge planning layer.
5. Make one suite green at a time, preserving RED-GREEN discipline.
