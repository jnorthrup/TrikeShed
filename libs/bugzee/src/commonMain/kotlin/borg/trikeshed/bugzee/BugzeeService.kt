package borg.trikeshed.bugzee

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

private val bugzeeRowKeys = listOf("product", "bugId", "commentId", "summary", "description", "assignee", "severity", "attachmentCount")

data class BugzeeEnvelope(
    val product: CharSequence,
    val bugId: CharSequence,
    val commentId: CharSequence? = null,
    val summary: CharSequence,
    val description: CharSequence,
    val assignee: CharSequence? = null,
    val severity: Int = 0,
    val metadata: Map<CharSequence, CharSequence> = emptyMap(),
    val attachments: Series<RowVec> = emptySeries(),
)

data class BugzeeQuery(
    val product: CharSequence,
    val listing: CharSequence = "open",
    val after: CharSequence? = null,
    val limit: Int = 25,
)

data class BugzeeWriteReceipt(
    val product: CharSequence,
    val bugId: CharSequence,
    val commentId: CharSequence? = null,
    val accepted: Boolean,
    val revision: CharSequence? = null,
)

interface BugzeeClient {
    fun upsert(envelope: BugzeeEnvelope): BugzeeWriteReceipt
    fun query(query: BugzeeQuery): Series<BugzeeEnvelope>
}

fun BugzeeEnvelope.toRowVec(): DocRowVec =
    DocRowVec(
        keys = bugzeeRowKeys,
        cells = listOf(
            product,
            bugId,
            commentId,
            summary,
            description,
            assignee,
            severity,
            attachments.size,
        ),
        child = attachments,
    )

class BugzeeService(
    private val client: BugzeeClient,
) {
    fun publish(envelope: BugzeeEnvelope): BugzeeWriteReceipt = client.upsert(envelope)

    fun sync(query: BugzeeQuery): Series<BugzeeEnvelope> = client.query(query)

    fun project(envelope: BugzeeEnvelope): DocRowVec = envelope.toRowVec()
}
