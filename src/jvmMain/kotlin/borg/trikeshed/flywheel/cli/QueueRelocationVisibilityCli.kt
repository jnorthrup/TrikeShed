package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import java.io.File
import kotlinx.coroutines.runBlocking

/** Queue relocation-visibility RED (57Way :82) as a durable Jules cut. */
fun main() = runBlocking {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, "jules-board.wal")))
    val workId = "cas:funnel-residual-relocation-visibility:20260814"
    val spec = """
        Turn the committed RED at FunnelResidualMerge57WayEvidenceTest.kt:82 green
        (receipt.relocatedCount >= 1) by making master-line relocation VISIBLE in
        the residual pipeline.

        Current behavior (measured): sources S14..S20 remove master line L30 and
        reinsert it at ordinal 5. Every copy of L30 HITS the master funnel (it IS
        master content), so residualsOf drops it and relocation is invisible —
        relocatedCount = 0 despite 7 genuine relocations.

        Root cause: residualsOf (FunnelResidualMerge.kt) treats any funnel hit as
        inherited regardless of stamp change. A relocated line has a DIFFERENT
        NeighborStamp than its master copy (both neighbors changed) — the stamp
        diff is the relocation signal the grading already knows about but never
        receives, because the atoms are filtered out before topologyOf.

        Required production cut:
        1. residualsOf must emit an atom for a funnel-hit line when its
           NeighborStamp differs from the master copy's stamp. Atom shape stays
           unchanged (mini64 / neighborPrefix / sourceIdx / ordinal / contentCid).
           Visibility requires the master copy's stamp: add a frozen
           master-stamp lookup (contentCid.hex -> stamp hex or packed prefix)
           built in the same pass as buildMasterFunnel — same frozen-baseline
           discipline as the funnel.
        2. Preserve the INHERITED-unreachability theorem: the strict-INHERITED
           arm of gradeClusters must stay unreachable via merge(). Grade
           stamp-changed hits RELOCATED (preferred: stamp change on master
           content IS relocation) or add a distinct grade; update the
           ClusterGrade doc block either way.
        3. Keep merge() cost O(|union residual|): the master-stamp lookup is a
           frozen per-master map built once; no per-source full-tree walks.
        4. Receipt counts stay honest: relocatedCount counts distinct clusters
           whose stamps differ from master's stamp (not per-copy);
           kept.size == novelCount + relocatedCount must hold.
        5. Focused tests, RED first:
           - relocated master line visible: relocatedCount >= 1 for the
             S14..S20 shape, kept.size == novel + relocated.
           - pure insert still NOVEL; duplicate insert still INHERITED_CROSS;
             master-identical source yields empty residuals (no noise).
           - theorem check: inheritedCount == 0 still holds via merge().
        6. Re-measure the 57Way MEASURED header after landing.

        Files expected:
        src/commonMain/kotlin/borg/trikeshed/cas/FunnelResidualMerge.kt
        src/jvmTest/kotlin/borg/trikeshed/cas/FunnelResidualMerge57WayEvidenceTest.kt

        Acceptance:
        ./gradlew jvmTest --tests 'borg.trikeshed.cas.FunnelResidualMerge57WayEvidenceTest' --console=plain
        ./gradlew jvmMainClasses --console=plain
        """.trimIndent()
    store.appendWork(
        workId,
        JulesCause.WorkQueued(
            workId = workId,
            tier = "forge",
            title = "Make master-line relocation visible in funnel residual merge (57Way RED :82)",
            spec = spec,
            parent = "gap:funnel-residual-relocation-visibility",
            score = 0.95,
            at = System.currentTimeMillis(),
        ),
    )
    println("[SEED] queued $workId")
}
