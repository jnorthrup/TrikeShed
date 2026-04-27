package borg.trikeshed.openapi

import java.io.File

/**
 * Generates client and server Kotlin sources from an OpenAPI spec.
 * Usage:
 *   kotlin -cp openapi.jar borg.trikeshed.openapi.GenerateSourcesKt \\
 *     --spec <path-to-spec.yaml> \\
 *     --target <target-lib-name> \\
 *     --output <output-directory> \\
 *     --sides client,server
 */
fun main(args: Array<String>) {
    val opts = args.toList()

    val specPath = opts.require("--spec")
    val targetLib = opts.require("--target")
    val outputDir = File(opts.require("--output"))
    val sides = opts.option("--sides")?.split(',') ?: listOf("client", "server")

    val specText = File(specPath).readText()
    val rawDoc = OpenApiRawParser.parse(specText)
    val resolved = rawDoc.resolve()

    val taskName = "generate${targetLib.replaceFirstChar { it.uppercase() }}Sources"

    val sources = mutableMapOf<String, String>()

    if ("client" in sides) {
        sources.putAll(renderAllClientSources(resolved, specPath, taskName))
    }
    if ("server" in sides) {
        sources.putAll(renderAllServerSources(resolved, specPath, taskName))
    }

    for ((relativePath, content) in sources) {
        val file = File(outputDir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        println("Generated: $file")
    }

    println("Done — ${sources.size} files generated.")
}
fun List<String>.option(key: String): String? =
    indexOf(key).takeIf { it >= 0 }?.let { get(it + 1) }
fun List<String>.require(key: String): String =
    option(key) ?: throw IllegalArgumentException("Missing required argument: $key")
