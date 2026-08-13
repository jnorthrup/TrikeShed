package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import java.io.File
import kotlinx.coroutines.runBlocking

/** Queue the ontology marker→packed-facet isomorph as a durable Jules cut. */
fun main() = runBlocking {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, "jules-board.wal")))
    val workId = "ontology:packed-facet-isomorph:20260812"
    val spec = """
        Replace the duplicate memory-ontology marker/object ↔ ordinal bridge with one canonical
        packed-facet representation, and wire it through MemoryStore.

        Current duplicate identity:
        - singleton markers (LatentState, Procedural, DialogueManagement, ...)
        - Byte-backed SubstrateMark/MechanismMark/SubjectMark companion constants
        - three exhaustive from(marker) conversions
        - separate marker leaf Series.

        Required production cut:
        1. Make SubstrateMark, MechanismMark, and SubjectMark the canonical identity value classes.
           Preserve semantic domain names with typealias MemorySubstrate = SubstrateMark,
           MemoryMechanism = MechanismMark, MemorySubject = SubjectMark. Do NOT collapse all
           three domains to Byte; type safety must remain.
        2. Move presentation/wire metadata (stable name, gloss, family/tier) into canonical
           Series-backed descriptor tables indexed by raw ordinal. Do not recreate singleton
           marker objects as a second identity system.
        3. Add a bidirectional facet codec/isomorph:
             FacetClassification ↔ Couch Document fields
             substrateMark, mechanismMark, subjectMark
           Decode must reject missing/invalid fields rather than inventing a default facet.
        4. Extend MemoryStore.put/get so Ring 2 Couch metadata persists and restores the
           classification. Ring 0 CAS and Ring 1 LineCas behavior must remain unchanged.
        5. Extend MemoryIndexLayer with facet routes/query access so Ring 2 classifications are
           observable to Ring 3 causal and Ring 4 pointcut/Forge consumers.
        6. Wire Ring 3 causality as a first-class surface, not a comment: each persisted facet
           classification mutation must emit or update a typed causal fact/edge carrying the
           memory ContentId plus the three packed marks. The causal projection must support
           querying memories by facet provenance and must be driven from the MemoryStore mutation
           path; do not make Forge infer causality from Couch fields ad hoc.
        7. Add focused JVM tests that are actually RED before the implementation: round-trip
           FacetClassification through Couch metadata and verify facet-index queries. Marker-only
           `is` assertions are not acceptance evidence. Include a causal-observation test proving
           a classified MemoryStore.put produces the expected facet-provenance causal fact.

        Architectural boundary:
        - commonMain owns value classes, descriptors, codec, MemoryStore, MemoryIndexLayer, and
          the Ring 3 causal facet projection.
        - jvmMain owns no ontology identity or storage semantics.
        - Use Join, Series, s_, and α; do not add List-only terminal representations.
        - No libs/.

        Files expected:
        src/commonMain/kotlin/borg/trikeshed/memory/ontology/FacetClassification.kt
        src/commonMain/kotlin/borg/trikeshed/memory/ontology/OntologyAlgebra.kt
        src/commonMain/kotlin/borg/trikeshed/memory/MemoryStore.kt
        src/commonMain/kotlin/borg/trikeshed/memory/MemoryIndexLayer.kt
        src/commonMain/kotlin/borg/trikeshed/dag/... causal facet projection/edge surface
        src/jvmTest/kotlin/borg/trikeshed/memory/ontology/... focused round-trip/index tests

        Acceptance:
        ./gradlew jvmMainClasses --console=plain
        """.trimIndent()
    store.appendWork(
        workId,
        JulesCause.WorkQueued(
            workId = workId,
            tier = "forge",
            title = "Replace ontology markers with packed facet isomorph and persist through MemoryStore",
            spec = spec,
            parent = "gap:ontology-marker-glue",
            score = 0.98,
            at = System.currentTimeMillis(),
        ),
    )
    println("[SEED] queued $workId")
}
