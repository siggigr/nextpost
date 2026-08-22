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

    /** Fixes worse than this are rejected rather than trusted for arrival, per section 6. */
    const val MAX_ACCEPTABLE_ACCURACY_METERS = 50.0

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