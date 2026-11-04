/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.ical4android

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.Temporal
import net.fortuna.ical4j.model.TemporalAdapter
import at.bitfire.ical4android.util.TimeApiExtensions.toTimestamp

import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.model.property.immutable.ImmutableAction
import net.fortuna.ical4j.validate.ValidationException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.Charset
import java.time.Duration

class EventTest {

    /* public interface tests */

    @Test
    fun testCalendarProperties() {
        javaClass.classLoader!!.getResourceAsStream("events/multiple.ics").use { stream ->
            val properties = mutableMapOf<String, String>()
            Event.eventsFromReader(InputStreamReader(stream, Charsets.UTF_8), properties)
            assertEquals(1, properties.size)
            assertEquals("Test-Kalender", properties[ICalendar.CALENDAR_NAME])
        }
    }

    @Test
    fun testCharsets() {
        var e = parseCalendar("latin1.ics", Charsets.ISO_8859_1)[0]
        assertEquals("äöüß", e.summary)

        e = parseCalendar("utf8.ics").first()
        assertEquals("© äö — üß", e.summary)
        assertEquals("中华人民共和国", e.location)
    }

    @Test
    fun testDstOnlyVtimezone() {
        // see https://github.com/ical4j/ical4j/issues/230
        val events = parseCalendar("dst-only-vtimezone.ics")
        assertEquals(1, events.size)
        val e = events.first()
        assertEquals("only-dst@example.com", e.uid)
        val dtStart = e.dtStart!!
        assertEquals("Europe/Berlin", DateUtils.tzId(dtStart))
        assertEquals(1522738800000L, dtStart.date.toTimestamp())
    }

    @Test
    fun testGenerateEtcUTC() {
        val tzUTC = DateUtils.ical4jTimeZone("Etc/UTC")

        val e = Event()
        e.uid = "etc-utc-test@example.com"
        e.dtStart = DtStart(TemporalAdapter.parse("20200926T080000", tzUTC!!.toZoneId()).temporal)
        e.dtEnd = DtEnd(TemporalAdapter.parse("20200926T100000", tzUTC!!.toZoneId()).temporal)
        e.alarms += VAlarm(Duration.ofMinutes(-30)).apply { this += ImmutableAction.AUDIO }
        e.attendees += Attendee("mailto:test@example.com")
        val baos = ByteArrayOutputStream()
        e.write(baos)
        val ical = baos.toString()

        assertTrue("BEGIN:VTIMEZONE.+BEGIN:STANDARD.+END:STANDARD.+END:VTIMEZONE".toRegex(RegexOption.DOT_MATCHES_ALL).containsMatchIn(ical))
    }

    @Test
    fun testGrouping() {
        val events = parseCalendar("multiple.ics")
        assertEquals(3, events.size)

        var e = findEvent(events, "multiple-0@ical4android.EventTest")
        assertEquals("Event 0", e.summary)
        assertEquals(0, e.exceptions.size)

        e = findEvent(events, "multiple-1@ical4android.EventTest")
        assertEquals("Event 1", e.summary)
        assertEquals(1, e.exceptions.size)
        assertEquals("Event 1 Exception", e.exceptions.first.summary)
        assertEquals("20230712", e.exceptions.first.recurrenceId!!.value)

        e = findEvent(events, "multiple-2@ical4android.EventTest")
        assertEquals("Event 2", e.summary)
        assertEquals(2, e.exceptions.size)
        assertTrue("Event 2 Updated Exception 1" == e.exceptions.first.summary || "Event 2 Updated Exception 1" == e.exceptions[1].summary)
        assertTrue("Event 2 Exception 2" == e.exceptions.first.summary || "Event 2 Exception 2" == e.exceptions[1].summary)
    }

    @Test
    fun testParse() {
        val event = parseCalendar("utf8.ics").first()
        assertEquals("utf8@ical4android.EventTest", event.uid)
        assertEquals("© äö — üß", event.summary)
        assertEquals("Test Description", event.description)
        assertEquals("中华人民共和国", event.location)
        assertEquals(Css3Color.aliceblue, event.color)

        // The redundant trailing T accepted by older parsers denotes floating local time, not UTC.
        assertEquals(LocalDateTime.of(2013, 10, 9, 17, 0), event.dtStart!!.date)
        assertEquals(LocalDateTime.of(2013, 10, 9, 18, 0), event.dtEnd!!.date)
        assertFalse(event.dtStart!!.isUtc)
        assertNull(DateUtils.tzId(event.dtStart))

        assertEquals("cyrus@example.com", event.attendees.first.getParameter<Parameter>("EMAIL").get().value)

        val unknown = event.unknownProperties.first
        assertEquals("X-UNKNOWN-PROP", unknown.name)
        assertEquals("xxx", unknown.getParameter<Parameter>("param1").get().value)
        assertEquals("Unknown Value", unknown.value)
    }

