package borg.trikeshed.hazelnut

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

private val hazelnutRowKeys = listOf("forum", "threadId", "commentId", "title", "body", "author", "attachmentCount")

data class HazelnutEnvelope(
    val forum: String,
    val threadId: String,
    val commentId: String,
    val title: String? = null,
    val body: String,
    val author: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val attachments: Series<RowVec> = emptySeries(),
)

data class HazelnutQuery(
    val forum: String,
    val threadId: String? = null,
    val cursor: String? = null,
    val limit: Int = 25,
)

data class HazelnutWriteReceipt(
    val forum: String,
    val threadId: String,
    val commentId: String,
    val accepted: Boolean,
    val revision: String? = null,
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
