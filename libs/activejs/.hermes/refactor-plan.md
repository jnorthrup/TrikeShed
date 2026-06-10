# ActiveJS CCEK Orchestration Refactoring Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Replace Channel/coroutine reactive patterns in activejs with CCEK SPI bus zero-copy fanout orchestrations, ensuring FieldSynapse 24B wireproto integration and Series.view cold-path boundaries.

**Architecture:**
- CCEK SPI bus (via NioSupervisor service registry) replaces explicit Channel/ReceiveChannel
- Observer delegates hook into taxonomy.rows via ObserverDelegateRegistration facet
- FieldSynapse wireproto records flow through CCEK transport without intermediate buffering
- Cold queries use Series α-conversion + Series.view boundary iteration (PRELOAD style)

**Tech Stack:** Kotlin Multiplatform (JVM/JS/WASM), TrikeShed lib_cursor, CCEK (Coroutine→Context→Element→Key), Confix

---

## Pattern Inventory

### P1: LivePointcutCursor — Hot Path Channel Fanout
**File:** `src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt`
**Lines:** 35-36, 55, 96, 139-149
**Pattern:** `private val eventChannel = Channel<PointcutEvent>(capacity)` + `CoroutineScope.launch` + `ReceiveChannel` subscription
**CCEK Replacement:** Register `PointcutEventProducer` as CCEK Element in NioSupervisor; subscribers use `service<PointcutEventConsumer>()` for zero-copy fanout

### P2: LivePointcutCursor — Delivery Scope Coroutine Launch
**File:** `src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt`
**Lines:** 36, 141
**Pattern:** `private val deliveryScope = CoroutineScope(Dispatchers.Default)` + `deliveryScope.launch { subscribe().consumeEach {...} }`
**CCEK Replacement:** CCEK delegate registration via `ObserverDelegateRegistration` facet on ColumnMetaRef; lifecycle managed by SupervisorJob

### P3: ConfixActiveJsTaxonomy — Confix Observation Channel
**File:** `src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt`
**Lines:** 111-138
**Pattern:** `Channel<BlackBoardEntry>` + `CoroutineScope.launch` wrapping `pointcutCursor.reactiveQuery().consumeEach`
**CCEK Replacement:** `ConfixObservationProducer` CCEK element; fanout to `ConfixObservationConsumer` delegates

### P4: ConfixActiveJsTaxonomy — Eager Collection in toBlackboardEntries
**File:** `src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt`
**Lines:** 41-63
**Pattern:** `mutableListOf<BlackBoardEntry>()` + `for` loop materializing all entries
**CCEK Replacement:** Lazy `Series.view` boundary iteration; BlackBoardEntry materialization on-demand via cursor projection

### P5: ActiveJsTaxonomy — Observable Delegate Not CCEK-Aware
**File:** `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt`
**Lines:** 43-49
**Pattern:** `kotlin.properties.Delegates.observable` on `MutableSeries` — no CCEK facet integration
**CCEK Replacement:** `ObserverDelegateRegistration` facet on ColumnMetaRef; delegate fires CCEK SPI notifications via NioSupervisor

### P6: ActiveJsTaxonomy — Eager filterByKind/filterByOwner/filterByFacet
**File:** `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt`
**Lines:** 67-82, 173-180
**Pattern:** Creates new taxonomy + `for` loop + `register` for each filter
**CCEK Replacement:** Cold `Series.view` with predicate; no intermediate collection allocation

### P7: ConfixActiveJsTaxonomy — Eager facetCursor
**File:** `src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt`
**Lines:** 89-98
**Pattern:** Materializes new taxonomy + filters at cursor level
**CCEK Replacement:** `Cursor.filterColumns` via `Series.view` boundary iteration with facet predicate

### P8: Test Harnesses — Manual Channel/Coroutine in Tests
**Files:** `ConfixActiveJsTaxonomyTest.kt:82-100`, `LivePointcutCursorTest.kt:54-70`, `ActiveJsTaxonomyTest.kt` (no reactive)
**Pattern:** `runBlocking` + `Channel` + `consumeEach` for reactive verification
**CCEK Replacement:** Test orchestrations use `CcekTestHarness` with injected SupervisorJob; verify via CCEK SPI capture

