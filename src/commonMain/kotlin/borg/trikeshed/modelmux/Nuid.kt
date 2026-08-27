package borg.trikeshed.modelmux

/**
 * Nuid — network-unique identifier, the host part of a task address
 * (scope prefix + NUID suffix ≙ network part + host part). ~40 lines on
 * purpose: a seeded prefix plus a per-process counter over base62, collision-
 * free within a process and collision-negligible across processes for any
 * realistic box count. No NATS import, no UUID library, no wall clock in the
 * value itself — monotonic within a process, prefix-stable across restarts
 * when seeded.
 */
class Nuid private constructor(private val prefix: String) {
    private var counter: Long = 0L

    /** Next identifier: `<prefix><counter in base62>` — lexicographic order == allocation order. */
    @Synchronized
    fun next(): String {
        var c = counter
        counter = if (counter == Long.MAX_VALUE) 0L else counter + 1
        return prefix + encode(c)
    }

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val BASE = 62L

        /** Seeded prefix — same seed across restarts keeps the address space stable. */
        fun seeded(seed: String): Nuid = Nuid(sanitize(seed))

        /** Random-ish default prefix: process-unique without coordination. */
        fun ephemeral(): Nuid = Nuid(sanitize(defaultProcessPrefix()))

        /** Base62 encode a non-negative counter. */
        fun encode(value: Long): String {
            require(value >= 0) { "counter must be non-negative" }
            if (value == 0L) return "0"
            var v = value
            val sb = StringBuilder()
            while (v > 0) {
                sb.append(ALPHABET[(v % BASE).toInt()])
                v /= BASE
            }
            return sb.reverse().toString()
        }

        fun decode(s: String): Long {
            var v = 0L
            for (ch in s) {
                val d = ALPHABET.indexOf(ch)
                require(d >= 0) { "not a base62 digit: $ch" }
                v = v * BASE + d
            }
            return v
        }

        private fun sanitize(seed: String): String {
            val kept = seed.filter { it.isLetterOrDigit() }
            require(kept.isNotEmpty()) { "seed must contain at least one alphanumeric char" }
            return kept
        }

        private fun defaultProcessPrefix(): String =
            (System.getProperty("user.name") ?: "nuid").filter { it.isLetterOrDigit() }.take(8)
    }
}
