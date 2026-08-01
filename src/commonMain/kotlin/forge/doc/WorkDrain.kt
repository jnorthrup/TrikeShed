package forge.doc

import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.utils.kanban.*

suspend fun drainReadmeDagReteRefraction(store: JulesBoardStore) {
    store.appendWork("readme-dag-rete-refraction", JulesCause.WorkDrained(
        workId = "readme-dag-rete-refraction",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
}

suspend fun drainSynth17160760388671804749(store: JulesBoardStore) {
    store.appendWork("synth:17160760388671804749", JulesCause.WorkDrained(
        workId = "synth:17160760388671804749",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = "synth:17160760388671804749",
            producer = "necromancer",
            producerRef = "necromanced",
            patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            revision = "superseded-by-review",
            versionTag = "superseded-by-review",
            lexicalMemory = LexicalMemory(
                summary = "Superseded necromanced work",
                title = "[rework #1] NUID/authorization algebra in commonMain",
                content = "Superseded via drain script."
            ),
            claimedAt = 0L,
            prUrl = null
        ),
        at = 0L
    ))
}

suspend fun drainMmapCasStoreRework(store: JulesBoardStore) {
    store.appendWork("rework:synth:965389205015589639#2", JulesCause.WorkDrained(
        workId = "rework:synth:965389205015589639#2",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
    store.appendWork("synth:965389205015589639", JulesCause.WorkDrained(
        workId = "synth:965389205015589639",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
}

suspend fun drainReactorAlgebraNecromancedWork(store: JulesBoardStore) {
    store.appendWork("synth:15340577469777603245", JulesCause.WorkDrained(
        workId = "synth:15340577469777603245",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = "synth:15340577469777603245",
            producer = "necromancer",
            producerRef = "necromanced",
            patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            revision = "superseded-by-review",
            versionTag = "superseded-by-review",
            lexicalMemory = LexicalMemory(
                summary = "Superseded necromanced work",
                title = "[rework #1] Reactor algebra in commonMain",
                content = "Superseded via drain script."
            ),
            claimedAt = 0L,
            prUrl = null
        ),
        at = 0L
    ))
}

suspend fun drainReactorAlgebra(store: JulesBoardStore) {
    store.appendWork("synth:15340577469777603245#2", JulesCause.WorkDrained(
        workId = "synth:15340577469777603245#2",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
}

suspend fun drainSynth3803389897151472172(store: JulesBoardStore) {
    val workId = "synth:3803389897151472172"
    store.appendWork(workId, JulesCause.WorkDrained(
        workId = workId,
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = workId,
            producer = "necromancer",
            producerRef = "necromanced",
            patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            revision = "superseded-by-review",
            versionTag = "superseded-by-review",
            lexicalMemory = LexicalMemory(
                summary = "Superseded necromanced work",
                title = "[rework #1] Wire DoubleSeries into query engine for primitive dispatch",
                content = "Superseded via drain script."
            ),
            claimedAt = 0L,
            prUrl = null
        ),
        at = 0L
    ))
}

suspend fun drainDoubleSeriesWire(store: JulesBoardStore) {
    store.appendWork("synth:2704096756101430624", JulesCause.WorkDrained(
        workId = "synth:2704096756101430624",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
}

suspend fun drainSupersededTask(store: JulesBoardStore) {
    val targetWorkId = "synth:17160760388671804749#2"
    store.appendWork(
        targetWorkId,
        JulesCause.WorkDrained(
            workId = targetWorkId,
            sessionId = "superseded",
            commitSha = "superseded",
            taskId = targetWorkId,
            receipt = MergeReceipt(
                workId = targetWorkId,
                producer = "jules",
                producerRef = "superseded",
                patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
                revision = "superseded",
                versionTag = "superseded",
                lexicalMemory = LexicalMemory(
                    summary = "superseded",
                    title = "superseded",
                    content = "superseded"
                ),
                claimedAt = 0L
            ),
            at = 0L
        )
    )
}

suspend fun drainWork(store: JulesBoardStore) {
    val workId = "synth:12224356407860756599#2"

    val receipt = MergeReceipt(
        workId = workId,
        producer = "jules",
        producerRef = "manual-override",
        patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
        revision = "e299ad5e47a8bcc74b6bdd026e8c920ffc483b2a",
        versionTag = "superseded",
        lexicalMemory = LexicalMemory("Browser mutations lower to JobCommand", "", ""),
        claimedAt = 0L // use any timestamp
    )

    store.appendWork(workId, JulesCause.WorkDrained(
        workId = workId,
        sessionId = "manual-override",
        commitSha = "superseded",
        taskId = "unknown",
        receipt = receipt,
        at = 0L
    ))
}

suspend fun drainReworkSynth3803389897151472172(store: JulesBoardStore) {
    store.appendWork("rework:synth:3803389897151472172#2", JulesCause.WorkDrained(
        workId = "rework:synth:3803389897151472172#2",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = "rework:synth:3803389897151472172#2",
            producer = "necromancer",
            producerRef = "necromanced",
            patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            revision = "superseded-by-review",
            versionTag = "superseded-by-review",
            lexicalMemory = LexicalMemory(
                summary = "Superseded necromanced work",
                title = "[rework #2] [rework #1] Wire DoubleSeries into query engine for primitive dispatch",
                content = "Superseded via drain script."
            ),
            claimedAt = 0L,
            prUrl = null
        ),
        at = 0L
    ))
}

suspend fun drainSynth9667103323583014411(store: JulesBoardStore) {
    store.appendWork("synth:9667103323583014411", JulesCause.WorkDrained(
        workId = "synth:9667103323583014411",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = "synth:9667103323583014411",
            producer = "necromancer",
            producerRef = "necromanced",
            patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            revision = "superseded-by-review",
            versionTag = "superseded-by-review",
            lexicalMemory = LexicalMemory(
                summary = "Superseded necromanced work",
                title = "[rework #1] Browser mutations lower to JobCommand through bounded ingress",
                content = "Superseded via drain script."
            ),
            claimedAt = 0L,
            prUrl = null
        ),
        at = 0L
    ))
}
