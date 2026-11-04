package at.bitfire.ical4android.util

import net.fortuna.ical4j.util.TimeZones
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import java.time.temporal.UnsupportedTemporalTypeException
import java.util.Calendar
import java.util.TimeZone

object TimeApiExtensions {

    const val DAYS_PER_WEEK = 7

    const val SECONDS_PER_MINUTE = 60
    const val SECONDS_PER_HOUR = SECONDS_PER_MINUTE * 60
    const val SECONDS_PER_DAY = SECONDS_PER_HOUR * 24
    const val SECONDS_PER_WEEK = SECONDS_PER_DAY * DAYS_PER_WEEK

    const val MILLIS_PER_SECOND = 1000
    const val MILLIS_PER_DAY = SECONDS_PER_DAY * MILLIS_PER_SECOND

    val tzUTC: TimeZone by lazy { TimeZones.getUtcTimeZone() }


    /***** Temporals *****/

    /*
     * ical4j represents iCalendar values as java.time objects:
     *
     *   - DATE: [LocalDate] (interpreted as UTC midnight, like Android does for all-day events)
     *   - DATE-TIME in UTC: [Instant] or [OffsetDateTime] with offset 0
     *   - floating DATE-TIME: [LocalDateTime] (interpreted in the system default time zone)
     *   - DATE-TIME with TZID: [ZonedDateTime]
     */

    /**
     * Returns the time zone this temporal is interpreted in:
     *
     *   - [ZoneOffset.UTC] for DATE values and UTC date-times,
     *   - the system default time zone for floating date-times,
     *   - the time zone/offset of the value otherwise.
     */
    fun Temporal.requireZoneId(): ZoneId =
            when (this) {
                is ZonedDateTime -> zone
                is OffsetDateTime -> offset
                is Instant, is LocalDate -> ZoneOffset.UTC
                is LocalDateTime -> ZoneId.systemDefault()
                else -> throw UnsupportedTemporalTypeException("Unsupported temporal: ${this::class.java.name}")
            }

    /**
     * Converts this temporal to a [ZonedDateTime] in the zone given by [requireZoneId]. DATE values
     * are mapped to midnight (start of day).
     */
    fun Temporal.toZonedDateTime(): ZonedDateTime =
            when (this) {
                is ZonedDateTime -> this
                is OffsetDateTime -> toZonedDateTime()
                is Instant -> atZone(ZoneOffset.UTC)
                is LocalDateTime -> atZone(ZoneId.systemDefault())
                is LocalDate -> atStartOfDay(ZoneOffset.UTC)
                else -> throw UnsupportedTemporalTypeException("Unsupported temporal: ${this::class.java.name}")
            }

    /**
     * Converts this temporal to an [Instant] (see [toZonedDateTime]).
     */
    fun Temporal.toInstant(): Instant = toZonedDateTime().toInstant()

    /**
     * Converts this temporal to a UNIX timestamp in milliseconds (see [toZonedDateTime]), like it's
     * used by the Android calendar/tasks providers.
     */
    fun Temporal.toTimestamp(): Long = toInstant().toEpochMilli()

    /**
     * Gets the calendar date of this temporal (in its own time zone, see [requireZoneId]).
     */
    fun Temporal.toLocalDate(): LocalDate =
            if (this is LocalDate)
                this
            else
                toZonedDateTime().toLocalDate()

    /**
     * Gets the time of day of this temporal (in its own time zone, see [requireZoneId]).
     */
    fun Temporal.toLocalTime(): LocalTime = toZonedDateTime().toLocalTime()


    /***** Durations *****/

    fun TemporalAmount.toDuration(position: Instant): Duration =
            if (this is Duration)
                this

            else if (this is Period) {
                val calEnd = Calendar.getInstance(tzUTC)
                calEnd.timeInMillis = position.toEpochMilli()
                calEnd.add(Calendar.DAY_OF_MONTH, days)
                calEnd.add(Calendar.MONTH, months)
                calEnd.add(Calendar.YEAR, years)
                Duration.ofMillis(calEnd.timeInMillis - position.toEpochMilli())
            } else
                throw IllegalArgumentException("TemporalAmount must be Period or Duration")

    /**
     * Converts a [TemporalAmount] to an RFC5545 duration value, which only uses
     * weeks, days, hours, minutes and seconds. Because years and months can't be used,
     * they're converted to weeks/days using the duration's position in the calendar.
     *
     * @param position the duration's position in the calendar
     *
     * @return RFC5545 duration value
     */
    fun TemporalAmount.toRfc5545Duration(position: Instant): String {
        /*  [RFC 5545 3.3.6 Duration]
            dur-value  = (["+"] / "-") "P" (dur-date / dur-time / dur-week)
            dur-date   = dur-day [dur-time]
            dur-time   = "T" (dur-hour / dur-minute / dur-second)
            dur-week   = 1*DIGIT "W"
            dur-hour   = 1*DIGIT "H" [dur-minute]
            dur-minute = 1*DIGIT "M" [dur-second]
            dur-second = 1*DIGIT "S"
            dur-day    = 1*DIGIT "D"
         */
        val builder = StringBuilder("P")
        if (this is Duration) {
            // TemporalAmountAdapter(Duration).toString() sometimes drops minutes: https://github.com/ical4j/ical4j/issues/420
            var secs = seconds

            if (secs == 0L)
                return "P0S"

            var weeks = secs / SECONDS_PER_WEEK
            secs -= weeks * SECONDS_PER_WEEK

            var days = secs / SECONDS_PER_DAY
            secs -= days * SECONDS_PER_DAY

            val hours = secs / SECONDS_PER_HOUR
            secs -= hours * SECONDS_PER_HOUR

            val minutes = secs / SECONDS_PER_MINUTE
            secs -= minutes * SECONDS_PER_MINUTE

            if (weeks != 0L && (days == 0L && hours == 0L && minutes == 0L && secs == 0L))
                return "P${weeks}W"

            days += weeks * DAYS_PER_WEEK
            weeks = 0

            if (days != 0L)
                builder.append("${days}D")

            if (hours != 0L || minutes != 0L || secs != 0L) {
                builder.append("T")
                if (hours != 0L)
                    builder.append("${hours}H")
                if (minutes != 0L)
                    builder.append("${minutes}M")
                if (secs != 0L)
                    builder.append("${secs}S")
            }

        } else if (this is Period) {
            // TemporalAmountAdapter(Period).toString() returns wrong values: https://github.com/ical4j/ical4j/issues/419
            var days = this.toDuration(position).toDays().toInt()

            if (days < 0) {
                builder.append("-")
                days = -days
            }

            if (days > 0L && days.rem(DAYS_PER_WEEK) == 0) {
                val weeks = days / DAYS_PER_WEEK
                builder.append("${weeks}W")
            } else
                builder.append("${days}D")
        } else
            throw NotImplementedError("Only Duration and Period is supported")
        return builder.toString()
    }

}