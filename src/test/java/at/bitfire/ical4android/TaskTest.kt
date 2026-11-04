/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.ical4android

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.Temporal

import net.fortuna.ical4j.model.*
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.parameter.RelType
import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.immutable.ImmutableAction
import net.fortuna.ical4j.model.property.immutable.ImmutableClazz
import net.fortuna.ical4j.model.property.immutable.ImmutableStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.Charset

class TaskTest {

    val tzVienna: TimeZone = DateUtils.ical4jTimeZone("Europe/Vienna")!!


    /* public interface tests */

    @Test
    fun testCharsets() {
        var t = parseCalendarFile("latin1.ics", Charsets.ISO_8859_1)
        assertEquals("äöüß", t.summary)

        t = parseCalendarFile("utf8.ics")
        assertEquals("© äö — üß", t.summary)
        assertEquals("中华人民共和国", t.location)
        assertEquals(LocalDateTime.of(2013, 10, 9, 17, 0), t.dtStart!!.date)
        assertFalse(t.dtStart!!.isUtc)
        assertNull(DateUtils.tzId(t.dtStart))
    }

    @Test
    fun testDtStartDate_DueDateTime() {
        val t = parseCalendar("BEGIN:VCALENDAR\r\n" +
                "VERSION 2:0\r\n" +
                "BEGIN:VTODO\r\n" +
                "SUMMARY:DTSTART is DATE, but DUE is DATE-TIME\r\n" +
                "DTSTART;VALUE=DATE:20200731\r\n" +
                "DUE;TZID=Europe/Vienna:20200731T234600\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n")
        assertEquals("DTSTART is DATE, but DUE is DATE-TIME", t.summary)
        // rewrite DTSTART to DATE-TIME, too
        assertEquals(DtStart(TemporalAdapter.parse("20200731T000000", tzVienna.toZoneId()).temporal), t.dtStart)
        assertEquals(Due(TemporalAdapter.parse("20200731T234600", tzVienna.toZoneId()).temporal), t.due)
    }

    @Test
    fun testDtStartDateTime_DueDate() {
        val t = parseCalendar("BEGIN:VCALENDAR\r\n" +
                "VERSION 2:0\r\n" +
                "BEGIN:VTODO\r\n" +
                "SUMMARY:DTSTART is DATE-TIME, but DUE is DATE\r\n" +
                "DTSTART;TZID=Europe/Vienna:20200731T235510\r\n" +
                "DUE;VALUE=DATE:20200801\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n")
        assertEquals("DTSTART is DATE-TIME, but DUE is DATE", t.summary)
        // rewrite DTSTART to DATE-TIME, too
        assertEquals(DtStart(TemporalAdapter.parse("20200731T235510", tzVienna.toZoneId()).temporal), t.dtStart)
        assertEquals(Due(TemporalAdapter.parse("20200801T000000", tzVienna.toZoneId()).temporal), t.due)
    }

    @Test
    fun testDueBeforeDtStart() {
        val t = parseCalendar("BEGIN:VCALENDAR\r\n" +
                "VERSION 2:0\r\n" +
                "BEGIN:VTODO\r\n" +
                "SUMMARY:DUE before DTSTART\r\n" +
                "DTSTART;TZID=Europe/Vienna:20200731T234600\r\n" +
                "DUE;TZID=Europe/Vienna:20200731T123000\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n")
        assertEquals("DUE before DTSTART", t.summary)
        // invalid tasks with DUE before DTSTART: DTSTART should be set to null
        assertNull(t.dtStart)
        assertEquals(Due(TemporalAdapter.parse("20200731T123000", tzVienna.toZoneId()).temporal), t.due)
    }

    @Test
    fun testDurationWithoutDtStart() {
        val t = parseCalendar("BEGIN:VCALENDAR\r\n" +
                "VERSION 2:0\r\n" +
                "BEGIN:VTODO\r\n" +
                "SUMMARY:DURATION without DTSTART\r\n" +
                "DURATION:PT1H\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n")
        assertEquals("DURATION without DTSTART", t.summary)
        assertNull(t.dtStart)
        assertNull(t.duration)
    }

    @Test
    fun testEmptyPriority() {
        val t = parseCalendar("BEGIN:VCALENDAR\r\n" +
                "VERSION 2:0\r\n" +
                "BEGIN:VTODO\r\n" +
                "SUMMARY:Empty PRIORITY\r\n" +
                "PRIORITY:\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n")
        assertEquals("Empty PRIORITY", t.summary)
        assertEquals(0, t.priority)
    }

