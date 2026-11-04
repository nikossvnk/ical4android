package at.bitfire.ical4android

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.component.VEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStreamReader
import java.io.StringReader

class ICalPreprocessorTest {

    @Test
    fun testFixInvalidUtcOffset() {
        val invalid = "BEGIN:VEVENT" +
                "SUMMARY:Test" +
                "DTSTART;TZID=Test:19970714T133000" +
                "END:VEVENT" +
                "BEGIN:VTIMEZONE\n" +
                "TZID:Test\n" +
                "BEGIN:DAYLIGHT\n" +
                "DTSTART:19670430T020000\n" +
                "TZOFFSETFROM:-5730\n" +
                "TZOFFSETTO:+1920\n" +
                "TZNAME:EDT\n" +
                "END:DAYLIGHT\n" +
                "BEGIN:STANDARD\n" +
                "DTSTART:19671029T020000\n" +
                "TZOFFSETFROM:-0400\n" +
                "TZOFFSETTO:-0500\n" +
                "TZNAME:EST" +
                "END:STANDARD\n" +
                "END:VTIMEZONE"
        val valid = "BEGIN:VEVENT" +
                "SUMMARY:Test" +
                "DTSTART;TZID=Test:19970714T133000" +
                "END:VEVENT" +
                "BEGIN:VTIMEZONE\n" +
                "TZID:Test\n" +
                "BEGIN:DAYLIGHT\n" +
                "DTSTART:19670430T020000\n" +
                "TZOFFSETFROM:-005730\n" +
                "TZOFFSETTO:+001920\n" +
                "TZNAME:EDT\n" +
                "END:DAYLIGHT\n" +
                "BEGIN:STANDARD\n" +
                "DTSTART:19671029T020000\n" +
                "TZOFFSETFROM:-0400\n" +
                "TZOFFSETTO:-0500\n" +
                "TZNAME:EST" +
                "END:STANDARD\n" +
                "END:VTIMEZONE"
        ICalPreprocessor.fixInvalidValues(StringReader(invalid)).let { result ->
            assertEquals(valid, result.readText())
        }
        ICalPreprocessor.fixInvalidValues(StringReader(valid)).let { result ->
            assertEquals(valid, result.readText())
        }
    }

    @Test
    fun testFixRedundantDateTimeSuffix() {
        val invalid = "DTSTART:20131009T170000T\r\n" +
                "DTEND;TZID=Europe/Vienna:20131009T180000T\r\n" +
                "EXDATE:20131010T170000T,20131011T170000Z,20131012T170000T\r\n" +
                "DUE;VALUE=DATE:20131009\r\n" +
                "SUMMARY:20131009T170000T\r\n"
        val valid = "DTSTART:20131009T170000\r\n" +
                "DTEND;TZID=Europe/Vienna:20131009T180000\r\n" +
                "EXDATE:20131010T170000,20131011T170000Z,20131012T170000\r\n" +
                "DUE;VALUE=DATE:20131009\r\n" +
                "SUMMARY:20131009T170000T\r\n"
        assertEquals(valid, ICalPreprocessor.fixInvalidValues(StringReader(invalid)).readText())
        assertEquals(valid, ICalPreprocessor.fixInvalidValues(StringReader(valid)).readText())
        // Exercise the non-resettable reader path, too.
        val streamReader = InputStreamReader(invalid.byteInputStream(), Charsets.UTF_8)
        assertEquals(valid, ICalPreprocessor.fixInvalidValues(streamReader).readText())
    }

    @Test
    fun testFixExtendedDateUsesLegacyRollover() {
        val invalid = "RECURRENCE-ID;VALUE=DATE:202131012\r\n" +
                "DTSTART;VALUE=DATE:201312032\r\n" +
                "EXDATE;VALUE=DATE:20140101,201301032\r\n" +
                "RDATE;VALUE=DATE:20140201\r\n" +
                "SUMMARY:202131012\r\n"
        val valid = "RECURRENCE-ID;VALUE=DATE:20230712\r\n" +
                "DTSTART;VALUE=DATE:20140101\r\n" +
                "EXDATE;VALUE=DATE:20140101,20130201\r\n" +
                "RDATE;VALUE=DATE:20140201\r\n" +
                "SUMMARY:202131012\r\n"
        assertEquals(valid, ICalPreprocessor.fixInvalidValues(StringReader(invalid)).readText())
        assertEquals(valid, ICalPreprocessor.fixInvalidValues(StringReader(valid)).readText())
    }

    @Test
    fun testMsTimeZones() {
        javaClass.classLoader!!.getResourceAsStream("events/outlook1.ics").use { stream ->
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val calendar = CalendarBuilder().build(reader)
            val vEvent = calendar.getComponent<VEvent>(Component.VEVENT).get()

            assertEquals("W. Europe Standard Time", DateUtils.tzId(vEvent.getStartDate<java.time.temporal.Temporal>().get()))
            ICalPreprocessor.preProcess(calendar)
            assertEquals("Europe/Vienna", DateUtils.tzId(vEvent.getStartDate<java.time.temporal.Temporal>().get()))
        }
    }

}