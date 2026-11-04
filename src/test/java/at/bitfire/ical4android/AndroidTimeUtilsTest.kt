package at.bitfire.ical4android

import at.bitfire.ical4android.util.AndroidTimeUtils
import at.bitfire.ical4android.util.TimeApiExtensions.toTimestamp
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TemporalAdapter
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.util.TimeZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import kotlin.jvm.optionals.getOrNull

class AndroidTimeUtilsTest {

    val tzBerlin: ZoneId = ZoneId.of("Europe/Berlin")
    val tzToronto: ZoneId = ZoneId.of("America/Toronto")

    val tzIdDefault: String = ZoneId.systemDefault().id
    val tzDefault: ZoneId = ZoneId.systemDefault()

    /**
     * Parses a property within a VEVENT of an iCalendar that also defines a custom time zone
     * "CustomTime" (+03:10, not known by Android).
     */
    private fun <T: Property> parseProperty(line: String, name: String): T {
        val cal = CalendarBuilder().build(StringReader("BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:test\r\n" +
                "BEGIN:VTIMEZONE\r\n" +
                "TZID:CustomTime\r\n" +
                "BEGIN:STANDARD\r\n" +
                "TZOFFSETFROM:+0310\r\n" +
                "TZOFFSETTO:+0310\r\n" +
                "DTSTART:19600101T000000\r\n" +
                "END:STANDARD\r\n" +
                "END:VTIMEZONE\r\n" +
                "BEGIN:VEVENT\r\n" +
                "UID:test\r\n" +
                "$line\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR\r\n"))
        val vEvent = cal.getComponent<VEvent>(Component.VEVENT).get()
        return vEvent.getProperty<T>(name).get()
    }

    private fun tzIdOf(prop: Property) = prop.getParameter<TzId>(Parameter.TZID).getOrNull()?.value

    private fun rDate(vararg dates: Temporal, tzId: String? = null): RDate<Temporal> {
        val rDate = RDate(DateList(dates.toList()))
        if (tzId != null)
            rDate.replace<Property>(TzId(tzId))
        else if (dates.all { DateUtils.isDate(it) })
            rDate.replace<Property>(Value.DATE)
        return rDate
    }


    @Test
    fun testAndroidifyTimeZone_DateProperty_Null() {
        // must not throw an exception
        AndroidTimeUtils.androidifyTimeZone(null as DtStart<*>?)
    }

    @Test
    fun testAndroidifyTimeZone_DateProperty_Date() {
        // dates (without time) should be ignored
        val dtStart = DtStart(LocalDate.of(2015, 1, 1))
        AndroidTimeUtils.androidifyTimeZone(dtStart)
        assertTrue(DateUtils.isDate(dtStart))
        assertEquals(LocalDate.of(2015, 1, 1), dtStart.date)
        assertNull(DateUtils.tzId(dtStart))
        assertFalse(dtStart.isUtc)
    }

    @Test
    fun testAndroidifyTimeZone_DateProperty_KnownTimeZone() {
        // date-time with known time zone should be unchanged
        val dtStart = DtStart(ZonedDateTime.of(2015, 1, 1, 23, 3, 50, 0, tzBerlin))
        AndroidTimeUtils.androidifyTimeZone(dtStart)
        assertEquals(ZonedDateTime.of(2015, 1, 1, 23, 3, 50, 0, tzBerlin), dtStart.date)
        assertEquals("Europe/Berlin", DateUtils.tzId(dtStart))
        assertFalse(dtStart.isUtc)
    }

    @Test
    fun testAndroidifyTimeZone_DateProperty_KnownTimeZone_Parsed() {
        // parsed date-time with known time zone: ical4j zone is replaced by system zone, time is unchanged
        val dtStart = parseProperty<DtStart<*>>("DTSTART;TZID=Europe/Berlin:20150101T230350", Property.DTSTART)
        val timestamp = dtStart.date.toTimestamp()
        AndroidTimeUtils.androidifyTimeZone(dtStart)
        assertEquals(timestamp, dtStart.date.toTimestamp())
        assertEquals(tzBerlin, (dtStart.date as ZonedDateTime).zone)
        assertEquals("Europe/Berlin", DateUtils.tzId(dtStart))
        assertFalse(dtStart.isUtc)
    }

