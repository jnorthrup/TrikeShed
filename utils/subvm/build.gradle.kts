import java.security.MessageDigest

/**
 * Guest module resolver for the Oroboros sub-VM.
 *
 * TrikeShed's own classpath must stay the library's classpath. Heavy, optional or
 * differently-licensed dependencies that exist only so a `vm.*` lego can call them
 * belong to the DAEMON at runtime, not to the library at compile time — CoreNLP is
 * GPL v3 and ships ~450MB of models, Camel drags a component graph, and neither has
 * a single compile-time reference in `src/`.
 *
 * So they are resolved HERE, into `<module>/lib`, and the daemon mounts that directory
 * into a per-guest URLClassLoader. Nothing in this build is on TrikeShed's classpath;
 * nothing here compiles against TrikeShed.
 *
 *   ./gradlew install              # every module
 *   ./gradlew installCorenlp       # one module
 *
 * Each module gets a MANIFEST.tsv (file, size, sha256) so a mounted classpath is
 * content-addressable and a drifted jar is visible rather than silent.
 */
plugins { base }

/** module name -> coordinates. A module is one mountable guest classpath. */
val guestModules: Map<String, List<String>> = mapOf(
    // The curator lane: tokenize/pos/lemma/depparse/ner behind vm.corenlp.
    // GPL v3 or later — see src/commonMain/resources/nlp/lemma/en/ATTRIBUTION.md.
    "corenlp" to listOf(
        "edu.stanford.nlp:stanford-corenlp:4.5.10",
        "edu.stanford.nlp:stanford-corenlp:4.5.10:models",
    ),
    // The dispatch fabric: routes, components and the DefaultCamelContext lifecycle.
    // This is the SPINE, not the whole of Camel — it carries the EIP engine and the 22
    // component schemes that ship with core. Everything else arrives as a DEPARTMENT.
    "camel" to listOf(
        "org.apache.camel:camel-core:4.8.5",
        "org.apache.camel:camel-main:4.8.5",
    ),
    // ── departments ────────────────────────────────────────────────────────────────
    // A department is a guest module that EXTENDS the camel spine rather than repeating
    // it: it resolves with camel-core on the compile side but ships only what core does
    // not already have, and it is mounted alongside `camel` as a composed classpath
    // (see GuestModules.loaderFor). Bundling camel-core into each department instead
    // would put two CamelContext classes in two loaders and turn a missing-class error
    // into a class-identity one, which is strictly worse to diagnose.
    //
    // Departments are the unit of PURCHASE and of PRIVILEGE. A deployment that mounts
    // only `camel` cannot name an smtp endpoint, because the scheme is not on any
    // mounted classpath — nothing has to enforce that, it is simply absent.
    "camel-mail" to listOf(
        "org.apache.camel:camel-mail:4.8.5",
    ),
    // JDBC belongs to the mounted guest department, never the TrikeShed host classpath.
    "camel-jdbc" to listOf(
        "org.apache.camel:camel-jdbc:4.8.5",
        "com.h2database:h2:2.3.232",
    ),
    // Text/metadata extraction. Listed so the door is open; note that unlike the other
    // two, Tika still has a real host-side consumer (JvmTikaIngestAdapter.kt), so it
    // cannot leave TrikeShed's classpath until that adapter moves guest-side too.
    // This is the managed aggregate recipe, not the old tika4all embedded Maven launcher:
    // the resolved tika-parser-*-module jars are transitive output recorded in MANIFEST.tsv.
    "tika" to listOf(
        "org.apache.tika:tika-core:3.2.3",
        "org.apache.tika:tika-parsers-standard-package:3.2.3",
    ),
)

/**
 * Gradle-name form of a module key: `camel-mail` -> `CamelMail`, `camel` -> `Camel`.
 * The directory keeps the dashed name because it matches the Maven artifact an
 * operator is looking for; only the task and configuration names are folded.
 */
/**
 * Departments and the module each EXTENDS. A department resolves against its parent's
 * artifacts but ships only the delta, and mounts with the parent's classpath behind it.
 */
val departmentParents: Map<String, String> = mapOf(
    "camel-mail" to "camel",
    "camel-jdbc" to "camel",
)

fun gradleName(module: String): String =
    module.split('-').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

fun sha256(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

val installAll = tasks.register("installAll") {
    group = "subvm"
    description = "Resolve every guest module into <module>/lib with a MANIFEST.tsv"
}

guestModules.forEach { (module, coordinates) ->
    val cfg = configurations.create("guest${gradleName(module)}") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies { coordinates.forEach { add(cfg.name, it) } }

    val task = tasks.register("install${gradleName(module)}") {
        group = "subvm"
        description = "Resolve $module into $module/lib"
        val libDir = layout.projectDirectory.dir("$module/lib").asFile
        val manifest = layout.projectDirectory.file("$module/MANIFEST.tsv").asFile
        val declared = coordinates
        // Resolve at execution time; the configuration is the task's real input.
        val resolved = cfg
        outputs.dir(libDir)
        val parentModule = departmentParents[module]
        parentModule?.let { dependsOn("install${gradleName(it)}") }
        val parentLib = parentModule?.let { layout.projectDirectory.dir("$it/lib").asFile }
        doLast {
            libDir.deleteRecursively()
            libDir.mkdirs()
            // A department resolves its parent's artifacts too — camel-mail pulls camel-core,
            // camel-support and the rest of the spine. Shipping those again would put a second
            // copy of every spine class on a second classloader, so the SAME class arriving
            // through two loaders stops being the same class. Subtracting by file name is the
            // whole mechanism: what the parent already carries, the department does not.
            val parentJars = parentLib?.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
                ?.map { it.name }?.toSet() ?: emptySet()
            if (parentModule != null && parentJars.isEmpty()) {
                throw GradleException(
                    "department '$module' extends '$parentModule', which is not resolved yet — " +
                        "run ./gradlew -p utils/subvm install${gradleName(parentModule)} first",
                )
            }
            val all = resolved.resolve().sortedBy { it.name }
            val files = all.filter { it.name !in parentJars }
            val shared = all.size - files.size
            files.forEach { it.copyTo(File(libDir, it.name), overwrite = true) }
            val lines = buildList {
                add("# guest module\t$module")
                parentModule?.let { add("# parent\t$it") }
                declared.forEach { add("# declared\t$it") }
                if (parentModule != null) add("# inherited\t$shared jars from $parentModule")
                add("# resolved\t${files.size} jars\t${files.sumOf { it.length() }} bytes")
                add("file\tsize\tsha256")
                files.forEach { add("${it.name}\t${it.length()}\t${sha256(it)}") }
            }
            manifest.writeText(lines.joinToString("\n") + "\n")
            val inherited = if (parentModule != null) " (+$shared inherited from $parentModule)" else ""
            logger.lifecycle("[subvm] $module: ${files.size} jars, ${files.sumOf { it.length() } / 1024 / 1024} MB$inherited -> ${libDir.path}")
        }
    }
    installAll.configure { dependsOn(task) }
}

tasks.register("install") {
    group = "subvm"
    description = "Alias for installAll"
    dependsOn(installAll)
}
