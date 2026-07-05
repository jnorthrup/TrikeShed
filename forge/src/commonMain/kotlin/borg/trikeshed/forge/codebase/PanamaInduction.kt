package borg.trikeshed.forge.codebase

/**
 * PanamaInduction — the canonical three-service ensemble fixture.
 *
 * ../panama/ is inducted as a real forge project. Its three services exert
 * deep corrective demand on the IO caching and the program at heart because
 * they span the exact tiers a real distributed cache architecture must serve:
 *
 *   gswormk (TS brain)    — hot/realtime, WebSocket feeds, in-process caching,
 *                            interpolation, signal computation. The "program
 *                            at heart" that decides what's stale and what's
 *                            not.
 *   cache-tier (Hazelcast) — warm distributed cache, IMap tiers, promotion
 *                            from realtime → historical. The IO caching layer
 *                            proper.
 *   columnar (ISAM)        — cold/archive, immutable historical data via
 *                            ISAMCursor. The source of truth the warm tier
 *                            loads from.
 *
 * The classpath separation is non-negotiable:
 *   - forge project state = panama's three services (read-only subject).
 *   - master blackboard   = TrikeShed's own CCEK jars (forge's runtime).
 *
 * The two are structurally disjoint. [panamaInduction] enforces this in the
 * constructor via the [CodebaseInduction] invariant.
 */
object PanamaInduction {

    /** The repo root forge interrogates. */
    const val REPO_PATH = "../panama"

    /** The master blackboard's own classpath — TrikeShed runtime, never panama. */
    private const val TRIKESHED_ROOT = "."

    /** The three-service ensemble, with real manifest paths and entry points. */
    val services: List<CodebaseService> = listOf(
        CodebaseService(
            name = "gswormk",
            language = CodebaseLanguage.TYPESCRIPT,
            manifestPath = "$REPO_PATH/gswormk/package.json",
            buildCommand = "npm run build",
            classpath = listOf(
                "$REPO_PATH/gswormk/src/trader/cache-manager.ts",
                "$REPO_PATH/gswormk/src/trader/engine.ts",
                "$REPO_PATH/gswormk/src/trader/calibration.ts",
                "$REPO_PATH/gswormk/src/feeds/coinbase-ws.ts",
                "$REPO_PATH/gswormk/src/feeds/coinbase-rest.ts",
            ),
            entryPoints = listOf(
                "$REPO_PATH/gswormk/src/cli.ts",
                "$REPO_PATH/gswormk/src/trader/engine.ts",
                "$REPO_PATH/gswormk/src/feeds/exchange-feed.ts",
                "$REPO_PATH/gswormk/src/trader/cache-manager.ts",
            ),
            role = ServiceRole.BRAIN,
        ),
        CodebaseService(
            name = "cache-tier",
            language = CodebaseLanguage.KOTLIN_JVM,
            manifestPath = "$REPO_PATH/cache-tier/pom.xml",
            buildCommand = "mvn compile -pl cache-tier -am -q -DskipTests",
            classpath = listOf(
                "$REPO_PATH/cache-tier/src/main/java/org/bereft/cache/CacheTier.kt",
                "$REPO_PATH/cache-tier/src/main/java/org/bereft/cache/CacheTierBridge.kt",
                "$REPO_PATH/cache-tier/src/main/java/org/bereft/cache/wal/SessionWAL.kt",
                "$REPO_PATH/cache-tier/src/main/java/org/bereft/cache/CacheTierMain.kt",
            ),
            entryPoints = listOf(
                "org.bereft.cache.CacheTierMain",
                "org.bereft.cache.CacheTier",
                "org.bereft.cache.CacheTierBridge",
            ),
            role = ServiceRole.CACHE_TIER,
        ),
        CodebaseService(
            name = "columnar",
            language = CodebaseLanguage.KOTLIN_JVM,
            manifestPath = "$REPO_PATH/columnar/pom.xml",
            buildCommand = "mvn compile -pl columnar -q -DskipTests",
            classpath = listOf(
                "$REPO_PATH/columnar/src/main/java/cursors/io/ISAMCursor.kt",
                "$REPO_PATH/columnar/src/main/java/org/bereft/MarketData.kt",
                "$REPO_PATH/columnar/src/main/java/vec/util/BloomFilter.kt",
            ),
            entryPoints = listOf(
                "cursors.io.ISAMCursor",
                "org.bereft.MarketData",
            ),
            role = ServiceRole.ARCHIVE,
        ),
    )

    val project: CodebaseProject = CodebaseProject(
        repoPath = REPO_PATH,
        name = "panama",
        services = services,
        gitHead = "a469c81",
    )

    /**
     * The canonical induction. Forge project state is panama's classpath roots;
     * master blackboard is TrikeShed's CCEK jars. The two are disjoint by
     * construction — [CodebaseInduction]'s init block enforces it at runtime.
     */
    val induction: CodebaseInduction = CodebaseInduction(
        forgeProjectState = ForgeProjectState(
            project = project,
            classpathRoots = services.flatMap { it.classpath },
        ),
        masterBlackboardClasspath = MasterBlackboardClasspath(
            trikeshedRoot = TRIKESHED_ROOT,
            ccekJars = listOf(
                "build/libs/TrikeShed-jvm-1.0.jar",
                "libs/ccek-core/build/libs/ccek-core-0.1.0-SNAPSHOT.jar",
                "libs/ccek-dsl/build/libs/ccek-dsl-0.1.0-SNAPSHOT.jar",
                "libs/forge/build/libs/forge-1.0.jar",
            ),
        ),
    )

    /**
     * Resolve the full ensemble into ConfixBlackboard DAG entries.
     *
     * This is the IO-caching corrective demand: each service's symbols become
     * language-tagged DAG coordinates the master blackboard can join on.
     * A rete rule matching `js:*` fires only on gswormk; `jvm:*` fires on
     * cache-tier + columnar; the two never collide.
     */
    fun resolveDagEntries(): List<BlackboardDagEntry> =
        PolyglotAdapters.resolveEnsemble(services)
}