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
=======
actual fun readLines(path: String): List<String> = borg.trikeshed.userspace.nio.file.Files.readAllLines(Paths.get(path))
>>>>>>> origin/bolt-remove-redundant-map-16172767021023886607
=======
actual fun readLines(path: String): List<String> = borg.trikeshed.userspace.nio.file.Files.readAllLines(Paths.get(path))
>>>>>>> origin/bolt-remove-redundant-map-readlines-17089873818167771253
=======
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path)) // ⚡ Bolt: removed redundant .map { it } which allocates an intermediate ArrayList O(N)
>>>>>>> origin/bolt/avoid-redundant-identity-map-16165159785968055024
=======
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
>>>>>>> origin/bolt/avoid-redundant-map-allocation-5919844668792147379
=======
actual fun readLines(path: String): List<String> =borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))
>>>>>>> origin/bolt/map-identity-remove-9778445747226041844