---

## Task Breakdown

### Task 1: Add CCEK SPI Definitions for Pointcut Events
**Objective:** Define CCEK Elements for pointcut event production/consumption

**Files:**
- Create: `src/commonMain/kotlin/org/xvm/activejs/ccek/PointcutCcekElements.kt`
- Modify: `src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt:35-36,55,96`

**Step 1: Write failing test**
```kotlin
// src/test/kotlin/org/xvm/activejs/ccek/PointcutCcekElementsTest.kt
@Test
fun `pointcut event producer registers in NioSupervisor`() = runBlocking {
    val supervisor = NioSupervisor()
    supervisor.open()
    val producer = PointcutEventProducer()
    supervisor.register(producer)
    val found = supervisor.service<PointcutEventProducer>()
    assertNotNull(found)
}
```

**Step 2: Run test to verify failure**
```bash
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.ccek.PointcutCcekElementsTest.pointcut event producer registers in NioSupervisor" --rerun-tasks
```
Expected: FAIL — `PointcutEventProducer` not defined

**Step 3: Write minimal implementation**
```kotlin
// src/commonMain/kotlin/org/xvm/activejs/ccek/PointcutCcekElements.kt
package org.xvm.activejs.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.cursor.FieldSynapse
import kotlinx.coroutines.CoroutineContext

interface PointcutEventProducer : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PointcutEventProducer>
    override val key: CoroutineContext.Key<*> get() = Key
    fun emit(synapse: FieldSynapse)
}

interface PointcutEventConsumer : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PointcutEventConsumer>
    override val key: CoroutineContext.Key<*> get() = Key
    fun onEvent(synapse: FieldSynapse)
}

class PointcutEventProducerImpl : AsyncContextElement(), PointcutEventProducer {
    private val consumers = mutableListOf<PointcutEventConsumer>()
    override suspend fun open() { super.open(); state = ElementState.ACTIVE }
    override fun close() { consumers.clear(); super.close() }
    fun registerConsumer(consumer: PointcutEventConsumer) { consumers += consumer }
    override fun emit(synapse: FieldSynapse) { consumers.forEach { it.onEvent(synapse) } }
}
```

**Step 4: Run test to verify pass**
```bash
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.ccek.PointcutCcekElementsTest.pointcut event producer registers in NioSupervisor" --rerun-tasks
```
Expected: PASS

**Step 5: Commit**
```bash
git add src/commonMain/kotlin/org/xvm/activejs/ccek/PointcutCcekElements.kt src/test/kotlin/org/xvm/activejs/ccek/PointcutCcekElementsTest.kt
git commit -m "feat(activejs): ccek pointcut event SPI elements"
```

---

### Task 2: Replace LivePointcutCursor.eventChannel with CCEK Producer
**Objective:** Remove `Channel<PointcutEvent>` and `CoroutineScope.launch`; emit via CCEK SPI

**Files:**
- Modify: `src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt:35-36,55,61-97,139-149`
- Test: `src/test/kotlin/org/xvm/activejs/LivePointcutCursorTest.kt:54-70`

**Step 1: Write failing test**
```kotlin
// In LivePointcutCursorTest.kt
@Test
fun `feed emits via CCEK producer not channel`() = runBlocking {
    val supervisor = NioSupervisor().also { it.open() }
    val cursor = LivePointcutCursorFactory.empty()
    val captured = mutableListOf<FieldSynapse>()
    val consumer = object : PointcutEventConsumer {
        override fun onEvent(s: FieldSynapse) { captured += s }
    }
    supervisor.register(consumer)
    
    cursor.feed(PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.Test.call"))
    
    assertEquals(1, captured.size)
    assertEquals(0x10.toByte(), captured[0].opcode)
}
```

**Step 2: Run test to verify failure**
```bash
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.LivePointcutCursorTest.feed emits via CCEK producer not channel" --rerun-tasks
```
Expected: FAIL — `feed` still uses `eventChannel.trySend`

