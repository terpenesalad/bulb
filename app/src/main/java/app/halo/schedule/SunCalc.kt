package app.halo.schedule

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** NOAA-style sunrise/sunset (accurate to a minute or two), no network needed. */
object SunCalc {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Returns sunrise/sunset for [date] at the location, or null in polar day/night. */
    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime>? {
        val rise = calc(date, lat, lon, zone, true) ?: return null
        val set = calc(date, lat, lon, zone, false) ?: return null
        return rise to set
    }

    private fun calc(date: LocalDate, lat: Double, lon: Double, zone: ZoneId, rising: Boolean): ZonedDateTime? {
        val zenith = 90.833
        val n = date.dayOfYear
        val lngHour = lon / 15.0
        val t = if (rising) n + ((6 - lngHour) / 24) else n + ((18 - lngHour) / 24)
        val m = (0.9856 * t) - 3.289
        var l = m + (1.916 * sin(rad(m))) + (0.020 * sin(rad(2 * m))) + 282.634
        l = ((l % 360) + 360) % 360
        var ra = deg(atan(0.91764 * tan(rad(l))))
        ra = ((ra % 360) + 360) % 360
        val lQuadrant = floor(l / 90) * 90
        val raQuadrant = floor(ra / 90) * 90
        ra = (ra + (lQuadrant - raQuadrant)) / 15
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(zenith)) - (sinDec * sin(rad(lat)))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        val localMean = h + ra - (0.06571 * t) - 6.622
        val ut = ((localMean - lngHour) % 24 + 24) % 24
        val secs = (ut * 3600).toLong()
        val utcMidnight = date.atStartOfDay(ZoneId.of("UTC"))
        var result = utcMidnight.plusSeconds(secs).withZoneSameInstant(zone)
        // keep it on the requested local date
        if (result.toLocalDate().isAfter(date)) result = result.minusDays(1)
        if (result.toLocalDate().isBefore(date)) result = result.plusDays(1)
        return result
    }
}
