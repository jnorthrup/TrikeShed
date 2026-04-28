import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

plugins {
    kotlin("multiplatform")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}

val htxGeneralOpenApiSpec = layout.projectDirectory.file("../server/openapi/htx-general.openapi.yaml")
val generatedSourceRoot = layout.projectDirectory.dir("src/generated/kotlin")
val generatedPackageRoot = "borg.trikeshed.htx.client.generated"
val generatedPackagePath = generatedPackageRoot.replace('.', '/')
val generatedOutputRelativePaths = listOf(
    "$generatedPackagePath/api/HtxGeneralApi.kt",
    "$generatedPackagePath/infrastructure/GeneratedRequest.kt",
    "$generatedPackagePath/model/HealthStatus.kt",
    "$generatedPackagePath/Keys.kt",
    "$generatedPackagePath/Elements.kt",
    "$generatedPackagePath/SupervisorJobs.kt",
)
val generatedFileBanner =
    """
    /**
     * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
     * Repository policy: this checked-in file must be regenerated, not edited by hand.
     */
    """.trimIndent()

data class ContextBinding(
    val name: String,
    val keyFqn: String,
    val elementFqn: String,
    val openFqn: String,
) {
    val keySimple get() = keyFqn.substringAfterLast('.')
    val elementSimple get() = elementFqn.substringAfterLast('.')
    val keyImport get() = keyFqn
    val elementImport get() = elementFqn
    val openImport get() = openFqn
    val openAlias get() = "open${name.replaceFirstChar { it.uppercase() }}ElementRuntime"
}

data class TrikeshedContract(
    val path: String,
    val method: String,
    val operationId: String,
    val responseBody: String,
    val supervisorOperationIds: List<String>,
    val clientBindings: List<ContextBinding>,
    val serverBindings: List<ContextBinding>,
)

fun parseContextBindings(specText: String, role: String): List<ContextBinding> {
    // Find the x-trikeshed-context: block, then the named sub-list under <role>:.
    // The sub-list ends at the next same-indent key (another role or end of the extension block).
    val contextStart = specText.indexOf("x-trikeshed-context:")
    if (contextStart < 0) return emptyList()
    val afterContext = specText.substring(contextStart)
    // Match "  <role>:\n" and capture indented lines up to the next "  \w" sibling key or end.
    val blockRegex = Regex("""(?m)^  $role:\n((?:(?:    |\t).*\n?)*)""")
    val block = blockRegex.find(afterContext)?.groupValues?.get(1) ?: return emptyList()
    val entryRegex = Regex("""- name:\s*(\S+)\s*\n\s+key:\s*(\S+)\s*\n\s+element:\s*(\S+)\s*\n\s+open:\s*(\S+)""")
    return entryRegex.findAll(block).map { m ->
        ContextBinding(
            name = m.groupValues[1],
            keyFqn = m.groupValues[2],
            elementFqn = m.groupValues[3],
            openFqn = m.groupValues[4],
        )
    }.toList()
}

fun parseTrikeshedContract(specText: String, specPath: String): TrikeshedContract {
    require(specText.contains("title: HTX General Server API")) {
        "Expected HTX General Server API title in $specPath"
    }

    val path = Regex("""(?m)^  (/[^:]+):\s*$""").find(specText)?.groupValues?.get(1)
        ?: error("Unable to resolve a path from $specPath")
    val method = Regex("""(?m)^    ([a-z]+):\s*$""").find(specText)?.groupValues?.get(1)?.uppercase()
        ?: error("Unable to resolve an HTTP method from $specPath")
    val operationId = Regex("""(?m)^      operationId:\s*([A-Za-z0-9_]+)\s*$""")
        .find(specText)?.groupValues?.get(1)
        ?: error("Unable to resolve operationId from $specPath")
    val responseBody = Regex("""(?m)^                const:\s*(.+?)\s*$""")
        .find(specText)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
        ?: error("Unable to resolve response body contract from $specPath")

    // Collect all operationIds that carry x-trikeshed-supervisor: true.
    val supervisorIds = Regex("""(?ms)operationId:\s*(\S+).*?x-trikeshed-supervisor:\s*true""")
        .findAll(specText).map { it.groupValues[1] }.toList()

    return TrikeshedContract(
        path = path,
        method = method,
        operationId = operationId,
        responseBody = responseBody,
        supervisorOperationIds = supervisorIds,
        clientBindings = parseContextBindings(specText, "client"),
        serverBindings = parseContextBindings(specText, "server"),
    )
}

