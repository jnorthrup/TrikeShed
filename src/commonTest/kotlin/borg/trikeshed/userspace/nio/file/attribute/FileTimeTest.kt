package borg.trikeshed.userspace.nio.file.attribute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

class FileTimeTest {
    @Test
    fun fileTimeFromMillisConvertsToInstant() {
        val millis = 1_700_000_000_000L
        val fileTime = FileTime.fromMillis(millis)

        assertEquals(millis, fileTime.toMillis())
        assertEquals(Instant.fromEpochMilliseconds(millis), fileTime.toInstant())
    }

    @Test
    fun fileTimeComparisonUsesEpochMillis() {
        val earlier = FileTime.fromMillis(1_000L)
        val later = FileTime.fromMillis(2_000L)

        assertEquals(-1, earlier.compareTo(later))
        assertEquals(1, later.compareTo(earlier))
        assertEquals(0, earlier.compareTo(FileTime.fromMillis(1_000L)))
    }
}
