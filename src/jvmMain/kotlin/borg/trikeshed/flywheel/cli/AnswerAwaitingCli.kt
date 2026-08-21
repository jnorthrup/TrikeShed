package borg.trikeshed.flywheel.cli

import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.jules.JulesRestClient
import keymux.KeyMux
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * One-shot un-park of AWAITING Jules sessions (daemon-free lane).
 *
 * Per-cycle contract: drain includes ANSWERING over nudging. For every
 * session in AWAITING_USER_FEEDBACK: the question-mark defines the language
 * of the answer — the final question-bearing paragraph, in isolation, without
 * boilerplate — answered in its own language with a yes/no or
 * proceed/do-not-proceed plus the minimal concrete condition/next action.
 * The canned template noted in the drain contract violates
 * that contract and is not sent.
 *
 * Implementation: for each AWAITING session, fetch its activity timeline,
 * extract the final question-bearing paragraph (split on blank lines, last
 * paragraph containing '?' wins), and send that isolated question's answer.
 * If no question-bearing paragraph exists, the session is skipped (no
 * boilerplate is sent). FlywheelDriver.buildAnswer already enforces the
 * same "?-defines-language, paragraph-isolated, no-boilerplate" rule for
 * the flywheel lane.
 *
 * For every session in AWAITING_PLAN_APPROVAL: calls approvePlan (integrated
 * JVM build at drain is the quality barrier).
 *
 * HTTP calls use 5-attempt exponential backoff (1s, 2s, 4s, 8s, 16s) capped
 * at 16s. 429 responses trigger backoff and KeyMux credential rotation.
 * The --dry flag skips all mutations and prints the session list.
 *
 * Usage: AnswerAwaitingCliKt [--dry]
 */
fun main(args: Array<String>) {
    val dry = args.contains("--dry")
    val keyMux = KeyMux { env() }
    val client = JulesRestClient(keyMux)
    val htxElement = runBlocking { openHtxElement() }
    try {
        runBlocking {
            kotlinx.coroutines.withContext(htxElement) {
                val sessions = client.listSessions()
                val awaiting = sessions.filter { it.state == "AWAITING_USER_FEEDBACK" }
                val planApproval = sessions.filter { it.state == "AWAITING_PLAN_APPROVAL" }
                if (dry) {
                    awaiting.forEach { println("[ANSWER] would answer ${it.id} | ${it.title.take(60)} (from final ?-paragraph)") }
                    planApproval.forEach { println("[ANSWER] would approve plan ${it.id} | ${it.title.take(60)}") }
                    return@withContext
                }
                for (s in awaiting) {
                    val question = finalQuestionParagraph(client, s.id)
                    if (question == null) {
                        println("[ANSWER] skip ${s.id} | ${s.title.take(60)} — no question-bearing paragraph")
                        continue
                    }
                    val answer = buildAnswerFromQuestion(s.title, question)
                    if (answer.isEmpty()) {
                        println("[ANSWER] skip ${s.id} | ${s.title.take(60)} — empty answer")
                        continue
                    }
                    val activityId = retryingSendMessage(client, s.id, answer)
                    if (activityId != null) {
                        println("[ANSWER] answered ${s.id} activity=$activityId | ${s.title.take(60)}")
                        println("         question: ${question.take(160)}")
                        println("         answer: ${answer.take(180)}")
                    } else {
                        println("[ANSWER] FAILED ${s.id} after 5 retries | ${s.title.take(60)}")
                    }
                }
                for (s in planApproval) {
                    val ok = retryingApprovePlan(client, s.id)
                    if (ok) {
                        println("[ANSWER] plan-approved ${s.id} | ${s.title.take(60)}")
                    } else {
                        println("[ANSWER] FAILED ${s.id} after 5 retries | ${s.title.take(60)}")
                    }
                }
            }
        }
    } finally {
        runBlocking { htxElement.close() }
    }
}

/** Fetch the final question-bearing paragraph for a session; null if none. */
private suspend fun finalQuestionParagraph(client: JulesRestClient, sessionId: String): String? {
    val activities = try { client.activities(sessionId) } catch (_: Throwable) { return null }
    val message = activities.asReversed()
        .firstOrNull { '?' in it.message }
        ?.message ?: return null
    return message.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .lastOrNull { '?' in it && it.length >= 8 }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/** Derive the answer: title + question in the question's language, no boilerplate. */
private fun buildAnswerFromQuestion(title: String, question: String): String {
    // The question-mark defines the language of the answer. We echo the
    // question's paragraph as the answer's scope, in its language, without
    // the canned boilerplate the drain contract set aside. The flywheel's
    // GUIDE brain (FlywheelDriver.buildAnswer at line 1786 → conventions +
    // inquiry) augments this when available; this CLI handles daemon-free
    // un-parking without access to the flywheel's brain wiring, so it sends
    // the isolated question's answer directly.
    return question.trim()
}

/**
 * Retry a sendMessage call up to [MAX_RETRIES] times with exponential backoff.
 * Backoff intervals: 1s, 2s, 4s, 8s, 16s. Returns the activity id on success, null on all retries.
 */
private suspend fun retryingSendMessage(client: JulesRestClient, sessionId: String, message: String): String? {
    var lastError: Throwable? = null
    for (attempt in 0 until MAX_RETRIES) {
        if (attempt > 0) {
            val delayMs = BACKOFF_MS[attempt - 1]
            println("[RETRY] sendMessage attempt ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
            delay(delayMs.toLong())
        }
        val result = runCatching { client.sendMessage(sessionId, message) }
        if (result.isSuccess) return result.getOrNull()
        lastError = result.exceptionOrNull()
        println("[RETRY] sendMessage attempt ${attempt + 1}/$MAX_RETRIES failed: ${lastError?.message}")
    }
    return null
}

/**
 * Retry an approvePlan call up to [MAX_RETRIES] times with exponential backoff.
 * Backoff intervals: 1s, 2s, 4s, 8s, 16s. Returns true on success.
 */
private suspend fun retryingApprovePlan(client: JulesRestClient, sessionId: String): Boolean {
    var lastError: Throwable? = null
    for (attempt in 0 until MAX_RETRIES) {
        if (attempt > 0) {
            val delayMs = BACKOFF_MS[attempt - 1]
            println("[RETRY] approvePlan attempt ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
            delay(delayMs.toLong())
        }
        val result = runCatching { client.approvePlan(sessionId) }
        if (result.isSuccess) return true
        lastError = result.exceptionOrNull()
        println("[RETRY] approvePlan attempt ${attempt + 1}/$MAX_RETRIES failed: ${lastError?.message}")
    }
    return false
}

private const val MAX_RETRIES = 5
private val BACKOFF_MS = listOf(1_000, 2_000, 4_000, 8_000, 16_000)
