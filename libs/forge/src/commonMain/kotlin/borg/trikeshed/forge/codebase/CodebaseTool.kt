package borg.trikeshed.forge.codebase

import borg.trikeshed.forge.ForgeFileId
import kotlinx.serialization.Serializable

/**
 * CodebaseTool — forge's notion-metaphor adapter for interrogating an external
 * git repo as a "forge project state".
 *
 * CRITICAL SEPARATION (per design):
 *   - [ForgeProjectState] = the EXTERNAL repo under interrogation (e.g. ../panama).
 *     Classpath is read-only; forge observes and projects it into notion blocks.
 *   - [MasterBlackboardClasspath] = TrikeShed's OWN introspection classpath.
 *     This is the local CCEK/ConfixBlackboard DAG that forge runs inside.
 *
 * The two never merge: forge project state is a *subject* of the master
 * blackboard, never a participant. The master blackboard owns causality;
 * forge project state is observed.
 *
 * Polyglot adapters ([PolyglotBlackboardAdapter]) translate per-language
 * symbols (JS exports, Python defs, Kotlin classes) into the master
 * blackboard's [ConfixBlackboard] DAG so the rete/kanban machinery can fire
 * on real codebase events.
 */

/** A git-based repo inducted as a forge project. */
@Serializable
data class CodebaseProject(
    val repoPath: String,
    val name: String,
    val services: List<CodebaseService>,
    val gitHead: String = "",
) {
    companion object
}

/**
 * One service in a multi-service ensemble.
 *
 * A service is identified by its build manifest (package.json / pom.xml / etc.),
 * carries a [language] and [buildCommand], and exposes the classpath entries
 * forge needs to interrogate it.
 */
@Serializable
data class CodebaseService(
    val name: String,
    val language: CodebaseLanguage,
    val manifestPath: String,
    val buildCommand: String,
    val classpath: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList(),
    /** Role in the ensemble — distinguishes hot/warm/cold tiers. */
    val role: ServiceRole = ServiceRole.ENGINE,
)

@Serializable
enum class CodebaseLanguage {
    TYPESCRIPT,
    KOTLIN_JVM,
    PYTHON,
    ;

    val adapterKind: String get() = when (this) {
        TYPESCRIPT -> "js"
        KOTLIN_JVM -> "jvm"
        PYTHON -> "py"
    }
}

@Serializable
enum class ServiceRole {
    /** Realtime/hot engine (e.g. gswormk TS brain). */
    BRAIN,
    /** Warm distributed cache (e.g. cache-tier Hazelcast). */
    CACHE_TIER,
    /** Cold/archive storage (e.g. columnar ISAM). */
    ARCHIVE,
    /** Generic engine — default when role is undetermined. */
    ENGINE,
}

/**
 * The forge project state — the observed external repo.
 *
 * Distinct from [MasterBlackboardClasspath]; the two are never aliases.
 */
@Serializable
data class ForgeProjectState(
    val project: CodebaseProject,
    val classpathRoots: List<String>,
) {
    val isDistinctFromMaster: Boolean get() = classpathRoots.isNotEmpty()
}

/**
 * The master blackboard classpath — TrikeShed's own introspection surface.
 *
 * Forge runs inside this; the forge project state is observed *through* it.
 */
@Serializable
data class MasterBlackboardClasspath(
    val trikeshedRoot: String,
    val ccekJars: List<String>,
    val confixBlackboardEntries: List<String> = emptyList(),
)

/**
 * Result of inducting a repo: the separated project state + master classpath.
 *
 * This is the single value forge's notion layer consumes to render a codebase
 * as notion blocks, and rete/kanban consumes to fire on codebase causality.
 */
@Serializable
data class CodebaseInduction(
    val forgeProjectState: ForgeProjectState,
    val masterBlackboardClasspath: MasterBlackboardClasspath,
) {
    init {
        require(forgeProjectState.isDistinctFromMaster) {
            "forge project state must be distinct from master blackboard classpath"
        }
        val overlap = forgeProjectState.classpathRoots.toSet() intersect masterBlackboardClasspath.ccekJars.toSet()
        require(overlap.isEmpty()) {
            "forge project classpath roots must not overlap master blackboard jars: $overlap"
        }
    }
}