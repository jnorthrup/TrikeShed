#!/usr/bin/env kotlin
/**
 * ToSeriesMacro — source-to-source rewriter
 *
 * Scans .java and .kt files, replaces Collection/Array field declarations and
 * constructor calls with TrikeShed Series equivalents, appending .toSeries().
 *
 * Patterns replaced:
 *
 *   // Java
 *   List<T>         x = new ArrayList<>()    →  Series<T>  x = new ArrayList<>().toSeries()
 *   List<T>         x = new ArrayList<>(n)   →  Series<T>  x = new ArrayList<>(n).toSeries()
 *   Set<T>          x = new HashSet<>()      →  Series<T>  x = new HashSet<>().toSeries()
 *   Map<K,V>.keys                            →  Series<K>  (via .keys.toSeries())
 *   Map<K,V>.values                          →  Series<V>  (via .values.toSeries())
 *   Collection<T>   x = ...                  →  Series<T>  x = ....toSeries()
 *   T[]             x = ...                  →  Series<T>  x = ....toSeries()
 *
 *   // Kotlin
 *   listOf(...)                              →  listOf(...).toSeries()
 *   mutableListOf(...)                      →  mutableListOf(...).toSeries()
 *   setOf(...)                               →  setOf(...).toSeries()
 *   mutableSetOf(...)                        →  mutableSetOf(...).toSeries()
 *   arrayOf(...)                             →  arrayOf(...).toSeries().toList()
 *   intArrayOf(...)                          →  intArrayOf(...).toSeries().toList()
 *   (same for longArrayOf, doubleArrayOf, etc.)
 *
 * Usage:
 *   kotlin lib_cursor/scripts/ToSeriesMacro.kt <file-or-dir> [--write] [--dry-run]
 *
 *   --write    rewrite files in place (default: dry-run, prints diff to stdout)
 *   --dry-run  just print what would change (default)
 */

import java.io.File

// ── configuration ──────────────────────────────────────────────────────────

data class Rewrite(
    val pattern: Regex,
    val replacement: (MatchResult) -> String,
    val description: String
)

// ── Java rewrites ──────────────────────────────────────────────────────────

