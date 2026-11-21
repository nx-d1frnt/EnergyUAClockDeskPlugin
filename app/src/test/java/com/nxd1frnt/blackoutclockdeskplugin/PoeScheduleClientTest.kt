package com.nxd1frnt.blackoutclockdeskplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PoeScheduleClientTest {

    @Test
    fun testParseScheduleGpvTable() {
        val date = LocalDate.of(2026, 11, 21)
        val rows = PoeScheduleClient.ALL_QUEUES.joinToString("\n") { q ->
            val cells = (0 until 48).joinToString("") { slot ->
                val cls = if (q == "5.2" && slot in 4..7) "gpv-s2"
                else if (q == "5.2" && slot in 46..47) "gpv-s2"
                else if (slot == 10) "gpv-s3"
                else "gpv-s1"
                "<td class=\"$cls\"></td>"
            }
            "<tr><td class=\"gpv-q\">$q</td>$cells</tr>"
        }

        val html = """
            <div class="gpvinfodetail">
                <p>Графік на 21 листопада 2026 року</p>
                <table class="gpv-table"><tbody>$rows</tbody></table>
            </div>
        """.trimIndent()

        val parsed = PoeScheduleClient.parseSchedule(html, date)
        assertNotNull(parsed)
        assertEquals(12, parsed!!.size)

        val q52 = parsed["5.2"]!!
        assertEquals(2, q52.size)
        assertEquals(OutageInterval("02:00", "04:00", "2"), q52[0])
        assertEquals(OutageInterval("23:00", "24:00", "1"), q52[1])
        assertTrue(parsed["1.1"]!!.isEmpty())
    }

    @Test
    fun testParseScheduleReturnsNullOnBlankOrWrongDate() {
        val date = LocalDate.of(2026, 11, 21)
        assertNull(PoeScheduleClient.parseSchedule("", date))
        assertNull(
            PoeScheduleClient.parseSchedule(
                "<div class=\"gpvinfodetail\"><p>20 листопада 2026 року</p></div>",
                date
            )
        )
    }

    @Test
    fun testSlotsToOutageIntervalsHandlesHalfHours() {
        val slots = MutableList(48) { 1 }
        slots[1] = 0 // 00:30 - 01:00
        slots[2] = 0 // 01:00 - 01:30
        slots[3] = 0 // 01:30 - 02:00
        val intervals = PoeScheduleClient.slotsToOutageIntervals(slots)
        assertEquals(1, intervals.size)
        assertEquals(OutageInterval("00:30", "02:00", "1.5"), intervals[0])
    }
}
