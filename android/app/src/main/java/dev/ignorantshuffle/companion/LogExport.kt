package dev.ignorantshuffle.companion

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object LogExport {
    fun filename(now: ZonedDateTime = ZonedDateTime.now()): String =
        "IgnorantShuffle-${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.log"
}