fun writeGeneratedFile(file: File, content: String) {
    file.parentFile.mkdirs()
    file.writeText(content.trimIndent() + "\n")
}

fun String.asKotlinStringLiteral(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

fun generatedKotlinFile(packageName: String, body: String): String =
    buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine(generatedFileBanner)
        appendLine()
        append(body.trimIndent())
        appendLine()
    }

fun renderGeneratedSources(contract: TrikeshedContract): Map<String, String> {
    val bindings = contract.clientBindings

    // Keys.kt — one val per binding, keyed by name
    val keysImports = buildString {
        appendLine("import borg.trikeshed.context.AsyncContextKey")
        bindings.forEach { b ->
            appendLine("import ${b.keyImport}")
            appendLine("import ${b.elementImport}")
        }
    }.trimEnd()
    val keysBody = buildString {
        bindings.forEach { b ->
            appendLine("    val ${b.name}: AsyncContextKey<${b.elementSimple}> = ${b.keySimple}")
        }
        append("    const val operationId: String = \"${contract.operationId.asKotlinStringLiteral()}\"")
    }

    // Elements.kt — one suspend fun per binding
    val elementsImports = buildString {
        bindings.forEach { b ->
            appendLine("import ${b.elementImport}")
            appendLine("import ${b.openImport} as ${b.openAlias}")
        }
    }.trimEnd()
    val elementsBody = buildString {
        bindings.forEachIndexed { i, b ->
            if (i > 0) appendLine()
            append("    suspend fun ${b.name}(): ${b.elementSimple} = ${b.openAlias}()")
        }
    }

    // SupervisorJobs.kt — one fun per supervisor operationId
    val supervisorBody = buildString {
        contract.supervisorOperationIds.forEachIndexed { i, opId ->
            if (i > 0) appendLine()
            append("    fun $opId(parent: Job? = null): Job = SupervisorJob(parent)")
        }
    }

    return mapOf(
        "$generatedPackagePath/api/HtxGeneralApi.kt" to
            generatedKotlinFile(
                packageName = "${generatedPackageRoot}.api",
                body =
                    """
                    import ${generatedPackageRoot}.infrastructure.GeneratedRequest
                    import ${generatedPackageRoot}.infrastructure.HttpMethod
                    import ${generatedPackageRoot}.model.HealthStatus

                    interface HtxGeneralApi {
                        suspend fun ${contract.operationId}(): HealthStatus
                    }

                    class DefaultHtxGeneralApi(
                       val call: suspend (GeneratedRequest) -> String,
                    ) : HtxGeneralApi {
                        override suspend fun ${contract.operationId}(): HealthStatus =
                            HealthStatus(call(HtxGeneralApiContract.GetHealth.request))
                    }

                    object HtxGeneralApiContract {
                        object GetHealth {
                            const val operationId: String = "${contract.operationId.asKotlinStringLiteral()}"
                            const val responseBody: String = "${contract.responseBody.asKotlinStringLiteral()}"
                            val request: GeneratedRequest = GeneratedRequest(
                                method = HttpMethod.${contract.method},
                                path = "${contract.path.asKotlinStringLiteral()}",
                            )
                        }
                    }
                    """,
            ),
        "$generatedPackagePath/infrastructure/GeneratedRequest.kt" to
            generatedKotlinFile(
                packageName = "${generatedPackageRoot}.infrastructure",
                body =
                    """
                    enum class HttpMethod {
                        ${contract.method},
                    }

                    data class GeneratedRequest(
                        val method: HttpMethod,
                        val path: String,
                    )
                    """,
            ),
        "$generatedPackagePath/model/HealthStatus.kt" to
            generatedKotlinFile(
                packageName = "${generatedPackageRoot}.model",
                body =
                    """
                    data class HealthStatus(
                        val body: String,
                    ) {
                        val ok: Boolean
                            get() = body == "${contract.responseBody.asKotlinStringLiteral()}"
                    }
                    """,
            ),
        "$generatedPackagePath/Keys.kt" to
            generatedKotlinFile(
                packageName = generatedPackageRoot,
                body = "$keysImports\n\nobject Keys {\n$keysBody\n}",
            ),
        "$generatedPackagePath/Elements.kt" to
            generatedKotlinFile(
                packageName = generatedPackageRoot,
                body = "$elementsImports\n\nobject Elements {\n$elementsBody\n}",
            ),
        "$generatedPackagePath/SupervisorJobs.kt" to
            generatedKotlinFile(
                packageName = generatedPackageRoot,
                body = "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.SupervisorJob\n\nobject SupervisorJobs {\n$supervisorBody\n}",
            ),
    )
}

