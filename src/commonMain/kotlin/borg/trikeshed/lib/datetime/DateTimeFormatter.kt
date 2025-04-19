package borg.trikeshed.lib.datetime

expect fun getCurrentDateTime(): DateTimeComponents

data class DateTimeComponents(
    val dayOfWeek: String, // e.g., Mon, Tue
    val day: Int,          // Day of the month
    val month: String,     // e.g., Jan, Feb
    val year: Int,         // Full year
    val hour: Int,         // 0-23 hour
    val minute: Int,       // 0-59 minute
    val second: Int,       // 0-59 second
    val timeZone: String   // Time zone, e.g., GMT
)

//fun formatRfc1123(dateTime: DateTimeComponents): String {
//    return "${dateTime.dayOfWeek}, ${"%02d".format(dateTime.day)} ${dateTime.month} ${dateTime.year} " +
//           "${"%02d".format(dateTime.hour)}:${"%02d".format(dateTime.minute)}:${"%02d".format(dateTime.second)} ${dateTime.timeZone}"
//}
fun formatRfc1123(dateTime: DateTimeComponents): String {
    val day = dateTime.day.toString().padStart(2, '0')
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val second = dateTime.second.toString().padStart(2, '0')

    return "${dateTime.dayOfWeek}, $day ${dateTime.month} ${dateTime.year} " +
            "$hour:$minute:$second ${dateTime.timeZone}"
}

fun main() {
    val dateTime = getCurrentDateTime()
    println(formatRfc1123(dateTime))
}