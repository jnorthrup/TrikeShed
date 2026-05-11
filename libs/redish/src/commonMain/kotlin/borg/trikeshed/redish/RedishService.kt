package borg.trikeshed.redish

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

data class RedishEnvelope(
    val subreddit: String,
    val postId: String,
    val commentId: String? = null,
    val title: String,
    val body: String,
    val author: String? = null,
    val score: Int = 0,
    val tags: Map<String, String> = emptyMap(),
    val media: Series<RowVec> = emptySeries(),
)

data class RedishQuery(
    val subreddit: String,
    val listing: String = "hot",
    val after: String? = null,
    val limit: Int = 25,
)

data class RedishWriteReceipt(
    val subreddit: String,
    val postId: String,
    val commentId: String? = null,
    val accepted: Boolean,
    val revision: String? = null,
)

interface RedishClient {
    fun upsert(envelope: RedishEnvelope): RedishWriteReceipt
    fun query(query: RedishQuery): Series<RedishEnvelope>
}

class RedishService(
    private val client: RedishClient,
) {
    fun publish(envelope: RedishEnvelope): RedishWriteReceipt = client.upsert(envelope)

    fun sync(query: RedishQuery): Series<RedishEnvelope> = client.query(query)

    fun project(envelope: RedishEnvelope): DocRowVec =
        DocRowVec(
            keys = listOf("subreddit", "postId", "commentId", "title", "body", "author", "score", "tagCount"),
            cells = listOf(
                envelope.subreddit,
                envelope.postId,
                envelope.commentId,
                envelope.title,
                envelope.body,
                envelope.author,
                envelope.score,
                envelope.media.size,
            ),
            child = mediaRows(envelope.media),
        )

    private fun mediaRows(media: Series<RowVec>): Series<RowVec> = media
}
