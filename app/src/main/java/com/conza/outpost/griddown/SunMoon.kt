package com.conza.outpost.griddown

import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise/sunset (US Naval Observatory "Almanac for Computers" algorithm) and moon phase, all offline. */
object SunMoon {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(x: Double, m: Double) = ((x % m) + m) % m

    sealed interface SunEvent {
        data class At(val localHours: Double) : SunEvent
        data object Never : SunEvent // sun never reaches this altitude today (polar night or midnight sun)
    }

    /** Local clock time (hours) of sunrise or sunset. Official zenith 90°50′ includes refraction. */
    fun sunEvent(date: LocalDate, lat: Double, lon: Double, utcOffsetHours: Double, rising: Boolean, zenith: Double = 90.833): SunEvent {
        val n = date.dayOfYear
        val lngHour = lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(zenith)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return SunEvent.Never
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val localMean = h + ra - 0.06571 * t - 6.622
        val ut = norm(localMean - lngHour, 24.0)
        return SunEvent.At(norm(ut + utcOffsetHours, 24.0))
    }

    fun format(e: SunEvent): String = when (e) {
        SunEvent.Never -> "—"
        is SunEvent.At -> {
            val totalMin = Math.round(e.localHours * 60).toInt() % (24 * 60)
            val h = totalMin / 60; val mi = totalMin % 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            "%d:%02d %s".format(h12, mi, if (h < 12) "AM" else "PM")
        }
    }

    const val SYNODIC = 29.530588853

    data class Moon(val ageDays: Double, val illumination: Double, val name: String)

    /** Moon age from the reference new moon of 2000-01-06 18:14 UTC (JD 2451550.26). Accurate to about a day. */
    fun moon(epochMillis: Long): Moon {
        val jd = epochMillis / 86_400_000.0 + 2440587.5
        val age = norm(jd - 2451550.26, SYNODIC)
        val illum = (1 - cos(2 * Math.PI * age / SYNODIC)) / 2
        val name = when {
            age < 1.85 -> "New moon"
            age < 5.54 -> "Waxing crescent"
            age < 9.23 -> "First quarter"
            age < 12.92 -> "Waxing gibbous"
            age < 16.61 -> "Full moon"
            age < 20.30 -> "Waning gibbous"
            age < 23.99 -> "Last quarter"
            age < 27.68 -> "Waning crescent"
            else -> "New moon"
        }
        return Moon(age, illum, name)
    }
}
