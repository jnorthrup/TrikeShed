package borg.trikeshed.common

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Paths

actual fun readLinesSeq(path: String): Sequence<String> {
    return sequence {
        val lines = borg.trikeshed.userspace.nio.file.Files.lines(Paths.get(path))
        for (line in lines) yield(line)
    }
}

<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
=======
actual fun readLines(path: String): List<String> = borg.trikeshed.userspace.nio.file.Files.readAllLines(Paths.get(path))
>>>>>>> origin/bolt-optimize-readlines-map-13293556096672935698
=======
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
>>>>>>> origin/bolt-readlines-optimization-1347311716355117455
=======
actual fun readLines(path: String): List<String> = borg.trikeshed.userspace.nio.file.Files.readAllLines(Paths.get(path))
>>>>>>> origin/bolt-remove-redundant-map-1342439825706907454
=======
// ⚡ Bolt: Removed redundant `.map { it }` which unnecessarily allocated an O(N) copy of the entire list.
// returning the result of Files.readAllLines directly avoids this overhead.
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
>>>>>>> origin/bolt-remove-redundant-map-13984665341473829744
=======
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
>>>>>>> origin/bolt-remove-redundant-map-16127802584372370913
