package com.alex.rutasapie

import java.util.Locale
import kotlin.math.*

/** Pure recording logic. Monotonic time is used only in a live process, never across boots. */
data class GpsFix(
    val latitude: Double, val longitude: Double, val accuracy: Double,
    val timeMillis: Long, val elapsedMillis: Long,
    val altitude: Double? = null, val speedMps: Double? = null
) {
    fun isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracy.isFinite() && accuracy > 0 && timeMillis > 0 && elapsedMillis >= 0 &&
        (altitude == null || altitude.isFinite()) &&
        (speedMps == null || speedMps.isFinite() && speedMps >= 0)
}

data class TrackPoint(
    val latitude: Double, val longitude: Double, val timeMillis: Long,
    val accuracy: Double, val altitude: Double? = null, val segment: Int = 0
)

data class WalkSession(
    val id: Long, val name: String, val startedAt: Long, val status: String,
    val distanceMeters: Double, val durationMillis: Long, val pointCount: Int
)

data class RecordedPoint(val point: TrackPoint, val distanceAdded: Double)

object GeoMath {
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a1 = Math.toRadians(lat1); val a2 = Math.toRadians(lat2)
        val dLat = a2-a1; val dLon = Math.toRadians(lon2-lon1)
        val a = sin(dLat/2).pow(2) + cos(a1)*cos(a2)*sin(dLon/2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a1=Math.toRadians(lat1); val a2=Math.toRadians(lat2); val d=Math.toRadians(lon2-lon1)
        return (Math.toDegrees(atan2(sin(d)*cos(a2), cos(a1)*sin(a2)-sin(a1)*cos(a2)*cos(d)))+360) % 360
    }
    fun duration(ms: Long): String {
        val s=ms.coerceAtLeast(0)/1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s/3600, s/60%60, s%60)
    }
    fun averageKmh(meters: Double, millis: Long) = if (millis > 0) meters * 3600 / millis else 0.0
}

class WalkRecorder(
    initialDistance: Double = 0.0, initialDuration: Long = 0L, nextSegment: Int = 0
) {
    var distanceMeters = initialDistance
        private set
    private var accumulated = initialDuration
    private var anchor: Long? = null
    private var lastAccepted: GpsFix? = null
    private var lastSeen = -1L
    private var segment = nextSegment
    private var needsSegment = false
    val recording get() = anchor != null

    fun resume(now: Long) {
        if (recording) return
        anchor=now; lastAccepted=null; lastSeen=-1; needsSegment=false
    }
    fun pause(now: Long) {
        if (!recording) return
        accumulated=duration(now); anchor=null; lastAccepted=null; segment++
    }
    fun duration(now: Long): Long = accumulated + (anchor?.let { (now-it).coerceAtLeast(0) } ?: 0)

    /** Reject bad accuracy, stale fixes, duplicates, impossible walking jumps and stationary jitter. */
    fun accept(fix: GpsFix, now: Long): RecordedPoint? {
        if (!recording || !fix.isValid() || fix.accuracy > MAX_ACCURACY ||
            now-fix.elapsedMillis !in -1000L..15_000L || fix.elapsedMillis <= lastSeen) return null
        lastSeen=fix.elapsedMillis
        val previous=lastAccepted
        var delta=0.0
        if (previous != null) {
            val seconds=(fix.elapsedMillis-previous.elapsedMillis)/1000.0
            val step=GeoMath.distance(previous.latitude,previous.longitude,fix.latitude,fix.longitude)
            if (seconds <= 0) return null
            if (seconds > 30) {
                segment++; needsSegment=false
            } else {
                if (step/seconds > MAX_WALKING_SPEED || (fix.speedMps ?: 0.0) > MAX_WALKING_SPEED) {
                    needsSegment=true
                    return null
                }
                val threshold=max(4.0, min(12.0, (previous.accuracy+fix.accuracy)*0.325))
                if (step < threshold || (fix.speedMps != null && fix.speedMps < 0.45)) return null
                if(needsSegment) {segment++; needsSegment=false} else delta=step
            }
        }
        if ((fix.speedMps ?: 0.0) > MAX_WALKING_SPEED) return null
        lastAccepted=fix; distanceMeters+=delta
        return RecordedPoint(TrackPoint(fix.latitude,fix.longitude,fix.timeMillis,fix.accuracy,fix.altitude,segment),delta)
    }
    companion object {
        const val MAX_ACCURACY=35.0
        const val MAX_WALKING_SPEED=12.0
    }
}
