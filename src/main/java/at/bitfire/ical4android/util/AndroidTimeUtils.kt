package at.bitfire.ical4android.util

import at.bitfire.ical4android.DateUtils
import at.bitfire.ical4android.Ical4Android
import at.bitfire.ical4android.util.TimeApiExtensions.toInstant
import at.bitfire.ical4android.util.TimeApiExtensions.toLocalDate
import at.bitfire.ical4android.util.TimeApiExtensions.toTimestamp
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TemporalAdapter
import net.fortuna.ical4j.model.TemporalAmountAdapter
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.util.TimeZones
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import java.util.LinkedList
import java.util.Locale

object AndroidTimeUtils {

    /**
     * Timezone ID to store for all-day events, according to CalendarContract.Events SDK documentation.
     */
    const val TZID_ALLDAY = "UTC"

    const val RECURRENCE_LIST_TZID_SEPARATOR = ';'
    const val RECURRENCE_LIST_VALUE_SEPARATOR = ","

    /**
     * Used to separate multiple RRULEs/EXRULEs in the RRULE/EXRULE storage field.
     */
    const val RECURRENCE_RULE_SEPARATOR = "\n"

    private val FORMAT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    private val FORMAT_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
    private val FORMAT_DATE_TIME_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
    private val FORMAT_UTC_MIDNIGHT = DateTimeFormatter.ofPattern("yyyyMMdd'T'000000'Z'", Locale.ROOT)


    /**
     * Ensures that a given [DateProperty] either
     *
     * 1. has a time zone with an ID that is available in Android (the value will then be a
     *    [ZonedDateTime] with a system [ZoneId] and the TZID parameter will be set to its ID), or
     * 2. is an UTC property ([DateProperty.isUtc] = *true*).
     *
     * The point in time of the value is not changed. Floating date-times are converted to
     * the system default time zone.
     *
     * To get the time zone ID which shall be given to the Calendar provider,
     * use [storageTzId].
     *
     * @param date [DateProperty] to validate. Values which are not DATE-TIME will be ignored.
     */
    fun androidifyTimeZone(date: DateProperty<*>?) {
        if (date == null || !DateUtils.isDateTime(date) || date.isUtc)
            return

        when (val value: Temporal = date.date) {
            is ZonedDateTime -> {
                val tzID = DateUtils.tzId(date) ?: value.zone.id
                val bestMatchingTzId = DateUtils.findAndroidTimezoneID(tzID)
                if (tzID != bestMatchingTzId)
                    Ical4Android.log.warning("Android doesn't know time zone $tzID, setting default time zone $bestMatchingTzId")
                if (value.zone.id != bestMatchingTzId || tzID != bestMatchingTzId)
                    setDate(date, value.withZoneSameInstant(ZoneId.of(bestMatchingTzId)))
            }
            is LocalDateTime -> {
                val bestMatchingTzId = DateUtils.findAndroidTimezoneID(null)
                Ical4Android.log.warning("Android doesn't know time zone (floating), setting default time zone $bestMatchingTzId")
                setDate(date, value.atZone(ZoneId.of(bestMatchingTzId)))
            }
        }
    }

    /**
     * Ensures that a given [DateListProperty] either
     *
     * 1. has a time zone (TZID parameter) with an ID that is available in Android, or
     * 2. is an UTC property (no TZID and all values in UTC).
     *
     * The points in time of the values are not changed. Floating date-times are converted to
     * the system default time zone.
     *
     * @param dateList [DateListProperty] to validate. Values which are not DATE-TIME will be ignored.
     */
    fun androidifyTimeZone(dateList: DateListProperty<*>) {
        val dates = dateList.dates
        if (dates.isEmpty() || !DateUtils.isDateTime(dates.first()) || dates.all { TemporalAdapter.isUtc(it) })
            return

        val tzID = DateUtils.tzId(dateList)
        val bestMatchingTzId = DateUtils.findAndroidTimezoneID(tzID)
        if (tzID != bestMatchingTzId) {
            Ical4Android.log.warning("Android doesn't know time zone ${tzID ?: "(floating)"}, setting default time zone $bestMatchingTzId")
            // DateListProperty.dates keep their points in time; the new TZID is used to format them
            dateList.replace<Property>(TzId(bestMatchingTzId))
        }
    }

