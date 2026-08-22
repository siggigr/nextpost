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

    @Test
    fun `checkArrival registers arrival within radius with a good fix`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 10.0,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = 25.0
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
            radiusMeters = 25.0
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
    fun `checkArrival rejects a fix worse than 50m accuracy even inside the radius`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 80.0,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = 25.0
        )
        assertFalse(result.hasArrived)
        assertTrue(result.accuracyRejected)
        // The distance itself is still reported correctly, only arrival is withheld.
        assertEquals(0.0, result.distanceMeters, 0.001)
    }

    @Test
    fun `checkArrival accepts a fix at exactly 50m accuracy`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 50.0,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = 25.0
        )
        assertTrue(result.hasArrived)
        assertFalse(result.accuracyRejected)
    }

    @Test
    fun `checkArrival just above 50m accuracy is rejected`() {
        val result = ProximityChecker.checkArrival(
            currentLat = 64.1466,
            currentLng = -21.9426,
            currentAccuracyMeters = 50.001,
            targetLat = 64.1466,
            targetLng = -21.9426,
            radiusMeters = 25.0
        )
        assertFalse(result.hasArrived)
        assertTrue(result.accuracyRejected)
    }
}