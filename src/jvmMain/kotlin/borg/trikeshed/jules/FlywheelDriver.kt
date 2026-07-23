/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant

/**
 * Flywheel driver — the actual loop that turns the wheel.
 *
 * Every cycle:
 * 1. POLL Jules sessions via [JulesConductor.pollOnce]
 * 2. ANSWER every AWAITING_USER_FEEDBACK session through the [BrainClient]
 *    brain with project conventions — the GUIDE role fires a real model.
 * 3. HARVEST every COMPLETED session with a patch: git apply, jvmTest, land
 * 4. DISPATCH unchecked items from doc/todo.md until slots are full
 *
 * Run with:
 *   ./gradlew jvmRun -PmainClass=borg.trikeshed.jules.FlywheelDriver
 */
class FlywheelDriver(
    private val apiKey: String,
    private val repoDir: File = File(System.getProperty("user.dir")),
    private val forgeDir: File = File(System.getProperty("user.home"), ".local/forge"),
    private val intervalMs: Long = 60_000L,
    private val maxSlots: Int = 15,
    private val source: String = "sources/github/jnorthrup/TrikeShed",
) {
    private val client = JulesRestClient(apiKey)
    private val brain: BrainClient? = System.getenv("NVIDIA_API_KEY")?.let { BrainClient(it) }
    private val store = JulesBoardStore(forgeDir)
    private val conductor = JulesConductor(
        client = client,
        headShaProvider = { headSha() },
        store = store,
    )

    /** One cycle: poll → answer → harvest → dispatch → persist */
    suspend fun cycle(): CycleReport {
        // 1. POLL
        val before = conductor.cards.size
        conductor.pollOnce()
        val after = conductor.cards.size

        // 2. ANSWER every AWAITING_USER_FEEDBACK session
        var answered = 0
        val awaiting = conductor.cards.values.filter { it.snapshot.state == "AWAITING_USER_FEEDBACK" }
        for (card in awaiting) {
            val answer = buildAnswer(card)
            if (answer.isNotEmpty()) {
                conductor.answer(card.snapshot.sessionId, answer)
                answered++
                println("[FLYWHEEL] ANSWER ${card.snapshot.sessionId.takeLast(6)} ${card.card.title.take(60)}")
            }
        }

        // 3. HARVEST every COMPLETED session with a patch
        var harvested = 0
        val completed = conductor.cards.values.filter { it.snapshot.state == "COMPLETED" && !it.drained }
        for (card in completed) {
            val sid = card.snapshot.sessionId
            val patch = client.lastPatch(sid)
            if (patch != null && patch.isNotEmpty()) {
                val sha = applyAndTest(patch, card.card.title)
                if (sha != null) {
                    conductor.recordDrain(sid, sha, 0)
                    harvested++
                    println("[FLYWHEEL] LAND ${sid.takeLast(6)} sha=${sha.take(9)} ${card.card.title.take(50)}")
                } else {
                    conductor.recordDrain(sid, "gate-red-${System.currentTimeMillis()}", 1)
                    println("[FLYWHEEL] GATE-RED ${sid.takeLast(6)} ${card.card.title.take(60)}")
                }
            }
        }

        // 4. DISPATCH from doc/todo.md
        var dispatched = 0
        val alive = conductor.cards.values.count { it.snapshot.state != "COMPLETED" && it.snapshot.state != "FINISHED" }
        val available = maxSlots - alive
        if (available > 0) {
            val todo = File(repoDir, "doc/todo.md")
            if (todo.exists()) {
                val items = todo.readLines().filter { it.matches(Regex("^\\s*- \\[ \\].*")) }
                val existing = conductor.cards.values.map { it.card.title }.toSet()
                for (item in items.take(available)) {
                    val title = item.replace(Regex("^\\s*- \\[ \\]\\s*\\*\\*?|\\*\\*?$"), "").trim()
                    if (title.isNotEmpty() && title !in existing) {
                        val spec = buildSpec(title)
                        try {
                            client.createSession(prompt = spec, title = title, source = source)
                            dispatched++
                            println("[FLYWHEEL] DISPATCH ${title.take(60)}")
                        } catch (t: Throwable) {
                            println("[FLYWHEEL] FAIL $title: ${t.message}")
                        }
                    }
                }
            }
        }

        return CycleReport(answered, harvested, dispatched, alive)
    }

    /** Project the unified Forge×Jules board and render the saturation wheel. */
    fun renderSaturation(): String {
        val kanban = try { ForgeKanbanIngest.load("jim").board }
        catch (_: Throwable) { borg.trikeshed.kanban.KanbanBoard(
            id = borg.trikeshed.kanban.KanbanBoardId("flywheel"),
            name = "flywheel",
            columns = JulesLane.values().map { borg.trikeshed.kanban.KanbanColumn(
                borg.trikeshed.kanban.KanbanColumnId(it.columnName), it.columnName, it.order) },
            cards = emptyList(),
        ) }
        val unified = unifyBoard(kanban, conductor.cards.values)
        val aliveCount = conductor.cards.values.count {
            it.snapshot.state != "COMPLETED" && it.snapshot.state != "FINISHED" }
        return renderWheel(unified, aliveCount, maxSlots, intervalMs)
    }

    /** Build a project-conventions answer for an AWAITING session inquiry.
     *  Fires the [brain] (BrainClient → NVIDIA NIM Laguna XS 2.1) with
     *  conventions as the system message and the inquiry as the user message.
     *  Returns "" if no brain is configured (NVIDIA_API_KEY missing) — the
     *  caller skips the answer; never sends a template. */
    private fun buildAnswer(card: JulesSessionCard): String {
        val title = card.card.title
        val lastCause = card.causes.lastOrNull()
        val lastAct = client.activities(card.snapshot.sessionId).lastOrNull()
        val inquiry = lastAct?.excerpt?.take(400) ?: lastCause?.let { when (it) {
            is JulesCause.AgentMessaged -> it.excerpt.take(400)
            else -> null
        } } ?: return ""

        val conventions = buildString {
            appendLine("You are the GUIDE for the TrikeShed project (KMP, AGPLv3 2017).")
            appendLine("Answer coding-agent questions with concrete, decisive guidance (<200 words).")
            appendLine("Project conventions:")
            appendLine("  - domain logic goes in commonMain/kotlin/; platform adapters in jvmMain/jsMain/nativeMain")
            appendLine("  - use Series<T> over List<T> for read-only indexed data")
            appendLine("  - use Confix JSON (borg.trikeshed.parse.json.JsonSupport), not kotlinx-serialization-json")
            appendLine("  - test gate: ./gradlew jvmTest --no-daemon")
            appendLine("  - TDD: write failing test first, then one minimal implementation file")
            appendLine("  - one test file + one implementation file per task")
            appendLine("  - never use the word 'notion' in code, comments, or identifiers (trademark)")
            appendLine("  - never delete a working runner to replace with not-yet-built code")
        }

        val b = brain
        if (b == null) {
            println("[FLYWHEEL] WARN no NVIDIA_API_KEY — GUIDE offline, skipping ${card.snapshot.sessionId.takeLast(6)}")
            return ""
        }
        return try {
            b.chat(
                messages = listOf(
                    "system" to conventions,
                    "user" to "Task title: $title\n\nInquiry from the coding agent:\n$inquiry",
                ),
                maxTokens = 400,
                temperature = 0.2,
            )
        } catch (t: Throwable) {
            println("[FLYWHEEL] BRAIN-ERROR ${card.snapshot.sessionId.takeLast(6)}: ${t.message}")
            ""
        }
    }

    /** Build a dispatch spec from a todo title. */
    private fun buildSpec(title: String): String = """
        Write failing tests for $title.
        - TDD: one test file in the correct location
        - one minimal implementation file
        - gate: ./gradlew jvmTest --no-daemon
    """.trimIndent()

    /** Apply a patch locally, run jvmTest, commit on green. Returns commit SHA or null. */
    private fun applyAndTest(patch: String, title: String): String? {
        try {
            // Write patch file
            val patchFile = File(repoDir, ".flywheel-patch")
            patchFile.writeText(patch)

            // Check if can apply
            val apply = ProcessBuilder("git", "apply", "--check", ".flywheel-patch")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor() }
            if (apply.exitValue() != 0) {
                patchFile.delete()
                return null
            }

            // Apply
            ProcessBuilder("git", "apply", ".flywheel-patch")
                .directory(repoDir).start().waitFor()
            patchFile.delete()

            // Run jvmTest
            val test = ProcessBuilder("./gradlew", "jvmTest", "--no-daemon")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor() }
            if (test.exitValue() != 0) {
                ProcessBuilder("git", "checkout", ".")
                    .directory(repoDir).start().waitFor()
                return null
            }

            // Commit
            val add = ProcessBuilder("git", "add", "-A")
                .directory(repoDir).start().also { it.waitFor() }
            val commit = ProcessBuilder("git", "commit", "-m", "flywheel: $title")
                .directory(repoDir).start().also { it.waitFor() }
            if (commit.exitValue() != 0) return null

            return headSha()
        } catch (_: Exception) {
            ProcessBuilder("git", "checkout", ".").directory(repoDir).start().waitFor()
            return null
        }
    }

    private fun headSha(): String {
        val proc = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoDir).redirectErrorStream(true).start()
        return proc.inputStream.bufferedReader().readText().trim()
    }

    data class CycleReport(val answered: Int, val harvested: Int, val dispatched: Int, val alive: Int)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val apiKey = System.getenv("JULES_API_KEY") ?: error("JULES_API_KEY required")
            val driver = FlywheelDriver(apiKey)
            println("[FLYWHEEL] Starting driver on ${driver.repoDir}")
            runBlocking {
                while (true) {
                    val start = System.currentTimeMillis()
                    val report = driver.cycle()
                    println(driver.renderSaturation())
                    println("[FLYWHEEL] Cycle: answered=${report.answered} harvested=${report.harvested} dispatched=${report.dispatched} alive=${report.alive}")
                    val elapsed = System.currentTimeMillis() - start
                    val delay = (driver.intervalMs - elapsed).coerceAtLeast(5_000)
                    delay(delay)
                }
            }
        }
    }
}