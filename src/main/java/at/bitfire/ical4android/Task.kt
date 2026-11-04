/*
 * Copyright © Ricki Hirner and contributors (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 *
 * Contributors: Alex Baker
 */

package at.bitfire.ical4android

import androidx.annotation.IntRange
import at.bitfire.ical4android.util.AndroidTimeUtils
import at.bitfire.ical4android.util.TimeApiExtensions.toInstant
import at.bitfire.ical4android.util.TimeApiExtensions.toLocalDate
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TextList
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.property.Categories
import net.fortuna.ical4j.model.property.Clazz
import net.fortuna.ical4j.model.property.Color
import net.fortuna.ical4j.model.property.Completed
import net.fortuna.ical4j.model.property.Created
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Due
import net.fortuna.ical4j.model.property.Duration
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.Geo
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.Organizer
import net.fortuna.ical4j.model.property.PercentComplete
import net.fortuna.ical4j.model.property.Priority
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RelatedTo
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Url
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import java.io.IOException
import java.io.OutputStream
import java.io.Reader
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.util.LinkedList
import java.util.logging.Level

class Task: ICalendar() {

    var createdAt: Long? = null
    var lastModified: Long? = null

    var summary: String? = null
    var location: String? = null
    var geoPosition: Geo? = null
    var description: String? = null
    var color: Int? = null
    var url: String? = null
    var organizer: Organizer? = null

    @IntRange(from = 0, to = 9)
    var priority: Int = Priority.VALUE_UNDEFINED

    var classification: Clazz? = null
    var status: Status? = null

    var dtStart: DtStart<*>? = null
    var due: Due<*>? = null
    var duration: Duration? = null
    var completedAt: Completed? = null

    @IntRange(from = 0, to = 100)
    var percentComplete: Int? = null

    var rRule: RRule<*>? = null
    val rDates = LinkedList<RDate<*>>()
    val exDates = LinkedList<ExDate<*>>()

    val categories = LinkedList<String>()
    var relatedTo = LinkedList<RelatedTo>()
    val unknownProperties = LinkedList<Property>()

    val alarms = LinkedList<VAlarm>()

    companion object {

        /**
         * Parses an iCalendar resource, applies [ICalPreprocessor] to increase compatibility
         * and extracts the VTODOs.
         *
         * @param reader where the iCalendar is taken from
         *
         * @return array of filled [Task] data objects (may have size 0)
         *
         * @throws ParserException when the iCalendar can't be parsed
         * @throws IllegalArgumentException when the iCalendar resource contains an invalid value
         * @throws IOException on I/O errors
         */
        @UsesThreadContextClassLoader
        fun tasksFromReader(reader: Reader): List<Task> {
            val ical = fromReader(reader)
            val vToDos = ical.getComponents<VToDo>(Component.VTODO)
            return vToDos.mapTo(LinkedList()) { this.fromVToDo(it) }
        }

        private fun fromVToDo(todo: VToDo): Task {
            val t = Task()

            val uid = todo.uid
            if (uid.isPresent)
                t.uid = uid.get().value
            else {
                Ical4Android.log.warning("Received VTODO without UID, generating new one")
                t.generateUID()
            }

            // sequence must only be null for locally created, not-yet-synchronized events
            t.sequence = 0

            for (prop in todo.getProperties<Property>())
                when (prop) {
                    is Sequence -> t.sequence = prop.sequenceNo
                    is Created -> t.createdAt = prop.date.toEpochMilli()
                    is LastModified -> t.lastModified = prop.date.toEpochMilli()
                    is Summary -> t.summary = prop.value
                    is Location -> t.location = prop.value
                    is Geo -> t.geoPosition = prop
                    is Description -> t.description = prop.value
                    is Color -> t.color = Css3Color.fromString(prop.value)?.argb
                    is Url -> t.url = prop.value
                    is Organizer -> t.organizer = prop
                    is Priority -> t.priority = prop.level
                    is Clazz -> t.classification = prop
                    is Status -> t.status = prop
                    is Due<*> -> { t.due = prop }
                    is Duration -> t.duration = prop
                    is DtStart<*> -> { t.dtStart = prop }
                    is Completed -> { t.completedAt = prop }
                    is PercentComplete -> t.percentComplete = prop.percentage
                    is RRule<*> -> t.rRule = prop
                    is RDate<*> -> t.rDates += prop
                    is ExDate<*> -> t.exDates += prop
                    is Categories ->
                        for (category in prop.categories.texts)
                            t.categories += category
                    is RelatedTo -> t.relatedTo.add(prop)
                    is Uid, is ProdId, is DtStamp -> { /* don't save these as unknown properties */ }
                    else -> t.unknownProperties += prop
                }

            t.alarms.addAll(todo.alarms)

            // There seem to be many invalid tasks out there because of some defect clients, do some validation.
            val dtStart = t.dtStart
            val due = t.due

            if (dtStart != null && due != null) {
                if (DateUtils.isDate(dtStart) && DateUtils.isDateTime(due)) {
                    Ical4Android.log.warning("DTSTART is DATE but DUE is DATE-TIME, rewriting DTSTART to DATE-TIME")
                    t.dtStart = DtStart(startOfDayLike(dtStart, due)).also { copyTzId(due, it) }
                } else if (DateUtils.isDateTime(dtStart) && DateUtils.isDate(due)) {
                    Ical4Android.log.warning("DTSTART is DATE-TIME but DUE is DATE, rewriting DUE to DATE-TIME")
                    t.due = Due(startOfDayLike(due, dtStart)).also { copyTzId(dtStart, it) }
                }

                val newDtStart = t.dtStart
                val newDue = t.due
                if (newDtStart != null && newDue != null && newDue.date.toInstant() <= newDtStart.date.toInstant()) {
                    Ical4Android.log.warning("Found invalid DUE <= DTSTART; dropping DTSTART")
                    t.dtStart = null
                }
            }

            if (t.duration != null && t.dtStart == null) {
                Ical4Android.log.warning("Found DURATION without DTSTART; ignoring")
                t.duration = null
            }

            return t
        }

        /**
         * Converts the DATE value of [date] to a DATE-TIME (start of the day) of the same kind as
         * [reference] (UTC, with time zone or floating).
         */
        private fun startOfDayLike(date: DateProperty<*>, reference: DateProperty<*>): Temporal {
            val day = date.date.toLocalDate()
            val referenceValue: Temporal = reference.date
            return when {
                reference.isUtc -> day.atStartOfDay(ZoneOffset.UTC).toInstant()
                referenceValue is ZonedDateTime -> day.atStartOfDay(referenceValue.zone)
                else -> day.atStartOfDay()
            }
        }

        /**
         * Applies the TZID of [from] (if there is one) to [to].
         */
        private fun copyTzId(from: DateProperty<*>, to: DateProperty<*>) {
            DateUtils.tzId(from)?.let { tzId ->
                AndroidTimeUtils.setDate(to, to.date, tzId)
            }
        }

    }


