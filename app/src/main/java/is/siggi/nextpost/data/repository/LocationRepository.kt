package `is`.siggi.nextpost.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
}