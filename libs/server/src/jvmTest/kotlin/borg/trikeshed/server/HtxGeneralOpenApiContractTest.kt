package borg.trikeshed.server

import borg.trikeshed.htx.client.generated.api.HtxGeneralApiContract
import borg.trikeshed.htx.client.generated.infrastructure.GeneratedRequest
import borg.trikeshed.htx.client.generated.infrastructure.HttpMethod
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtxGeneralOpenApiContractTest {
    private val spec = File("openapi/htx-general.openapi.yaml")
    private val contract = File("openapi/htx-general-htx-client.codegen-contract.yaml")
    private val repoRoot = spec.canonicalFile.parentFile.parentFile.parentFile.parentFile
    private val clientBuild = File(repoRoot, "libs/htx-client/build.gradle.kts")
    private val clientReadme = File(repoRoot, "libs/htx-client/README.md")
    private val clientGeneratedTest = File(
        repoRoot,
        "libs/htx-client/src/commonTest/kotlin/borg/trikeshed/htx/client/GeneratedHtxGeneralClientTest.kt",
    )
    private val compatibilityTest = File(
        "src/jvmTest/kotlin/borg/trikeshed/server/HtxGeneralClientServerCompatibilityTest.kt",
    )
    private val generatedSourceRoot = File(repoRoot, "libs/htx-client/src/generated/kotlin")
    private val generatedPackageRoot = File(generatedSourceRoot, "borg/trikeshed/htx/client/generated")
    private val generatedApiFile = File(
        generatedSourceRoot,
        "borg/trikeshed/htx/client/generated/api/HtxGeneralApi.kt",
    )
    private val generatedInfrastructureFile = File(
        generatedSourceRoot,
        "borg/trikeshed/htx/client/generated/infrastructure/GeneratedRequest.kt",
    )
    private val generatedModelFile = File(
        generatedSourceRoot,
        "borg/trikeshed/htx/client/generated/model/HealthStatus.kt",
    )
    private val expectedOutputPackages = listOf(
        "borg.trikeshed.htx.client.generated.api",
        "borg.trikeshed.htx.client.generated.infrastructure",
        "borg.trikeshed.htx.client.generated.model",
    )
    private val expectedOutputModes = listOf(
        "generated-api",
        "generated-support",
        "generated-model",
    )
    private val expectedGeneratedFiles = listOf(
        "borg/trikeshed/htx/client/generated/api/HtxGeneralApi.kt",
        "borg/trikeshed/htx/client/generated/infrastructure/GeneratedRequest.kt",
        "borg/trikeshed/htx/client/generated/model/HealthStatus.kt",
    )
    private val expectedDocumentedGeneratedFiles = expectedGeneratedFiles.map {
        "libs/htx-client/src/generated/kotlin/$it"
    }

    @Test
    fun authoritativeSpecDeclaresTheServerSurface() {
        assertTrue(spec.exists(), "Expected ${spec.path} to exist")
        val text = spec.readText()

        assertTrue(text.contains("title: HTX General Server API"))
        assertTrue(text.contains("  /health:"))
        assertTrue(text.contains("      operationId: getHealth"))
        assertTrue(text.contains("            text/plain:"))
        assertTrue(text.contains("                const: ok"))
    }

    @Test
    fun authoritativeOpenApiInputIsSingleAndSharedByContractAndCodegen() {
        assertTrue(contract.exists(), "Expected ${contract.path} to exist")
        assertTrue(clientBuild.exists(), "Expected ${clientBuild.path} to exist")

        val contractText = contract.readText()
        val buildText = clientBuild.readText()
        val authoritativeSource = Regex("(?m)^  source: (.+)$")
            .find(contractText)
            ?.groupValues
            ?.get(1)

        assertEquals("libs/server/openapi/htx-general.openapi.yaml", authoritativeSource)
        assertEquals(
            1,
            Regex("(?m)^\\s*source: libs/server/openapi/htx-general\\.openapi\\.yaml\\s*$").findAll(contractText).count(),
        )
        assertTrue(contractText.contains("authoritative: true"))
        assertTrue(contractText.contains("source_count: 1"))
        assertTrue(contractText.contains("source: libs/server/openapi/htx-general.openapi.yaml"))
        assertTrue(buildText.contains("val htxGeneralOpenApiSpec = layout.projectDirectory.file(\"../server/openapi/htx-general.openapi.yaml\")"))
    }

    @Test
    fun generatedOutputsAreEnumeratedWithDestinationModulePackageAndGeneratorMode() {
        assertTrue(contract.exists(), "Expected ${contract.path} to exist")
        assertTrue(clientBuild.exists(), "Expected ${clientBuild.path} to exist")
        assertTrue(clientReadme.exists(), "Expected ${clientReadme.path} to exist")

        val contractText = contract.readText()
        val buildText = clientBuild.readText()
        val readmeText = clientReadme.readText()
        val documentedPackages = Regex("(?m)^    - package: ([^\\s]+)\\s*$")
            .findAll(contractText)
            .map { it.groupValues[1] }
            .toList()
        val documentedModes = Regex("(?m)^      mode: ([^\\s]+)\\s*$")
            .findAll(contractText)
            .map { it.groupValues[1] }
            .toList()
        val documentedPaths = Regex("(?m)^      path: (libs/htx-client/src/generated/kotlin/[^\\s]+)\\s*$")
            .findAll(contractText)
            .map { it.groupValues[1] }
            .toList()
        val documentedFileNames = Regex("(?m)^        - (HtxGeneralApi\\.kt|GeneratedRequest\\.kt|HealthStatus\\.kt)\\s*$")
            .findAll(contractText)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(contractText.contains("working_directory: ."))
        assertTrue(contractText.contains("working_directory_context: repository root"))
        assertTrue(contractText.contains("command: ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient"))
        assertTrue(contractText.contains("task: openApiGenerateHtxGeneralClient"))
        assertTrue(contractText.contains("generator: kotlin"))
        assertTrue(contractText.contains("library: multiplatform"))
        assertTrue(contractText.contains("generator_mode: kotlin + multiplatform"))
        assertTrue(contractText.contains("destination_module: libs/htx-client"))
        assertTrue(contractText.contains("destination_source_root: libs/htx-client/src/generated/kotlin"))
        assertTrue(contractText.contains("destination_package_root: borg.trikeshed.htx.client.generated"))
        assertTrue(contractText.contains("output_count: 3"))
        assertTrue(contractText.contains("generated_outputs: checked-in"))
        assertTrue(contractText.contains("verification_only: false"))
        assertTrue(contractText.contains("verification_mode: non-mutating verification of checked-in generated sources"))
        assertTrue(contractText.contains("commit_requirement: regeneration changes must be committed with the spec or consumer change they represent"))
        assertTrue(contractText.contains("verification_command: ./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources"))
        assertTrue(contractText.contains("editing_rule: regenerate with openApiGenerateHtxGeneralClient instead of editing src/generated/kotlin by hand"))
        assertEquals(expectedOutputPackages, documentedPackages)
        assertEquals(expectedOutputModes, documentedModes)
        assertEquals(
            listOf(
                "libs/htx-client/src/generated/kotlin/borg/trikeshed/htx/client/generated/api",
                "libs/htx-client/src/generated/kotlin/borg/trikeshed/htx/client/generated/infrastructure",
                "libs/htx-client/src/generated/kotlin/borg/trikeshed/htx/client/generated/model",
            ),
            documentedPaths,
        )
        assertEquals(listOf("HtxGeneralApi.kt", "GeneratedRequest.kt", "HealthStatus.kt"), documentedFileNames)
        assertTrue(buildText.contains("tasks.register(\"openApiGenerateHtxGeneralClient\")"))
        assertTrue(buildText.contains("tasks.register(\"verifyHtxGeneralClientGeneratedSources\")"))
        assertTrue(buildText.contains("val generatedSourceRoot = layout.projectDirectory.dir(\"src/generated/kotlin\")"))
        assertTrue(buildText.contains("val generatedPackageRoot = \"borg.trikeshed.htx.client.generated\""))
        assertTrue(buildText.contains("Repository policy: this checked-in file must be regenerated, not edited by hand."))
        assertTrue(readmeText.contains("./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient"))
        assertTrue(readmeText.contains("./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources"))
        assertTrue(readmeText.contains("the generated outputs are checked in for review and must be committed after regeneration"))
        assertTrue(readmeText.contains("verification is non-mutating: `verifyHtxGeneralClientGeneratedSources` checks for drift without rewriting files"))
        assertTrue(readmeText.contains("this repo does not use a verify-only policy for these generated files"))
        assertTrue(readmeText.contains("CI/local validation targets:"))
        assertTrue(readmeText.contains("codegen drift gate: `./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources`"))
        assertTrue(readmeText.contains("generated client contract check: `./gradlew -p libs/htx-client jvmTest --tests borg.trikeshed.htx.client.GeneratedHtxGeneralClientTest`"))
        assertTrue(readmeText.contains("client/server compatibility check: `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.generatedClientRoundTripMatchesOpenApiContract`"))
        assertTrue(readmeText.contains("route drift check: `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.adapterRejectsPathDriftWithConcreteFailureSignals`"))
        assertTrue(readmeText.contains("reviewable contract suite: `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralOpenApiContractTest`"))
    }

    @Test
    fun compatibilityValidationScopeIsDocumentedAsObservableCodegenAndIntegrationCriteria() {
        assertTrue(contract.exists(), "Expected ${contract.path} to exist")
        val text = contract.readText()
        val documentedGeneratedFiles = Regex("(?m)^        - (libs/htx-client/src/generated/kotlin/[^\\s]+\\.kt)\\s*$")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(text.contains("  codegen_run:"))
        assertTrue(text.contains("    passes_when:"))
        assertTrue(text.contains("      generation_command: ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient"))
        assertTrue(text.contains("      task_discovery_command: ./gradlew -p libs/htx-client tasks --all"))
        assertTrue(text.contains("      verification_command: ./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources"))
        assertTrue(text.contains("      exit_code: 0"))
        assertTrue(text.contains("      client_test_command: ./gradlew -p libs/htx-client jvmTest --tests borg.trikeshed.htx.client.GeneratedHtxGeneralClientTest"))
        assertTrue(text.contains("      generated_files_confined_to: libs/htx-client/src/generated/kotlin"))
        assertEquals(expectedDocumentedGeneratedFiles, documentedGeneratedFiles)
        assertTrue(text.contains("The task list for libs/htx-client includes openApiGenerateHtxGeneralClient."))
        assertTrue(text.contains("The generation command exits with status 0."))
        assertTrue(text.contains("./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources confirms the checked-in generated sources are in sync."))
        assertTrue(text.contains("Verification is non-mutating and does not rewrite checked-in generated files."))
        assertTrue(text.contains("Generated outputs are not ignored by git and are committed for review rather than verified-only."))
        assertTrue(text.contains("The generated package root is borg.trikeshed.htx.client.generated."))
        assertTrue(text.contains("The generated API package exposes a GET /health operation bound to operationId getHealth."))
        assertTrue(text.contains("./gradlew -p libs/htx-client jvmTest --tests borg.trikeshed.htx.client.GeneratedHtxGeneralClientTest succeeds after generation."))

        assertTrue(text.contains("  integration_check:"))
        assertTrue(text.contains("      integration_test_command: ./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.generatedClientRoundTripMatchesOpenApiContract"))
        assertTrue(text.contains("      route_drift_test_command: ./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.adapterRejectsPathDriftWithConcreteFailureSignals"))
        assertTrue(text.contains("      server_contract_suite_command: ./gradlew -p libs/server jvmTest"))
        assertTrue(text.contains("      client_adapter: borg.trikeshed.server.HtxGeneralServerAdapter"))
        assertTrue(text.contains("      request:"))
        assertTrue(text.contains("        method: GET"))
        assertTrue(text.contains("        path: /health"))
        assertTrue(text.contains("      expected_response:"))
        assertTrue(text.contains("        status: 200"))
        assertTrue(text.contains("        body: ok"))
        assertTrue(text.contains("      drift_signal:"))
        assertTrue(text.contains("        path: /healthz"))
        assertTrue(text.contains("        status: 404"))
        assertTrue(text.contains("        body: not found"))
        assertTrue(text.contains("./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.generatedClientRoundTripMatchesOpenApiContract succeeds."))
        assertTrue(text.contains("The generated client calls GET /health through borg.trikeshed.server.HtxGeneralServerAdapter against the server context."))
        assertTrue(text.contains("The adapter observes transport status 200 before returning body ok to the generated client."))
        assertTrue(text.contains("The generated client resolves HealthStatus(body = ok) and reports ok == true."))
        assertTrue(text.contains("./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.adapterRejectsPathDriftWithConcreteFailureSignals succeeds."))
        assertTrue(text.contains("GET /healthz returns status 404 with body not found, proving the integration check is route-sensitive."))
        assertTrue(text.contains("acceptance_criteria:"))
        assertTrue(text.contains("criterion: A test or validation target exists that fails when generated htx-client artifacts drift from the authoritative htx-general OpenAPI contract."))
        assertTrue(text.contains("criterion: A reviewable integration check exists for the selected client/server flows between htx-client and htx-general server."))
        assertTrue(text.contains("criterion: Pass/fail output is concrete enough for CI gating, with no reliance on manual inspection."))
        assertTrue(text.contains("pass_fail_signal: exit code 0 plus direct assertion/check failures on drift or incompatibility; no manual inspection."))
    }

    @Test
    fun acceptanceCriteriaStayBoundToConcreteFailureSignalsAndReviewableTargets() {
        assertTrue(contract.exists(), "Expected ${contract.path} to exist")
        assertTrue(clientBuild.exists(), "Expected ${clientBuild.path} to exist")
        assertTrue(clientGeneratedTest.exists(), "Expected ${clientGeneratedTest.path} to exist")
        assertTrue(compatibilityTest.exists(), "Expected ${compatibilityTest.path} to exist")

        val contractText = contract.readText()
        val buildText = clientBuild.readText()
        val generatedClientText = clientGeneratedTest.readText()
        val compatibilityText = compatibilityTest.readText()

        assertTrue(contractText.contains("task: ./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources"))
        assertTrue(contractText.contains("implementation: libs/htx-client/build.gradle.kts"))
        assertTrue(contractText.contains("Checked-in generated outputs differ from the documented policy."))
        assertTrue(contractText.contains("Missing checked-in generated source <relativePath>."))
        assertTrue(contractText.contains("Generated source <relativePath> is stale."))
        assertTrue(buildText.contains("check(actualRelativePaths == generatedOutputRelativePaths)"))
        assertTrue(buildText.contains("Checked-in generated outputs differ from the documented policy."))
        assertTrue(buildText.contains("Missing checked-in generated source \$relativePath."))
        assertTrue(buildText.contains("Generated source \$relativePath is stale."))

        assertTrue(contractText.contains("implementation: libs/server/src/jvmTest/kotlin/borg/trikeshed/server/HtxGeneralClientServerCompatibilityTest.kt"))
        assertTrue(contractText.contains("GET /health -> 200 / ok"))
        assertTrue(contractText.contains("HealthStatus.ok == true"))
        assertTrue(contractText.contains("GET /healthz -> 404 / not found"))
        assertTrue(generatedClientText.contains("assertEquals(HttpMethod.GET, request.method)"))
        assertTrue(generatedClientText.contains("assertEquals(\"/health\", request.path)"))
        assertTrue(generatedClientText.contains("assertEquals(\"getHealth\", HtxGeneralApiContract.GetHealth.operationId)"))
        assertTrue(compatibilityText.contains("fun generatedClientRoundTripMatchesOpenApiContract()"))
        assertTrue(compatibilityText.contains("assertEquals(200, transportResponse.status)"))
        assertTrue(compatibilityText.contains("assertEquals(\"ok\", transportResponse.body)"))
        assertTrue(compatibilityText.contains("assertTrue(response.ok)"))
        assertTrue(compatibilityText.contains("fun adapterRejectsPathDriftWithConcreteFailureSignals()"))
        assertTrue(compatibilityText.contains("path = \"/healthz\""))
        assertTrue(compatibilityText.contains("assertEquals(404, response.status)"))
        assertTrue(compatibilityText.contains("assertEquals(\"not found\", response.body)"))

        assertTrue(contractText.contains("./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources"))
        assertTrue(contractText.contains("./gradlew -p libs/htx-client jvmTest --tests borg.trikeshed.htx.client.GeneratedHtxGeneralClientTest"))
        assertTrue(contractText.contains("./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.generatedClientRoundTripMatchesOpenApiContract"))
        assertTrue(contractText.contains("./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.adapterRejectsPathDriftWithConcreteFailureSignals"))
        assertTrue(contractText.contains("./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralOpenApiContractTest"))
    }

    @Test
    fun codegenTaskExistsAndGeneratesTheDocumentedOutputs() {
        val tasksOutput = runGradle("-p", "libs/htx-client", "tasks", "--all")
        assertEquals(0, tasksOutput.exitCode, tasksOutput.output)
        assertTrue(tasksOutput.output.contains("Code generation tasks"), tasksOutput.output)
        assertTrue(tasksOutput.output.contains("openApiGenerateHtxGeneralClient"), tasksOutput.output)
        assertTrue(tasksOutput.output.contains("verifyHtxGeneralClientGeneratedSources"), tasksOutput.output)

        val codegenOutput = runGradle("-p", "libs/htx-client", "openApiGenerateHtxGeneralClient")
        assertEquals(0, codegenOutput.exitCode, codegenOutput.output)
        assertTrue(
            codegenOutput.output.contains(":openApiGenerateHtxGeneralClient") ||
                codegenOutput.output.contains("openApiGenerateHtxGeneralClient UP-TO-DATE"),
            codegenOutput.output,
        )

        val beforeVerifyContents = snapshotGeneratedContents()
        val verifyOutput = runGradle("-p", "libs/htx-client", "verifyHtxGeneralClientGeneratedSources")
        assertEquals(0, verifyOutput.exitCode, verifyOutput.output)
        assertTrue(verifyOutput.output.contains("verifyHtxGeneralClientGeneratedSources"), verifyOutput.output)
        assertEquals(beforeVerifyContents, snapshotGeneratedContents())

        assertTrue(generatedApiFile.exists(), "Expected ${generatedApiFile.path} to exist after codegen")
        assertTrue(generatedInfrastructureFile.exists(), "Expected ${generatedInfrastructureFile.path} to exist after codegen")
        assertTrue(generatedModelFile.exists(), "Expected ${generatedModelFile.path} to exist after codegen")
        val actualGeneratedFiles = generatedPackageRoot.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(generatedSourceRoot).invariantSeparatorsPath }
            .sorted()
            .toList()
        assertEquals(expectedGeneratedFiles, actualGeneratedFiles)
        assertGeneratedFilesAreNotIgnoredByGit()
        assertTrue(generatedApiFile.canonicalPath.startsWith(generatedSourceRoot.canonicalPath))
        assertTrue(generatedInfrastructureFile.canonicalPath.startsWith(generatedSourceRoot.canonicalPath))
        assertTrue(generatedModelFile.canonicalPath.startsWith(generatedSourceRoot.canonicalPath))

        val apiText = generatedApiFile.readText()
        assertTrue(apiText.contains("package borg.trikeshed.htx.client.generated.api"))
        assertTrue(apiText.contains("Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient."))
        assertTrue(apiText.contains("Repository policy: this checked-in file must be regenerated, not edited by hand."))
        assertTrue(apiText.contains("suspend fun getHealth(): HealthStatus"))
        assertTrue(apiText.contains("const val operationId: String = \"getHealth\""))
        assertTrue(apiText.contains("path = \"/health\""))

        val infrastructureText = generatedInfrastructureFile.readText()
        assertTrue(infrastructureText.contains("package borg.trikeshed.htx.client.generated.infrastructure"))
        assertTrue(infrastructureText.contains("Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient."))
        assertTrue(infrastructureText.contains("enum class HttpMethod"))
        assertTrue(infrastructureText.contains("GET"))

        val modelText = generatedModelFile.readText()
        assertTrue(modelText.contains("package borg.trikeshed.htx.client.generated.model"))
        assertTrue(modelText.contains("Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient."))
        assertTrue(modelText.contains("data class HealthStatus"))
        assertTrue(modelText.contains("body == \"ok\""))

        val clientTestOutput = runGradle(
            "-p",
            "libs/htx-client",
            "jvmTest",
            "--tests",
            "borg.trikeshed.htx.client.GeneratedHtxGeneralClientTest",
        )
        assertEquals(0, clientTestOutput.exitCode, clientTestOutput.output)
    }

    @Test
    fun generatedClientRoundTripMatchesTheServerAdapterAndOpenApiContract() = runTest {
        val context = buildServerContext()
        try {
            val adapter = HtxGeneralServerAdapter(context)
            val request = HtxGeneralApiContract.GetHealth.request

            assertEquals(HttpMethod.GET, request.method)
            assertEquals("/health", request.path)

            val transportResponse = adapter.execute(request)
            assertEquals(200, transportResponse.status)
            assertEquals("ok", transportResponse.body)

            val clientResponse = adapter.client().getHealth()
            assertEquals("getHealth", HtxGeneralApiContract.GetHealth.operationId)
            assertEquals("ok", clientResponse.body)
            assertTrue(clientResponse.ok)

            val pathDrift = adapter.execute(
                GeneratedRequest(
                    method = HttpMethod.GET,
                    path = "/healthz",
                ),
            )
            assertEquals(404, pathDrift.status)
            assertEquals("not found", pathDrift.body)
        } finally {
            closeServerContext(context)
        }
    }

    private fun snapshotGeneratedContents(): Map<String, String> =
        expectedGeneratedFiles.associateWith { relativePath ->
            File(generatedSourceRoot, relativePath).readText()
        }

    private fun assertGeneratedFilesAreNotIgnoredByGit() {
        expectedDocumentedGeneratedFiles.forEach { relativePath ->
            val gitOutput = runCommand("git", "check-ignore", relativePath)
            assertEquals(1, gitOutput.exitCode, gitOutput.output)
        }
    }

    private fun runGradle(vararg arguments: String): GradleRun {
        val process = ProcessBuilder(
            listOf(File(repoRoot, "gradlew").absolutePath, "--no-daemon") + arguments,
        )
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(300, TimeUnit.SECONDS)
        assertTrue(finished, "Gradle command timed out: ${arguments.joinToString(" ")}\n$output")

        return GradleRun(
            exitCode = process.exitValue(),
            output = output,
        )
    }

    private fun runCommand(vararg arguments: String): CommandRun {
        val process = ProcessBuilder(arguments.toList())
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        assertTrue(finished, "Command timed out: ${arguments.joinToString(" ")}\n$output")

        return CommandRun(
            exitCode = process.exitValue(),
            output = output,
        )
    }

    private data class GradleRun(
        val exitCode: Int,
        val output: String,
    )

    private data class CommandRun(
        val exitCode: Int,
        val output: String,
    )
}
