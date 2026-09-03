package garden.butler.app

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

/** The moisture chart as data: which samples, in which segments, on which
 * axis, under which caption. The Canvas only draws what this file decides,
 * so the JVM tests cover every choice without a screen.
 */
const val HISTORY_HOURS = 24
const val HISTORY_BUCKET_S = 300

/** How far back the chart looks, and how coarsely. The bucket is chosen to
 * keep the point count near a day's 288 whatever the span: a month at
 * five-minute buckets would be 8640 points on a phone-width canvas, most of
 * them thinner than a pixel, and the backend refuses over 2016 anyway. */
enum class ChartWindow(val hours: Int, val bucketS: Int, val label: String) {
    DAY(24, HISTORY_BUCKET_S, "day"),
    WEEK(24 * 7, 1800, "week"),
    MONTH(24 * 30, 3600, "month"),
    ;

    val points: Int
        get() = hours * 3600 / bucketS
}

data class Sample(val ts: Long, val value: Double)

fun isCalibrated(dryRaw: Long?, wetRaw: Long?): Boolean =
    dryRaw != null && wetRaw != null && dryRaw != wetRaw

/** The backend's moisture_pct, operation for operation: the same double
 * division and the same round-half-to-even (Math.rint is Python's round),
 * so the curve's % is the pot's % — a chart that ends one point off the
 * title would look like a bug. */
fun moisturePct(raw: Long, dryRaw: Long?, wetRaw: Long?): Int? {
    if (dryRaw == null || wetRaw == null || dryRaw == wetRaw) return null
    val pct = (dryRaw - raw) * 100.0 / (dryRaw - wetRaw)
    return Math.rint(pct).toInt().coerceIn(0, 100)
}

/** How long a controller may go unheard before it counts as silent: three
 * missed reports, and never under ten minutes for a board on a fast pace. */
fun silentAfterS(nextS: Int?, nextDefault: Int): Long = maxOf(600L, 3L * (nextS ?: nextDefault))

/** The hole between two buckets that breaks the line: a missed bucket is
 * noise, a silent board is a gap the eye should see. */
fun chartGapS(bucketS: Int, controller: ControllerHealth?, nextDefault: Int): Long =
    maxOf(2L * bucketS, silentAfterS(controller?.nextS, nextDefault))

/** The polyline's segments: consecutive buckets more than `gapS` apart start
 * a new one. Values are % under the pot's current calibration, else raw
 * counts. */
fun chartSeries(
    points: List<HistoryPoint>,
    dryRaw: Long?,
    wetRaw: Long?,
    gapS: Long,
): List<List<Sample>> {
    val calibrated = isCalibrated(dryRaw, wetRaw)
    val segments = mutableListOf<MutableList<Sample>>()
    var prevTs: Long? = null
    for (p in points) {
        val value =
            if (calibrated) moisturePct(p.raw, dryRaw, wetRaw)!!.toDouble() else p.raw.toDouble()
        if (prevTs == null || p.ts - prevTs > gapS) segments.add(mutableListOf())
        segments.last().add(Sample(p.ts, value))
        prevTs = p.ts
    }
    return segments
}

data class YRange(val low: Double, val high: Double)

/** A calibrated chart is always 0..100, so two pots compare by eye; raw
 * counts get the data's own span with a little air, and a flat line some
 * room so it is not the top edge. */
fun chartRange(series: List<List<Sample>>, calibrated: Boolean): YRange {
    if (calibrated) return YRange(0.0, 100.0)
    val values = series.flatten().map { it.value }
    if (values.isEmpty()) return YRange(0.0, 1.0)
    val low = values.min()
    val high = values.max()
    val pad = if (high > low) (high - low) * 0.05 else 50.0
    return YRange(low - pad, high + pad)
}

data class Tick(val at: Double, val label: String)

/** The horizontal gridlines: every quarter when calibrated, so two pots
 * compare by eye; else the finest 1-2-5 step that puts at most six whole
 * counts inside the range. A range too narrow for two of them gets the
 * integers around it, so the axis is never a single line (chartRange pads,
 * so only a test ever sends one). */
fun yTicks(range: YRange, calibrated: Boolean): List<Tick> {
    if (calibrated) return (0..100 step 25).map { Tick(it.toDouble(), "$it%") }
    val steps = generateSequence(1.0) { it * 10 }.flatMap { sequenceOf(it, 2 * it, 5 * it) }
    val inside = steps.map { step -> multiplesInside(range, step) }.first { it.size <= 6 }
    val at = if (inside.size >= 2) inside else listOf(floor(range.low), floor(range.low) + 1)
    return at.map { Tick(it, "${it.toLong()}") }
}

private fun multiplesInside(range: YRange, step: Double): List<Double> =
    (ceil(range.low / step).toLong()..floor(range.high / step).toLong()).map { it * step }

