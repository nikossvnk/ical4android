/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.ical4android

import at.bitfire.ical4android.DateUtils.isDateTime
import at.bitfire.ical4android.ICalendar.Companion.CALENDAR_NAME
import at.bitfire.ical4android.util.AndroidTimeUtils
import at.bitfire.ical4android.util.TimeApiExtensions.toInstant
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TextList
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Attendee
import net.fortuna.ical4j.model.property.Categories
import net.fortuna.ical4j.model.property.Clazz
import net.fortuna.ical4j.model.property.Color
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Duration
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.ExRule
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.Organizer
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Transp
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.immutable.ImmutableTransp
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import java.io.IOException
import java.io.OutputStream
import java.io.Reader
import java.time.LocalDate
import java.time.Period
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import java.util.LinkedList
import java.util.UUID
import kotlin.jvm.optionals.getOrNull


class Event: ICalendar() {

    /** list of CUAs which have edited the event since last sync */
    var userAgents = LinkedList<String>()

    // uid and sequence are inherited from iCalendar
    var recurrenceId: RecurrenceId<*>? = null

    var summary: String? = null
    var location: String? = null
    var description: String? = null
    var color: Css3Color? = null

    var dtStart: DtStart<*>? = null
    var dtEnd: DtEnd<*>? = null

    var duration: Duration? = null
    val rRules = LinkedList<RRule<*>>()
    val exRules = LinkedList<ExRule<*>>()
    val rDates = LinkedList<RDate<*>>()
    val exDates = LinkedList<ExDate<*>>()

    val exceptions = LinkedList<Event>()

    var classification: Clazz? = null
    var status: Status? = null

    var opaque = true

    var organizer: Organizer? = null
    val attendees = LinkedList<Attendee>()

    val alarms = LinkedList<VAlarm>()

    var lastModified: LastModified? = null

    val categories = LinkedList<String>()
    val unknownProperties = LinkedList<Property>()