    @Test
    fun testAndroidifyTimeZone_DateProperty_UnknownTimeZone() {
        // time zone that is not available on Android systems should be rewritten to system default
        val dtStart = parseProperty<DtStart<*>>("DTSTART;TZID=CustomTime:20150101T031000", Property.DTSTART)
        // 20150101T031000 CustomTime [+0310] = 20150101T000000 UTC = 1420070400 UNIX
        assertEquals(1420070400000L, dtStart.date.toTimestamp())
        AndroidTimeUtils.androidifyTimeZone(dtStart)
        assertEquals(1420070400000L, dtStart.date.toTimestamp())
        assertEquals(tzIdDefault, (dtStart.date as ZonedDateTime).zone.id)
        assertEquals(tzIdDefault, DateUtils.tzId(dtStart))
        assertFalse(dtStart.isUtc)
    }

    @Test
    fun testAndroidifyTimeZone_DateProperty_FloatingTime() {
        // times with floating time should be treated as system default time zone
        val dtStart = DtStart(LocalDateTime.of(2015, 1, 1, 23, 3, 50))
        AndroidTimeUtils.androidifyTimeZone(dtStart)
        assertEquals(LocalDateTime.of(2015, 1, 1, 23, 3, 50).atZone(tzDefault).toInstant().toEpochMilli(), dtStart.date.toTimestamp())
        assertEquals(tzIdDefault, (dtStart.date as ZonedDateTime).zone.id)
        assertEquals(tzIdDefault, DateUtils.tzId(dtStart))
        assertFalse(dtStart.isUtc)
    }

    @Test
    fun testAndroidifyTimeZone_DateProperty_UTC() {
        // times with UTC should be unchanged
        val dtStart = DtStart<Temporal>("20150101T230350Z")
        AndroidTimeUtils.androidifyTimeZone(dtStart)
        assertEquals(1420153430000L, dtStart.date.toTimestamp())
        assertNull(DateUtils.tzId(dtStart))
        assertTrue(dtStart.isUtc)
    }


    @Test
    fun testAndroidifyTimeZone_DateListProperty_Date() {
        // dates (without time) should be ignored
        val rDate = rDate(LocalDate.of(2015, 1, 1), LocalDate.of(2015, 1, 2))
        AndroidTimeUtils.androidifyTimeZone(rDate)
        assertEquals(1420070400000L, rDate.dates[0].toTimestamp())
        assertEquals(1420156800000L, rDate.dates[1].toTimestamp())
        assertNull(DateUtils.tzId(rDate))
        assertTrue(rDate.dates.all { DateUtils.isDate(it) })
        assertEquals("20150101,20150102", rDate.value)
    }

    @Test
    fun testAndroidifyTimeZone_DateListProperty_KnownTimeZone() {
        // times with known time zone should be unchanged
        val rDate = parseProperty<RDate<*>>("RDATE;TZID=America/Toronto:20150101T150000,20150102T150000", Property.RDATE)
        AndroidTimeUtils.androidifyTimeZone(rDate)
        assertEquals(1420142400000L, rDate.dates[0].toTimestamp())
        assertEquals(1420228800000L, rDate.dates[1].toTimestamp())
        assertEquals(tzToronto.id, DateUtils.tzId(rDate))
        assertTrue(rDate.dates.all { DateUtils.isDateTime(it) && !TemporalAdapter.isUtc(it) })
        assertEquals("20150101T150000,20150102T150000", rDate.value)
    }

    @Test
    fun testAndroidifyTimeZone_DateListProperty_UnknownTimeZone() {
        // time zone that is not available on Android systems should be rewritten to system default
        val rDate = parseProperty<RDate<*>>("RDATE;TZID=CustomTime:20150101T031000,20150102T031000", Property.RDATE)
        AndroidTimeUtils.androidifyTimeZone(rDate)
        // 20150101T031000 CustomTime [+0310] = 20150101T000000 UTC
        assertEquals(1420070400000L, rDate.dates[0].toTimestamp())
        assertEquals(1420156800000L, rDate.dates[1].toTimestamp())
        assertEquals(tzIdDefault, DateUtils.tzId(rDate))
        assertTrue(rDate.dates.all { DateUtils.isDateTime(it) && !TemporalAdapter.isUtc(it) })
    }

