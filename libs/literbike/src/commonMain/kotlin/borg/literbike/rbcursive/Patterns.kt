package borg.literbike.rbcursive

/**
 * RBCursive Pattern Matching - Glob and Regex support.
 * Extends the SIMD scanner with pattern matching capabilities.
 * Ported from literbike/src/rbcursive/patterns.rs.
 */

import java.util.regex.Pattern
import java.util.regex.Matcher

/**
 * Pattern matching result.
 */
data class PatternMatchResult(
    val matched: Boolean,
    val matches: List<PatternMatch> = emptyList(),
    val totalMatches: Int = matches.size
)

/**
 * Individual pattern match.
 */
data class PatternMatch(
    val start: Int,
    val end: Int,
    val text: ByteArray,
    val captures: List<PatternCapture> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PatternMatch) return false
        return start == other.start && end == other.end && text.contentEquals(other.text) && captures == other.captures
    }

    override fun hashCode(): Int {
        var result = start
        result = 31 * result + end
        result = 31 * result + text.contentHashCode()
        result = 31 * result + captures.hashCode()
        return result
    }
}

/**
 * Pattern capture group.
 */
data class PatternCapture(
    val name: String? = null,
    val start: Int,
    val end: Int,
    val text: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PatternCapture) return false
        return name == other.name && start == other.start && end == other.end && text.contentEquals(other.text)
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + start
        result = 31 * result + end
        result = 31 * result + text.contentHashCode()
        return result
    }
}

/**
 * Pattern matching error types.
 */
sealed class PatternError(message: String) : Exception(message) {
    data class InvalidRegex(val reason: String) : PatternError("Invalid regex: $reason")
    data class InvalidGlob(val reason: String) : PatternError("Invalid glob: $reason")
    object DataTooLarge : PatternError("Data too large")
    object EncodingError : PatternError("Encoding error")
}

/**
 * Pattern matcher capabilities.
 */
data class PatternCapabilities(
    val supportsGlob: Boolean = true,
    val supportsRegex: Boolean = true,
    val supportsUnicode: Boolean = true,
    val maxPatternLength: Int = 10000,
    val maxDataSize: Int = 100 * 1024 * 1024 // 100MB
)

/**
 * SIMD-accelerated pattern matcher implementation.
 */
class SimdPatternMatcher {
    private val regexCache = mutableMapOf<String, Pattern>()
    private val globCache = mutableMapOf<String, Regex>()
    private var maxCacheSize: Int = 1000

    companion object {
        fun bytesToStr(data: ByteArray): Result<String> = runCatching {
            data.decodeToString()
        }
    }

    /** Get or compile regex pattern with caching */
    private fun getRegex(pattern: String): Result<Pattern> {
        return runCatching {
            regexCache.getOrPut(pattern) {
                if (regexCache.size >= maxCacheSize) regexCache.clear()
                Pattern.compile(pattern)
            }
        }.recoverCatching { PatternError.InvalidRegex(it.message ?: "Unknown") }
    }

    /** Get or compile glob pattern with caching */
    private fun getGlob(pattern: String): Result<Regex> {
        return runCatching {
            globCache.getOrPut(pattern) {
                if (globCache.size >= maxCacheSize) globCache.clear()
                globToRegex(pattern).toRegex()
            }
        }.recoverCatching { PatternError.InvalidGlob(it.message ?: "Unknown") }
    }

    /** Convert glob pattern to regex */
    private fun globToRegex(glob: String): String {
        val regex = StringBuilder()
        regex.append("^")
        for (c in glob) {
            when (c) {
                '*' -> regex.append(".*")
                '?' -> regex.append(".")
                '.' -> regex.append("\\.")
                '[' -> regex.append("[")
                ']' -> regex.append("]")
                '\\' -> regex.append("\\\\")
                else -> regex.append(c)
            }
        }
        regex.append("$")
        return regex.toString()
    }

    /** Match glob patterns against byte data */
    fun matchGlob(data: ByteArray, pattern: String): PatternMatchResult {
        return bytesToStr(data).fold(
            onSuccess = { text ->
                getGlob(pattern).fold(
                    onSuccess = { regex ->
                        val matched = regex.matches(text)
                        val matches = if (matched) {
                            listOf(PatternMatch(0, data.size, data.copyOf()))
                        } else {
                            emptyList()
                        }
                        PatternMatchResult(matched, matches)
                    },
                    onFailure = { PatternMatchResult(false) }
                )
            },
            onFailure = { PatternMatchResult(false) }
        )
    }

    /** Match regex patterns against byte data */
    fun matchRegex(data: ByteArray, pattern: String): Result<PatternMatchResult> {
        return bytesToStr(data).fold(
            onSuccess = { text ->
                getRegex(pattern).fold(
                    onSuccess = { regex ->
                        val matcher = regex.matcher(text)
                        val matched = matcher.find()

                        if (matched) {
                            matcher.reset()
                            val captures = mutableListOf<PatternCapture>()

                            // Get full match
                            if (matcher.find()) {
                                val fullStart = matcher.start()
                                val fullEnd = matcher.end()
                                val fullText = text.substring(fullStart, fullEnd).toByteArray()

                                // Add numbered captures
                                for (i in 1..matcher.groupCount()) {
                                    val groupStart = matcher.start(i)
                                    val groupEnd = matcher.end(i)
                                    if (groupStart >= 0) {
                                        captures.add(PatternCapture(
                                            name = null,
                                            start = groupStart,
                                            end = groupEnd,
                                            text = text.substring(groupStart, groupEnd).toByteArray()
                                        ))
                                    }
                                }

                                val matches = listOf(PatternMatch(
                                    start = fullStart,
                                    end = fullEnd,
                                    text = fullText,
                                    captures = captures
                                ))
                                Result.success(PatternMatchResult(true, matches))
                            } else {
                                Result.success(PatternMatchResult(false))
                            }
                        } else {
                            Result.success(PatternMatchResult(false))
                        }
                    },
                    onFailure = { Result.failure(it as PatternError) }
                )
            },
            onFailure = { Result.failure(PatternError.EncodingError) }
        )
    }