data class TimeTick(val ts: Long, val label: String)

/** The vertical gridlines: every local hour that is a multiple of `stepH`
 * from the first one at or after `since` up to, not including, `to` (the
 * right edge is "now" already). Built per local day, so midnight and a
 * clock change stay on the wall clock: java.time moves an hour that does
 * not exist to the one after it, and that duplicate is dropped. */
fun timeTicks(since: Long, to: Long, zone: ZoneId, stepH: Int = 6): List<TimeTick> {
    val firstDay = Instant.ofEpochSecond(since).atZone(zone).toLocalDate()
    val lastDay = Instant.ofEpochSecond(to).atZone(zone).toLocalDate()
    val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    return generateSequence(firstDay) { it.plusDays(1) }
        .takeWhile { it <= lastDay }
        .flatMap { day -> (0 until 24 step stepH).map { h -> ZonedDateTime.of(day, LocalTime.of(h, 0), zone) } }
        .filter { it.toEpochSecond() in since until to }
        .distinctBy { it.toEpochSecond() }
        .map { TimeTick(it.toEpochSecond(), hhmm.format(it)) }
        .toList()
}

/** Midnights every `stepDays`, labelled by date: an hour of the clock means
 * nothing across a week, and 28 "00:00" labels mean less than nothing. */
fun dayTicks(since: Long, to: Long, zone: ZoneId, stepDays: Int): List<TimeTick> {
    val firstDay = Instant.ofEpochSecond(since).atZone(zone).toLocalDate()
    val lastDay = Instant.ofEpochSecond(to).atZone(zone).toLocalDate()
    val dm = DateTimeFormatter.ofPattern("d MMM")
    return generateSequence(firstDay) { it.plusDays(stepDays.toLong()) }
        .takeWhile { it <= lastDay }
        .map { ZonedDateTime.of(it, LocalTime.MIDNIGHT, zone) }
        .filter { it.toEpochSecond() in since until to }
        .distinctBy { it.toEpochSecond() }
        .map { TimeTick(it.toEpochSecond(), dm.format(it)) }
        .toList()
}

/** The gridlines for a window: hours across a day, dates across a week or a
 * month. Same shape either way, so the canvas does not care which it got. */
fun windowTicks(window: ChartWindow, since: Long, to: Long, zone: ZoneId): List<TimeTick> =
    when (window) {
        ChartWindow.DAY -> timeTicks(since, to, zone)
        ChartWindow.WEEK -> dayTicks(since, to, zone, stepDays = 1)
        ChartWindow.MONTH -> dayTicks(since, to, zone, stepDays = 5)
    }

/** What the finger is on: the sample nearest the touched fraction of the
 * width, and null when the chart has no points to be near. The fraction,
 * not a pixel, so this stays a pure decision the tests can make. */
fun scrubbed(series: List<List<Sample>>, fraction: Double, since: Long, to: Long): Sample? {
    val samples = series.flatten()
    if (samples.isEmpty()) return null
    val at = since + (to - since) * fraction.coerceIn(0.0, 1.0)
    return samples.minByOrNull { kotlin.math.abs(it.ts - at) }
}

/** The readout under the finger: "48% · Tue 14:35", or raw counts when the
 * pot is not calibrated. The time is the sample's own, not the finger's —
 * pointing between two readings must not invent a third. */
fun scrubLabel(sample: Sample, calibrated: Boolean, zone: ZoneId): String {
    val value = if (calibrated) "${sample.value.toInt()}%" else "raw ${sample.value.toLong()}"
    val at = Instant.ofEpochSecond(sample.ts).atZone(zone)
    return "$value · " + DateTimeFormatter.ofPattern("EEE d MMM HH:mm").format(at)
}

/** "24 h · 5-min buckets, 1421 readings · % from dry 12000 / wet 4000": what
 * the curve is made of, and whose calibration reads it. An `env` pot's raw
 * counts are not asked to be calibrated: a thermometer never is. */
fun chartCaption(history: History, dryRaw: Long?, wetRaw: Long?, env: Boolean = false): String {
    val hours = ((history.to - history.since) / 3600).takeIf { it > 0 } ?: HISTORY_HOURS.toLong()
    // Past a couple of days "720 h" stops being a span anyone pictures.
    val span = if (hours >= 48) "${hours / 24} d" else "$hours h"
    if (history.points.isEmpty()) return "no readings in the last $span"
    val bucket =
        if (history.bucketS % 3600 == 0) "${history.bucketS / 3600}-h" else "${history.bucketS / 60}-min"
    val readings = history.points.sumOf { it.n }
    val scale =
        when {
            isCalibrated(dryRaw, wetRaw) -> "% from dry $dryRaw / wet $wetRaw"
            env -> "raw counts"
            else -> "raw counts — calibrate to read %"
        }
    return "$span · $bucket buckets, $readings readings · $scale"
}