    @Test
    fun testRedundantDateTimeSuffixKeepsTimeZoneAndRecurrences() {
        val event = Event.eventsFromReader(StringReader(
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
                "UID:legacy-date-time@example.com\r\n" +
                "DTSTART;TZID=Europe/Vienna:20131009T170000T\r\n" +
                "DTEND;TZID=Europe/Vienna:20131009T180000T\r\n" +
                "RDATE;TZID=Europe/Vienna:20131010T170000T\r\n" +
                "EXDATE;TZID=Europe/Vienna:20131011T170000T\r\n" +
                "SUMMARY:20131009T170000T\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR\r\n"
        )).single()
        val output = ByteArrayOutputStream()
        event.write(output)
        val regenerated = Event.eventsFromReader(StringReader(output.toString(Charsets.UTF_8.name()))).single()
        val zone = ZoneId.of("Europe/Vienna")
        assertEquals(LocalDateTime.of(2013, 10, 9, 17, 0).atZone(zone).toInstant().toEpochMilli(), regenerated.dtStart!!.date.toTimestamp())
        assertEquals(LocalDateTime.of(2013, 10, 9, 18, 0).atZone(zone).toInstant().toEpochMilli(), regenerated.dtEnd!!.date.toTimestamp())
        assertEquals("Europe/Vienna", DateUtils.tzId(regenerated.dtStart))
        assertEquals(listOf(LocalDateTime.of(2013, 10, 10, 17, 0).atZone(zone).toInstant().toEpochMilli()), regenerated.rDates.single().dates.map { it.toTimestamp() })
        assertEquals(listOf(LocalDateTime.of(2013, 10, 11, 17, 0).atZone(zone).toInstant().toEpochMilli()), regenerated.exDates.single().dates.map { it.toTimestamp() })
        assertEquals("20131009T170000T", regenerated.summary)
    }

    @Test
    fun testRecurringWriteFullDayException() {
        val event = Event().apply {
            uid = "test1"
            dtStart = DtStart(TemporalAdapter.parse("20190117T083000", DateUtils.ical4jTimeZone("Europe/Berlin")!!.toZoneId()).temporal)
            summary = "Main event"
            rRules += RRule<Temporal>("FREQ=DAILY;COUNT=5")
            exceptions += arrayOf(
                    Event().apply {
                        uid = "test2"
                        recurrenceId = RecurrenceId(TemporalAdapter.parse("20190118T073000", DateUtils.ical4jTimeZone("Europe/London")!!.toZoneId()).temporal)
                        summary = "Normal exception"
                    },
                    Event().apply {
                        uid = "test3"
                        recurrenceId = RecurrenceId(LocalDate.parse("20190223", java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
                        summary = "Full-day exception"
                    }
            )
        }
        val baos = ByteArrayOutputStream()
        event.write(baos)
        val iCal = baos.toString()
        assertTrue(iCal.contains("UID:test1\r\n"))
        assertTrue(iCal.contains("DTSTART;TZID=Europe/Berlin:20190117T083000\r\n"))

        // first RECURRENCE-ID has been rewritten
        // - to main event's UID
        // - to time zone Europe/Berlin (with one hour time difference)
        assertTrue(iCal.contains("UID:test1\r\n" +
                "RECURRENCE-ID;TZID=Europe/Berlin:20190118T083000\r\n" +
                "SUMMARY:Normal exception\r\n" +
                "END:VEVENT"))

        // no RECURRENCE-ID;VALUE=DATE:20190223
        assertFalse(iCal.contains(":20190223"))
    }

    @Test
    fun testRecurringWithException() {
        val event = parseCalendar("recurring-with-exception1.ics").first()
        assertTrue(DateUtils.isDate(event.dtStart))

        assertEquals(1, event.exceptions.size)
        val exception = event.exceptions.first
        assertEquals("20150503", exception.recurrenceId!!.value)
        assertEquals("Another summary for the third day", exception.summary)
    }

    @Test
    fun testStartEndTimes() {
        // event with start+end date-time
        val eViennaEvolution = parseCalendar("vienna-evolution.ics").first()
        assertEquals(1381330800000L, eViennaEvolution.dtStart!!.date.toTimestamp())
        assertEquals("Europe/Vienna", DateUtils.tzId(eViennaEvolution.dtStart))
        assertEquals(1381334400000L, eViennaEvolution.dtEnd!!.date.toTimestamp())
        assertEquals("Europe/Vienna", DateUtils.tzId(eViennaEvolution.dtEnd))
    }

    @Test
    fun testStartEndTimesAllDay() {
        // event with start date only
        val eOnThatDay = parseCalendar("event-on-that-day.ics").first()
        assertEquals(868838400000L, eOnThatDay.dtStart!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eOnThatDay.dtStart))

        // event with start+end date for all-day event (one day)
        val eAllDay1Day = parseCalendar("all-day-1day.ics").first()
        assertEquals(868838400000L, eAllDay1Day.dtStart!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eAllDay1Day.dtStart))
        assertEquals(868838400000L + 86400000, eAllDay1Day.dtEnd!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eAllDay1Day.dtEnd))

