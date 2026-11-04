package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.EnglishObservationGraph

/** Physical source currentness, never parser execution authentication or review authority. */
internal object EnglishObservationCurrent {
    suspend fun check(corpus: ProjectCorpus, snapshots: Series<EnglishObservationGraph.Snapshot>, mode: String) {
        require(mode in _s["snapshot", "current"]) { "Explicit English source mode required" }
        if (mode == "snapshot") return
        for (snapshot in snapshots.view) {
            val rows = requireNotNull(snapshot.sourceSnapshots) { "Historical receipt lacks complete source snapshots; use explicit snapshot mode" }
            for (expected in rows) {
                val row = corpus.document(expected.project, expected.id)
                require(row != null && row.cid == expected.cid && row.rev == expected.rev && row.seq == expected.seq && row.length == expected.length) {
                    "English observation source snapshot is stale or unavailable"
                }
            }
        }
    }
}
