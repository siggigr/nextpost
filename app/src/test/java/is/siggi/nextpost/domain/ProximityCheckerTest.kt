package `is`.siggi.nextpost.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityCheckerTest {

    @Test
    fun `distanceMeters is zero for identical points`() {
        val distance = ProximityChecker.distanceMeters(64.1466, -21.9426, 64.1466, -21.9426)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `distanceMeters matches the great-circle distance for one degree of latitude`() {
        // Along a meridian the central angle equals the latitude difference exactly,
        // independent of the haversine formula's usual approximations.
        val distance = ProximityChecker.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.9, distance, 1.0)
    }

    private val radiusMeters = ProximityChecker.DEFAULT_ARRIVAL_RADIUS_METERS.toDouble()
    private val maxAccuracyMeters = ProximityChecker.MAX_ACCEPTABLE_ACCURACY_METERS

    @Test
    fun `checkArrival registers arrival within radius with a good fix`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 10.0,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = radiusMeters
        )
        assertTrue(result.hasArrived)
        assertFalse(result.accuracyRejected)
        assertEquals(0.0, result.distanceMeters, 0.001)
    }

    @Test
    fun `checkArrival does not register arrival outside the radius`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 10.0,
            targetLat = 64.1566, // roughly 1.1 km north
            targetLng = -21.9426,
            radiusMeters = radiusMeters
        )
        assertFalse(result.hasArrived)
        assertFalse(result.accuracyRejected)
    }

    @Test
    fun `checkArrival treats the radius as inclusive`() {
        val exactDistance = ProximityChecker.distanceMeters(0.0, 0.0, 1.0, 0.0)
        val result = ProximityChecker.checkArrival(
            currentLat = 0.0,
            currentLng = 0.0,
            currentAccuracyMeters = 10.0,
            targetLat = 1.0,
            targetLng = 0.0,
            radiusMeters = exactDistance
        )
        assertTrue(result.hasArrived)
    }

    @Test
    fun `checkArrival rejects a fix worse than the accuracy gate even inside the radius`() {
        // AC-6: the equivalent of the old 25 m within / 80 m accuracy case at the new 18 m
        // radius — 40 m is comfortably worse than the ~27 m gate the radius now derives.
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 40.0,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = radiusMeters
        )
        assertFalse(result.hasArrived)
        assertTrue(result.accuracyRejected)
        // The distance itself is still reported correctly, only arrival is withheld.
        assertEquals(0.0, result.distanceMeters, 0.001)
    }

    @Test
    fun `checkArrival accepts a fix at exactly the accuracy gate`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = maxAccuracyMeters,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = radiusMeters
        )
        assertTrue(result.hasArrived)
        assertFalse(result.accuracyRejected)
    }

    @Test
    fun `checkArrival just above the accuracy gate is rejected`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = maxAccuracyMeters + 0.001,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = radiusMeters
        )
        assertFalse(result.hasArrived)
        assertTrue(result.accuracyRejected)
    }
}