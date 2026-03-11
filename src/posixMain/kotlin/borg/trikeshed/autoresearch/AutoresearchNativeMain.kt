package borg.trikeshed.autoresearch

import borg.trikeshed.common.Files
import kotlinx.datetime.Clock

fun autoresearchNativeMain(args: Array<String>) {
    val options = parseOptions(args)
    if (options.containsKey("help")) {
        printUsage()
        return
    }

    val stage = options["stage"] ?: AutoresearchStages.CONVERGENCE_4X4
    val theme = options["theme"] ?: AutoresearchThemes.M0_IDENTITY
    val task = AutoresearchTask.fromWireName(
        options["task"] ?: AutoresearchTasks.defaultTaskForTheme(theme).wireName,
    )
    val budget = (options["budget"] ?: "32").toInt()
    val seed = (options["seed"] ?: "7").toInt()
    val resultsLogPath = options["results-log"]
        ?: "${Files.cwd()}/build/autoresearch/kotlin_autoresearch_results.jsonl"
    val evidenceDirectory = options["evidence-dir"]
        ?: "${Files.cwd()}/build/autoresearch/evidence"
    val experimentId = options["experiment-id"]
        ?: "${stage}_${theme}_${task.wireName}_seed${seed}_b${budget}"
    val branch = options["branch"]
        ?: "exp/$stage/${task.wireName}/${Clock.System.now().toEpochMilliseconds()}"

    val config = AutoresearchRunConfig(
        stage = stage,
        theme = theme,
        fixedRunBudget = budget,
        seed = seed,
        resultsLogPath = resultsLogPath,
        evidenceDirectory = evidenceDirectory,
        experimentId = experimentId,
        branch = branch,
    )
    val result = AutoresearchHarness.runExperiment(task, config, MutableAutoresearchExperiment)
    println(result.toJsonLine())
}

private fun parseOptions(args: Array<String>): Map<String, String> {
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val current = args[index]
        when {
            current == "--help" -> {
                options["help"] = "true"
                index++
            }

            current.startsWith("--") && current.contains("=") -> {
                val key = current.substring(2, current.indexOf('='))
                val value = current.substring(current.indexOf('=') + 1)
                options[key] = value
                index++
            }

            current.startsWith("--") -> {
                require(index + 1 < args.size) { "Missing value for option $current" }
                options[current.substring(2)] = args[index + 1]
                index += 2
            }

            else -> error("Unknown positional argument: $current")
        }
    }
    return options
}

private fun printUsage() {
    println(
        """
        usage: autoresearchNativeMain [--stage convergence_4x4] [--theme M0_identity|M1_sine]
               [--task x_to_x|scalar_1x1_to_16x16|single_sine|mixed_sine|noisy_sine|piecewise_sine]
               [--budget 32] [--seed 7] [--results-log /path/results.jsonl]
               [--evidence-dir /path/evidence] [--experiment-id id] [--branch exp/...]
        """.trimIndent(),
    )
}