    /**
     * Returns the time-zone ID for a given date or date-time that should be used to store it
     * in the Android calendar provider.
     *
     * @param date DateProperty (DATE or DATE-TIME) whose time-zone information is used
     *
     * @return - UTC for dates and UTC date-times
     *         - the specified time zone ID for date-times with given time zone
     *         - the currently set default time zone ID for floating date-times
     */
    fun storageTzId(date: DateProperty<*>): String {
        val value: Temporal = date.date
        return if (DateUtils.isDateTime(value)) {
            // DATE-TIME
            when {
                date.isUtc ->
                    // DATE-TIME in UTC format
                    TimeZones.UTC_ID
                value is ZonedDateTime ->
                    // DATE-TIME with given time-zone
                    DateUtils.tzId(date) ?: value.zone.id
                else ->
                    // DATE-TIME in local format (floating)
                    ZoneId.systemDefault().id
            }
        } else
            // DATE
            TZID_ALLDAY
    }


    // recurrence sets

    /**
     * Concatenates, if necessary, multiple RDATE/EXDATE lists and converts them to
     * a formatted string which Android calendar provider can process.
     * Android expects this format: "[TZID;]date1,date2,date3" where date is "yyyymmddThhmmss" (when
     * TZID is given) or "yyyymmddThhmmssZ". All values are converted to the time zone of the
     * first list (or to UTC if the first list doesn't have a time zone).
     *
     * @param dates         one more more lists of RDATE or EXDATE
     * @param allDay        whether the event is an all-day event or not
     *
     * @return formatted string for Android calendar provider
     */
    fun recurrenceSetsToAndroidString(dates: List<DateListProperty<*>>, allDay: Boolean): String {
        /*  rdate/exdate:       DATE                                DATE_TIME
            all-day             store as ...T000000Z                cut off time and store as ...T000000Z
            event with time     (undefined)                         store as ...ThhmmssZ
        */
        val strDates = LinkedList<String>()

        // use time zone of first entry for the whole set (TZID or, if not available, zone of first value); null for UTC
        val tzId = dates.firstOrNull()?.let { first ->
            DateUtils.tzId(first) ?: (first.dates.firstOrNull() as? ZonedDateTime)?.zone?.id
        }?.let { DateUtils.findAndroidTimezoneID(it) }
        val zone = tzId?.let { ZoneId.of(it) }

        for (dateListProp in dates) {
            if (dateListProp is RDate && dateListProp.periods.isPresent) {
                Ical4Android.log.warning("RDATE PERIOD not supported, ignoring")
                continue
            }

            for (date in dateListProp.dates)
                strDates += when {
                    // DATE values have to be converted to DATE-TIME <date>T000000Z for Android
                    allDay || DateUtils.isDate(date) ->
                        FORMAT_UTC_MIDNIGHT.format(date.toLocalDate())
                    zone == null ->
                        FORMAT_DATE_TIME_UTC.format(date.toInstant().atZone(ZoneOffset.UTC))
                    else ->
                        FORMAT_DATE_TIME.format(date.toInstant().atZone(zone))
                }
        }

        // format: [tzid;]value1,value2,...
        val result = StringBuilder()
        if (tzId != null)
            result.append(tzId).append(RECURRENCE_LIST_TZID_SEPARATOR)
        result.append(strDates.joinToString(RECURRENCE_LIST_VALUE_SEPARATOR))
        return result.toString()
    }

    /**
     * Takes a formatted string as provided by the Android calendar provider and returns a DateListProperty
     * constructed from these values.
     *
     * @param dbStr     formatted string from Android calendar provider (RDATE/EXDATE field)
     *                  expected format: "[TZID;]date1,date2,date3" where date is "yyyymmddThhmmss[Z]"
     * @param allDay    true: list will contain DATE values; false: list will contain DATE_TIME values
     * @param exclude   this time stamp (milliseconds) won't be added to the [DateListProperty]
     * @param generator generates the [DateListProperty]; must call the constructor with the one argument of type [DateList]
     *
     * @return          instance of "type" containing the parsed dates/times from the string
     *
     * @throws DateTimeParseException when the string cannot be parsed
     */
    fun<T: DateListProperty<*>> androidStringToRecurrenceSet(dbStr: String, allDay: Boolean, exclude: Long? = null, generator: (DateList<Temporal>) -> T): T
    {
        // 1. split string into time zone and actual dates
        val zone: ZoneId?
        val datesStr: String

        val limiter = dbStr.indexOf(RECURRENCE_LIST_TZID_SEPARATOR)
        if (limiter != -1) {    // TZID given
            val tzId = dbStr.substring(0, limiter)
            zone = if (isUtcTzId(tzId))
                null
            else
                ZoneId.of(DateUtils.findAndroidTimezoneID(tzId))
            datesStr = dbStr.substring(limiter + 1)
        } else {
            zone = null
            datesStr = dbStr
        }

        // 2. process date string and generate list of DATEs or DATE-TIMEs
        val dates = datesStr
                .split(RECURRENCE_LIST_VALUE_SEPARATOR)
                .filter { it.isNotBlank() }
                .map { parseAndroidDate(it.trim(), zone, allDay) }

                // 3. filter excludes
                .filterNot { exclude != null && it.toTimestamp() == exclude }

        // 4. generate requested DateListProperty (RDate/ExDate) from list of DATEs or DATE-TIMEs
        val property = generator(DateList(dates))
        if (allDay)
            property.replace<Property>(Value.DATE)
        else if (zone != null)
            property.replace<Property>(TzId(zone.id))

        return property
    }

