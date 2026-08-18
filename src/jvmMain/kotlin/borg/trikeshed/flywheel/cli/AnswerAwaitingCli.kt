package borg.trikeshed.flywheel.cli

import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.jules.JulesRestClient
import keymux.KeyMux
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
                    runCatching {
                        val activityId = client.sendMessage(s.id, "Please proceed with the implementation")
                        println("[ANSWER] answered ${s.id} activity=$activityId | ${s.title.take(60)}")
                    }.onFailure { println("[ANSWER] FAILED ${s.id}: ${it.message}") }
                }
                for (s in planApproval) {
                    runCatching {
                        client.approvePlan(s.id)
                        println("[ANSWER] plan-approved ${s.id} | ${s.title.take(60)}")
                    }.onFailure { println("[ANSWER] FAILED ${s.id}: ${it.message}") }
                }
            }
        }
    } finally {
        runBlocking { htxElement.close() }
    }
}
