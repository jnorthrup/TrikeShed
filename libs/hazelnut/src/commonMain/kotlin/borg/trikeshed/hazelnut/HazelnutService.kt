package borg.trikeshed.hazelnut

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

private val hazelnutRowKeys = listOf("forum", "threadId", "commentId", "title", "body", "author", "attachmentCount")

data class HazelnutEnvelope(
    val forum: CharSequence,
    val threadId: CharSequence,
    val commentId: CharSequence,
    val title: CharSequence? = null,
    val body: CharSequence,
    val author: CharSequence? = null,
    val labels: Map<CharSequence, CharSequence> = emptyMap(),
    val attachments: Series<RowVec> = emptySeries(),
)

data class HazelnutQuery(
    val forum: CharSequence,
    val threadId: CharSequence? = null,
    val cursor: CharSequence? = null,
    val limit: Int = 25,
)

data class HazelnutWriteReceipt(
    val forum: CharSequence,
    val threadId: CharSequence,
    val commentId: CharSequence,
    val accepted: Boolean,
    val revision: CharSequence? = null,
)

interface HazelnutClient {
    fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt
    fun query(query: HazelnutQuery): Series<HazelnutEnvelope>
}

fun HazelnutEnvelope.toRowVec(): DocRowVec =
    DocRowVec(
        keys = hazelnutRowKeys,
        cells = listOf(
            forum,
            threadId,
            commentId,
            title,
            body,
            author,
            attachments.size,
        ),
        child = attachments,
    )

class HazelnutService(
    private val client: HazelnutClient,
) {
    fun publish(envelope: HazelnutEnvelope): HazelnutWriteReceipt = client.upsert(envelope)

    fun sync(query: HazelnutQuery): Series<HazelnutEnvelope> = client.query(query)

    fun project(envelope: HazelnutEnvelope): DocRowVec = envelope.toRowVec()
}