val openApiGenerateHtxGeneralClient = tasks.register("openApiGenerateHtxGeneralClient") {
    group = "code generation"
    description = "Generates the htx-general client sources from ../server/openapi/htx-general.openapi.yaml."

    inputs.file(htxGeneralOpenApiSpec)
    outputs.dir(generatedSourceRoot)

    doLast {
        val specFile = htxGeneralOpenApiSpec.asFile
        require(specFile.exists()) { "Missing authoritative OpenAPI input: ${specFile.path}" }

        val contract = parseTrikeshedContract(specFile.readText(), specFile.path)
        val generatedPackageDir = generatedSourceRoot.asFile.resolve(generatedPackagePath)
        delete(generatedPackageDir)

        renderGeneratedSources(contract).forEach { (relativePath, content) ->
            writeGeneratedFile(generatedSourceRoot.asFile.resolve(relativePath), content)
        }
    }
}

val verifyHtxGeneralClientGeneratedSources = tasks.register("verifyHtxGeneralClientGeneratedSources") {
    group = "verification"
    description = "Verifies the checked-in htx-general generated sources match ../server/openapi/htx-general.openapi.yaml."

    mustRunAfter(openApiGenerateHtxGeneralClient)

    inputs.file(htxGeneralOpenApiSpec)
    generatedOutputRelativePaths.forEach { relativePath ->
        inputs.file(generatedSourceRoot.file(relativePath))
    }

    doLast {
        val specFile = htxGeneralOpenApiSpec.asFile
        require(specFile.exists()) { "Missing authoritative OpenAPI input: ${specFile.path}" }

        val expectedSources = renderGeneratedSources(parseTrikeshedContract(specFile.readText(), specFile.path))
        val generatedPackageDir = generatedSourceRoot.asFile.resolve(generatedPackagePath)
        val actualRelativePaths =
            if (generatedPackageDir.exists()) {
                generatedPackageDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(generatedSourceRoot.asFile).invariantSeparatorsPath }
                    .sorted()
                    .toList()
            } else {
                emptyList()
            }

        check(actualRelativePaths == generatedOutputRelativePaths.sorted()) {
            buildString {
                appendLine("Checked-in generated outputs differ from the documented policy.")
                appendLine("Expected: ${generatedOutputRelativePaths.joinToString()}")
                appendLine("Actual: ${actualRelativePaths.joinToString()}")
                append("Run ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient and review the resulting src/generated/kotlin changes.")
            }
        }

        expectedSources.forEach { (relativePath, expectedContent) ->
            val outputFile = generatedSourceRoot.file(relativePath).asFile
            check(outputFile.exists()) {
                "Missing checked-in generated source $relativePath. Run ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient."
            }
            check(outputFile.readText() == expectedContent.trimIndent() + "\n") {
                "Generated source $relativePath is stale. Run ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient and commit the updated output."
            }
        }
    }
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
        )
    }

    jvmToolchain(21)
    jvm()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedSourceRoot)
            dependencies {
                api(project(":"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

tasks.matching { task ->
    task.name.contains("compile", ignoreCase = true) && task.name.contains("Kotlin")
}.configureEach {
    dependsOn(openApiGenerateHtxGeneralClient)
}
