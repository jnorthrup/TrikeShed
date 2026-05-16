package borg.trikeshed.openapi

import borg.trikeshed.lib.SeriesBuffer
import borg.trikeshed.lib.SeriesArrayList

/**
 * CLI entry point for OpenAPI code generation.
 *
 * Usage:
 *   GenerateSourcesKt --spec <yaml-path> --target <name> --output <dir> --sides <client|server|client,server>
 *
 * Reads the OpenAPI YAML spec, resolves all $ref, generates Kotlin sources
 * (client API / server adapter / models), and writes them to the output directory.
 *
 * Environment variable overrides:
 *   FORCE_HTX_CLIENT_GENERATED_PACKAGE — overrides the generated package root
 *   FORCE_HTX_CLIENT_DISPLAY_NAME — overrides the display name used in class names
 */
// RLM: library entrypoint commented out - fun main(args: Array<CharSequence>) {
// RLM: library entrypoint commented out -     val parsed = parseArgs(args)
// RLM: library entrypoint commented out -     val specPath = parsed["--spec"] ?: error("--spec <path> required")
// RLM: library entrypoint commented out -     val target = parsed["--target"] ?: "default"
// RLM: library entrypoint commented out -     val outputDir = parsed["--output"] ?: error("--output <dir> required")
// RLM: library entrypoint commented out -     val sides = parsed["--sides"]?.split(",") ?: listOf("client")
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     val specFile = File(specPath.toString())
// RLM: library entrypoint commented out -     if (!specFile.exists()) error("Spec file not found: $specPath")
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     // 1. Parse YAML → Map<CharSequence, Any?>
// RLM: library entrypoint commented out -     val rawMap = parseYamlToMap(specFile.readText())
// RLM: library entrypoint commented out -     val rawDoc = OpenApiRawDocument(rawMap)
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     // 2. Resolve all $ref → ResolvedOpenApiDocument
// RLM: library entrypoint commented out -     val resolved = rawDoc.resolve()
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     println("GenerateSources: ${resolved.title} v${resolved.version}")
// RLM: library entrypoint commented out -     println("  Operations: ${resolved.operations.size}")
// RLM: library entrypoint commented out -     println("  Target: $target, Sides: $sides")
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     // 3. Generate sources
// RLM: library entrypoint commented out -     val allSources = SeriesBuffer<Pair<CharSequence, CharSequence>>()
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     if (sides.contains("client")) {
// RLM: library entrypoint commented out -         val clientSources = renderAllClientSources(
// RLM: library entrypoint commented out -             doc = resolved,
// RLM: library entrypoint commented out -             specPath = specPath,
// RLM: library entrypoint commented out -             generatorTask = target,
// RLM: library entrypoint commented out -         )
// RLM: library entrypoint commented out -         clientSources.forEach { allSources += it }
// RLM: library entrypoint commented out -     }
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     if (sides.contains("server")) {
// RLM: library entrypoint commented out -         val serverSources = renderAllServerSources(
// RLM: library entrypoint commented out -             doc = resolved,
// RLM: library entrypoint commented out -             specPath = specPath,
// RLM: library entrypoint commented out -             generatorTask = target,
// RLM: library entrypoint commented out -         )
// RLM: library entrypoint commented out -         serverSources.forEach { allSources += it }
// RLM: library entrypoint commented out -     }
// RLM: library entrypoint commented out -
// RLM: library entrypoint commented out -     // 4. Write to output directory
// RLM: library entrypoint commented out -     val outRoot = File(outputDir.toString())
// RLM: library entrypoint commented out -     for (pair in allSources.snapshot()) {
// RLM: library entrypoint commented out -         val relPath = pair.first
// RLM: library entrypoint commented out -         val content = pair.second
// RLM: library entrypoint commented out -         val outFile = File(outRoot, relPath.toString())
// RLM: library entrypoint commented out -         outFile.parentFile.mkdirs()
// RLM: library entrypoint commented out -         outFile.writeText(content.toString())
// RLM: library entrypoint commented out -     }
// RLM: library entrypoint commented out -     println("Done: ${allSources.size} files written to $outRoot")
// RLM: library entrypoint commented out - }

// ── Arg parser ──────────────────────────────────────────────────────────

private fun parseArgs(args: Array<CharSequence>): Map<CharSequence, CharSequence> {
    val result = LongLongSeries.build { putAll(mapOf(<CharSequence, CharSequence>() })
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            result[args[i]] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return result
}

// ── Minimal YAML parser ────────────────────────────────────────────────

/**
 * Minimal YAML-to-Map parser. Handles the subset of YAML used in OpenAPI specs:
 * - Key-value mappings
 * - Sequences (lists)
 * - Scalars (strings, numbers, booleans, null)
 * - Nested mappings and sequences via indentation
 * - Quoted strings (single and double)
 * - Inline flow syntax ({ } and [ ])
 * - Multi-line strings (| and >)
 * - Comments (#)
 *
 * NOT supported: anchors, aliases, tags, complex keys, explicit type indicators.
 */
internal fun parseYamlToMap(yaml: CharSequence): Map<CharSequence, Any?> {
    val lines = yaml.lines()
    return YamlParser(lines).parseDocument()
}

private class YamlParser(private val lines: List<CharSequence>) {
    private var pos = 0

    fun parseDocument(): Map<CharSequence, Any?> {
        skipEmptyAndComments()
        if (pos >= lines.size) return emptyMap()
        val indent = lineIndent(lines[pos])
        return parseBlock(indent) as? Map<CharSequence, Any?> ?: emptyMap()
    }

    private fun parseBlock(baseIndent: Int): Any? {
        skipEmptyAndComments()
        if (pos >= lines.size) return null

        val line = lines[pos]
        val indent = lineIndent(line)
        if (indent < baseIndent) return null

        val trimmed = line.trim()
        return when {
            // Flow map: { ... }
            trimmed.startsWith("{") -> parseFlowMap(trimmed)

            // Flow sequence: [ ... ]
            trimmed.startsWith("[") -> parseFlowSequence(trimmed)

            // Sequence item
            trimmed.startsWith("- ") || trimmed == "-" -> parseSequence(indent)

            // Key-value mapping
            containsUnquotedColon(trimmed) -> parseMapping(indent)

            // Scalar
            else -> parseScalar(trimmed)
        }
    }

    private fun parseMapping(baseIndent: Int): Map<CharSequence, Any?> {
        val map = linkedMapOf<CharSequence, Any?>()
        while (pos < lines.size) {
            skipEmptyAndComments()
            if (pos >= lines.size) break

            val line = lines[pos]
            val indent = lineIndent(line)
            if (indent != baseIndent) break

            val trimmed = line.trim()
            if (!containsUnquotedColon(trimmed)) break

            val colonIdx = findUnquotedColon(trimmed)
            val key = parseScalar(trimmed.substring(0, colonIdx).trim()) as CharSequence
            val afterColon = trimmed.substring(colonIdx + 1).trimStart()

            pos++

            val value: Any? = when {
                // Inline value after colon
                afterColon.isNotEmpty() && afterColon != "|" && afterColon != ">" -> {
                    // Could be a flow map, flow sequence, or scalar
                    parseInlineValue(afterColon)
                }
                // Multi-line literal (|)
                afterColon == "|" -> parseMultilineLiteral(baseIndent + 2)
                // Multi-line folded (>)
                afterColon == ">" -> parseMultilineFolded(baseIndent + 2)
                // Block value on next line
                else -> {
                    skipEmptyAndComments()
                    if (pos < lines.size && lineIndent(lines[pos]) > baseIndent) {
                        parseBlock(baseIndent + 1)
                    } else null
                }
            }
            map[key] = value
        }
        return map
    }

    private fun parseSequence(baseIndent: Int): List<Any?> {
        val list = SeriesBuffer<Any?>()
        while (pos < lines.size) {
            skipEmptyAndComments()
            if (pos >= lines.size) break

            val line = lines[pos]
            val indent = lineIndent(line)
            if (indent != baseIndent) break

            val trimmed = line.trim()
            if (!trimmed.startsWith("- ") && trimmed != "-") break

            val afterDash = if (trimmed.length > 2) trimmed.substring(2).trim() else ""
            pos++

            val item: Any? = when {
                afterDash.isEmpty() -> {
                    skipEmptyAndComments()
                    if (pos < lines.size && lineIndent(lines[pos]) > baseIndent) {
                        parseBlock(baseIndent + 1)
                    } else null
                }
                containsUnquotedColon(afterDash) -> {
                    // Inline map start: push back and parse as mapping at indent+2
                    pos--
                    val itemIndent = baseIndent + 2
                    // The key is on this same line after "- "
                    val keyValuePart = afterDash
                    val colonIdx = findUnquotedColon(keyValuePart)
                    val key = parseScalar(keyValuePart.substring(0, colonIdx).trim()) as CharSequence
                    val afterColon = keyValuePart.substring(colonIdx + 1).trimStart()
                    pos++

                    val value: Any? = when {
                        afterColon.isNotEmpty() -> parseInlineValue(afterColon)
                        else -> {
                            skipEmptyAndComments()
                            if (pos < lines.size && lineIndent(lines[pos]) >= itemIndent) {
                                parseBlock(itemIndent)
                            } else null
                        }
                    }
                    // Continue reading siblings at itemIndent
                    val siblings = LongLongSeries.build { putAll(mapOf(<CharSequence, Any?>() })
                    while (pos < lines.size) {
                        skipEmptyAndComments()
                        if (pos >= lines.size) break
                        val nextLine = lines[pos]
                        val nextIndent = lineIndent(nextLine)
                        if (nextIndent != itemIndent) break
                        val nextTrimmed = nextLine.trim()
                        if (!containsUnquotedColon(nextTrimmed)) break
                        val ci = findUnquotedColon(nextTrimmed)
                        val k = parseScalar(nextTrimmed.substring(0, ci).trim()) as CharSequence
                        val ac = nextTrimmed.substring(ci + 1).trimStart()
                        pos++
                        val v: Any? = when {
                            ac.isNotEmpty() -> parseInlineValue(ac)
                            else -> {
                                skipEmptyAndComments()
                                if (pos < lines.size && lineIndent(lines[pos]) > itemIndent) {
                                    parseBlock(itemIndent + 1)
                                } else null
                            }
                        }
                        siblings[k] = v
                    }
                    siblings
                }
                afterDash.startsWith("[") -> parseFlowSequence(afterDash)
                afterDash.startsWith("{") -> parseFlowMap(afterDash)
                else -> parseScalar(afterDash)
            }
            list.add(item)
        }
        return list
    }

    private fun parseInlineValue(s: CharSequence): Any? = when {
        s.startsWith("{") -> parseFlowMap(s)
        s.startsWith("[") -> parseFlowSequence(s)
        s == "null" || s == "~" -> null
        s == "true" -> true
        s == "false" -> false
        else -> parseScalar(s)
    }

    private fun parseFlowMap(s: CharSequence): Map<CharSequence, Any?> {
        val content = s.trim().removeSurrounding("{", "}")
        if (content.isBlank()) return emptyMap()
        val map = linkedMapOf<CharSequence, Any?>()
        // Simple tokenizer for key:value pairs
        val items = splitFlowItems(content)
        for (item in items) {
            val colonIdx = item.indexOf(':')
            if (colonIdx < 0) continue
            val key = unquote(item.substring(0, colonIdx).trim())
            val valueStr = item.substring(colonIdx + 1).trim()
            map[key] = parseInlineValue(valueStr)
        }
        return map
    }

    private fun parseFlowSequence(s: CharSequence): List<Any?> {
        val content = s.trim().removeSurrounding("[", "]")
        if (content.isBlank()) return emptyList()
        return splitFlowItems(content).map { parseInlineValue(it.trim()) }
    }

    private fun splitFlowItems(s: CharSequence): List<CharSequence> {
        val items = SeriesArrayList<CharSequence>()
        var depth = 0
        val sb = StringBuilder()
        var inQuote: Char? = null
        for (c in s) {
            when {
                inQuote != null -> {
                    sb.append(c)
                    if (c == inQuote) inQuote = null
                }
                c == '"' || c == '\'' -> { inQuote = c; sb.append(c) }
                c == '{' || c == '[' -> { depth++; sb.append(c) }
                c == '}' || c == ']' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { items.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) items.add(sb.toString())
        return items
    }

    private fun parseMultilineLiteral(indent: Int): CharSequence {
        val sb = StringBuilder()
        while (pos < lines.size) {
            val line = lines[pos]
            val lineIndent = lineIndent(line)
            if (line.trim().isEmpty()) { sb.appendLine(); pos++; continue }
            if (lineIndent < indent) break
            sb.appendLine(line.substring(indent))
            pos++
        }
        return sb.toString().trimEnd('\n')
    }

    private fun parseMultilineFolded(indent: Int): CharSequence {
        val sb = StringBuilder()
        while (pos < lines.size) {
            val line = lines[pos]
            val lineIndent = lineIndent(line)
            if (line.trim().isEmpty()) { sb.append(' '); pos++; continue }
            if (lineIndent < indent) break
            sb.append(line.substring(indent)).append(' ')
            pos++
        }
        return sb.toString().trim()
    }

    private fun parseScalar(s: CharSequence): Any? {
        val trimmed = s.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed == "null" || trimmed == "~" -> null
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith('"') && trimmed.endsWith('"') -> unquote(trimmed)
            trimmed.startsWith('\'') && trimmed.endsWith('\'') -> unquote(trimmed)
            trimmed.toString().toDoubleOrNull() != null -> {
                // Keep as Int if no decimal point and fits
                val asLong = trimmed.toString().toLongOrNull()
                if ('.' !in trimmed.toString() && 'e' !in trimmed.toString().lowercase() && asLong != null) asLong
                else trimmed.toString().toDouble()
            }
            else -> unquote(trimmed)
        }
    }

    private fun unquote(s: CharSequence): CharSequence {
        if (s.length >= 2) {
            if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\''))) {
                return s.substring(1, s.length - 1)
            }
        }
        return s
    }

    private fun skipEmptyAndComments() {
        while (pos < lines.size) {
            val trimmed = lines[pos].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { pos++; continue }
            break
        }
    }

    private fun lineIndent(line: CharSequence): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    private fun containsUnquotedColon(s: CharSequence): Boolean {
        var inQ: Char? = null
        for (i in s.indices) {
            val c = s[i]
            when {
                inQ != null -> { if (c == inQ) inQ = null }
                c == '"' || c == '\'' -> inQ = c
                c == ':' && (i + 1 >= s.length || s[i + 1] == ' ' || s[i + 1] == '\t') -> return true
            }
        }
        return false
    }

    private fun findUnquotedColon(s: CharSequence): Int {
        var inQ: Char? = null
        for (i in s.indices) {
            val c = s[i]
            when {
                inQ != null -> { if (c == inQ) inQ = null }
                c == '"' || c == '\'' -> inQ = c
                c == ':' && (i + 1 >= s.length || s[i + 1] == ' ' || s[i + 1] == '\t') -> return i
            }
        }
        return -1
    }
}