    companion object {
        /**
         * Parses an iCalendar resource, applies [ICalPreprocessor] to increase compatibility
         * and extracts the VEVENTs.
         *
         * @param reader where the iCalendar is taken from
         * @param properties Known iCalendar properties (like [CALENDAR_NAME]) will be put into this map. Key: property name; value: property value
         *
         * @return array of filled [Event] data objects (may have size 0)
         *
         * @throws ParserException when the iCalendar can't be parsed
         * @throws IllegalArgumentException when the iCalendar resource contains an invalid value
         * @throws IOException on I/O errors
         * @throws InvalidCalendarException on parsing exceptions
         */
        @UsesThreadContextClassLoader
        fun eventsFromReader(reader: Reader, properties: MutableMap<String, String>? = null): List<Event> {
            val ical = fromReader(reader, properties)

            // process VEVENTs
            val vEvents = ical.getComponents<VEvent>(Component.VEVENT)

            // make sure every event has an UID
            for (vEvent in vEvents)
                if (vEvent.uid.isEmpty) {
                    val uid = Uid(UUID.randomUUID().toString())
                    Ical4Android.log.warning("Found VEVENT without UID, using a random one: ${uid.value}")
                    vEvent += uid
                }

            Ical4Android.log.fine("Assigning exceptions to main events")
            val mainEvents = mutableMapOf<String /* UID */,VEvent>()
            val exceptions = mutableMapOf<String /* UID */,MutableMap<String /* RECURRENCE-ID */,VEvent>>()

            for (vEvent in vEvents) {
                val uid = vEvent.uid.get().value
                val sequence = vEvent.sequence?.sequenceNo ?: 0

                val recurrenceId = vEvent.getRecurrenceId<Temporal>()
                if (recurrenceId == null) {
                    // main event (no RECURRENCE-ID)

                    // If there are multiple entries, compare SEQUENCE and use the one with higher SEQUENCE.
                    // If the SEQUENCE is identical, use latest version.
                    val event = mainEvents[uid]
                    val eventSequence = event?.sequence
                    if (event == null || (eventSequence != null && sequence >= eventSequence.sequenceNo))
                        mainEvents[uid] = vEvent

                } else {
                    // exception (RECURRENCE-ID)
                    // first index level: UID
                    val ex = exceptions.getOrPut(uid) { mutableMapOf() }
                    // second index level: RECURRENCE-ID
                    val recurrenceID = recurrenceId.value
                    val event = ex[recurrenceID]
                    val eventSequence = event?.sequence
                    if (event == null || (eventSequence != null && sequence >= eventSequence.sequenceNo))
                        ex[recurrenceID] = vEvent
                }
            }

            val events = mutableListOf<Event>()
            for ((uid, vEvent) in mainEvents) {
                val event = fromVEvent(vEvent)
                exceptions[uid]?.let { eventExceptions ->
                    event.exceptions.addAll(eventExceptions.map { (_,it) -> fromVEvent(it) })
                }

                // make sure that exceptions have at least a SUMMARY
                event.exceptions.forEach { it.summary = it.summary ?: event.summary }

                events += event
            }

            return events
        }

        private fun fromVEvent(event: VEvent): Event {
            val e = Event()

            // sequence must only be null for locally created, not-yet-synchronized events
            e.sequence = 0

            // process properties
            for (prop in event.getProperties<Property>())
                when (prop) {
                    is Uid -> e.uid = prop.value
                    is RecurrenceId<*> -> e.recurrenceId = prop
                    is Sequence -> e.sequence = prop.sequenceNo
                    is Summary -> e.summary = prop.value
                    is Location -> e.location = prop.value
                    is Description -> e.description = prop.value
                    is Categories ->
                        for (category in prop.categories.texts)
                            e.categories += category
                    is Color -> e.color = Css3Color.fromString(prop.value)
                    is DtStart<*> -> e.dtStart = prop
                    is DtEnd<*> -> e.dtEnd = prop
                    is Duration -> e.duration = prop
                    is RRule<*> -> e.rRules += prop
                    is RDate<*> -> e.rDates += prop
                    is ExRule<*> -> e.exRules += prop
                    is ExDate<*> -> e.exDates += prop
                    is Clazz -> e.classification = prop
                    is Status -> e.status = prop
                    is Transp -> e.opaque = prop == ImmutableTransp.OPAQUE
                    is Organizer -> e.organizer = prop
                    is Attendee -> e.attendees += prop
                    is LastModified -> e.lastModified = prop
                    is ProdId, is DtStamp -> { /* don't save these as unknown properties */ }
                    else -> e.unknownProperties += prop
                }

            e.alarms.addAll(event.alarms)

            // validation
            val dtStart = e.dtStart ?: throw InvalidCalendarException("Event without start time")
            val dtEnd = e.dtEnd
            if (dtEnd != null && dtStart.date.toInstant() > dtEnd.date.toInstant()) {
                Ical4Android.log.warning("DTSTART after DTEND; removing DTEND")
                e.dtEnd = null
            }

            return e
        }
    }


    @UsesThreadContextClassLoader
    fun write(os: OutputStream) {
        Ical4Android.checkThreadContextClassLoader()

        val ical = Calendar()
        ical += ImmutableVersion.VERSION_2_0
        ical +=
                if (userAgents.isEmpty())
                    prodId
                else
                    ProdId(prodId.value + " (" + userAgents.joinToString(",") + ")")

        val dtStart = dtStart ?: throw InvalidCalendarException("Won't generate event without start time")

        // "main event" (without exceptions)
        val mainEvent = toVEvent()
        ical += mainEvent

        // remember used time zones
        val usedTimeZones = mutableSetOf<String>()
        DateUtils.tzId(dtStart)?.let(usedTimeZones::add)
        DateUtils.tzId(dtEnd)?.let(usedTimeZones::add)

        // recurrence exceptions
        for (exception in exceptions) {
            // exceptions must always have the same UID as the main event
            exception.uid = uid

            val recurrenceId = exception.recurrenceId
            if (recurrenceId == null) {
                Ical4Android.log.warning("Ignoring exception without recurrenceId")
                continue
            }

            /* Exceptions must always have the same value type as DTSTART [RFC 5545 3.8.4.4].
               If this is not the case, we don't add the exception to the event because we're
               strict in what we send (and servers may reject such a case).
             */
            if (isDateTime(recurrenceId) != isDateTime(dtStart)) {
                Ical4Android.log.warning("Ignoring exception $recurrenceId with other date type than dtStart: $dtStart")
                continue
            }

            // for simplicity and compatibility, rewrite date-time exceptions to the same time zone as DTSTART
            if (isDateTime(recurrenceId) && (DateUtils.tzId(recurrenceId) != DateUtils.tzId(dtStart) || recurrenceId.isUtc != dtStart.isUtc)) {
                Ical4Android.log.fine("Changing timezone of $recurrenceId to same time zone as dtStart: $dtStart")
                AndroidTimeUtils.rewriteToTimeZoneOf(recurrenceId, dtStart)
            }

            // create and add VEVENT for exception
            val vException = exception.toVEvent()
            ical += vException

            // remember used time zones
            DateUtils.tzId(exception.dtStart)?.let(usedTimeZones::add)
            DateUtils.tzId(exception.dtEnd)?.let(usedTimeZones::add)
        }

        // determine first dtStart (there may be exceptions with an earlier DTSTART that the main event)
        val dtStarts = mutableListOf<Temporal>(dtStart.date)
        dtStarts.addAll(exceptions.mapNotNull { it.dtStart?.date })
        val earliest = dtStarts.minByOrNull { it.toInstant() }
        // add VTIMEZONE components
        for (tzId in usedTimeZones)
            DateUtils.ical4jTimeZone(tzId)?.let { tz ->
                ical += minifyVTimeZone(tz.vTimeZone, earliest)
            }

        softValidate(ical)
        CalendarOutputter(false).output(ical, os)
    }

