package borg.trikeshed.util.io

/**
 * Shared CLI-flag parsing primitives for Forge entrypoints.
 *
 * Every server entrypoint in the project (daemon, litebike kanban server,
 * oroboros main) hand-rolls the same `--key <value>` / `--flag` /
 * `-h/--help` loop. The pieces below are commonMain so they can be reused
 * by the daemon, the litebike server, and any future KMP target.
 *
 * Concrete CLIs still own their own flag inventory and usage string — this
 * is intentionally not a generic argparse; it just removes the duplication
 * of `args.iterator()` boilerplate, `die()` exit paths, and `--help`
 * branching.
 */
object ForgeCliArgs {

    /**
     * One recognized flag in a CLI's option table.
     *
     * @param withValue true if the flag takes a following arg (`--port 8888`).
     * @param action handler invoked with the remaining args list starting at
     *   the index AFTER the flag (or at the flag itself for boolean flags).
     *   The handler must return the next index to resume parsing from.
     */
    data class Flag(
        val name: String,
        val aliases: Set<String> = emptySet(),
        val withValue: Boolean = false,
        val action: (args: List<String>, startIndex: Int) -> Int,
    ) {
        fun matches(token: String): Boolean =
            token == name || token in aliases
    }

    /** Result of a [parse] call. */
    sealed class Result {
        data class Parsed(val remaining: List<String>) : Result()
        data object Help : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Walk [args] once, dispatching each recognized flag to its action.
     * Unknown flags and missing values become [Result.Error]; `-h` / `--help`
     * becomes [Result.Help].
     *
     * The list returned in [Result.Parsed.remaining] is the positional tail
     * (everything that wasn't a flag or flag-value).
     */
    fun parse(
        args: List<String>,
        flags: List<Flag>,
        helpAliases: Set<String> = setOf("-h", "--help"),
    ): Result {
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            if (token in helpAliases) return Result.Help
            val flag = flags.firstOrNull { it.matches(token) }
            if (flag == null) {
                positional.add(token)
                i++
                continue
            }
            i = if (flag.withValue) {
                if (i + 1 >= args.size) {
                    return Result.Error("Missing value after ${flag.name}")
                }
                flag.action(args, i + 1)
            } else {
                flag.action(args, i)
            }
        }
        return Result.Parsed(positional)
    }
}
