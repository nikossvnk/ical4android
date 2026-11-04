package at.bitfire.ical4android

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.transform.compliance.DateListPropertyRule
import net.fortuna.ical4j.transform.compliance.DatePropertyRule
import net.fortuna.ical4j.transform.compliance.Rfc5545PropertyRule
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.Scanner
import java.util.logging.Level

/**
 * Applies some rules to increase compatibility or parsed iCalendars:
 *
 *   - [DatePropertyRule], [DateListPropertyRule]: to rename Outlook-specific TZID parameters
 * (like "W. Europe Standard Time" to an Android-friendly name like "Europe/Vienna")
 *
 * (CREATED is always UTC since ical4j 4, so there's no rule for that anymore.)
 */
object ICalPreprocessor {

    private val TZOFFSET_REGEXP = Regex("^(TZOFFSET(FROM|TO):[+\\-]?)((18|19|[2-6]\\d)\\d\\d)$", RegexOption.MULTILINE)

    // Older ical4j parsers accepted a redundant trailing T as a floating DATE-TIME.
    // Normalize only date-valued properties, never arbitrary text containing a date.
    private val INVALID_DATETIME_REGEXP = Regex(
        "^((?:DTSTART|DTEND|DUE|RECURRENCE-ID|RDATE|EXDATE|DTSTAMP|CREATED|LAST-MODIFIED|COMPLETED)(?:;[^\\r\\n:]*)?:)" +
            "((?=[^\\r\\n]*\\d{8}T\\d{6}T(?:,|\\r?$))[^\\r\\n]*)",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )
    private val DATETIME_SUFFIX_REGEXP = Regex("(^|,)(\\d{8}T\\d{6})T(?=,|$)", RegexOption.IGNORE_CASE)
    // SimpleDateFormat("yyyyMMdd") consumed four year digits, two month digits and
    // a variable-width day, rolling overflowing fields forward under relaxed parsing.
    private val EXTENDED_DATE_REGEXP = Regex(
        "^((?:DTSTART|DTEND|DUE|RECURRENCE-ID|RDATE|EXDATE)(?:;[^\\r\\n:]*)?:)" +
            "((?=\\d{9,}(?:,|\\r?$)|[^\\r\\n]*,\\d{9,}(?:,|\\r?$))\\d{8,}(?:,\\d{8,})*)(?=\\r?$)",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )
    private val LEGACY_DATE_FORMAT = DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 1, 9, SignStyle.NOT_NEGATIVE)
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.LENIENT)
    private val INVALID_VALUE_REGEXP = Regex(
        "${TZOFFSET_REGEXP.pattern}|${INVALID_DATETIME_REGEXP.pattern}|${EXTENDED_DATE_REGEXP.pattern}",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )

    private val propertyRules = arrayOf(
            DatePropertyRule(),
            DateListPropertyRule()
    )


    /**
     * Repairs known non-standard date/time values before ical4j parses them.
     *
     * Some servers shorten UTC offsets like "+005730" to an invalid "+5730".
     * Older parsers also accepted DATE-TIME values ending in a redundant "T";
     * these remain floating values (or use their explicit TZID), not UTC values.
     * Numeric DATEs with an extended day field retain legacy relaxed rollover semantics.
     *
     * @param reader Reader that reads the potentially broken iCalendar
     * @return Reader that reads the corrected iCalendar
     */
    fun fixInvalidValues(reader: Reader): Reader {
        fun fixStringFromReader(): String {
            val offsetsFixed = reader.readText().replace(TZOFFSET_REGEXP) {
                Ical4Android.log.log(Level.FINE, "Applying Synology WebDAV fix to invalid utc-offset", it.value)
                "${it.groupValues[1]}00${it.groupValues[3]}"
            }
            val dateTimesFixed = offsetsFixed.replace(INVALID_DATETIME_REGEXP) {
                it.groupValues[1] + it.groupValues[2].replace(DATETIME_SUFFIX_REGEXP, "$1$2")
            }
            return dateTimesFixed.replace(EXTENDED_DATE_REGEXP) {
                it.groupValues[1] + it.groupValues[2].splitToSequence(',').joinToString(",") { date ->
                    if (date.length > 8)
                        LocalDate.parse(date, LEGACY_DATE_FORMAT).format(DateTimeFormatter.BASIC_ISO_DATE)
                    else
                        date
                }
            }
        }

        var result: String? = null

        val resetSupported = try {
            reader.reset()
            true
        } catch(_: IOException) {
            false
        }

        if (resetSupported) {
            // Avoid copying the whole stream unless a value needs correction.
            if (Scanner(reader).findWithinHorizon(INVALID_VALUE_REGEXP.toPattern(), 0) != null) {
                reader.reset()
                result = fixStringFromReader()
            }
        } else
            result = fixStringFromReader()

        if (result != null)
            return StringReader(result)

        // not modified, return original iCalendar
        reader.reset()
        return reader
    }


    /**
     * Applies the set of rules (see class definition) to a given calendar object.
     *
     * @param calendar the calendar object that is going to be modified
     */
    fun preProcess(calendar: Calendar) {
        for (component in calendar.getComponents<CalendarComponent>()) {
            for (property in component.getProperties<Property>())
                applyRules(property)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyRules(property: Property) {
        propertyRules
                .filter { rule -> rule.supportedType.isAssignableFrom(property::class.java) }
                .forEach {
                    Ical4Android.log.log(Level.FINER, "Applying rules to $property")
                    (it as Rfc5545PropertyRule<Property>).apply(property)
                    Ical4Android.log.log(Level.FINER, "-> $property")
                }
    }

}
