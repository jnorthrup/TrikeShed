package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.*

object std {
    val ws: KursiveParser<CharSeries> = parser("ws") { input ->
        val start = input.pos
        input.skipWhitespace()
        input.slice(start)
    }

    val lineBreak: KursiveParser<CharSeries> = parser("lineBreak") { input ->
        val start = input.pos
        when {
            input.consume('\n') -> input.slice(start)
            input.consume('\r') -> {
                input.consume('\n')
                input.slice(start)
            }
            else -> null
        }
    }

    fun ch(expected: Char): KursiveParser<CharSeries> = parser("ch($expected)") { input ->
        val start = input.pos
        if (input.consume(expected)) input.slice(start) else null
    }

    fun lit(text: String): KursiveParser<CharSeries> = parser("lit($text)") { input ->
        val start = input.pos
        if (input.consume(text.toSeries())) input.slice(start) else null
    }

    fun takeWhile(name: String, min: Int = 1, predicate: (Char) -> Boolean): KursiveParser<CharSeries> =
        parser(name) { input ->
            val start = input.pos
            while (input.peek()?.let(predicate) == true) input.advance()
            if (input.pos - start >= min) input.slice(start) else null
        }

    fun takeUntil(name: String, min: Int = 1, stop: (Char) -> Boolean): KursiveParser<CharSeries> =
        parser(name) { input ->
            val start = input.pos
            while (input.hasRemaining && input.peek()?.let(stop) != true) input.advance()
            if (input.pos - start >= min) input.slice(start) else null
        }

    val quoted: KursiveParser<CharSeries> = parser("quoted") { input ->
        ch('"')(input) ?: return@parser null
        val start = input.pos
        var escaped = false
        while (input.hasRemaining) {
            val c = input.peek() ?: break
            input.advance()
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '"' -> return@parser input.slice(start, input.pos - 1)
            }
        }
        null
    }

    val number: KursiveParser<CharSeries> = parser("number") { input ->
        val start = input.pos
        if (input.peek() == '+' || input.peek() == '-') input.advance()

        var sawDigit = false
        while (input.peek()?.isDigit() == true) {
            sawDigit = true
            input.advance()
        }

        if (input.peek() == '.') {
            input.advance()
            while (input.peek()?.isDigit() == true) {
                sawDigit = true
                input.advance()
            }
        }

        if (input.peek() == 'e' || input.peek() == 'E') {
            val exponentStart = input.checkpoint()
            input.advance()
            if (input.peek() == '+' || input.peek() == '-') input.advance()
            var exponentDigits = 0
            while (input.peek()?.isDigit() == true) {
                exponentDigits++
                input.advance()
            }
            if (exponentDigits == 0) input.rewind(exponentStart)
        }

        if (!sawDigit) null else input.slice(start)
    }

    fun <T> separated(
        name: String,
        item: KursiveParser<T>,
        separator: KursiveParser<*>,
        allowEmpty: Boolean = true,
    ): KursiveParser<Series<T>> = parser(name) { input ->
        val items = SeriesBuffer<T>()
        val first = item(input)
        if (first == null) return@parser if (allowEmpty) emptySeries() else null
        items.add(first)

        while (true) {
            val checkpoint = input.checkpoint()
            if (separator(input) == null) break
            val next = item(input)
            if (next == null) {
                input.rewind(checkpoint)
                break
            }
            items.add(next)
        }
        items.snapshot()
    }

    fun trimmed(name: String, delegate: KursiveParser<CharSeries>): KursiveParser<CharSeries> = parser(name) { input ->
        delegate(input)?.let { CharSeries(it).trim.slice }
    }

    fun restOfLine(name: String): KursiveParser<CharSeries> =
        trimmed(name, takeUntil("${name}Raw", min = 1) { it == '\n' || it == '\r' })

    // step shorthands for infix grammar composition
    val wss: KursiveStep get() = ws.s
    val nums: KursiveStep get() = number.s
    val qs: KursiveStep get() = quoted.s
}
