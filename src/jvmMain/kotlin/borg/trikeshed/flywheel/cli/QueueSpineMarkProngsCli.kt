package borg.trikeshed.flywheel.cli

import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Queue the SPINE-MARK prongs (user-signals → lcnc → forge semantics) as
 * durable WorkQueued envelopes. One prong per capability thick, each with
 * its own TDD-RED failure and disjoint file ownership so the daemon can
 * dispatch them in parallel.
 *
 * Usage: QueueSpineMarkProngsCli
 */
fun main(args: Array<String>) = runBlocking {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, "jules-board.wal")))

    val prongs: List<Triple<String, String, String>> = listOf(
        Triple(
            "spine-blackboard-facetmark-wiring",
            "Wire FacetMark into BlackboardDagFabric facet transitions (TDD-RED first)",
            """
            TARGET: The blackboard capability of the spine markers (LcncSpineMarks.kt FacetMark).

            CURRENT STATE (audit aec225abb, Aug 14 2026):
            - FacetMark exists: src/commonMain/kotlin/borg/trikeshed/context/lcnc/LcncSpineMarks.kt
              (@JvmInline value class, Byte ordinal, aligned 1:1 with FacetTransitionType).
            - BlackboardEvent.FacetTransition exists: dag/BlackboardDagFabric.kt:89 —
              vocabulary present, ZERO production emitters (only tests construct it).
            - BlackboardDagCausalGraph consumes facet transitions but nothing feeds it live.

            RED TEST FIRST (write it, run it, watch it fail):
            - commonTest: a facet transition emitted from a real dispatch must carry
              FacetMark.from(transitionType) and land in the blackboard DAG as a node
              whose coordinate resolves. Assert node exists; observe RED.

            IMPLEMENTATION SURFACE (disjoint ownership — do not touch other prongs' files):
            - src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagFabric.kt (add a
              FacetMark bridge on FacetTransition, not a parallel type)
            - src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph*.kt
              (project the facetMark column via α, Series not List)

            PRELOAD CONTRACT (binding):
            - Join/Series/Cursor typealiases only; no interface Series, no .toList() demotion.
            - facetMarkColumn() projections lazy via α.
            - Zero-cost discipline: Byte ordinals, gloss strings only at the
              KanbanEvent serialization boundary.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed and pasted in the PR body before implementation
            [ ] FacetTransition emitted from a production dispatch carries FacetMark
            [ ] BlackboardDagCausalGraph projects facetMarkColumn() as Series<FacetMark>
            [ ] Gate green
            """.trimIndent()
        ),
        Triple(
            "spine-causal-causalmark-wiring",
            "Wire CausalMark into CausalKernel flywheel edges (TDD-RED first)",
            """
            TARGET: The causality capability of the spine markers (CausalMark).

            CURRENT STATE (audit aec225abb, Aug 14 2026):
            - CausalMark exists: src/commonMain/kotlin/borg/trikeshed/context/lcnc/LcncSpineMarks.kt
              (@JvmInline value class, Byte ordinal, aligned 1:1 with CausalEdgeKind).
            - CausalKernel.kt has the full vocabulary (CausalEdgeKind sealed, EventPayload
              GADT, EventNode Join composition) but no production feed from the
              lcnc dispatch path.

            RED TEST FIRST:
            - commonTest: an LcncFanoutElement.dispatch that executes a reduction must
              append an EventNode with CausalMark.Dispatched whose payload resolves to
              the SignalFacetReduced emitted to KanbanFSM. Assert the edge exists in the
              graph; observe RED.

            IMPLEMENTATION SURFACE (disjoint ownership):
            - src/commonMain/kotlin/borg/trikeshed/causal/CausalKernel.kt (CausalMark
              bridge on the edge kinds — reuse CausalMark.from(kind), do not invent)
            - src/commonMain/kotlin/borg/trikeshed/context/lcnc/LcncFanoutElement.kt
              is OWNED BY THIS SESSION — do not edit; consume the SpineMark it already
              returns (dispatch returns MarkedResult as of this commit).

            PRELOAD CONTRACT (binding):
            - EventNode stays Join-composed: workId j (ordinal j (edge j payload)).
            - filter / % return Series; α projections; no List demotion.

            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed and pasted in the PR body before implementation
            [ ] dispatch-executed reductions append CausalMark-carrying EventNodes
            [ ] Gate green
            """.trimIndent()
        ),
        Triple(
            "spine-pointcut-pointcutmark-wiring",
            "Wire PointcutMark into polyglot pointcut observation (TDD-RED first)",
            """
            TARGET: The pointcutting capability of the spine markers (PointcutMark).

            CURRENT STATE (audit aec225abb, Aug 14 2026):
            - PointcutMark exists: src/commonMain/kotlin/borg/trikeshed/context/lcnc/LcncSpineMarks.kt
              (@JvmInline value class, Byte ordinal, aligned 1:1 with FieldSynapse TPL_*
              template indices).
            - SubgraalPointcutRunner (jvmMain pointcut/) already mints FieldSynapse
              records from ExecutionListener events; the marks never leave that file.
            - The compiled-out classfile/slab FacetedCursorContract.kt TODO() layer is
              OUT OF SCOPE — do not resurrect slab code.

            RED TEST FIRST:
            - jvmTest: a SubgraalPointcutRunner evaluation must expose its FieldSynapse
              stream with PointcutMark.fromTemplate(templateIdx) observable downstream
              (not sealed inside the runner). Assert mark visible; observe RED.

            RED TEST FIRST (contract): a polyglot eval must surface BeforeGet/AfterGet
            marks on the spine (PointcutMark column of the marked results Series).

            IMPLEMENTATION SURFACE (disjoint ownership):
            - src/jvmMain/kotlin/borg/trikeshed/pointcut/SubgraalPointcutRunner.kt
              (expose the synapse stream; do not route it into commonMain markers —
              the runner stays JVM)
            - Optionally a small commonMain PointcutMark consumer for the Series
              projection if needed by the test.

            PRELOAD CONTRACT (binding): Byte ordinals; α projections; no List demotion.
            GATE: ./gradlew jvmMainClasses --console=plain
            ACCEPTANCE:
            [ ] RED observed and pasted in the PR body before implementation
            [ ] FieldSynapse stream carries PointcutMark observable downstream
            [ ] Gate green
            """.trimIndent()
        ),
    )

    prongs.forEach { (workId, title, spec) ->
        val cause = JulesCause.WorkQueued(
            workId = workId,
            tier = "trikeshed",
            title = title,
            spec = spec,
            parent = null,
            score = 0.75,
            at = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        store.appendWork(workId, cause)
        println("Appended work: $workId")
    }
    println("Queue entry count: ${store.loadQueue().size}")
}
