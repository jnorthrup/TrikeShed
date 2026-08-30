package borg.trikeshed.common

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Paths

actual fun readLinesSeq(path: String): Sequence<String> {
    return sequence {
        val lines = borg.trikeshed.userspace.nio.file.Files.lines(Paths.get(path))
        for (line in lines) yield(line)
    }
}

actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