        // event with start+end date for all-day event (ten days)
        val eAllDay10Days = parseCalendar("all-day-10days.ics").first()
        assertEquals(868838400000L, eAllDay10Days.dtStart!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eAllDay10Days.dtStart))
        assertEquals(868838400000L + 10 * 86400000, eAllDay10Days.dtEnd!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eAllDay10Days.dtEnd))

        // event with start+end date on some day (0 sec-event)
        val eAllDay0Sec = parseCalendar("all-day-0sec.ics").first()
        assertEquals(868838400000L, eAllDay0Sec.dtStart!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eAllDay0Sec.dtStart))
        // DTEND is same as DTSTART which is not valid for Android – but this will be handled by AndroidEvent, not Event
        assertEquals(868838400000L, eAllDay0Sec.dtEnd!!.date.toTimestamp())
        assertNull(DateUtils.tzId(eAllDay0Sec.dtEnd))
    }

    @Test
    fun testUnfolding() {
        val e = parseCalendar("two-line-description-without-crlf.ics").first()
        assertEquals("http://www.tgbornheim.de/index.php?sessionid=&page=&id=&sportcentergroup=&day=6", e.description)
    }

    @Test
    fun testToString() {
        val e = Event()
        e.uid = "SAMPLEUID"
        val s = e.toString()
        assertTrue(s.contains(Event::class.java.simpleName))
        assertTrue(s.contains("uid=SAMPLEUID"))
    }


    /* generating */

    @Test
    fun testWrite() {
        val e = Event()
        e.uid = "SAMPLEUID"
        e.dtStart = DtStart(TemporalAdapter.parse("20190101T100000", TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone("Europe/Berlin")!!.toZoneId()).temporal)
        e.alarms += VAlarm(Duration.ofHours(-1)).apply { this += ImmutableAction.AUDIO }

        val os = ByteArrayOutputStream()
        e.write(os)
        val raw = os.toString(Charsets.UTF_8.name())

        assertTrue(raw.contains("PRODID:${ICalendar.prodId.value}"))
        assertTrue(raw.contains("UID:SAMPLEUID"))
        assertTrue(raw.contains("DTSTART;TZID=Europe/Berlin:20190101T100000"))
        assertTrue(raw.contains("DTSTAMP:"))
        val alarm = Event.eventsFromReader(StringReader(raw)).single().alarms.single()
        assertEquals(ImmutableAction.AUDIO, alarm.getRequiredProperty<Action>("ACTION"))
        assertEquals(Duration.ofHours(-1), alarm.getRequiredProperty<Trigger>("TRIGGER").duration)
        assertTrue(raw.contains("BEGIN:VTIMEZONE"))
    }

    @Test(expected = ValidationException::class)
    fun testWriteRejectsAlarmWithoutAction() {
        val event = Event().apply {
            uid = "invalid-alarm@example.com"
            dtStart = DtStart(LocalDateTime.of(2020, 1, 1, 12, 0))
            alarms += VAlarm(Duration.ofMinutes(-30))
        }
        event.write(ByteArrayOutputStream())
    }


    /* internal tests */

    @Test
    fun testFindMasterEventsAndExceptions() {
        // two single events
        var events = parseCalendar("two-events-without-exceptions.ics")
        assertEquals(2, events.size)
        for (event in events)
            assertTrue(event.exceptions.isEmpty())

        // one event with exception, another single event
        events = parseCalendar("one-event-with-exception-one-without.ics")
        assertEquals(2, events.size)
        for (event in events) {
            val uid = event.uid
            if ("event1" == uid)
                assertEquals(1, event.exceptions.size)
            else
                assertTrue(event.exceptions.isEmpty())
        }

        // one event two exceptions (thereof one updated two times) and updated exception, another single event
        events = parseCalendar("one-event-with-multiple-exceptions-one-without.ics")
        assertEquals(2, events.size)
        for (event in events) {
            val uid = event.uid
            if ("event1" == uid) {
                assertEquals(2, event.exceptions.size)
                for (exception in event.exceptions)
                    if ("20150503" == exception.recurrenceId!!.value) {
                        assertEquals(2, exception.sequence)
                        assertEquals("Final summary", exception.summary)
                    }
            } else
                assertTrue(event.exceptions.isEmpty())
        }
    }


    // helpers

    private fun findEvent(events: Iterable<Event>, uid: String): Event {
        for (event in events)
            if (uid == event.uid)
                return event
        throw FileNotFoundException()
    }

    private fun parseCalendar(fname: String, charset: Charset = Charsets.UTF_8): List<Event> =
            javaClass.classLoader!!.getResourceAsStream("events/$fname").use { stream ->
                return Event.eventsFromReader(InputStreamReader(stream, charset))
            }

}
