package se.lixo.app.platform

import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.parse

/**
 * Parses an RFC 1123 HTTP `Date` header (e.g. `"Wed, 21 Oct 2015 07:28:00 GMT"`) into epoch
 * millis, or `null` if [header] is not a valid RFC 1123 date. Shared by the platform
 * `fetchHttpServerTimeMillis` implementations (`PlatformContracts.kt`).
 */
fun parseHttpDateMillis(header: String): Long? = try {
    DateTimeComponents.parse(header.trim(), DateTimeComponents.Formats.RFC_1123).toInstantUsingOffset()
        .toEpochMilliseconds()
} catch (_: Throwable) {
    null
}
