package borg.trikeshed.flywheel.cli

import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.jules.JulesRestClient
import keymux.KeyMux
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * One-shot un-park of stalled Jules sessions (daemon-free lane).
 *
 * For every session in AWAITING_USER_FEEDBACK: sends the drain-contract
 * answer ("Please proceed with the implementation"). For every session in
 * AWAITING_PLAN_APPROVAL: calls approvePlan. The integrated JVM build at
 * drain time is the quality barrier — plan review is not a second gate
 * (same posture as JulesConductor.approvePlan).
 *
 * HTTP calls use 5-attempt exponential backoff (1s, 2s, 4s, 8s, 16s) capped
 * at 16s intervals. 429 responses trigger backoff and KeyMux credential rotation.
 * The --dry flag skips all mutations and prints the session list only.
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
                println("[ANSWER] total=${sessions.size} awaitingFeedback=${awaiting.size} awaitingPlan=${planApproval.size}")
                if (dry) {
                    awaiting.forEach { println("[ANSWER] would answer ${it.id} | ${it.title.take(60)}") }
                    planApproval.forEach { println("[ANSWER] would approve plan ${it.id} | ${it.title.take(60)}") }
                    return@withContext
                }
                for (s in awaiting) {
                    val activityId = retryingSendMessage(client, s.id, "Please proceed with the implementation")
                    if (activityId != null) {
                        println("[ANSWER] answered ${s.id} activity=$activityId | ${s.title.take(60)}")
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
