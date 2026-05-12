@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

import kotlin.time.DurationUnit
import kotlinx.datetime.Instant

public class FileTime private constructor(private val millis: Long) : Comparable<FileTime> {

    fun to(unit: DurationUnit): Long = when (unit) {
        DurationUnit.MILLISECONDS -> millis
        DurationUnit.SECONDS -> millis / 1000L
        DurationUnit.MINUTES -> millis / 60_000L
        DurationUnit.HOURS -> millis / 3_600_000L
        DurationUnit.DAYS -> millis / 86_400_000L
        DurationUnit.MICROSECONDS -> millis * 1000L
        DurationUnit.NANOSECONDS -> millis * 1_000_000L
        else -> millis
    }

    fun toMillis(): Long = millis

    fun toInstant(): Instant = Instant.fromEpochMilliseconds(millis)

    override fun equals(other: Any?): Boolean =
        this === other || (other is FileTime && millis == other.millis)

    override fun hashCode(): Int = millis.hashCode()

    override fun compareTo(other: FileTime): Int = millis.compareTo(other.millis)

    override fun toString(): String= toInstant().toString()

    companion object {
        fun from(value: Long, unit: DurationUnit): FileTime = when (unit) {
            DurationUnit.MILLISECONDS -> FileTime(value)
            DurationUnit.SECONDS -> FileTime(value * 1000L)
            DurationUnit.MINUTES -> FileTime(value * 60_000L)
            DurationUnit.HOURS -> FileTime(value * 3_600_000L)
            DurationUnit.DAYS -> FileTime(value * 86_400_000L)
            DurationUnit.MICROSECONDS -> FileTime(value / 1000L)
            DurationUnit.NANOSECONDS -> FileTime(value / 1_000_000L)
            else -> FileTime(value)
        }

        fun fromMillis(value: Long): FileTime = FileTime(value)

        fun from(instant: Instant): FileTime = FileTime(instant.toEpochMilliseconds())
    }
}
