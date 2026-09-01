package `is`.siggi.nextpost.ui.common

import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Shared vocabulary for a failed write, reused across every screen that performs one rather than
 * redefined per screen: a write the rules refused needs different words than one that never
 * reached the server ("Check your connection" sent a debugger looking at the network for a rules
 * bug once already, on the play screen's load path — see [is.siggi.nextpost.ui.play.PlayLoadError],
 * which draws the same distinction for a *load* failure). [PermissionDenied] is anything the
 * security rules explicitly refused; [Unreachable] is everything else — a dropped connection, a
 * timeout, or any other failure a retry might fix.
 */
sealed interface WriteError {
    data object PermissionDenied : WriteError
    data object Unreachable : WriteError
}

/** The one place a caught write failure gets classified — see [WriteError]'s own doc. */
fun Throwable.toWriteError(): WriteError =
    if (this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        WriteError.PermissionDenied
    } else {
        WriteError.Unreachable
    }
