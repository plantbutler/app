package garden.butler.app

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun point(ts: Long, raw: Long, n: Int = 1) = HistoryPoint(ts, raw, n = n)

private fun controller(nextS: Int? = null) = ControllerHealth("b1", lastSeen = 1000, nextS = nextS)

private fun history(bucketS: Int = 300, hours: Long = 24, vararg points: HistoryPoint) =
    History("b1", 0, since = 2_000_000 - hours * 3600, to = 2_000_000, bucketS = bucketS, points = points.toList())

private val zurich = ZoneId.of("Europe/Zurich")

private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
    ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zurich).toEpochSecond()

class ChartTest {
    @Test
    fun `moisture percent is linear between the calibration points`() {
        assertEquals(50, moisturePct(8000, 12000, 4000))
        assertEquals(0, moisturePct(12000, 12000, 4000))
        assertEquals(100, moisturePct(4000, 12000, 4000))
    }

    @Test
    fun `a half rounds to even like the backend's round`() {
        assertEquals(0, moisturePct(199, 200, 0)) // 0.5
        assertEquals(2, moisturePct(197, 200, 0)) // 1.5
        assertEquals(2, moisturePct(195, 200, 0)) // 2.5
        assertEquals(4, moisturePct(193, 200, 0)) // 3.5
    }

    @Test
    fun `beyond the endpoints clamps to 0 and 100`() {
        assertEquals(0, moisturePct(13000, 12000, 4000))
        assertEquals(100, moisturePct(3000, 12000, 4000))
    }

    @Test
    fun `uncalibrated or degenerate calibration has no percent`() {
        assertNull(moisturePct(8000, null, 4000))
        assertNull(moisturePct(8000, 12000, null))
        assertNull(moisturePct(8000, 5000, 5000))
        assertFalse(isCalibrated(5000, 5000))
        assertFalse(isCalibrated(null, 4000))
        assertTrue(isCalibrated(12000, 4000))
    }

    @Test
    fun `a probe that counts up when wet works too`() {
        assertEquals(50, moisturePct(8000, 4000, 12000))
        assertEquals(0, moisturePct(4000, 4000, 12000))
        assertEquals(100, moisturePct(12000, 4000, 12000))
    }

    @Test
    fun `silence is three intervals and never under ten minutes`() {
        assertEquals(600, silentAfterS(null, 60))
        assertEquals(600, silentAfterS(5, 60))
        assertEquals(900, silentAfterS(300, 60))
        assertEquals(900, silentAfterS(null, 300))
    }

    @Test
    fun `the gap is two buckets or the silence threshold, whichever is longer`() {
        assertEquals(600, chartGapS(300, controller(), 60))
        assertEquals(900, chartGapS(300, controller(nextS = 300), 60))
        assertEquals(7200, chartGapS(3600, controller(), 60))
        assertEquals(600, chartGapS(60, null, 60))
        assertEquals(900, chartGapS(60, null, 300))
    }

    @Test
    fun `buckets exactly a gap apart stay in one segment, one second more splits`() {
        val joined = chartSeries(listOf(point(0, 8000), point(600, 8000)), null, null, 600)
        assertEquals(1, joined.size)
        val split = chartSeries(listOf(point(0, 8000), point(601, 8000)), null, null, 600)
        assertEquals(2, split.size)
        assertEquals(listOf(0L), split[0].map { it.ts })
        assertEquals(listOf(601L), split[1].map { it.ts })
    }

    @Test
    fun `one point is one segment of one sample and no points is no segment`() {
        assertEquals(listOf(listOf(Sample(0, 8000.0))), chartSeries(listOf(point(0, 8000)), null, null, 600))
        assertEquals(emptyList(), chartSeries(emptyList(), null, null, 600))
    }

    @Test
    fun `values are percent when calibrated and raw counts otherwise`() {
        val points = listOf(point(0, 8000), point(300, 12000))
        assertEquals(listOf(50.0, 0.0), chartSeries(points, 12000, 4000, 600).single().map { it.value })
        assertEquals(listOf(8000.0, 12000.0), chartSeries(points, null, null, 600).single().map { it.value })
        assertEquals(listOf(8000.0, 12000.0), chartSeries(points, 5000, 5000, 600).single().map { it.value })
    }

    @Test
    fun `a calibrated range is always 0 to 100`() {
        assertEquals(YRange(0.0, 100.0), chartRange(listOf(listOf(Sample(0, 50.0))), calibrated = true))
        assertEquals(YRange(0.0, 100.0), chartRange(emptyList(), calibrated = true))
    }

    @Test
    fun `a raw range spans the data padded by five percent across segments`() {
        val series = listOf(listOf(Sample(0, 8000.0), Sample(300, 9000.0)), listOf(Sample(1200, 10000.0)))
        assertEquals(YRange(7900.0, 10100.0), chartRange(series, calibrated = false))
    }

