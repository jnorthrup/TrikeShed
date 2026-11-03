package borg.trikeshed.agent.modelmux

import borg.trikeshed.htx.HtxHeaders
import borg.trikeshed.lib.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Quota facts reported by one HTTP response; absence remains unknown. */
data class HttpQuota(
    val limit: Long?,
    val remaining: Long?,
    val resetAtMs: Long,
    val exhausted: Boolean,
) {
    companion object {
        fun parse(status: Int, headers: HtxHeaders, nowMs: Long, fallbackMs: Long = 60_000L): HttpQuota {
            fun header(vararg names: String): String? = headers.view
                .firstOrNull { row -> names.any { row.a.equals(it, ignoreCase = true) } }?.b
            fun count(vararg names: String): Long? = header(*names)?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
            val limit = count("x-ratelimit-limit-tokens", "anthropic-ratelimit-tokens-limit")
            val remaining = count("x-ratelimit-remaining-tokens", "anthropic-ratelimit-tokens-remaining")
            val requests = count("x-ratelimit-remaining-requests", "anthropic-ratelimit-requests-remaining")
            val tokenReset = reset(header("x-ratelimit-reset-tokens", "anthropic-ratelimit-tokens-reset"), nowMs)
            val requestReset = reset(header("x-ratelimit-reset-requests", "anthropic-ratelimit-requests-reset"), nowMs)
            val retry = reset(header("retry-after"), nowMs)
            val exhausted = status == 429 || remaining == 0L || requests == 0L
            val resetAt = if (exhausted) {
                maxOf(
                    if (remaining == 0L) tokenReset ?: 0L else 0L,
                    if (requests == 0L) requestReset ?: 0L else 0L,
                    retry ?: 0L,
                ).takeIf { it > nowMs }
            } else tokenReset
            return HttpQuota(limit, remaining, resetAt ?: (nowMs + fallbackMs), exhausted)
        }

        /** Provider reset formats: relative seconds, unit durations, epoch seconds/ms, ISO time, or HTTP-date. */
        fun reset(value: String?, nowMs: Long): Long? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }?.let {
                return when {
                    it >= 1_000_000_000_000.0 -> it.toLong()
                    it >= 1_000_000_000.0 -> (it * 1000.0).toLong()
                    else -> nowMs + (it * 1000.0).toLong()
                }
            }
            runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()?.let { return it }
            httpDate(raw)?.let { return it }
            val parts = Regex("""(\d+(?:\.\d+)?)(ms|s|m|h|d)""").findAll(raw).toList()
            if (parts.isEmpty() || parts.joinToString("") { it.value } != raw) return null
            val duration = parts.sumOf {
                it.groupValues[1].toDouble() * when (it.groupValues[2]) {
                    "ms" -> 1.0
                    "s" -> 1000.0
                    "m" -> 60_000.0
                    "h" -> 3_600_000.0
                    else -> 86_400_000.0
                }
            }
            return duration.takeIf { it.isFinite() }?.let { nowMs + it.toLong() }
        }

        /** IMF-fixdate, the fixed-width UTC form used by HTTP Retry-After. */
        private fun httpDate(raw: String): Long? {
            if (raw.length != 29) return null
            val match = Regex("""(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT""").matchEntire(raw) ?: return null
            val (weekday, day, month, year, hour, minute, second) = match.destructured
            val monthNumber = when (month) {
                "Jan" -> "01"; "Feb" -> "02"; "Mar" -> "03"; "Apr" -> "04"
                "May" -> "05"; "Jun" -> "06"; "Jul" -> "07"; "Aug" -> "08"
                "Sep" -> "09"; "Oct" -> "10"; "Nov" -> "11"; "Dec" -> "12"
                else -> return null
            }
            val instant = runCatching { Instant.parse("$year-$monthNumber-${day}T$hour:$minute:${second}Z") }.getOrNull() ?: return null
            if (!instant.toLocalDateTime(TimeZone.UTC).dayOfWeek.name.startsWith(weekday.uppercase())) return null
            return instant.toEpochMilliseconds()
        }
    }
}