**Step 3: Write minimal implementation**
```kotlin
// In LivePointcutCursor.kt
// Remove: private val eventChannel = Channel<PointcutEvent>(capacity)
// Remove: private val deliveryScope = CoroutineScope(Dispatchers.Default)
// Replace subscribe():
fun subscribe(): PointcutEventConsumer = PointcutEventConsumerImpl(this)
// Replace feed() emission:
/* eventChannel.trySend(event) */ PointcutEventProducer.getFromContext()?.emit(toFieldSynapse(event))
// Remove reactiveQuery() Channel + launch, replace with CCEK consumer registration
```

**Step 4: Run test to verify pass**
```bash
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.LivePointcutCursorTest.feed emits via CCEK producer not channel" --rerun-tasks
```
Expected: PASS

**Step 5: Run all LivePointcutCursor tests**
```bash
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.LivePointcutCursorTest" --rerun-tasks
```
Expected: All PASS

**Step 6: Commit**
```bash
git add src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt src/test/kotlin/org/xvm/activejs/LivePointcutCursorTest.kt
git commit -m "refactor(activejs): ccek orchestration — LivePointcutCursor hot path"
```

---

### Task 3: Replace ConfixActiveJsTaxonomy.confixObservations with CCEK
**Objective:** Remove Channel<BlackBoardEntry> + coroutine launch; use CCEK fanout

**Files:**
- Modify: `src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt:111-138`
- Test: `src/test/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomyTest.kt:82-100`

**Step 1: Write failing test**
```kotlin
// In ConfixActiveJsTaxonomyTest.kt
@Test
fun `confixObservations uses CCEK fanout not Channel`() = runBlocking {
    val supervisor = NioSupervisor().also { it.open() }
    val tax = ActiveJsTaxonomy()
    val pointcutCursor = LivePointcutCursorFactory.empty()
    val confix = ConfixActiveJsTaxonomy(tax)
    
    val captured = mutableListOf<BlackBoardEntry>()
    val consumer = object : ConfixObservationConsumer {
        override fun onObservation(entry: BlackBoardEntry) { captured += entry }
    }
    supervisor.register(consumer)
    
    pointcutCursor.feed(PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.Test.call"))
    
    assertEquals(1, captured.size)
    assertEquals(ConfixRole.OBSERVATION, captured[0].role)
}
```

**Step 2: Run test to verify failure**
```bash
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.ConfixActiveJsTaxonomyTest.confixObservations uses CCEK fanout not Channel" --rerun-tasks
```

**Step 3: Write minimal implementation**
```kotlin
// In ConfixActiveJsTaxonomy.kt
// Add CCEK element interfaces for ConfixObservationProducer/Consumer
// Replace confixObservations() Channel + launch with:
fun confixObservations(pointcutCursor: LivePointcutCursor): ConfixObservationConsumer {
    val producer = ConfixEventProducer(pointcutCursor)
    val consumer = ConfixObservationConsumerImpl()
    producer.registerConsumer(consumer)
    return consumer
}

// Remove: Channel<BlackBoardEntry> + CoroutineScope.launch block
```

**Step 4: Run test to verify pass**

**Step 5: Run all ConfixActiveJsTaxonomy tests**

**Step 6: Commit**
```bash
git add src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt src/test/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomyTest.kt
git commit -m "refactor(activejs): ccek orchestration — ConfixActiveJsTaxonomy observations"
```

---

### Task 4: Convert toBlackboardEntries to Lazy Series.view
**Objective:** Replace eager `mutableListOf` + for-loop with cold Series α-conversion + Series.view

**Files:**
- Modify: `src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt:41-63`
- Modify: `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt:87-109`
- Test: `src/test/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomyTest.kt:21-41`

**Step 1: Write failing test**
```kotlin
// In ConfixActiveJsTaxonomyTest.kt
@Test
fun `toBlackboardEntries returns lazy series not eager list`() {
    val tax = ActiveJsTaxonomy()
    repeat(1000) { i -> tax.register(coordinateRow("pkg.T$i", "m", 0x10, i, ActiveJsFacet.JsFunction)) }
    val confix = ConfixActiveJsTaxonomy(tax)
    
    val series = confix.toBlackboardEntriesSeries() // NEW API
    // Series.view: only first 3 accessed
    val first3 = series.take(3).toList()
    
    assertEquals(3, first3.size)
    // Verify no full materialization occurred (check allocation count or timing)
}
```