    /**
     * Parses a date or date-time as used in Android RDATE/EXDATE strings.
     *
     * @param str       value like `yyyymmdd`, `yyyymmddThhmmss` or `yyyymmddThhmmssZ`
     * @param zone      time zone of the value (*null* for UTC)
     * @param allDay    *true* to return a [LocalDate]; *false* to return a DATE-TIME
     *
     * @return [LocalDate] (all-day), [Instant] (UTC) or [ZonedDateTime] (time zone given)
     */
    private fun parseAndroidDate(str: String, zone: ZoneId?, allDay: Boolean): Temporal {
        val value: Temporal = when {
            str.endsWith('Z') ->
                LocalDateTime.parse(str, FORMAT_DATE_TIME_UTC).toInstant(ZoneOffset.UTC)
            str.contains('T') -> {
                val local = LocalDateTime.parse(str, FORMAT_DATE_TIME)
                if (zone == null)
                    local.toInstant(ZoneOffset.UTC)
                else
                    local.atZone(zone)
            }
            else -> {
                val date = LocalDate.parse(str, FORMAT_DATE)
                if (allDay)
                    return date
                else if (zone == null)
                    date.atStartOfDay(ZoneOffset.UTC).toInstant()
                else
                    date.atStartOfDay(zone)
            }
        }
        return if (allDay)
            value.toLocalDate()
        else
            value
    }

    private fun isUtcTzId(tzId: String) =
            tzId == TZID_ALLDAY || tzId == TimeZones.UTC_ID || tzId == TimeZones.IBM_UTC_ID

    /**
     * Concatenates, if necessary, multiple RDATE/EXDATE lists and converts them to
     * a formatted string which OpenTasks can process.
     * OpenTasks expect a list of RFC 5545 DATE ("yyyymmdd") or DATE-TIME ("yyyymmdd[Z]") values,
     * where the time zone is stored in a separate field.
     *
     * @param dates         one more more lists of RDATE or EXDATE
     * @param tz            output time zone (*null* for all-day event)
     *
     * @return formatted string for Android calendar provider
     */
    fun recurrenceSetsToOpenTasksString(dates: List<DateListProperty<*>>, tz: ZoneId?): String {
        val strDates = LinkedList<String>()
        for (dateListProp in dates) {
            if (dateListProp is RDate && dateListProp.periods.isPresent) {
                Ical4Android.log.warning("RDATE PERIOD not supported, ignoring")
                continue
            }

            for (date in dateListProp.dates)
                strDates += when {
                    tz == null ->                       // allDay=1 (VALUE=DATE or VALUE=DATE-TIME)
                        FORMAT_DATE.format(date.toLocalDate())
                    DateUtils.isDate(date) ->           // VALUE=DATE, but allDay=0
                        FORMAT_DATE_TIME.format(date.toLocalDate().atStartOfDay())
                    TemporalAdapter.isUtc(date) ->      // UTC DATE-TIME
                        FORMAT_DATE_TIME_UTC.format(date.toInstant().atZone(ZoneOffset.UTC))
                    else ->                             // DATE-TIME with time zone or floating
                        FORMAT_DATE_TIME.format(date.toInstant().atZone(tz))
                }
        }
        return strDates.joinToString(RECURRENCE_LIST_VALUE_SEPARATOR)
    }


    // duration

