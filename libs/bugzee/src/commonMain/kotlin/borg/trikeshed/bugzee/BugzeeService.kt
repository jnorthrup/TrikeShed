package borg.trikeshed.bugzee

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import kotlin.math.log10
import kotlin.math.max

enum class BugStatus {
    open,
    investigating,
    resolved,
    wontfix,
    duplicate,
    needinfo,
}

enum class FeedType {
    HOT,
    NEW,
    TOP,
    CONTROVERSIAL,
    RESOLVED,
}

enum class SubscriptionEvent {
    created,
    updated,
    resolved,
    commented,
    statusChanged,
}

enum class SubscriptionTransport {
    sctp,
    quic,
    htx,
    ipfs,
}

private val bugzeeRowKeys = listOf(
    "product", "bugId", "commentId", "summary", "description",
    "assignee", "severity", "attachmentCount",
    "upvotes", "downvotes", "score", "timestamp", "hotness",
    "status", "tags", "parentThreadId", "commentCount",
)

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
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val score: Double = 0.0,
    val timestamp: Long = 0L,
    val hotness: Double = 0.0,
    val status: BugStatus = BugStatus.open,
    val tags: List<CharSequence> = emptyList(),
    val parentThreadId: CharSequence? = null,
    val commentCount: Int = 0,
)

data class BugzeeComment(
    val id: CharSequence,
    val bugId: CharSequence,
    val author: CharSequence,
    val body: CharSequence,
    val timestamp: Long,
    val upvotes: Int = 0,
    val parentCommentId: CharSequence? = null,
    val depth: Int = 0,
    val metadata: Map<CharSequence, CharSequence> = emptyMap(),
)

data class BugzeeFeed(
    val type: FeedType,
    val query: CharSequence = "",
    val cursor: CharSequence? = null,
    val limit: Int = 25,
)

data class BugzeeSubscription(
    val topic: CharSequence,
    val user: CharSequence,
    val notifyOn: Set<SubscriptionEvent> = emptySet(),
    val transport: SubscriptionTransport = SubscriptionTransport.sctp,
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

private fun calculateHotness(upvotes: Int, downvotes: Int, timestamp: Long, now: Long): Double {
    val score = max(upvotes - downvotes, 1).toDouble()
    val order = log10(score)
    val age = max(now - timestamp, 0L)
    val timeFactor = if (upvotes >= downvotes) age else -age
    return order + (timeFactor / 45000.0)
}

private fun calculateHNStyleScore(upvotes: Int, downvotes: Int, timestamp: Long, now: Long, gravity: Double): Double {
    val votes = (upvotes - downvotes - 1).coerceAtLeast(0).toDouble()
    val ageHours = max(now - timestamp, 0L) / 3600.0
    return votes / kotlin.math.pow(ageHours + 2.0, gravity)
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
            upvotes,
            downvotes,
            score,
            timestamp,
            hotness,
            status,
            tags.joinToString("|"),
            parentThreadId,
            commentCount,
        ),
        child = attachments,
    )

class BugzeeService(
    private val client: BugzeeClient,
) {
    fun publish(envelope: BugzeeEnvelope): BugzeeWriteReceipt = client.upsert(envelope)

    fun sync(query: BugzeeQuery): Series<BugzeeEnvelope> = client.query(query)

    fun project(envelope: BugzeeEnvelope): DocRowVec = envelope.toRowVec()

    fun calculateHotness(envelope: BugzeeEnvelope, now: Long = 0L): Double =
        calculateHotness(envelope.upvotes, envelope.downvotes, envelope.timestamp, now)

    fun calculateScore(envelope: BugzeeEnvelope, now: Long = 0L, gravity: Double = 1.8): Double =
        calculateHNStyleScore(envelope.upvotes, envelope.downvotes, envelope.timestamp, now, gravity)
}
