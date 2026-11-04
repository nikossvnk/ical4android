/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.ical4android

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.TemporalAdapter
import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.DateProperty
import java.io.StringReader
import java.time.ZoneId
import java.time.temporal.Temporal
import kotlin.jvm.optionals.getOrNull

/**
 * Date/time utilities
 *
 * Before this object is accessed the first time, the accessing thread's contextClassLoader
 * must be set to an Android Context.classLoader!
 */
object DateUtils {

    /**
     * Global ical4j time zone registry used for event/task processing. Do not
     * modify this registry or its entries!
     */
    @UsesThreadContextClassLoader
    private val tzRegistry = TimeZoneRegistryFactory.getInstance().createRegistry()

    /**
     * ical4j registers its own (VTIMEZONE-based) zones in the Java time zone database. Their
     * IDs start with this prefix and are not known by Android.
     */
    private const val ICAL4J_ZONE_ID_PREFIX = "ical4j"

    init {
        Ical4Android.checkThreadContextClassLoader()
    }


    // time zones

    /**
     * IDs of the time zones which are known by the system (Android), without the zones
     * that are only registered by ical4j.
     */
    private fun systemTimezoneIDs(): List<String> =
            ZoneId.getAvailableZoneIds().filterNot { it.startsWith(ICAL4J_ZONE_ID_PREFIX) }

    /**
     * For a given time zone ID taken from an iCalendar resource, find the matching
     * Android time zone ID (if possible):
     *
     * 1. Use a case-insensitive match ("EUROPE/VIENNA" will return "Europe/Vienna",
     *    assuming "Europe/Vienna") is available in Android
     * 2. Find partial matches (case-sensitive) in both directions, so both "Vienna"
     *    and "MyClient: Europe/Vienna" will return "Europe/Vienna". This shouldn't be
     *    case-sensitive, because that would (for instance) return "EST" for "Westeuropäische Sommerzeit"
     * 3. If nothing can be found, use the system default time zone
     *
     * @param tzID time zone ID to be converted into Android time zone ID
     *
     * @return best matching Android time zone ID
     */
    fun findAndroidTimezoneID(tzID: String?): String {
        val availableTZs = systemTimezoneIDs()
        var result: String? = null

        if (tzID != null) {
            // first, try to find an exact match (case insensitive)
            result = availableTZs.firstOrNull { it.equals(tzID, true) }

            // if that doesn't work, try to find something else that matches
            if (result == null)
                for (availableTZ in availableTZs)
                    if (availableTZ.contains(tzID) || tzID.contains(availableTZ)) {
                        result = availableTZ
                        Ical4Android.log.warning("Couldn't find system time zone \"$tzID\", assuming $result")
                        break
                    }
        }

        // if that doesn't work, use device default as fallback
        return result ?: ZoneId.systemDefault().id
    }

    /**
     * Gets a [ZoneId] from a given ID string. In opposite to [ZoneId.of],
     * this methods returns null when the zone is not available.
     *
     * @param id    zone ID, like "Europe/Berlin" (may be null)
     *
     * @return      ZoneId or null if the argument was null or no zone with this ID could be found
     */
    fun getZoneId(id: String?): ZoneId? =
            id?.let {
                try {
                    ZoneId.of(id)
                } catch (_: Exception) {
                    null
                }
            }

    /**
     * Gets the ical4j time zone (which provides a [TimeZone.vTimeZone]) with the given ID
     * from the global ical4j time zone registry.
     *
     * @param id    time zone ID, like "Europe/Berlin"
     * @return      ical4j time zone, or null if no time zone with this ID is known by ical4j
     */
    @UsesThreadContextClassLoader
    fun ical4jTimeZone(id: String): TimeZone? = tzRegistry.getTimeZone(id)

    /**
     * Gets the value of the TZID parameter of a date(-time) property.
     *
     * @param date date property (may be null)
     * @return TZID parameter value or null if the property has no TZID (for instance, because
     * it's a DATE, an UTC DATE-TIME or a floating DATE-TIME) or the argument was null
     */
    fun tzId(date: DateProperty<*>?): String? =
            date?.getParameter<TzId>(Parameter.TZID)?.getOrNull()?.value

    /**
     * Gets the value of the TZID parameter of a date(-time) list property.
     *
     * @param dateList date list property
     * @return TZID parameter value or null if the property has no TZID
     */
    fun tzId(dateList: DateListProperty<*>): String? =
            dateList.getParameter<TzId>(Parameter.TZID).getOrNull()?.value

    /**
     * Determines whether a given date represents a DATE value.
     * @param date date property to check
     * @return *true* if the date is a DATE value; *false* otherwise (for instance, when the argument is a DATE-TIME value or null)
     */
    fun isDate(date: DateProperty<*>?) = date?.date != null && !TemporalAdapter.isDateTimePrecision(date.date)

    /**
     * Determines whether a given date represents a DATE-TIME value.
     * @param date date property to check
     * @return *true* if the date is a DATE-TIME value; *false* otherwise (for instance, when the argument is a DATE value or null)
     */
    fun isDateTime(date: DateProperty<*>?) = date?.date != null && TemporalAdapter.isDateTimePrecision(date.date)

    /**
     * Determines whether a given [Temporal] represents a DATE value.
     * @param date value to check
     * @return *true* if the value is a DATE value; *false* otherwise (DATE-TIME value or null)
     */
    fun isDate(date: Temporal?) = date != null && !TemporalAdapter.isDateTimePrecision(date)

    /**
     * Determines whether a given [Temporal] represents a DATE-TIME value.
     * @param date value to check
     * @return *true* if the value is a DATE-TIME value; *false* otherwise (DATE value or null)
     */
    fun isDateTime(date: Temporal?) = date != null && TemporalAdapter.isDateTimePrecision(date)

    /**
     * Parses a VTIMEZONE definition to a VTimeZone object.
     * @param timezoneDef VTIMEZONE definition
     * @return parsed VTimeZone
     * @throws IllegalArgumentException when the timezone definition can't be parsed
     */
    @UsesThreadContextClassLoader
    fun parseVTimeZone(timezoneDef: String): VTimeZone {
        Ical4Android.checkThreadContextClassLoader()
        // use a separate registry so that the parsed VTIMEZONE isn't registered globally
        val builder = CalendarBuilder()
        try {
            val cal = builder.build(StringReader(timezoneDef))
            return cal.getComponent<VTimeZone>(Component.VTIMEZONE).get()
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't parse timezone definition", e)
        }
    }

}
