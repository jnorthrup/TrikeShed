package borg.trikeshed.common

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Paths

actual fun readLinesSeq(path: String): Sequence<String> {
    return sequence {
        val lines = borg.trikeshed.userspace.nio.file.Files.lines(Paths.get(path))
        for (line in lines) yield(line)
    }
}

// ⚡ Bolt: Removed redundant `.map { it }` which unnecessarily allocated an O(N) copy of the entire list.
// returning the result of Files.readAllLines directly avoids this overhead.
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
