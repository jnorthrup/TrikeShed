package borg.trikeshed.lib.datetime

import kotlinx.cinterop.*
import platform.posix.*

actual fun getCurrentDateTime(): DateTimeComponents = memScoped {
    val timePtr = alloc<time_tVar>()
    time(timePtr.ptr)
    
    val tmPtr = localtime(timePtr.ptr)?.pointed ?: throw RuntimeException("Failed to get local time")
    
    // Convert tm struct fields to our DateTimeComponents format
    val weekDays = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    
    DateTimeComponents(
        dayOfWeek = weekDays[tmPtr.tm_wday],
        day = tmPtr.tm_mday,
        month = months[tmPtr.tm_mon],
        year = 1900 + tmPtr.tm_year,
        hour = tmPtr.tm_hour,
        minute = tmPtr.tm_min,
        second = tmPtr.tm_sec,
        timeZone = "GMT" // Note: POSIX implementation defaults to GMT
    )
}
