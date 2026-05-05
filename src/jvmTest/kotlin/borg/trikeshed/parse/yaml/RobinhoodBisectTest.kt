package borg.trikeshed.parse.yaml

import kotlin.test.Test
import kotlin.test.assertTrue

class RobinhoodBisectTest {
    private val specFile = java.io.File("/Users/jim/work/TrikeShed/libs/rhood/robinhood.openapi.yaml")
    private val fullSpec = specFile.readText()
    private val lines = fullSpec.lines()

    @Test
    fun `bisect robinhood spec components boundary`() {
        // Find the line where paths: starts
        val pathsLine = lines.indexOfFirst { it == "paths:" }
        println("paths: at line ${pathsLine + 1}")
        
        // Try parsing just components + paths (skip to components:)
        val componentsLine = lines.indexOfFirst { it.startsWith("components:") }
        println("components: at line ${componentsLine + 1}")

        // Parse just the paths section to confirm they parse alone
        val pathsOnly = lines.drop(pathsLine).joinToString("\n")
        val pathsResult = parse(pathsOnly)
        val p = pathsResult["paths"] as? Map<*, *>
        println("paths-only count: ${p?.size}")

        // Parse components only (up to paths)
        val componentsOnly = lines.subList(componentsLine, pathsLine).joinToString("\n") + "\n"
        val compResult = parse(componentsOnly)
        println("components-only top keys: ${compResult.keys}")

        // Parse openapi + info + components up to line N, then paths
        for (n in listOf(50, 100, 150, 200, 250, componentsLine)) {
            val prefix = lines.subList(0, n).joinToString("\n") + "\n"
            try {
                val r = parse(prefix)
                println("first $n lines: top keys = ${r.keys}")
            } catch (e: Exception) {
                println("first $n lines: EXCEPTION ${e.message?.take(80)}")
            }
        }

        // The real test: full spec
        val full = parse(fullSpec)
        println("FULL top keys: ${full.keys}")
        val paths = full["paths"] as? Map<*, *>
        println("FULL paths count: ${paths?.size}")
    }
}