**Step 2: Run test to verify failure**

**Step 3: Write minimal implementation**
```kotlin
// In ConfixActiveJsTaxonomy.kt
fun toBlackboardEntriesSeries(): Series<BlackBoardEntry> = liveSeries(
    count = { taxonomy.size },
    access = { idx ->
        val r = taxonomy.rowAt(idx)
        BlackBoardEntry(confixDoc(entryJson(r)), ConfixRole.OBSERVATION)
    }
)

// Deprecate toBlackboardEntries() or implement via series.toList()
```

**Step 4: Run test to verify pass**

**Step 5: Run all tests**

**Step 6: Commit**
```bash
git add src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt
git commit -m "refactor(activejs): ccek orchestration — lazy Series.view for BlackBoardEntry"
```

---

### Task 5: Integrate ObserverDelegateRegistration Facet into ColumnMetaRef
**Objective:** Tag ColumnMetaRef with ObserverDelegateRegistration facet to enable CCEK observer hooks

**Files:**
- Modify: `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt:129-161` (SCHEMA_REFS, toRowVec)
- Modify: `src/jvmMain/kotlin/org/xvm/activejs/Actual.kt:105-128` (ColumnMetaRef.fromJvm)
- Test: `src/test/kotlin/org/xvm/activejs/ActiveJsTaxonomyTest.kt:122-148`

**Step 1: Write failing test**
```kotlin
// In ActiveJsTaxonomyTest.kt
@Test
fun `activeJsFacet column carries ObserverDelegateRegistration facet`() {
    val tax = ActiveJsTaxonomy()
    tax.register(coordinateRow("pkg.F", "x", 0x10, 0, ActiveJsFacet.JsFunction))
    val cursor = tax.asCursor()
    val meta = cursor.columnMeta("activeJsFacet") as ColumnMetaRef
    
    // The activeJsFacet column should be tagged for observer delegate registration
    assertEquals(PointcutFacet.ObserverDelegateRegistration, meta.facet)
    assertEquals(ActiveJsFacet.JsFunction, meta.activeJsFacet)
}
```

**Step 2: Run test to verify failure**

**Step 3: Write minimal implementation**
```kotlin
// In ActiveJsTaxonomy.kt SCHEMA_REFS
ColumnMetaRef(9, "activeJsFacet", "String", PointcutFacet.ObserverDelegateRegistration, ActiveJsFacet.Unfaceted)

// In Actual.kt ColumnMetaRef.fromJvm - ensure facet mapping preserves ObserverDelegateRegistration
```

**Step 4: Run test to verify pass**

**Step 5: Run all ActiveJsTaxonomy tests**

**Step 6: Commit**
```bash
git add src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt src/jvmMain/kotlin/org/xvm/activejs/Actual.kt
git commit -m "refactor(activejs): ccek orchestration — ObserverDelegateRegistration facet on activeJsFacet column"
```

---

### Task 6: Replace ActiveJsTaxonomy Observable Delegate with CCEK Observer Hook
**Objective:** Remove `Delegates.observable`; fire CCEK SPI notifications on row changes

**Files:**
- Modify: `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt:43-49`
- Test: `src/test/kotlin/org/xvm/activejs/ActiveJsTaxonomyTest.kt` (verify row registration triggers CCEK notify)

**Step 1: Write failing test**
```kotlin
// In ActiveJsTaxonomyTest.kt
@Test
fun `register triggers CCEK observer notification`() = runBlocking {
    val supervisor = NioSupervisor().also { it.open() }
    val tax = ActiveJsTaxonomy()
    
    val notified = mutableListOf<CoordinateRow>()
    val observer = object : TaxonomyObserver {
        override fun onRowRegistered(row: CoordinateRow) { notified += row }
    }
    supervisor.register(observer)
    
    tax.register(coordinateRow("pkg.Test", "method", 0x10, 1, ActiveJsFacet.JsFunction))
    
    assertEquals(1, notified.size)
    assertEquals("pkg.Test.method", notified[0].symbolName)
}
```

**Step 2: Run test to verify failure**