    @Test
    fun testAndroidifyTimeZone_DateListProperty_FloatingTime() {
        // times with floating time should be treated as system default time zone
        val rDate = rDate(LocalDateTime.of(2015, 1, 1, 3, 10), LocalDateTime.of(2015, 1, 2, 3, 10))
        AndroidTimeUtils.androidifyTimeZone(rDate)
        assertEquals(LocalDateTime.of(2015, 1, 1, 3, 10).atZone(tzDefault).toInstant().toEpochMilli(), rDate.dates[0].toTimestamp())
        assertEquals(LocalDateTime.of(2015, 1, 2, 3, 10).atZone(tzDefault).toInstant().toEpochMilli(), rDate.dates[1].toTimestamp())
        assertEquals(tzIdDefault, DateUtils.tzId(rDate))
        assertTrue(rDate.dates.all { DateUtils.isDateTime(it) && !TemporalAdapter.isUtc(it) })
    }

    @Test
    fun testAndroidifyTimeZone_DateListProperty_UTC() {
        // times with UTC should be unchanged
        val rDate = rDate(Instant.parse("2015-01-01T03:10:00Z"), Instant.parse("2015-01-02T03:10:00Z"))
        AndroidTimeUtils.androidifyTimeZone(rDate)
        assertEquals(Instant.parse("2015-01-01T03:10:00Z").toEpochMilli(), rDate.dates[0].toTimestamp())
        assertEquals(Instant.parse("2015-01-02T03:10:00Z").toEpochMilli(), rDate.dates[1].toTimestamp())
        assertNull(DateUtils.tzId(rDate))
        assertTrue(rDate.dates.all { TemporalAdapter.isUtc(it) })
        assertEquals("20150101T031000Z,20150102T031000Z", rDate.value)
    }


    @Test
    fun testStorageTzId_Date() =
            assertEquals(AndroidTimeUtils.TZID_ALLDAY, AndroidTimeUtils.storageTzId(DtStart(LocalDate.of(2015, 1, 1))))

    @Test
    fun testStorageTzId_FloatingTime() =
            assertEquals(tzIdDefault, AndroidTimeUtils.storageTzId(DtStart(LocalDateTime.of(2015, 1, 1, 0, 0))))

    @Test
    fun testStorageTzId_UTC() =
            assertEquals(TimeZones.UTC_ID, AndroidTimeUtils.storageTzId(DtStart(Instant.parse("2015-01-01T00:00:00Z"))))

    @Test
    fun testStorageTzId_ZonedTime() {
        assertEquals(tzToronto.id, AndroidTimeUtils.storageTzId(DtStart(ZonedDateTime.of(2015, 1, 1, 0, 0, 0, 0, tzToronto))))
    }


    // conversion from Android values

    @Test
    fun testAndroidDateTime() {
        assertNull(AndroidTimeUtils.androidDateTime(1420070400000L, null))
        assertNull(AndroidTimeUtils.androidDateTime(1420070400000L, "Unknown/Zone"))
        assertEquals(Instant.ofEpochMilli(1420070400000L), AndroidTimeUtils.androidDateTime(1420070400000L, "UTC"))
        assertEquals(Instant.ofEpochMilli(1420070400000L), AndroidTimeUtils.androidDateTime(1420070400000L, TimeZones.UTC_ID))
        assertEquals(Instant.ofEpochMilli(1420070400000L).atZone(tzToronto), AndroidTimeUtils.androidDateTime(1420070400000L, tzToronto.id))
    }


    // recurrence sets

    @Test
    fun testAndroidStringToRecurrenceSets_UtcTimes() {
        // list of UTC times
        val exDate = AndroidTimeUtils.androidStringToRecurrenceSet("20150101T103010Z,20150702T103020Z", false) { ExDate(it) }
        assertNull(DateUtils.tzId(exDate))
        val exDates = exDate.dates
        assertTrue(exDates.all { TemporalAdapter.isUtc(it) })
        assertEquals(2, exDates.size)
        assertEquals(1420108210000L, exDates[0].toTimestamp())
        assertEquals(1435833020000L, exDates[1].toTimestamp())
        assertEquals("20150101T103010Z,20150702T103020Z", exDate.value)
    }

