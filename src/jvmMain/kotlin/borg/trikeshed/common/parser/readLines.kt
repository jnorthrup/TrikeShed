package borg.trikeshed.common.parser

import borg.trikeshed.common.parser.simple.CharSeries

actual fun readLines(path: String): Sequence<CharSeries> {
    return sequence {
        val lines = java.nio.file.Files.lines(java.nio.file.Paths.get(path))
        for (line in lines) {
            yield(CharSeries(line))
        }
    }
}