    @UsesThreadContextClassLoader
    fun write(os: OutputStream) {
        Ical4Android.checkThreadContextClassLoader()

        val ical = Calendar()
        ical += ImmutableVersion.VERSION_2_0
        ical += prodId

        val vTodo = VToDo(true /* generates DTSTAMP */)
        ical += vTodo

        uid?.let { vTodo += Uid(it) }
        sequence?.let {
            if (it != 0)
                vTodo += Sequence(it)
        }

        createdAt?.let { vTodo += Created(Instant.ofEpochMilli(it)) }
        lastModified?.let { vTodo += LastModified(Instant.ofEpochMilli(it)) }

        summary?.let { vTodo += Summary(it) }
        location?.let { vTodo += Location(it) }
        geoPosition?.let { vTodo += it }
        description?.let { vTodo += Description(it) }
        color?.let { vTodo += Color(ParameterList(), Css3Color.nearestMatch(it).name) }
        url?.let {
            try {
                vTodo += Url(URI(it))
            } catch (e: URISyntaxException) {
                Ical4Android.log.log(Level.WARNING, "Ignoring invalid task URL: $url", e)
            }
        }
        organizer?.let { vTodo += it }

        if (priority != Priority.VALUE_UNDEFINED)
            vTodo += Priority(priority)
        classification?.let { vTodo += it }
        status?.let { vTodo += it }

        rRule?.let { vTodo += it }
        rDates.forEach { vTodo += it }
        exDates.forEach { vTodo += it }

        if (categories.isNotEmpty())
            vTodo += Categories(TextList(categories))
        relatedTo.forEach { vTodo += it }
        unknownProperties.forEach { vTodo += it }

        // remember used time zones
        val usedTimeZones = HashSet<String>()
        due?.let {
            vTodo += it
            DateUtils.tzId(it)?.let(usedTimeZones::add)
        }
        duration?.let { vTodo += it }
        dtStart?.let {
            vTodo += it
            DateUtils.tzId(it)?.let(usedTimeZones::add)
        }
        completedAt?.let { vTodo += it }
        percentComplete?.let { vTodo += PercentComplete(it) }

        alarms.forEach { vTodo += it }

        // determine earliest referenced date
        val earliest = listOfNotNull<Temporal>(
                dtStart?.date,
                due?.date,
                completedAt?.date
        ).minByOrNull { it.toInstant() }
        // add VTIMEZONE components
        for (tzId in usedTimeZones)
            DateUtils.ical4jTimeZone(tzId)?.let { tz ->
                ical += minifyVTimeZone(tz.vTimeZone, earliest)
            }

        softValidate(ical)
        CalendarOutputter(false).output(ical, os)
    }


    fun isAllDay(): Boolean {
        return  dtStart?.let { DateUtils.isDate(it) } ?:
                due?.let { DateUtils.isDate(it) } ?:
                true
    }

}