    @Test
    fun testAndroidStringToRecurrenceSets_ZonedTimes() {
        // list of time zone times
        val exDate = AndroidTimeUtils.androidStringToRecurrenceSet("${tzToronto.id};20150103T113030,20150704T113040",false) { ExDate(it) }
        assertEquals(tzToronto.id, DateUtils.tzId(exDate))
        val exDates = exDate.dates
        assertTrue(exDates.all { DateUtils.isDateTime(it) && !TemporalAdapter.isUtc(it) })
        assertEquals(2, exDates.size)
        assertEquals(1420302630000L, exDates[0].toTimestamp())
        assertEquals(1436023840000L, exDates[1].toTimestamp())
        assertEquals("20150103T113030,20150704T113040", exDate.value)
    }

    @Test
    fun testAndroidStringToRecurrenceSets_Dates() {
        // list of dates
        val exDate = AndroidTimeUtils.androidStringToRecurrenceSet("20150101T103010Z,20150702T103020Z", true) { ExDate(it) }
        val exDates = exDate.dates
        assertTrue(exDates.all { DateUtils.isDate(it) })
        assertEquals(2, exDates.size)
        assertEquals(LocalDate.of(2015, 1, 1), exDates[0])
        assertEquals(LocalDate.of(2015, 7, 2), exDates[1])
        assertEquals(Value.DATE, exDate.getParameter<Value>(Parameter.VALUE).get())
        assertEquals("20150101,20150702", exDate.value)
    }

    @Test
    fun testAndroidStringToRecurrenceSets_Exclude() {
        val exDate = AndroidTimeUtils.androidStringToRecurrenceSet("${tzToronto.id};20150103T113030",false, 1420302630000L) { ExDate(it) }
        assertEquals(0, exDate.dates.size)
    }

