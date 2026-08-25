package `is`.siggi.nextpost.data.firebase

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Same manual listener-to-coroutine bridge LocationRepository already uses for GMS Tasks,
 * kept consistent rather than pulling in kotlinx-coroutines-play-services for one extension.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { exception -> continuation.resumeWithException(exception) }
}