    @Test
    fun `a flat raw series gets fifty counts of air and no data is 0 to 1`() {
        assertEquals(YRange(7950.0, 8050.0), chartRange(listOf(listOf(Sample(0, 8000.0), Sample(300, 8000.0))), false))
        assertEquals(YRange(0.0, 1.0), chartRange(emptyList(), calibrated = false))
        assertEquals(YRange(0.0, 1.0), chartRange(listOf(emptyList()), calibrated = false))
    }

    @Test
    fun `the caption names the window, the buckets, the readings and the scale`() {
        val h = history(300, 24, point(0, 8000, n = 1000), point(300, 8100, n = 421))
        assertEquals(
            "24 h · 5-min buckets, 1421 readings · % from dry 12000 / wet 4000",
            chartCaption(h, 12000, 4000),
        )
        assertEquals(
            "24 h · 5-min buckets, 1421 readings · raw counts — calibrate to read %",
            chartCaption(h, null, 4000),
        )
        assertEquals(
            "24 h · 5-min buckets, 1421 readings · raw counts — calibrate to read %",
            chartCaption(h, 5000, 5000),
        )
    }

    @Test
    fun `hour buckets and other windows read from the answer`() {
        val h = history(3600, 168, point(0, 8000, n = 60))
        // Past a couple of days the span reads in days: "168 h" is a number
        // nobody pictures as a week.
        assertEquals("7 d · 1-h buckets, 60 readings · raw counts — calibrate to read %", chartCaption(h, null, null))
        val month = history(3600, 720, point(0, 8000, n = 60))
        assertEquals("30 d · 1-h buckets, 60 readings · raw counts — calibrate to read %", chartCaption(month, null, null))
        // A day and just under two are still hours.
        assertEquals("24 h · 5-min buckets, 1 readings · raw counts — calibrate to read %",
            chartCaption(history(300, 24, point(0, 8000)), null, null))
        assertEquals("47 h · 5-min buckets, 1 readings · raw counts — calibrate to read %",
            chartCaption(history(300, 47, point(0, 8000)), null, null))
    }

    @Test
    fun `no points says so`() {
        assertEquals("no readings in the last 24 h", chartCaption(history(300, 24), 12000, 4000))
        assertEquals("no readings in the last 24 h", chartCaption(History(), null, null))
    }

    @Test
    fun `an environment pot's raw counts are not asked to be calibrated`() {
        val h = history(300, 24, point(0, 8000, n = 1000), point(300, 8100, n = 421))
        assertEquals("24 h · 5-min buckets, 1421 readings · raw counts", chartCaption(h, null, null, env = true))
        assertEquals(
            "24 h · 5-min buckets, 1421 readings · % from dry 12000 / wet 4000",
            chartCaption(h, 12000, 4000, env = true),
        )
    }

    @Test
    fun `calibrated ticks are every quarter`() {
        val ticks = yTicks(YRange(0.0, 100.0), calibrated = true)
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), ticks.map { it.at })
        assertEquals(listOf("0%", "25%", "50%", "75%", "100%"), ticks.map { it.label })
    }

    @Test
    fun `raw ticks are the finest round step that fits at most six times`() {
        val thousands = yTicks(YRange(5840.0, 10288.0), calibrated = false)
        assertEquals(listOf("6000", "7000", "8000", "9000", "10000"), thousands.map { it.label })
        assertEquals(listOf(6000.0, 7000.0, 8000.0, 9000.0, 10000.0), thousands.map { it.at })
        val flat = yTicks(YRange(7950.0, 8050.0), calibrated = false)
        assertEquals(listOf("7960", "7980", "8000", "8020", "8040"), flat.map { it.label })
    }

    @Test
    fun `a range under ten ticks at whole counts`() {
        assertEquals(listOf("3", "4", "5", "6", "7", "8"), yTicks(YRange(2.75, 8.25), false).map { it.label })
        assertEquals(listOf("2", "4", "6", "8"), yTicks(YRange(0.5, 9.5), false).map { it.label })
    }

    @Test
    fun `a range too narrow for two whole counts gets the integers around it`() {
        assertEquals(listOf(5.0, 6.0), yTicks(YRange(5.0, 5.0), false).map { it.at })
        assertEquals(listOf(0.0, 1.0), yTicks(YRange(0.2, 0.8), false).map { it.at })
        assertEquals(listOf("0", "1"), yTicks(YRange(0.0, 1.0), false).map { it.label })
    }

    @Test
    fun `time ticks are the six-hour marks, across midnight too`() {
        val ticks = timeTicks(at(6, 10, 20, 30), at(6, 11, 8), zurich)
        assertEquals(listOf("00:00", "06:00"), ticks.map { it.label })
        assertEquals(listOf(at(6, 11, 0), at(6, 11, 6)), ticks.map { it.ts })
    }

    @Test
    fun `the spring-forward day keeps its wall-clock marks, five then six hours apart`() {
        val ticks = timeTicks(at(3, 29, 0), at(3, 30, 0), zurich)
        assertEquals(listOf("00:00", "06:00", "12:00", "18:00"), ticks.map { it.label })
        assertEquals(listOf(5 * 3600L, 6 * 3600L, 6 * 3600L), ticks.zipWithNext { a, b -> b.ts - a.ts })
    }

    @Test
    fun `the hour the clock skips is not a mark`() {
        val ticks = timeTicks(at(3, 29, 1), at(3, 29, 5), zurich, stepH = 1)
        assertEquals(listOf("01:00", "03:00", "04:00"), ticks.map { it.label })
    }

    @Test
    fun `a day from an odd minute starts at the next mark and stops before now`() {
        val since = at(3, 30, 9, 17)
        val ticks = timeTicks(since, since + 24 * 3600, zurich)
        assertEquals(listOf("12:00", "18:00", "00:00", "06:00"), ticks.map { it.label })
        assertEquals(at(3, 30, 12), ticks.first().ts)
        assertTrue(ticks.all { it.ts >= since })
    }

    @Test
    fun `an empty window has no marks, even on one`() {
        assertEquals(emptyList(), timeTicks(at(6, 10, 12), at(6, 10, 12), zurich))
        assertEquals(emptyList(), timeTicks(at(6, 10, 12, 30), at(6, 10, 12, 30), zurich))
    }
}

