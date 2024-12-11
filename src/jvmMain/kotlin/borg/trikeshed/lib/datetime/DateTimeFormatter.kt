package borg.trikeshed.lib.datetime

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

actual fun getCurrentDateTime(): DateTimeComponents {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    return DateTimeComponents(
        dayOfWeek = now.dayOfWeek.toString().take(3),
        day = now.dayOfMonth,
        month = now.month.toString().take(3),
        year = now.year,
        hour = now.hour,
        minute = now.minute,
        second = now.second,
        timeZone = "GMT"
    )
}