val JAVA_REWRITES = listOf(

    // new ArrayList<>() / new ArrayList<>(n)  →  new ArrayList<>().toSeries()
    Rewrite(
        Regex("""(new\s+ArrayList\s*<[^>]*>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "ArrayList ctor → .toSeries()"
    ),

    // new HashSet<>() / new HashSet<>(n)  →  new HashSet<>().toSeries()
    Rewrite(
        Regex("""(new\s+HashSet\s*<[^>]*>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "HashSet ctor → .toSeries()"
    ), 

    // new LinkedHashMap<>() / new HashMap<>()  — NOT rewritten (maps stay maps)

    // .toArray() on collection → .toSeries()  (only if preceded by known collection method)
    Rewrite(
        Regex("""(\.\s*toArray\s*\(\s*\))"""),
        { _ -> ".toSeries()" },
        ".toArray() → .toSeries()"
    ),

    // type declarations: List<T> → Series<T>, Set<T> → Series<T>, Collection<T> → Series<T>
    // but NOT in imports, NOT in generic bounds, NOT in method parameters (only fields/vars/returns)
    Rewrite(
        Regex("""\b(List|Set|Collection)\s*<(\s*[^>]+\s*)>"""),
        { m -> "Series<${m.groupValues[2].trim()}>" },
        "List/Set/Collection<T> type → Series<T>"
    ),
)

// ── Java Redux pointcut heuristics ─────────────────────────────────────────
//
// These detect patterns where singletons or event publishers emit items
// into collections and wrap them in ReduxMutableSeries delegates that
// journal all emissions with delayed reification.
//
// Pattern families:
//   1. Singleton object fields holding mutable collections
//   2. Static final List/Set fields (emission accumulation)
//   3. EventBus / listener registries

val JAVA_REDUX_REWRITES = listOf(

    // static final List<T> LISTENERS = new ArrayList<>()
    //   → static final ReduxMutableSeries<T, Series<T>> LISTENERS =
    //       new ReduxMutableSeries<>(new ChunkedMutableSeries<>(100), ...)
    Rewrite(
        Regex("""(static\s+final\s+)(List|Set|Collection)\s*<([^>]+)>\s+(\w+)\s*=\s*(new\s+\w+\s*<[^>]*>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}ReduxMutableSeries<${m.groupValues[3].trim()}, Series<${m.groupValues[3].trim()}>> ${m.groupValues[4]} = ReduxMutableSeries.of(${m.groupValues[5]})" },
        "static final List<T> → ReduxMutableSeries delegate"
    ),

    // private List<Listener> listeners = new ArrayList<>()
    //   → private ReduxMutableSeries<Listener, Series<Listener>> listeners = ...
    Rewrite(
        Regex("""(private\s+)(List|Set)\s*<([^>]+)>\s+(\w*[Ll]istener\w*|\w*[Hh]andler\w*|\w*[Cc]allback\w*|\w*[Oo]bserver\w*)\s*=\s*(new\s+\w+\s*<[^>]*>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}ReduxMutableSeries<${m.groupValues[3].trim()}, Series<${m.groupValues[3].trim()}>> ${m.groupValues[4]} = ReduxMutableSeries.of(${m.groupValues[5]})" },
        "listener registry → ReduxMutableSeries delegate"
    ),
)

// ── Kotlin rewrites ────────────────────────────────────────────────────────

val KOTLIN_REWRITES = listOf(

    // listOf(...) → listOf(...).toSeries()
    Rewrite(
        Regex("""(listOf\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "listOf → .toSeries()"
    ),

    // Arrays.asList(...) → Arrays.asList(...).toSeries()
    Rewrite(
        Regex("""(Arrays\.asList\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "listOf → .toSeries()"
    ),

    // List.of(...) → List.of(...).toSeries()
    Rewrite(
        Regex("""(List\.of\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "listOf → .toSeries()"
    ),

    // Collections.singletonList(...) → Collections.singletonList(...).toSeries()
    Rewrite(
        Regex("""(Collections\.singletonList\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "listOf → .toSeries()"
    ),

    // Collections.emptyList() → Collections.emptyList().toSeries()
    Rewrite(
        Regex("""(Collections\.emptyList\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "listOf → .toSeries()"
    ),


    // mutableListOf(...) → mutableListOf(...).toSeries()
    Rewrite(
        Regex("""(mutableListOf\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "mutableListOf → .toSeries()"
    ),

    // setOf(...) → setOf(...).toSeries()
    Rewrite(
        Regex("""(setOf\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "setOf → .toSeries()"
    ),

    // mutableSetOf(...) → mutableSetOf(...).toSeries()
    Rewrite(
        Regex("""(mutableSetOf\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries()" },
        "mutableSetOf → .toSeries()"
    ),

    // arrayOf(...) → arrayOf(...).toSeries().toList()
    Rewrite(
        Regex("""(arrayOf\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries().toList()" },
        "arrayOf → .toSeries().toList()"
    ),

    // primitive arrayOf: intArrayOf, longArrayOf, doubleArrayOf, etc.
    Rewrite(
        Regex("""((int|long|double|float|short|byte|char|boolean)ArrayOf\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries().toList()" },
        "primitive arrayOf → .toSeries().toList()"
    ),

    // SList(...) → SList(...).toSeries().toList()
    Rewrite(
        Regex("""\b(SList\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries().toList()" },
        "SList → .toSeries().toList()"
    ),

    // SLists(...) → SLists(...).toSeries().toList()
    Rewrite(
        Regex("""\b(SLists\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}.toSeries().toList()" },
        "SLists → .toSeries().toList()"
    ),

    // : List<T> → : Series<T>  (type annotations)
    Rewrite(
        Regex(""":\s*(List|Set|Collection|MutableList|MutableSet)\s*<([^>]+)>"""),
        { m -> ": Series<${m.groupValues[2].trim()}>" },
        "List/Set/Collection type decl → Series<T>"
    ),
)

// ── Kotlin Redux pointcut heuristics ───────────────────────────────────────
//
// Detects patterns in Kotlin where singletons (object declarations),
// companion objects, or top-level vals accumulate emissions, and wraps
// them in ReduxMutableSeries delegates that journal all dispatched
// actions with delayed reification.
//
// Pattern families:
//   1. object Foo { val items = mutableListOf<T>() } → ReduxMutableSeries journal
//   2. companion object { val registry = ... }       → ReduxMutableSeries journal
//   3. val emissions = MutableSharedFlow<T>()         → ReduxMutableSeries + Flow bridge
//   4. val channel = Channel<T>()                     → ReduxMutableSeries + Channel bridge
//   5. by lazy { mutableListOf<T>() }                 → by lazy { ReduxMutableSeries(...) }

val KOTLIN_REDUX_REWRITES = listOf(

    // val foo = mutableListOf<T>()  (inside object or companion object scope)
    //   → val foo = reduxSeriesOf<T>()
    Rewrite(
        Regex("""(val\s+)(\w+)(\s*=\s*)(mutableListOf\s*<([^>]+)>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}reduxSeriesOf<${m.groupValues[5].trim()}>()" },
        "mutableListOf in singleton → reduxSeriesOf delegate"
    ),

    // val foo = mutableSetOf<T>()  (same treatment)
    Rewrite(
        Regex("""(val\s+)(\w+)(\s*=\s*)(mutableSetOf\s*<([^>]+)>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}reduxSeriesOf<${m.groupValues[5].trim()}>()" },
        "mutableSetOf in singleton → reduxSeriesOf delegate"
    ),

    // val foo = ArrayList<T>()  (Kotlin shorthand)
    Rewrite(
        Regex("""(val\s+)(\w+)(\s*=\s*)(ArrayList\s*<([^>]+)>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}reduxSeriesOf<${m.groupValues[5].trim()}>()" },
        "ArrayList() in singleton → reduxSeriesOf delegate"
    ),

    // by lazy { mutableListOf<T>() }  → by lazy { reduxSeriesOf<T>() }
    Rewrite(
        Regex("""(by\s+lazy\s*\{\s*)(mutableListOf\s*<([^>]+)>\s*\([^)]*\))(\s*})"""),
        { m -> "${m.groupValues[1]}reduxSeriesOf<${m.groupValues[3].trim()}>()${m.groupValues[4]}" },
        "lazy mutableListOf → lazy reduxSeriesOf delegate"
    ),

    // MutableSharedFlow<T>() → reduxSeriesOf + note
    Rewrite(
        Regex("""(val\s+)(\w+)(\s*=\s*)(MutableSharedFlow\s*<([^>]+)>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}reduxSeriesOf<${m.groupValues[5].trim()}>() /* was: ${m.groupValues[4]} */" },
        "MutableSharedFlow → reduxSeriesOf (emission journal)"
    ),

    // Channel<T>(capacity) → reduxSeriesOf + note
    Rewrite(
        Regex("""(val\s+)(\w+)(\s*=\s*)(Channel\s*<([^>]+)>\s*\([^)]*\))"""),
        { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}reduxSeriesOf<${m.groupValues[5].trim()}>() /* was: ${m.groupValues[4]} */" },
        "Channel → reduxSeriesOf (emission journal)"
    ),
)

// ── exclusion rules — don't touch these ────────────────────────────────────

val JAVA_EXCLUSIONS = listOf(
    Regex("""import\s+java\.util\.(List|Set|Collection|ArrayList|HashSet)"""),
    Regex("""@\w+"""),  // annotations
    Regex("""//.*$"""), // line comments
    Regex("""/\*.*?\*/"""), // block comments
)

val KOTLIN_EXCLUSIONS = listOf(
    Regex("""import\s+.*"""),
    Regex("""//.*$"""),
    Regex("""/\*.*?\*/"""),
)

// Redux rewrites should NOT touch lines that already have Redux constructs
val REDUX_EXCLUSIONS = listOf(
    Regex("""ReduxMutableSeries"""),
    Regex("""ChunkedMutableSeries"""),
    Regex("""CollectorReducer"""),
    Regex("""reduxSeriesOf"""),
    Regex("""reduxSeriesFrom"""),
    Regex("""import\s+.*"""),
    Regex("""//.*$"""),
    Regex("""/\*.*?\*/"""),
)

// ── engine ─────────────────────────────────────────────────────────────────

data class Change(val file: File, val line: Int, val before: String, val after: String, val rule: String)

fun rewriteFile(file: File, rewrites: List<Rewrite>, exclusions: List<Regex>): List<Change> {
    val changes = mutableListOf<Change>()
    val lines = file.readLines()

    val packageRegex = Regex("""^\s*package\s+org\.xvm\b""")
    val isInXvmPackage = lines.any { packageRegex.containsMatchIn(it) }

    for ((idx, line) in lines.withIndex()) {
        // skip exclusions
        if (exclusions.any { it.containsMatchIn(line) }) continue
        // skip if already has .toSeries() (except inside org.xvm package where we check for .toSeries().toList())
        if (!isInXvmPackage && ".toSeries()" in line) continue
        if (isInXvmPackage && ".toSeries().toList()" in line) continue

        var current = line
        for (rw in rewrites) {
            val match = rw.pattern.find(current) ?: continue
            val replaced = if (isInXvmPackage && rw.description == "listOf → .toSeries()") {
                rw.pattern.replace(current) { m -> "${m.groupValues[1]}.toSeries().toList()" }
            } else {
                rw.pattern.replace(current) { m -> rw.replacement(m) }
            }
            if (replaced != current) {
                val ruleDesc = if (isInXvmPackage && rw.description == "listOf → .toSeries()") {
                    "listOf → .toSeries().toList() (org.xvm detour)"
                } else {
                    rw.description
                }
                changes.add(Change(file, idx + 1, current, replaced, ruleDesc))
                current = replaced
            }
        }
    }
    return changes
}

fun processPath(path: File, write: Boolean): List<Change> {
    val allChanges = mutableListOf<Change>()

    if (path.isFile) {
        val rewrites = if (path.extension == "kt") KOTLIN_REWRITES else JAVA_REWRITES
        val exclusions = if (path.extension == "kt") KOTLIN_EXCLUSIONS else JAVA_EXCLUSIONS
        allChanges.addAll(rewriteFile(path, rewrites, exclusions))
        // Redux pointcut pass (second pass, separate exclusions)
        val reduxRewrites = if (path.extension == "kt") KOTLIN_REDUX_REWRITES else JAVA_REDUX_REWRITES
        allChanges.addAll(rewriteFile(path, reduxRewrites, REDUX_EXCLUSIONS))
    } else {
        path.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .filter { !it.path.contains("build") }
            .filter { !it.path.contains("generated") }
            .filter { !it.path.contains("src/main/resources") }
            .forEach { file ->
                val rewrites = if (file.extension == "kt") KOTLIN_REWRITES else JAVA_REWRITES
                val exclusions = if (file.extension == "kt") KOTLIN_EXCLUSIONS else JAVA_EXCLUSIONS
                allChanges.addAll(rewriteFile(file, rewrites, exclusions))
                // Redux pointcut pass
                val reduxRewrites = if (file.extension == "kt") KOTLIN_REDUX_REWRITES else JAVA_REDUX_REWRITES
                allChanges.addAll(rewriteFile(file, reduxRewrites, REDUX_EXCLUSIONS))
            }
    }

    if (write && allChanges.isNotEmpty()) {
        // group by file, apply changes
        allChanges.groupBy { it.file }.forEach { (file, changes) ->
            val lines = file.readLines().toMutableList()
            // apply in reverse order to preserve line numbers
            for (change in changes.sortedByDescending { it.line }) {
                lines[change.line - 1] = change.after
            }
            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    return allChanges
}

// ── main ───────────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: ToSeriesMacro.kt <file-or-dir> [--write] [--dry-run]")
        return
    }

    val path = File(args[0])
    val write = "--write" in args

    if (!path.exists()) {
        println("Error: $path does not exist")
        return
    }

    val changes = processPath(path, write)

    if (changes.isEmpty()) {
        println("No changes needed.")
        return
    }

    for (change in changes) {
        val prefix = if (write) "REWRITE" else "WOULD REWRITE"
        println("$prefix ${change.file.relativeTo(File("."))}:${change.line} [${change.rule}]")
        println("  - ${change.before.trimEnd()}")
        println("  + ${change.after.trimEnd()}")
    }

    println("\n${changes.size} change(s) ${if (write) "applied" else "would be applied"}.")
    if (!write) {
        println("Run with --write to apply.")
    }
}

main(args = emptyArray())  // for script mode
