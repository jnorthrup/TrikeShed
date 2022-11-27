package borg.trikeshed.common

import java.nio.file.Files
import java.nio.file.Paths

actual fun readLinesSeq(path: String): Sequence<String> {
    return sequence {
        val lines = Files.lines(Paths.get(path))
        for (line in lines) yield(line)
    }
}

actual fun readLines(path: String): List<String> =Files.readAllLines( Paths.get(path)).map { it }