    /**
     * Generates a VEvent representation of this event.
     *
     * @return generated VEvent
     */
    private fun toVEvent(): VEvent {
        val event = VEvent(/* generates DTSTAMP */)
        uid?.let { event += Uid(it) }

        recurrenceId?.let { event += it }
        sequence?.let {
            if (it != 0)
                event += Sequence(it)
        }

        summary?.let { event += Summary(it) }
        location?.let { event += Location(it) }
        description?.let { event += Description(it) }
        color?.let { event += Color(ParameterList(), it.name) }

        dtStart?.let { event += it }
        dtEnd?.let { event += it }
        duration?.let { event += it }

        rRules.forEach { event += it }
        rDates.forEach { event += it }
        exRules.forEach { event += it }
        exDates.forEach { event += it }

        classification?.let { event += it }
        status?.let { event += it }
        if (!opaque)
            event += ImmutableTransp.TRANSPARENT

        organizer?.let { event += it }
        attendees.forEach { event += it }

        if (categories.isNotEmpty())
            event += Categories(TextList(categories))
        unknownProperties.forEach { event += it }

        lastModified?.let { event += it }

        alarms.forEach { event += it }

        return event
    }

    /**
     * Returns the end of the event: either [dtEnd] or, if [deriveFromDuration] is set and [dtEnd]
     * is not available, a DTEND which is calculated from [dtStart] and [duration].
     *
     * @param deriveFromDuration whether DTEND shall be calculated from DTSTART/DURATION if it's not available
     * @return [dtEnd] or calculated DTEND (may be *null*)
     */
    fun getEndDate(deriveFromDuration: Boolean): DtEnd<*>? {
        val dtStart = this.dtStart
        var dtEnd: DtEnd<*>? = this.dtEnd
        // No DTEND? No problem, we'll use the DURATION.
        if (dtEnd == null && deriveFromDuration && dtStart != null) {
            val start: Temporal = dtStart.date
            var duration: TemporalAmount = duration?.duration
                    ?: if (isDateTime(dtStart))
                        // If "DTSTART" is a DATE-TIME, then the event's duration is zero (see: RFC 5545, 3.6.1 Event Component)
                        java.time.Duration.ZERO
                    else
                        // If "DTSTART" is a DATE, then the event's duration is one day (see: RFC 5545, 3.6.1 Event Component)
                        Period.ofDays(1)

            if (start is LocalDate && duration is java.time.Duration)
                // DATE values can only be used with days
                duration = Period.ofDays(duration.toDays().toInt())

            dtEnd = DtEnd(start.plus(duration)).also { end ->
                // keep TZID of DTSTART
                DateUtils.tzId(dtStart)?.let { tzId ->
                    AndroidTimeUtils.setDate(end, end.date, tzId)
                }
            }
        }
        return dtEnd
    }

    // helpers

    /**
     * Determines whether this Event is an all-day event.
     *
     * @return *true* if [dtStart] is a DATE value; *false* otherwise ([dtStart] is a DATE-TIME value or *null*)
     */
    fun isAllDay() = dtStart != null && !isDateTime(dtStart)

}
