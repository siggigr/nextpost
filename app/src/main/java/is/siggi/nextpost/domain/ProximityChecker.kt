package `is`.siggi.nextpost.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Arrival detection, kept free of android.location.Location so it runs on the JVM test
 * source set. Reimplements great-circle (haversine) distance rather than using
 * Location.distanceTo, which is Android-only.
 */
object ProximityChecker {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Single source of truth for the arrival radius, per section 12. Only new games/posts pick
     * up a change here — existing ones keep whatever radius is already in their stored data, so
     * this is safe to retune after further field testing without a migration.
     */
    const val DEFAULT_ARRIVAL_RADIUS_METERS = 18

    /**
     * Fixes worse than this are rejected rather than trusted for arrival, per section 6. Derived
     * from [DEFAULT_ARRIVAL_RADIUS_METERS] rather than an independent constant so the two can't
     * drift apart if the radius changes again: a fix reporting a short distance to target with
     * much worse accuracy carries no information, since the true position could be anywhere in a
     * circle far larger than the target.
     */
    val MAX_ACCEPTABLE_ACCURACY_METERS: Double = DEFAULT_ARRIVAL_RADIUS_METERS * 1.5

    data class ArrivalCheck(
        val hasArrived: Boolean,
        val distanceMeters: Double,
        val accuracyRejected: Boolean
    )

    fun checkArrival(
        currentLat: Double,
        currentLng: Double,
        currentAccuracyMeters: Double,
        targetLat: Double,
        targetLng: Double,
        radiusMeters: Double
    ): ArrivalCheck {
        val distance = distanceMeters(currentLat, currentLng, targetLat, targetLng)
        if (currentAccuracyMeters > MAX_ACCEPTABLE_ACCURACY_METERS) {
            return ArrivalCheck(hasArrived = false, distanceMeters = distance, accuracyRejected = true)
        }
        return ArrivalCheck(
            hasArrived = distance <= radiusMeters,
            distanceMeters = distance,
            accuracyRejected = false
        )
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}