@file:JvmName("RunHeadlessKt")
package borg.trikeshed.forge.ui

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanColumnId
import java.io.File

/**
 * Headless entry point — runs all walkthrough usecases and produces MP4s.
 *
 * Usecases:
 *   1. teach-pendant-robot  — robot arm pipeline (calibrate → weld)
 *   2. ai-kanban-benefits   — AI agent benefit showcase (backlog grooming, auto-move)
 *   3. kanban-replay        — records a sequence, then replays it to show recording/replay
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "${System.getProperty("user.home")}/Movies/forge-walkthroughs")
    outDir.mkdirs()

    println("Output directory: ${outDir.absolutePath}")
    println()

    val usecases = listOf(
        Pair("1_teach_pendant_robot.mp4",  teachPendantRobotScript()),
        Pair("2_ai_kanban_benefits.mp4",   aiKanbanBenefitsScript()),
        Pair("3_recording_replay_demo.mp4", recordingReplayDemoScript()),
    )

    for ((filename, script) in usecases) {
        val outFile = File(outDir, filename)
        println("▶  Running: ${script.title}")
        println("   → ${outFile.absolutePath}")
        val result = HeadlessWalkthroughRunner.run(
            script     = script,
            outputFile = outFile,
            verbose    = true,
        )
        println()
        println("   ✓ ${result.totalFrames} frames · ${result.durationMs}ms → ${outFile.name}")
        println()
    }

    val widgetMovies = widgetShowcasePages().mapIndexed { index, page ->
        File(outDir, "${index + 4}_${page.id}.mp4") to page
    }
    for ((outFile, page) in widgetMovies) {
        println("▶  Rendering widget showcase: ${page.title}")
        println("   → ${outFile.absolutePath}")
        val result = WidgetShowcaseMovieRunner.run(page = page, outputFile = outFile, verbose = true)
        println()
        println("   ✓ ${result.totalFrames} frames · ${result.durationMs}ms → ${outFile.name}")
        println()
    }

    println("All usecases complete.  Open: ${outDir.absolutePath}")
}

// ─── Usecase 2: AI Kanban benefits ───────────────────────────────────────────

fun aiKanbanBenefitsScript(): WalkthroughScript {
    val backlog = KanbanColumnId("col-backlog")
    val wip     = KanbanColumnId("col-inprogress")
    val review  = KanbanColumnId("col-review")
    val done    = KanbanColumnId("col-done")

    return WalkthroughScript(
        id          = WalkthroughScriptId("ai-kanban-benefits-v1"),
        title       = "Forge Kanban — AI Agent Benefits",
        description = "Shows how AI agents auto-populate backlog, advance tasks, and enforce WIP limits",
        targetFps   = 30,
        steps = listOf(
            WalkthroughStep("b1", "Start: empty board — the AI agent is about to groom the backlog",
                delayMs = 500, holdMs = 1500,
                action = WalkthroughAction.LoadDefaultBoard),

            WalkthroughStep("b2", "AI agent analyses the project — adding high-priority tasks",
                delayMs = 800, holdMs = 1200,
                action = WalkthroughAction.CreateCard("Design system prompt", backlog, CardPriority.HIGH, "agent-01")),
            WalkthroughStep("b3", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.CreateCard("Write eval harness", backlog, CardPriority.HIGH, "agent-01")),
            WalkthroughStep("b4", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.CreateCard("Integrate tool calls", backlog, CardPriority.MEDIUM, "agent-02")),
            WalkthroughStep("b5", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.CreateCard("Add streaming output", backlog, CardPriority.LOW, "agent-02")),
            WalkthroughStep("b6", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.CreateCard("Benchmark latency", backlog, CardPriority.MEDIUM, "agent-03")),

            WalkthroughStep("b7", "AI agent picks highest priority — auto-advances to In Progress",
                delayMs = 1000, holdMs = 1200,
                action = WalkthroughAction.MoveCardByTitle("Design system prompt", wip)),
            WalkthroughStep("b8", "Second task in parallel (WIP limit = 3)",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Write eval harness", wip)),

            WalkthroughStep("b9", "First task complete — agent moves to Review automatically",
                delayMs = 1200, holdMs = 1000,
                action = WalkthroughAction.MoveCardByTitle("Design system prompt", review)),
            WalkthroughStep("b10", "Review passes — task Done",
                delayMs = 800, holdMs = 1400,
                action = WalkthroughAction.MoveCardByTitle("Design system prompt", done)),

            WalkthroughStep("b11", "Agent pulls next from backlog (WIP slot freed)",
                delayMs = 600, holdMs = 1000,
                action = WalkthroughAction.MoveCardByTitle("Integrate tool calls", wip)),
            WalkthroughStep("b12", "Eval harness done → review",
                delayMs = 600, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Write eval harness", review)),
            WalkthroughStep("b13", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Write eval harness", done)),

            WalkthroughStep("b14", "AI-driven Kanban: zero manual coordination — agent owns the board",
                delayMs = 600, holdMs = 3000,
                action = WalkthroughAction.Narrate("Benefit: agents groom, advance, and close tasks autonomously")),
        ),
    )
}

// ─── Usecase 3: Recording & replay demo ──────────────────────────────────────

fun recordingReplayDemoScript(): WalkthroughScript {
    val backlog = KanbanColumnId("col-backlog")
    val wip     = KanbanColumnId("col-inprogress")
    val review  = KanbanColumnId("col-review")
    val done    = KanbanColumnId("col-done")

    return WalkthroughScript(
        id          = WalkthroughScriptId("recording-replay-demo-v1"),
        title       = "Forge Kanban — Recording & Replay Demonstration",
        description = "Shows the teach-pendant record-then-replay loop: record operator actions, replay headlessly, export video",
        targetFps   = 30,
        steps = listOf(
            WalkthroughStep("r1", "RECORD PHASE: Operator loads a fresh board",
                delayMs = 500, holdMs = 2000,
                action = WalkthroughAction.LoadDefaultBoard),

            WalkthroughStep("r2", "Operator creates the first task manually",
                delayMs = 800, holdMs = 1200,
                action = WalkthroughAction.CreateCard("Define acceptance criteria", backlog, CardPriority.HIGH, "operator")),
            WalkthroughStep("r3", "",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.CreateCard("Implement feature X", backlog, CardPriority.CRITICAL, "operator")),
            WalkthroughStep("r4", "",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.CreateCard("Write regression tests", backlog, CardPriority.HIGH, "operator")),

            WalkthroughStep("r5", "Operator drags task to In Progress (teach-pendant gesture captured)",
                delayMs = 1000, holdMs = 1500,
                action = WalkthroughAction.MoveCardByTitle("Define acceptance criteria", wip)),

            WalkthroughStep("r6", "REPLAY PHASE: Script plays back without operator",
                delayMs = 1500, holdMs = 2000,
                action = WalkthroughAction.Narrate("REPLAY: exact sequence reproduced from recorded script")),

            WalkthroughStep("r7", "Replay: criteria task advances to Review",
                delayMs = 800, holdMs = 1200,
                action = WalkthroughAction.MoveCardByTitle("Define acceptance criteria", review)),
            WalkthroughStep("r8", "Replay: implementation task starts",
                delayMs = 600, holdMs = 1000,
                action = WalkthroughAction.MoveCardByTitle("Implement feature X", wip)),
            WalkthroughStep("r9", "Replay: criteria approved — Done",
                delayMs = 600, holdMs = 1000,
                action = WalkthroughAction.MoveCardByTitle("Define acceptance criteria", done)),
            WalkthroughStep("r10", "Replay: tests start in parallel",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Write regression tests", wip)),
            WalkthroughStep("r11", "Replay: implementation → Review",
                delayMs = 600, holdMs = 1000,
                action = WalkthroughAction.MoveCardByTitle("Implement feature X", review)),
            WalkthroughStep("r12", "Replay: all tasks close",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Implement feature X", done)),
            WalkthroughStep("r13", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Write regression tests", review)),
            WalkthroughStep("r14", "",
                delayMs = 300, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle("Write regression tests", done)),

            WalkthroughStep("r15",
                "VIDEO EXPORTED — this file IS the replay artefact",
                delayMs = 800, holdMs = 3000,
                action = WalkthroughAction.Narrate("Recording exported via ffmpeg. Sharable. Auditable. Reproducible.")),
        ),
    )
}