    /**
     * Checks and fixes [at.bitfire.ical4android.Event.duration] values with incorrect format which can't be
     * parsed by ical4j. Searches for values like "1H" and "3M" and
     * groups them together in a standards-compliant way.
     *
     * @param durationStr value from the content provider (like "PT3600S" or "P3600S")
     * @return duration value in RFC 2445 format ("PT3600S" when the argument was "P3600S")
     */
    fun parseDuration(durationStr: String): TemporalAmount {
        /** [RFC 2445/5445]
         * dur-value  = (["+"] / "-") "P" (dur-date / dur-time / dur-week)
         * dur-date   = dur-day [dur-time]
         * dur-day    = 1*DIGIT "D"
         * dur-time   = "T" (dur-hour / dur-minute / dur-second)
         * dur-week   = 1*DIGIT "W"
         * dur-hour   = 1*DIGIT "H" [dur-minute]
         * dur-minute = 1*DIGIT "M" [dur-second]
         * dur-second = 1*DIGIT "S"
         */
        val possibleFormats = Regex("([+-]?)P?(T|((\\d+)W)|((\\d+)D)|((\\d+)H)|((\\d+)M)|((\\d+)S))*")
                                         //  1            4         6         8         10        12
        possibleFormats.matchEntire(durationStr)?.let { result ->
            fun fromMatch(s: String) = if (s.isEmpty()) 0 else s.toInt()

            val intSign = if (result.groupValues[1] == "-") -1 else 1
            val intDays = fromMatch(result.groupValues[4]) * TimeApiExtensions.DAYS_PER_WEEK + fromMatch(result.groupValues[6])
            val intHours = fromMatch(result.groupValues[8])
            val intMinutes = fromMatch(result.groupValues[10])
            val intSeconds = fromMatch(result.groupValues[12])

            return if (intDays != 0 && intHours == 0 && intMinutes == 0 && intSeconds == 0)
                Period.ofDays(intSign * intDays)
            else
                Duration.ofSeconds(intSign * (
                        intDays * TimeApiExtensions.SECONDS_PER_DAY.toLong() +
                        intHours * TimeApiExtensions.SECONDS_PER_HOUR +
                        intMinutes * TimeApiExtensions.SECONDS_PER_MINUTE +
                        intSeconds
                ))
        }
        // no match, try TemporalAmountAdapter
        return TemporalAmountAdapter.parse(durationStr).duration
    }


    // helpers

    /**
     * Sets the value of a date property (which is generic and thus can't be set directly
     * from a star-projected type). ical4j sets the TZID parameter for [ZonedDateTime] values;
     * for other values, an existing TZID parameter is removed.
     *
     * @param property  property to modify
     * @param value     new value
     * @param tzId      TZID to use for [ZonedDateTime] values (*null*: ID of the value's zone)
     */
    @Suppress("UNCHECKED_CAST")
    internal fun setDate(property: DateProperty<*>, value: Temporal, tzId: String? = null) {
        (property as DateProperty<Temporal>).setDate(value)
        if (value is ZonedDateTime) {
            if (tzId != null)
                property.replace<Property>(TzId(tzId))
        } else
            property.removeAll<Property>(Parameter.TZID)
    }

    /**
     * Converts a timestamp and a time zone ID, as they're stored by the Android calendar and
     * tasks providers, to a DATE-TIME value.
     *
     * @param timestamp UNIX timestamp in milliseconds
     * @param tzId      time zone ID (may be *null*)
     *
     * @return - [Instant] if [tzId] is UTC,
     *         - [ZonedDateTime] if [tzId] is a time zone that is known by the system,
     *         - *null* if there's no [tzId] or the time zone is not known
     */
    fun androidDateTime(timestamp: Long, tzId: String?): Temporal? {
        if (tzId == null)
            return null
        val instant = Instant.ofEpochMilli(timestamp)
        if (isUtcTzId(tzId))
            return instant
        val zone = DateUtils.getZoneId(tzId)?.takeUnless { it.id.startsWith("ical4j") }
        return zone?.let { instant.atZone(it) }
    }

    /**
     * Rewrites the value of [property] to the time zone of [reference] (without changing
     * the point in time). DATE values are not changed.
     *
     * - UTC reference: [property] is set to UTC
     * - reference with time zone: [property] is set to the same time zone (and TZID)
     * - floating reference: [property] is set to a floating time (in the system default time zone)
     */
    internal fun rewriteToTimeZoneOf(property: DateProperty<*>, reference: DateProperty<*>) {
        if (!DateUtils.isDateTime(property))
            return
        val instant = property.date.toInstant()
        val refValue: Temporal = reference.date
        when {
            reference.isUtc ->
                setDate(property, instant)
            refValue is ZonedDateTime ->
                setDate(property, instant.atZone(refValue.zone), DateUtils.tzId(reference))
            else ->
                setDate(property, LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))
        }
    }

}
