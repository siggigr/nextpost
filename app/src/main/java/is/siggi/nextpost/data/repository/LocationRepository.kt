package `is`.siggi.nextpost.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Section 6: high accuracy, polled every 5 seconds, only while the play screen collects this. */
private const val PLAY_LOCATION_INTERVAL_MS = 5_000L

class LocationRepository(context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * One-shot high-accuracy fix, e.g. to centre a map on launch. Callers must confirm
     * ACCESS_FINE_LOCATION is already granted; this does not check permissions itself.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location -> continuation.resume(location) }
            .addOnFailureListener { exception -> continuation.resumeWithException(exception) }
        continuation.invokeOnCancellation { cancellationTokenSource.cancel() }
    }

    /**
     * Section 6's arrival-polling feed for the play screen: `PRIORITY_HIGH_ACCURACY`, 5 second
     * interval, live only as long as the collector stays subscribed. The caller is what scopes
     * this to "play screen in the foreground" — collecting inside `repeatOnLifecycle(RESUMED)`
     * means backgrounding the app cancels collection, which tears down the underlying
     * `removeLocationUpdates` via [awaitClose], satisfying "pause location updates" without this
     * class needing to know about lifecycle itself. Callers must confirm ACCESS_FINE_LOCATION is
     * already granted; this does not check permissions itself.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, PLAY_LOCATION_INTERVAL_MS).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
    }
}