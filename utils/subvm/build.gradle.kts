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
    "clojure" to listOf(
        "org.clojure:clojure:1.12.6",
        "org.clojure:data.json:2.5.2",
    ),
    // Source compiler and data boundary; neither belongs on TrikeShed's library classpath.
    "groovy" to listOf(
        "org.apache.groovy:groovy:5.0.2",
        "org.apache.groovy:groovy-json:5.0.2",
    ),
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
    "camel-postgres" to listOf(
        "org.postgresql:postgresql:42.7.14",
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
    "camel-postgres" to "camel-jdbc",
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

// Hermes' uv.lock pins these pure wheels. No host Python environment, C accelerator,
// or Python package installer is executed. Like JVM departments, this is an optional mount.
val hermesPython = configurations.create("hermesPython") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    add(hermesPython.name, "python.wheels:ruamel.yaml:0.18.16:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:python_dotenv:1.2.2:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:requests:2.33.0:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:certifi:2026.5.20:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:charset_normalizer:3.5.1:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:idna:3.19:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:urllib3:2.7.0:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:httpx:0.28.1:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:httpcore:1.0.9:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:anyio:4.14.2:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:h11:0.16.0:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:markdown_it_py:4.2.0:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:mdurl:0.1.2:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:prompt_toolkit:3.0.52:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:pygments:2.21.0:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:rich:14.3.3:py3-none-any@whl")
    add(hermesPython.name, "python.wheels:wcwidth:0.8.2:py3-none-any@whl")
}
val installHermesPython = tasks.register("installHermesPython") {
    group = "subvm"
    description = "Resolve verified pure-Python Hermes dependencies into hermes-python/lib"
    inputs.files(hermesPython)
    val directory = layout.projectDirectory.dir("hermes-python").asFile
    outputs.dir(directory)
    doLast {
        val pins = mapOf(
            "ruamel.yaml-0.18.16-py3-none-any.whl" to "048f26d64245bae57a4f9ef6feb5b552a386830ef7a826f235ffb804c59efbba",
            "python_dotenv-1.2.2-py3-none-any.whl" to "1d8214789a24de455a8b8bd8ae6fe3c6b69a5e3d64aa8a8e5d68e694bbcb285a",
            "requests-2.33.0-py3-none-any.whl" to "3324635456fa185245e24865e810cecec7b4caf933d7eb133dcde67d48cee69b",
            "certifi-2026.5.20-py3-none-any.whl" to "3c52e209ba0a4ad7aebe60436a4ab349c39e1e602e8c134221e546902ad25897",
            "charset_normalizer-3.5.1-py3-none-any.whl" to "6df0ec430f9a831772c23ca5a224cba36517a58a84bb32c32bb59a9fa67c47f6",
            "idna-3.19-py3-none-any.whl" to "815e7be7a7806d54abb586dc943addc79e8b2ee16915059658cbeff4b1b43bf4",
            "urllib3-2.7.0-py3-none-any.whl" to "9fb4c81ebbb1ce9531cce37674bbc6f1360472bc18ca9a553ede278ef7276897",
            "httpx-0.28.1-py3-none-any.whl" to "d909fcccc110f8c7faf814ca82a9a4d816bc5a6dbfea25d6591d6985b8ba59ad",
            "httpcore-1.0.9-py3-none-any.whl" to "2d400746a40668fc9dec9810239072b40b4484b640a8c38fd654a024c7a1bf55",
            "anyio-4.14.2-py3-none-any.whl" to "9f505dda5ac9f0c8309b5e8bd445a8c2bf7246f3ce950121e45ea15bc41d1494",
            "h11-0.16.0-py3-none-any.whl" to "63cf8bbe7522de3bf65932fda1d9c2772064ffb3dae62d55932da54b31cb6c86",
            "markdown_it_py-4.2.0-py3-none-any.whl" to "9f7ebbcd14fe59494226453aed97c1070d83f8d24b6fc3a3bcf9a38092641c4a",
            "mdurl-0.1.2-py3-none-any.whl" to "84008a41e51615a49fc9966191ff91509e3c40b939176e643fd50a5c2196b8f8",
            "prompt_toolkit-3.0.52-py3-none-any.whl" to "9aac639a3bbd33284347de5ad8d68ecc044b91a762dc39b7c21095fcd6a19955",
            "pygments-2.21.0-py3-none-any.whl" to "2363c69b61c4a97c838da3b130dcd6468f4848992b21a82f2a63ec34377137d9",
            "rich-14.3.3-py3-none-any.whl" to "793431c1f8619afa7d3b52b2cdec859562b950ea0d4b6b505397612db8d5362d",
            "wcwidth-0.8.2-py3-none-any.whl" to "d63947694a0539a1d51e01eda7caf800c291020e6cdd7e28ad7b14dd33ad4f85",
        )
        val wheels = hermesPython.resolve().sortedBy { it.name }
        check(wheels.map { it.name }.toSet() == pins.keys) { "Hermes Python artifacts differ from the lockfile" }
        for (wheel in wheels) check(sha256(wheel) == pins.getValue(wheel.name)) {
            "Hermes ${wheel.name} does not match the donor lockfile"
        }
        val lib = File(directory, "lib").apply { mkdirs() }
        wheels.forEach { it.copyTo(File(lib, it.name), overwrite = true) }
        File(directory, "MANIFEST.tsv").writeText(
            "# guest module\thermes-python\n# declared\truamel.yaml==0.18.16\n# declared\tpython-dotenv==1.2.2\n" +
                "# declared\trequests==2.33.0\n# declared\tcertifi==2026.5.20\n" +
                "# declared\tcharset-normalizer==3.5.1\n# declared\tidna==3.19\n# declared\turllib3==2.7.0\n" +
                "# declared\thttpx==0.28.1\n# declared\thttpcore==1.0.9\n" +
                "# declared\tanyio==4.14.2\n# declared\th11==0.16.0\n" +
                "# declared\tmarkdown-it-py==4.2.0\n" +
                "# declared\tmdurl==0.1.2\n" +
                "# declared\tprompt-toolkit==3.0.52\n" +
                "# declared\tpygments==2.21.0\n" +
                "# declared\trich==14.3.3\n" +
                "# declared\twcwidth==0.8.2\n" +
                "file\tsize\tsha256\n" + wheels.joinToString("") { "${it.name}\t${it.length()}\t${pins.getValue(it.name)}\n" },
        )
        logger.lifecycle("[subvm] hermes-python: ${wheels.sumOf { it.length() }} bytes -> ${lib.path}")
    }
}
installAll.configure { dependsOn(installHermesPython) }