**Step 3: Write minimal implementation**
```kotlin
// In ActiveJsTaxonomy.kt
internal var rows: MutableSeries<CoordinateRow> = ChunkedMutableSeries()
// Remove Delegates.observable

fun register(row: CoordinateRow) {
    rows.add(row)
    // Fire CCEK observer notification
    TaxonomyObserver.getFromContext()?.onRowRegistered(row)
}

// Define TaxonomyObserver CCEK element interface
```

**Step 4: Run test to verify pass**

**Step 5: Run all tests**

**Step 6: Commit**
```bash
git add src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt
git commit -m "refactor(activejs): ccek orchestration — taxonomy observer delegate via CCEK SPI"
```

---

### Task 7: Convert Eager Filters to Cold Series.view
**Objective:** Replace filterByKind/filterByOwner/filterByFacet with lazy Series α-conversion

**Files:**
- Modify: `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt:67-83, 173-180`
- Modify: `src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt:89-98, 103-105`
- Modify: `src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt:109-125`

**Step 1: Write failing test**
```kotlin
// In ActiveJsTaxonomyTest.kt
@Test
fun `filterByKind returns cold series view not eager taxonomy`() {
    val tax = ActiveJsTaxonomy()
    repeat(100) { i -> tax.register(coordinateRow("pkg.T$i", "m", 0x10 + (i % 5), i, ActiveJsFacet.JsFunction)) }
    
    val filtered = tax.filterByKindSeries(0x10) // NEW API returning Series<CoordinateRow>
    assertEquals(20, filtered.a) // size
    // Access only first 5 — rest never materialized
    val first5 = (0 until 5).map { filtered.b(it) }
    assertAll { first5.forEach { it.assert { it.pointcutKind == 0x10 } } }
}
```

**Step 2: Run test to verify failure**

**Step 3: Write minimal implementation**
```kotlin
// In ActiveJsTaxonomy.kt
fun filterByKindSeries(kind: Int): Series<CoordinateRow> = liveSeries(
    count = { rows.count { it.pointcutKind == kind } },
    access = { idx ->
        var seen = 0
        for (i in 0 until rows.size) {
            val r = rows[i]
            if (r.pointcutKind == kind) {
                if (seen == idx) return@liveSeries r
                seen++
            }
        }
        throw IndexOutOfBoundsException()
    }
)

// Deprecate filterByKind() or implement via filterByKindSeries().toMutableSeries()
```

**Step 4: Run test to verify pass**

**Step 5: Run all tests**

**Step 6: Commit
```bash
git add src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt src/main/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomy.kt src/main/kotlin/org/xvm/activejs/LivePointcutCursor.kt
git commit -m "refactor(activejs): ckek orchestration — cold Series.filterBy* views"
```

---

### Task 8: Update Test Harnesses to CCEK Orchestrations
**Objective:** Convert Channel/coroutine test patterns to CCEK test harness

**Files:**
- Modify: `src/test/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomyTest.kt:82-100`
- Modify: `src/test/kotlin/org/xvm/activejs/LivePointcutCursorTest.kt:54-70`
- Create: `src/test/kotlin/org/xvm/activejs/ccek/CcekTestHarness.kt`

**Step 1: Create CCEK test harness**
```kotlin
// src/test/kotlin/org/xvm/activejs/ccek/CcekTestHarness.kt
class CcekTestHarness {
    val supervisor = NioSupervisor().apply { open() }
    val producer = PointcutEventProducerImpl().also { supervisor.register(it) }
    val confixProducer = ConfixObservationProducer().also { supervisor.register(it) }
    val taxonomyObserver = TaxonomyObserverImpl().also { supervisor.register(it) }
    
    fun capturePointcutEvents(): List<FieldSynapse> {
        val captured = mutableListOf<FieldSynapse>()
        supervisor.register(object : PointcutEventConsumer {
            override fun onEvent(s: FieldSynapse) { captured += s }
        })
        return captured
    }
    
    fun captureConfixObservations(): List<BlackBoardEntry> {
        val captured = mutableListOf<BlackBoardEntry>()
        supervisor.register(object : ConfixObservationConsumer {
            override fun onObservation(e: BlackBoardEntry) { captured += e }
        })
        return captured
    }
    
    fun close() { supervisor.close() }
}
```