    @Test
    fun testSamples() {
        val t = regenerate(parseCalendarFile("rfc5545-sample1.ics"))
        assertEquals(2, t.sequence)
        assertEquals("uid4@example.com", t.uid)
        assertEquals("mailto:unclesam@example.com", t.organizer!!.value)
        assertEquals(Due(LocalDateTime.parse("19980415T000000", java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))), t.due)
        assertFalse(t.isAllDay())
        assertEquals(ImmutableStatus.VTODO_NEEDS_ACTION, t.status)
        assertEquals("Submit Income Taxes", t.summary)
    }

    @Test
    fun testAllFields() {
        // 1. parse the VTODO file
        // 2. generate a new VTODO file from the parsed code
        // 3. parse it again – so we can test parsing and generating at once
        var t = regenerate(parseCalendarFile("most-fields1.ics"))
        assertEquals(1, t.sequence)
        assertEquals("most-fields1@example.com", t.uid)
        assertEquals("Conference Room - F123, Bldg. 002", t.location)
        assertEquals("37.386013", t.geoPosition!!.latitude.toPlainString())
        assertEquals("-122.082932", t.geoPosition!!.longitude.toPlainString())
        assertEquals("Meeting to provide technical review for \"Phoenix\" design.\nHappy Face Conference Room. Phoenix design team MUST attend this meeting.\nRSVP to team leader.", t.description)
        assertEquals("http://example.com/principals/jsmith", t.organizer!!.value)
        assertEquals("http://example.com/pub/calendars/jsmith/mytime.ics", t.url)
        assertEquals(1, t.priority)
        assertEquals(ImmutableClazz.CONFIDENTIAL, t.classification)
        assertEquals(ImmutableStatus.VTODO_IN_PROCESS, t.status)
        assertEquals(25, t.percentComplete)
        assertEquals(DtStart(LocalDate.parse("20100101", java.time.format.DateTimeFormatter.BASIC_ISO_DATE)), t.dtStart)
        assertEquals(Due(LocalDate.parse("20101001", java.time.format.DateTimeFormatter.BASIC_ISO_DATE)), t.due)
        assertTrue(t.isAllDay())

        assertEquals(RRule<Temporal>("FREQ=YEARLY;INTERVAL=2"), t.rRule)
        assertEquals(2, t.exDates.size)
        assertTrue(t.exDates.all { it.getParameter<Value>(Parameter.VALUE).get() == Value.DATE })
        assertEquals(setOf(
                listOf(LocalDate.of(2012, 1, 1)),
                listOf(LocalDate.of(2014, 1, 1), LocalDate.of(2018, 1, 1))
        ), t.exDates.map { it.dates }.toSet())
        assertEquals(2, t.rDates.size)
        assertTrue(t.rDates.all { it.getParameter<Value>(Parameter.VALUE).get() == Value.DATE })
        assertEquals(setOf(
                listOf(LocalDate.of(2010, 3, 10), LocalDate.of(2010, 3, 15)),
                listOf(LocalDate.of(2010, 8, 10))
        ), t.rDates.map { it.dates }.toSet())

        assertEquals(828106200000L, t.createdAt)
        assertEquals(840288600000L, t.lastModified)

        assertArrayEquals(arrayOf("Test","Sample"), t.categories.toArray())

        val sibling = t.relatedTo.first
        assertEquals("most-fields2@example.com", sibling.value)
        assertEquals(RelType.SIBLING, sibling.getParameter<RelType>(Parameter.RELTYPE).get())

        val unknown = t.unknownProperties.first
        assertEquals("X-UNKNOWN-PROP", unknown.name)
        assertEquals("xxx", unknown.getParameter<Parameter>("param1").get().value)
        assertEquals("Unknown Value", unknown.value)

        // other file
        t = regenerate(parseCalendarFile("most-fields2.ics"))
        assertEquals("most-fields2@example.com", t.uid)
        assertEquals(DtStart(Instant.parse("2010-01-01T10:10:10Z")), t.dtStart)
        assertEquals(Duration(java.time.Duration.ofDays(4).plusHours(3).plusMinutes(2).plusSeconds(1)), t.duration)
        assertTrue(t.unknownProperties.isEmpty())
    }


    /* generating */

    @Test
    fun testWrite() {
        val t = Task()
        t.uid = "SAMPLEUID"
        t.dtStart = DtStart(TemporalAdapter.parse("20190101T100000", TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone("Europe/Berlin")!!.toZoneId()).temporal)

        val alarm = VAlarm(java.time.Duration.ofHours(-1))
        alarm += ImmutableAction.AUDIO
        t.alarms += alarm

        val os = ByteArrayOutputStream()
        t.write(os)
        val raw = os.toString(Charsets.UTF_8.name())

        assertTrue(raw.contains("PRODID:${ICalendar.prodId.value}"))
        assertTrue(raw.contains("UID:SAMPLEUID"))
        assertTrue(raw.contains("DTSTAMP:"))
        assertTrue(raw.contains("DTSTART;TZID=Europe/Berlin:20190101T100000"))
        assertTrue(raw.contains("BEGIN:VALARM\r\n" +
                "TRIGGER:-PT1H\r\n" +
                "ACTION:AUDIO\r\n" +
                "END:VALARM\r\n"))
        assertTrue(raw.contains("BEGIN:VTIMEZONE"))
    }


    /* other methods */

    @Test
    fun testAllDay() {
        assertTrue(Task().isAllDay())

        // DTSTART has priority
        assertFalse(Task().apply {
            dtStart = DtStart(Instant.now())
        }.isAllDay())
        assertFalse(Task().apply {
            dtStart = DtStart(Instant.now())
            due = Due(LocalDate.now())
        }.isAllDay())
        assertTrue(Task().apply {
            dtStart = DtStart(LocalDate.now())
        }.isAllDay())
        assertTrue(Task().apply {
            dtStart = DtStart(LocalDate.now())
            due = Due(Instant.now())
        }.isAllDay())

        // if DTSTART is missing, DUE decides
        assertFalse(Task().apply {
            due = Due(Instant.now())
        }.isAllDay())
        assertTrue(Task().apply {
            due = Due(LocalDate.now())
        }.isAllDay())
    }


    /* helpers */

    private fun parseCalendar(iCalendar: String): Task =
            Task.tasksFromReader(StringReader(iCalendar)).first()

    private fun parseCalendarFile(fname: String, charset: Charset = Charsets.UTF_8): Task {
        javaClass.classLoader!!.getResourceAsStream("tasks/$fname").use { stream ->
            return Task.tasksFromReader(InputStreamReader(stream, charset)).first()
        }
    }

    private fun regenerate(t: Task): Task {
        val os = ByteArrayOutputStream()
        t.write(os)
        return Task.tasksFromReader(InputStreamReader(ByteArrayInputStream(os.toByteArray()), Charsets.UTF_8)).first()
    }
    
}