class ChartWindowTest {
    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun `every window stays near a day's point count and inside the backend's cap`() {
        // The backend refuses more than 2016 buckets; a phone-width canvas
        // cannot draw many more than a few hundred either way.
        for (w in ChartWindow.entries) {
            assertTrue(w.points in 200..800, "${w.label} asks for ${w.points} points")
            assertTrue(w.hours * 3600 / w.bucketS <= 2016)
        }
        assertEquals(288, ChartWindow.DAY.points)
    }

    @Test
    fun `a day is labelled by the clock and longer windows by the date`() {
        val day = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, zone).toEpochSecond()
        val dayTicks = windowTicks(ChartWindow.DAY, day, day + 24 * 3600, zone)
        assertEquals(listOf("00:00", "06:00", "12:00", "18:00"), dayTicks.map { it.label })

        val week = windowTicks(ChartWindow.WEEK, day, day + 7 * 24 * 3600, zone)
        assertEquals(7, week.size)
        assertEquals("1 Sep", week.first().label)
        assertEquals("7 Sep", week.last().label)

        val month = windowTicks(ChartWindow.MONTH, day, day + 30 * 24 * 3600, zone)
        assertEquals(listOf("1 Sep", "6 Sep", "11 Sep", "16 Sep", "21 Sep", "26 Sep"), month.map { it.label })
    }

    @Test
    fun `the gap that breaks the line scales with the bucket`() {
        // The rabbit hole: a threshold written for a day's 5-min buckets
        // would swallow a whole outage inside one hourly bucket.
        val board = ControllerHealth("b1", nextS = 60)
        assertEquals(600L, chartGapS(ChartWindow.DAY.bucketS, board, 60))
        assertEquals(3600L, chartGapS(ChartWindow.WEEK.bucketS, board, 60))
        assertEquals(7200L, chartGapS(ChartWindow.MONTH.bucketS, board, 60))
    }

    @Test
    fun `the scrub lands on the nearest real sample, never between two`() {
        val series = listOf(listOf(Sample(1000, 40.0), Sample(2000, 50.0), Sample(3000, 60.0)))
        assertEquals(Sample(1000, 40.0), scrubbed(series, 0.0, 1000, 3000))
        assertEquals(Sample(3000, 60.0), scrubbed(series, 1.0, 1000, 3000))
        assertEquals(Sample(2000, 50.0), scrubbed(series, 0.5, 1000, 3000))
        // Just past the midpoint is still the nearer of the two, not a mean.
        assertEquals(Sample(2000, 50.0), scrubbed(series, 0.6, 1000, 3000))
        // Off the ends clamps rather than returning nothing.
        assertEquals(Sample(1000, 40.0), scrubbed(series, -2.0, 1000, 3000))
        assertEquals(Sample(3000, 60.0), scrubbed(series, 9.0, 1000, 3000))
        assertNull(scrubbed(emptyList(), 0.5, 1000, 3000))
        assertNull(scrubbed(listOf(emptyList()), 0.5, 1000, 3000))
    }

    @Test
    fun `the scrub reads out the sample's own time, and its own scale`() {
        val ts = ZonedDateTime.of(2026, 9, 1, 14, 35, 0, 0, zone).toEpochSecond()
        assertEquals("48% · Tue 1 Sep 14:35", scrubLabel(Sample(ts, 48.0), calibrated = true, zone))
        assertEquals("raw 8123 · Tue 1 Sep 14:35", scrubLabel(Sample(ts, 8123.0), calibrated = false, zone))
    }
}