**Step 2: Rewrite tests using harness**

**Step 3: Run all tests**

**Step 4: Commit**
```bash
git add src/test/kotlin/org/xvm/activejs/ccek/CcekTestHarness.kt src/test/kotlin/org/xvm/activejs/ConfixActiveJsTaxonomyTest.kt src/test/kotlin/org/xvm/activejs/LivePointcutCursorTest.kt
git commit -m "refactor(activejs): ccek orchestration — test harness migration"
```

---

### Task 9: Verify Pinned Memseg Pointer Stability Across Targets
**Objective:** Ensure MemSegment pointers (FieldSynapse, CoordinateRow) are stable across JVM/JS/WASM

**Files:**
- Review: `src/main/kotlin/org/xvm/activejs/ActiveJsTaxonomy.kt:186-198` (CoordinateRow @Serializable)
- Review: `src/commonMain/kotlin/borg/trikeshed/cursor/FieldSynapse.kt` (24B wireproto)
- Test: Add multiplatform test for pointer stability

**Step 1: Write failing test**
```kotlin
// src/commonTest/kotlin/org/xvm/activejs/ccek/MemsegStabilityTest.kt
@Test
fun `FieldSynapse wireproto size is 24 bytes on all targets`() {
    val synapse = FieldSynapse(
        phase = 0, opcode = 0xA5.toByte(), methodIdx = 1, addr = 0x1000,
        seq = 42, nano = 123456789L, callsiteHash = 0xABCD, templateIdx = 0
    )
    val bytes = synapse.toWireproto() // NEW: serialization to 24B
    assertEquals(24, bytes.size)
}

@Test
fun `CoordinateRow poolId stable across serialization roundtrip`() {
    val row = CoordinateRow("pkg.Test", "pkg.Test", "method", "pkg.Test#method", 1, "()V", "", 0x10, 42, ActiveJsFacet.JsFunction)
    val bytes = row.toWireproto() // NEW
    val decoded = CoordinateRow.fromWireproto(bytes)
    assertEquals(row.poolId, decoded.poolId)
}
```

**Step 2: Run test to verify failure**

**Step 3: Implement wireproto serialization for CoordinateRow**

**Step 4: Run on all targets (jvmTest, jsTest, wasmJsTest)**
```bash
./gradlew :libs:activejs:jvmTest :libs:activejs:jsTest :libs:activejs:wasmJsTest --rerun-tasks
```

**Step 5: Commit**
```bash
git add src/commonMain/kotlin/org/xvm/activejs/ccek/MemsegWireproto.kt src/commonTest/kotlin/org/xvm/activejs/ccek/MemsegStabilityTest.kt
git commit -m "refactor(activejs): ccek orchestration — memseg wireproto stability"
```

---

## Verification Commands

After each task, run:
```bash
# Full test suite
./gradlew :libs:activejs:jvmTest --rerun-tasks

# Check for compilation errors
./gradlew :libs:activejs:compileKotlinJvm --rerun-tasks

# Quick smoke test
./gradlew :libs:activejs:jvmTest --tests "org.xvm.activejs.LivePointcutCursorTest" --rerun-tasks
```

---

## Priority Order

1. **Task 1-2** (HIGH) — Hot path: LivePointcutCursor Channel → CCEK Producer
2. **Task 3** (HIGH) — Hot path: ConfixActiveJsTaxonomy Channel → CCEK Fanout
3. **Task 5-6** (HIGH) — Core: ObserverDelegateRegistration facet + CCEK observer hook
4. **Task 4,7** (MEDIUM) — Cold path: Eager collections → Series.view
5. **Task 8** (MEDIUM) — Test harness migration
6. **Task 9** (MEDIUM) — Memseg pointer stability verification

---

## Tags
- `activejs-ccek-2025-06-10-01` — Task 1-2 complete
- `activejs-ccek-2025-06-10-02` — Task 3 complete
- `activejs-ccek-2025-06-10-03` — Task 5-6 complete
- `activejs-ccek-2025-06-10-04` — Task 4,7 complete
- `activejs-ccek-2025-06-10-05` — Task 8 complete
- `activejs-ccek-2025-06-10-06` — Task 9 complete