    @Test
    fun testRecurrenceSetsToAndroidString_UtcTime() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(Instant.parse("2015-01-01T10:30:10Z"), Instant.parse("2015-01-02T10:30:20Z")))
        assertEquals("20150101T103010Z,20150102T103020Z", AndroidTimeUtils.recurrenceSetsToAndroidString(list, false))
    }

    @Test
    fun testRecurrenceSetsToAndroidString_TwoTimesWithSameTimezone() {
        // two separate entries, both with timezone Toronto
        val list = ArrayList<DateListProperty<*>>(2)
        list.add(rDate(ZonedDateTime.of(2015, 1, 3, 11, 30, 30, 0, tzToronto), tzId = tzToronto.id))
        list.add(rDate(ZonedDateTime.of(2015, 7, 4, 11, 30, 40, 0, tzToronto), tzId = tzToronto.id))
        assertEquals("America/Toronto;20150103T113030,20150704T113040", AndroidTimeUtils.recurrenceSetsToAndroidString(list, false))
    }

    @Test
    fun testRecurrenceSetsToAndroidString_TwoTimesWithDifferentTimezone() {
        // two separate entries, one with timezone Toronto, one with Berlin
        // 2015/01/03 11:30:30 Toronto [-5] = 2015/01/03 16:30:30 UTC
        // DST: 2015/07/04 11:30:40 Berlin  [+2] = 2015/07/04 09:30:40 UTC = 2015/07/04 05:30:40 Toronto [-4]
        val list = ArrayList<DateListProperty<*>>(2)
        list.add(rDate(ZonedDateTime.of(2015, 1, 3, 11, 30, 30, 0, tzToronto), tzId = tzToronto.id))
        list.add(rDate(ZonedDateTime.of(2015, 7, 4, 11, 30, 40, 0, tzBerlin), tzId = tzBerlin.id))
        assertEquals("America/Toronto;20150103T113030,20150704T053040", AndroidTimeUtils.recurrenceSetsToAndroidString(list, false))
    }

    @Test
    fun testRecurrenceSetsToAndroidString_TwoTimesWithOneUtc() {
        // two separate entries, one in UTC, one with Berlin
        // 2015/01/03 11:30:30 UTC
        // DST: 2015/07/04 11:30:40 Berlin  [+2] = 2015/07/04 09:30:40 UTC
        val list = ArrayList<DateListProperty<*>>(2)
        list.add(rDate(Instant.parse("2015-01-03T11:30:30Z")))
        list.add(rDate(ZonedDateTime.of(2015, 7, 4, 11, 30, 40, 0, tzBerlin), tzId = tzBerlin.id))
        assertEquals("20150103T113030Z,20150704T093040Z", AndroidTimeUtils.recurrenceSetsToAndroidString(list, false))
    }

    @Test
    fun testRecurrenceSetsToAndroidString_Dates() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(LocalDate.of(2015, 1, 1), LocalDate.of(2015, 7, 2)))
        assertEquals("20150101T000000Z,20150702T000000Z", AndroidTimeUtils.recurrenceSetsToAndroidString(list, true))
    }

    @Test
    fun testRecurrenceSetsToOpenTasksString_UtcTimes() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(Instant.parse("2015-01-01T06:00:00Z"), Instant.parse("2015-07-02T06:00:00Z")))
        assertEquals("20150101T060000Z,20150702T060000Z", AndroidTimeUtils.recurrenceSetsToOpenTasksString(list, tzBerlin))
    }

    @Test
    fun testRecurrenceSetsToOpenTasksString_ZonedTimes() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(ZonedDateTime.of(2015, 1, 1, 6, 0, 0, 0, tzToronto), ZonedDateTime.of(2015, 7, 2, 6, 0, 0, 0, tzToronto), tzId = tzToronto.id))
        assertEquals("20150101T120000,20150702T120000", AndroidTimeUtils.recurrenceSetsToOpenTasksString(list, tzBerlin))
    }

    @Test
    fun testRecurrenceSetsToOpenTasksString_MixedTimes() {
        // UTC value in a list with TZID: treated as time in this time zone
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(Instant.parse("2015-01-01T06:00:00Z"), ZonedDateTime.of(2015, 7, 2, 6, 0, 0, 0, tzToronto), tzId = tzToronto.id))
        assertEquals("20150101T070000,20150702T120000", AndroidTimeUtils.recurrenceSetsToOpenTasksString(list, tzBerlin))
    }

    @Test
    fun testRecurrenceSetsToOpenTasksString_TimesAlthougAllDay() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(ZonedDateTime.of(2015, 1, 1, 6, 0, 0, 0, tzToronto), ZonedDateTime.of(2015, 7, 2, 6, 0, 0, 0, tzToronto), tzId = tzToronto.id))
        assertEquals("20150101,20150702", AndroidTimeUtils.recurrenceSetsToOpenTasksString(list, null))
    }

    @Test
    fun testRecurrenceSetsToOpenTasksString_Dates() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(LocalDate.of(2015, 1, 1), LocalDate.of(2015, 7, 2)))
        assertEquals("20150101,20150702", AndroidTimeUtils.recurrenceSetsToOpenTasksString(list, null))
    }

    @Test
    fun testRecurrenceSetsToOpenTasksString_DatesAlthoughTimeZone() {
        val list = ArrayList<DateListProperty<*>>(1)
        list.add(rDate(LocalDate.of(2015, 1, 1), LocalDate.of(2015, 7, 2)))
        assertEquals("20150101T000000,20150702T000000", AndroidTimeUtils.recurrenceSetsToOpenTasksString(list, tzBerlin))
    }


    @Test
    fun testParseDuration() {
        assertEquals(Duration.parse("PT3600S"), AndroidTimeUtils.parseDuration("3600S"))
        assertEquals(Duration.parse("PT3600S"), AndroidTimeUtils.parseDuration("P3600S"))
        assertEquals(Duration.parse("+PT3600S"), AndroidTimeUtils.parseDuration("+P3600S"))
        assertEquals(Duration.parse("PT3600S"), AndroidTimeUtils.parseDuration("PT3600S"))
        assertEquals(Duration.parse("+PT3600S"), AndroidTimeUtils.parseDuration("+PT3600S"))
        assertEquals(Period.parse("P10D"), AndroidTimeUtils.parseDuration("P1W3D"))
        assertEquals(Period.parse("P1D"), AndroidTimeUtils.parseDuration("1DT"))
        assertEquals(Duration.parse("P14DT3600S"), AndroidTimeUtils.parseDuration("P2W3600S"))
        assertEquals(Duration.parse("-P3DT4H5M6S"), AndroidTimeUtils.parseDuration("-P3D4H5M6S"))
        assertEquals(Duration.parse("PT3H2M1S"), AndroidTimeUtils.parseDuration("P1S2M3H"))
        assertEquals(Duration.parse("P4DT3H2M1S"), AndroidTimeUtils.parseDuration("P1S2M3H4D"))
        assertEquals(Duration.parse("P11DT3H2M1S"), AndroidTimeUtils.parseDuration("P1S2M3H4D1W"))
        assertEquals(Duration.parse("PT1H0M10S"), AndroidTimeUtils.parseDuration("1H10S"))
    }

}
