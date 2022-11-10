package borg.trikeshed.common

import java.nio.file.Files
import java.nio.file.Paths

actual fun readLinesSeq(path: String): Sequence<String> {
    return sequence {
        val lines = Files.lines(java.nio.file.Paths.get(path))
        for (line in lines) {
            yield(/*CharSeries*/(line))
        }
    }
}

actual fun readLines(path: String): List<String> =Files.readAllLines( Paths.get(path)).map { /*CharSeries*/(it) }