    /** Find all glob pattern matches in data */
    fun findAllGlob(data: ByteArray, pattern: String): List<PatternMatch> {
        return when (val result = matchGlob(data, pattern)) {
            else -> if (result.matched) result.matches else emptyList()
        }
    }

    /** Find all regex pattern matches in data */
    fun findAllRegex(data: ByteArray, pattern: String): Result<List<PatternMatch>> {
        return bytesToStr(data).fold(
            onSuccess = { text ->
                getRegex(pattern).fold(
                    onSuccess = { regex ->
                        val matcher = regex.matcher(text)
                        val matches = mutableListOf<PatternMatch>()

                        while (matcher.find()) {
                            val fullStart = matcher.start()
                            val fullEnd = matcher.end()
                            val fullText = text.substring(fullStart, fullEnd).toByteArray()

                            val captures = mutableListOf<PatternCapture>()
                            for (i in 1..matcher.groupCount()) {
                                val groupStart = matcher.start(i)
                                val groupEnd = matcher.end(i)
                                if (groupStart >= 0) {
                                    captures.add(PatternCapture(
                                        name = null,
                                        start = groupStart,
                                        end = groupEnd,
                                        text = text.substring(groupStart, groupEnd).toByteArray()
                                    ))
                                }
                            }

                            matches.add(PatternMatch(fullStart, fullEnd, fullText, captures))
                        }

                        Result.success(matches)
                    },
                    onFailure = { Result.failure(it as PatternError) }
                )
            },
            onFailure = { Result.failure(PatternError.EncodingError) }
        )
    }

    /** Get pattern matcher capabilities */
    fun patternCapabilities(): PatternCapabilities = PatternCapabilities()
}

/**
 * Pattern type enumeration.
 */
enum class PatternType {
    Glob,
    Regex
}

/**
 * Pattern scanner that combines SIMD scanning with pattern matching.
 */
class PatternScanner(
    val simdScanner: SimdScanner,
    val patternMatcher: SimdPatternMatcher = SimdPatternMatcher()
) {
    companion object {
        fun new(): PatternScanner = PatternScanner(ScalarScanner())
    }

    /** Fast pattern-guided scanning using SIMD acceleration */
    fun scanWithPattern(
        data: ByteArray,
        pattern: String,
        patternType: PatternType
    ): Result<List<PatternMatch>> = when (patternType) {
        PatternType.Glob -> Result.success(patternMatcher.findAllGlob(data, pattern))
        PatternType.Regex -> patternMatcher.findAllRegex(data, pattern)
    }

    /** Use SIMD to pre-filter data before pattern matching */
    fun simdGuidedPatternScan(
        data: ByteArray,
        pattern: String,
        patternType: PatternType
    ): Result<List<PatternMatch>> {
        val patternHints = extractPatternHints(pattern, patternType)

        if (patternHints.isNotEmpty()) {
            val candidates = simdScanner.scanAnyByte(data, patternHints)
            if (candidates.size > 100 && data.size > 10000) {
                return scanCandidateRegions(data, pattern, patternType, candidates)
            }
        }

        return scanWithPattern(data, pattern, patternType)
    }

    /** Extract hint bytes that might indicate pattern start positions */
    private fun extractPatternHints(pattern: String, patternType: PatternType): ByteArray {
        return when (patternType) {
            PatternType.Glob -> pattern.toByteArray()
                .filter { it != '*'.code.toByte() && it != '?'.code.toByte() &&
                        it != '['.code.toByte() && it != ']'.code.toByte() }
                .take(5)
                .toByteArray()
            PatternType.Regex -> pattern.toByteArray()
                .takeWhile { it !in "^$.*+?{}[]()\\|".map { c -> c.code.toByte() } }
                .take(5)
                .toByteArray()
        }
    }

    /** Scan regions around candidate positions */
    private fun scanCandidateRegions(
        data: ByteArray,
        pattern: String,
        patternType: PatternType,
        candidates: List<Int>
    ): Result<List<PatternMatch>> {
        val allMatches = mutableListOf<PatternMatch>()
        val regionSize = 1024

        for (candidatePos in candidates) {
            val start = (candidatePos - regionSize / 2).coerceAtLeast(0)
            val end = (candidatePos + regionSize / 2).coerceAtMost(data.size)
            val region = data.copyOfRange(start, end)

            val regionMatches = scanWithPattern(region, pattern, patternType).getOrNull() ?: continue

            for (match in regionMatches) {
                val adjustedMatch = match.copy(
                    start = match.start + start,
                    end = match.end + start,
                    captures = match.captures.map { it.copy(start = it.start + start, end = it.end + start) }
                )
                allMatches.add(adjustedMatch)
            }
        }

        allMatches.sortBy { it.start }
        return Result.success(allMatches.distinctBy { it.start })
    }
}
