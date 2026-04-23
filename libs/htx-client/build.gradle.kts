import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

val htxGeneralOpenApiSpec = layout.projectDirectory.file("../server/openapi/htx-general.openapi.yaml")
val generatedSourceRoot = layout.projectDirectory.dir("src/generated/kotlin")
val generatedPackageRoot = "borg.trikeshed.htx.client.generated"
val generatedPackagePath = generatedPackageRoot.replace('.', '/')
val generatedOutputRelativePaths = listOf(
    "$generatedPackagePath/api/HtxGeneralApi.kt",
    "$generatedPackagePath/infrastructure/GeneratedRequest.kt",
    "$generatedPackagePath/model/HealthStatus.kt",
)
val generatedFileBanner =
    """
    /**
     * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
     * Repository policy: this checked-in file must be regenerated, not edited by hand.
     */
    """.trimIndent()

data class HtxGeneralOperationContract(
    val path: String,
    val method: String,
    val operationId: String,
    val responseBody: String,
)

fun writeGeneratedFile(file: File, content: String) {
    file.parentFile.mkdirs()
    file.writeText(content.trimIndent() + "\n")
}

fun String.asKotlinStringLiteral(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

fun parseHtxGeneralOpenApiContract(specText: String, specPath: String): HtxGeneralOperationContract {
    require(specText.contains("title: HTX General Server API")) {
        "Expected HTX General Server API title in $specPath"
    }

    val path = Regex("(?m)^  (/[^:]+):\\s*$").find(specText)?.groupValues?.get(1)
        ?: error("Unable to resolve a path from $specPath")
    val method = Regex("(?m)^    ([a-z]+):\\s*$").find(specText)?.groupValues?.get(1)?.uppercase()
        ?: error("Unable to resolve an HTTP method from $specPath")
    val operationId = Regex("(?m)^      operationId:\\s*([A-Za-z0-9_]+)\\s*$")
        .find(specText)
        ?.groupValues
        ?.get(1)
        ?: error("Unable to resolve operationId from $specPath")
    val responseBody = Regex("(?m)^                const:\\s*(.+?)\\s*$")
        .find(specText)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.removeSurrounding("\"")
        ?: error("Unable to resolve response body contract from $specPath")

    return HtxGeneralOperationContract(
        path = path,
        method = method,
        operationId = operationId,
        responseBody = responseBody,
    )
}

fun generatedKotlinFile(packageName: String, body: String): String =
    buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine(generatedFileBanner)
        appendLine()
        append(body.trimIndent())
        appendLine()
    }

fun renderGeneratedSources(contract: HtxGeneralOperationContract): Map<String, String> =
    mapOf(
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
                        private val call: suspend (GeneratedRequest) -> String,
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
    )

val openApiGenerateHtxGeneralClient = tasks.register("openApiGenerateHtxGeneralClient") {
    group = "code generation"
    description = "Generates the htx-general client sources from ../server/openapi/htx-general.openapi.yaml."

    inputs.file(htxGeneralOpenApiSpec)
    outputs.dir(generatedSourceRoot)

    doLast {
        val specFile = htxGeneralOpenApiSpec.asFile
        require(specFile.exists()) { "Missing authoritative OpenAPI input: ${specFile.path}" }

        val contract = parseHtxGeneralOpenApiContract(specFile.readText(), specFile.path)
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

        val expectedSources = renderGeneratedSources(parseHtxGeneralOpenApiContract(specFile.readText(), specFile.path))
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

        check(actualRelativePaths == generatedOutputRelativePaths) {
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
                api(project(":libs:common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
