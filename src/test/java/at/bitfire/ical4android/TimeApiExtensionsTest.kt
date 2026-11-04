package at.bitfire.ical4android

import at.bitfire.ical4android.util.TimeApiExtensions.requireZoneId
import at.bitfire.ical4android.util.TimeApiExtensions.toDuration
import at.bitfire.ical4android.util.TimeApiExtensions.toLocalDate
import at.bitfire.ical4android.util.TimeApiExtensions.toLocalTime
import at.bitfire.ical4android.util.TimeApiExtensions.toRfc5545Duration
import at.bitfire.ical4android.util.TimeApiExtensions.toZonedDateTime
import net.fortuna.ical4j.model.TemporalAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.*

class TimeApiExtensionsTest {

    val tzBerlin: ZoneId = ZoneId.of("Europe/Berlin")


    @Test
    fun testDateToLocalDate() {
        val date = LocalDate.parse("20200620", java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toLocalDate()
        assertEquals(2020, date.year)
        assertEquals(6, date.monthValue)
        assertEquals(20, date.dayOfMonth)
        assertEquals(DayOfWeek.SATURDAY, date.dayOfWeek)
    }


    @Test
    fun testDateTimeRequireZoneId() {
        val time = TemporalAdapter.parse("20200707T010203", tzBerlin).temporal
        assertEquals(tzBerlin, time.requireZoneId())
    }

    @Test
    fun testDateTimeRequireZoneId_Floating() {
        val time = LocalDateTime.parse("20200707T010203", java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        assertEquals(ZoneId.systemDefault(), time.requireZoneId())
    }

    @Test
    fun testDateTimeRequireZoneId_Utc() {
        val time = Instant.parse("2020-07-07T01:02:03Z")
        assertTrue(TemporalAdapter.isUtc(time))
        assertEquals(ZoneOffset.UTC, time.requireZoneId())
    }


    @Test
    fun testDateTimeToLocalDate_TimezoneBoundary() {
        val date = TemporalAdapter.parse("20200620T000000", tzBerlin).temporal.toLocalDate()
        assertEquals(2020, date.year)
        assertEquals(6, date.monthValue)
        assertEquals(20, date.dayOfMonth)
        assertEquals(DayOfWeek.SATURDAY, date.dayOfWeek)
    }

    @Test
    fun testDateTimeToLocalDate_TimezoneDuringDay() {
        val date = TemporalAdapter.parse("20200620T123000", tzBerlin).temporal.toLocalDate()
        assertEquals(2020, date.year)
        assertEquals(6, date.monthValue)
        assertEquals(20, date.dayOfMonth)
        assertEquals(DayOfWeek.SATURDAY, date.dayOfWeek)
    }

    @Test
    fun testDateTimeToLocalDate_UtcDuringDay() {
        val date = Instant.parse("2020-06-20T12:30:00Z").toLocalDate()
        assertEquals(2020, date.year)
        assertEquals(6, date.monthValue)
        assertEquals(20, date.dayOfMonth)
        assertEquals(DayOfWeek.SATURDAY, date.dayOfWeek)
    }


    @Test
    fun testDateTimeToLocalTime() {
        assertEquals(LocalTime.of(12, 30), TemporalAdapter.parse("20200620T123000", tzBerlin).temporal.toLocalTime())
    }

    @Test
    fun testDateTimeToLocalTime_Floating() {
        assertEquals(LocalTime.of(12, 30), LocalDateTime.parse("20200620T123000", java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toLocalTime())
    }

    @Test
    fun testDateTimeToLocalTime_Utc() {
        assertEquals(LocalTime.of(12, 30), Instant.parse("2020-06-20T12:30:00Z").toLocalTime())
    }


    @Test
    fun testDateTimeToZonedDateTime() {
        assertEquals(
                ZonedDateTime.of(2020, 7, 7, 10, 30, 0, 0, tzBerlin),
                TemporalAdapter.parse("20200707T103000", tzBerlin).temporal.toZonedDateTime()
        )
    }

    @Test
    fun testDateTimeToZonedDateTime_Floating() {
        assertEquals(
                ZonedDateTime.of(2020, 7, 7, 10, 30, 0, 0, ZoneId.systemDefault()),
                LocalDateTime.parse("20200707T103000", java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toZonedDateTime()
        )
    }

    @Test
    fun testDateTimeToZonedDateTime_UTC() {
        assertEquals(
                ZonedDateTime.of(2020, 7, 7, 10, 30, 0, 0, ZoneOffset.UTC),
                Instant.parse("2020-07-07T10:30:00Z").toZonedDateTime()
        )
    }




    @Test
    fun testTemporalAmountToDuration() {
        assertEquals(Duration.ofHours(1), Duration.ofHours(1).toDuration(Instant.EPOCH))
        assertEquals(Duration.ofDays(1), Duration.ofDays(1).toDuration(Instant.EPOCH))
        assertEquals(Duration.ofDays(1), Period.ofDays(1).toDuration(Instant.EPOCH))
        assertEquals(Duration.ofDays(7), Period.ofWeeks(1).toDuration(Instant.EPOCH))
        assertEquals(Duration.ofDays(365), Period.ofYears(1).toDuration(Instant.EPOCH))
        assertEquals(Duration.ofDays(366), Period.ofYears(1).toDuration(Instant.ofEpochSecond(1577836800)))
    }

    @Test
    fun testTemporalAmountToRfc5545Duration_Duration() {
        assertEquals("P0S", Duration.ofDays(0).toRfc5545Duration(Instant.EPOCH))
        assertEquals("P2W", Duration.ofDays(14).toRfc5545Duration(Instant.EPOCH))
        assertEquals("P15D", Duration.ofDays(15).toRfc5545Duration(Instant.EPOCH))
        assertEquals("P16DT1H", Duration.parse("P16DT1H").toRfc5545Duration(Instant.EPOCH))
        assertEquals("P16DT1H4M", Duration.parse("P16DT1H4M").toRfc5545Duration(Instant.EPOCH))
        assertEquals("P2DT1H4M5S", Duration.parse("P2DT1H4M5S").toRfc5545Duration(Instant.EPOCH))
        assertEquals("PT1M20S", Duration.parse("PT80S").toRfc5545Duration(Instant.EPOCH))

        assertEquals("P0D", Period.ofWeeks(0).toRfc5545Duration(Instant.EPOCH))

        val date20200601 = Instant.ofEpochSecond(1590969600L)
        // 2020/06/01 + 1 year   = 2021/06/01 (365 days)
        // 2021/06/01 + 2 months = 2020/08/01 (30 days + 31 days = 61 days)
        // 2020/08/01 + 3 days   = 2020/08/04 (3 days)
        // total: 365 days + 61 days + 3 days = 429 days
        assertEquals("P429D", Period.of(1, 2, 3).toRfc5545Duration(date20200601))
        assertEquals("P2W", Period.ofWeeks(2).toRfc5545Duration(date20200601))
        assertEquals("P2W", Period.ofDays(14).toRfc5545Duration(date20200601))
        assertEquals("P15D", Period.ofDays(15).toRfc5545Duration(date20200601))
        assertEquals("P30D", Period.ofMonths(1).toRfc5545Duration(date20200601))
    }

}