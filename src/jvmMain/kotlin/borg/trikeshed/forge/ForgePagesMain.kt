package borg.trikeshed.forge

import java.io.File

fun main(args: Array<String>) {
    val outputDir = File(args.getOrElse(0) { "docs" }).absoluteFile
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }

    File(outputDir, "index.html").writeText(forgeAtlasHtml())
    File(outputDir, ".nojekyll").writeText("\n")

    println("Generated GitHub Pages site at ${outputDir.absolutePath}")
    println("- ${File(outputDir, "index.html").absolutePath}")
    println("- ${File(outputDir, ".nojekyll").absolutePath}")
}
