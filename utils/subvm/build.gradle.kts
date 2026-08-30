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
    "camel" to listOf(
        "org.apache.camel:camel-core:4.8.5",
        "org.apache.camel:camel-main:4.8.5",
    ),
    // Text/metadata extraction. Listed so the door is open; note that unlike the other
    // two, Tika still has a real host-side consumer (JvmTikaIngestAdapter.kt), so it
    // cannot leave TrikeShed's classpath until that adapter moves guest-side too.
    "tika" to listOf(
        "org.apache.tika:tika-core:3.2.3",
        "org.apache.tika:tika-parsers-standard-package:3.2.3",
    ),
)

fun sha256(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

val installAll = tasks.register("installAll") {
    group = "subvm"
    description = "Resolve every guest module into <module>/lib with a MANIFEST.tsv"
}

guestModules.forEach { (module, coordinates) ->
    val cfg = configurations.create("guest${module.replaceFirstChar { it.uppercase() }}") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies { coordinates.forEach { add(cfg.name, it) } }

    val task = tasks.register("install${module.replaceFirstChar { it.uppercase() }}") {
        group = "subvm"
        description = "Resolve $module into $module/lib"
        val libDir = layout.projectDirectory.dir("$module/lib").asFile
        val manifest = layout.projectDirectory.file("$module/MANIFEST.tsv").asFile
        val declared = coordinates
        // Resolve at execution time; the configuration is the task's real input.
        val resolved = cfg
        outputs.dir(libDir)
        doLast {
            libDir.deleteRecursively()
            libDir.mkdirs()
            val files = resolved.resolve().sortedBy { it.name }
            files.forEach { it.copyTo(File(libDir, it.name), overwrite = true) }
            val lines = buildList {
                add("# guest module\t$module")
                declared.forEach { add("# declared\t$it") }
                add("# resolved\t${files.size} jars\t${files.sumOf { it.length() }} bytes")
                add("file\tsize\tsha256")
                files.forEach { add("${it.name}\t${it.length()}\t${sha256(it)}") }
            }
            manifest.writeText(lines.joinToString("\n") + "\n")
            logger.lifecycle("[subvm] $module: ${files.size} jars, ${files.sumOf { it.length() } / 1024 / 1024} MB -> ${libDir.path}")
        }
    }
    installAll.configure { dependsOn(task) }
}

tasks.register("install") {
    group = "subvm"
    description = "Alias for installAll"
    dependsOn(installAll)
}
