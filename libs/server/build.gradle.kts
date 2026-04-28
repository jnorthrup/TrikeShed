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
    gradlePluginPortal()
    google()
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
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-core").get()}")
                api(project(":"))
                api(project(":libs:quic"))
                api(project(":libs:ngsctp"))
                api(project(":libs:htx-client"))
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

// Real generator task for server-side API support files — derived from x-trikeshed-context extensions in the spec.
val htxGeneralOpenApiSpec = layout.projectDirectory.file("openapi/htx-general.openapi.yaml")
val serverGeneratedSourceRoot = layout.projectDirectory.dir("src/generated/kotlin")
val serverGeneratedPackageRoot = "borg.trikeshed.server.generated"
val serverGeneratedPackagePath = serverGeneratedPackageRoot.replace('.', '/')

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

data class ServerContract(
    val operationId: String,
    val supervisorOperationIds: List<String>,
    val serverBindings: List<ContextBinding>,
)

fun parseServerContextBindings(specText: String): List<ContextBinding> {
    val contextStart = specText.indexOf("x-trikeshed-context:")
    if (contextStart < 0) return emptyList()
    val afterContext = specText.substring(contextStart)
    val blockRegex = Regex("""(?m)^  server:\n((?:(?:    |\t).*\n?)*)""")
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

fun parseServerContract(specText: String, specPath: String): ServerContract {
    val operationId = Regex("""(?m)^      operationId:\s*([A-Za-z0-9_]+)\s*$""")
        .find(specText)?.groupValues?.get(1)
        ?: error("Unable to resolve operationId from $specPath")
    val supervisorIds = Regex("""(?ms)operationId:\s*(\S+).*?x-trikeshed-supervisor:\s*true""")
        .findAll(specText).map { it.groupValues[1] }.toList()
    return ServerContract(
        operationId = operationId,
        supervisorOperationIds = supervisorIds,
        serverBindings = parseServerContextBindings(specText),
    )
}

fun renderServerGeneratedSources(contract: ServerContract): Map<String, String> {
    val bindings = contract.serverBindings

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
        append("    const val operationId: String = \"${contract.operationId}\"")
    }

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

    val supervisorBody = buildString {
        contract.supervisorOperationIds.forEachIndexed { i, opId ->
            if (i > 0) appendLine()
            append("    fun $opId(parent: Job? = null): Job = SupervisorJob(parent)")
        }
    }

    return mapOf(
        "$serverGeneratedPackagePath/Keys.kt" to
            "package $serverGeneratedPackageRoot\n\n$keysImports\n\nobject Keys {\n$keysBody\n}\n",
        "$serverGeneratedPackagePath/Elements.kt" to
            "package $serverGeneratedPackageRoot\n\n$elementsImports\n\nobject Elements {\n$elementsBody\n}\n",
        "$serverGeneratedPackagePath/SupervisorJobs.kt" to
            "package $serverGeneratedPackageRoot\n\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.SupervisorJob\n\nobject SupervisorJobs {\n$supervisorBody\n}\n",
    )
}

val openApiGenerateHtxGeneralServer = tasks.register("openApiGenerateHtxGeneralServer") {
    group = "code generation"
    description = "Generates server support files from x-trikeshed-context extensions in htx-general.openapi.yaml"

    inputs.file(htxGeneralOpenApiSpec)
    outputs.dir(serverGeneratedSourceRoot)

    doLast {
        val specFile = htxGeneralOpenApiSpec.asFile
        require(specFile.exists()) { "Missing authoritative OpenAPI input: ${specFile.path}" }

        val contract = parseServerContract(specFile.readText(), specFile.path)
        val pkgDir = serverGeneratedSourceRoot.asFile.resolve(serverGeneratedPackagePath)
        pkgDir.mkdirs()

        renderServerGeneratedSources(contract).forEach { (relativePath, content) ->
            serverGeneratedSourceRoot.asFile.resolve(relativePath).writeText(content)
        }
    }
}
