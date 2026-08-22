package `is`.siggi.nextpost.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `is`.siggi.nextpost.R

/** Never includes background access — section 6 explicitly excludes it. */
enum class LocationAccess {
    Denied,
    ApproximateOnly,
    Precise
}

data class LocationAccessState(
    val access: LocationAccess,
    val isPermanentlyDenied: Boolean,
    val request: () -> Unit
)

@Composable
fun rememberLocationAccessState(): LocationAccessState {
    val context = LocalContext.current
    var access by remember { mutableStateOf(currentLocationAccess(context)) }
    // shouldShowRequestPermissionRationale is false both before the first request and
    // after a permanent denial, so we can't tell those apart without tracking this ourselves.
    var hasRequested by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    fun refresh() {
        access = currentLocationAccess(context)
        permanentlyDenied = access == LocationAccess.Denied &&
            hasRequested &&
            context.findActivity()?.let { activity ->
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            } == true
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    // Denied, permanently-denied and approximate-only can all be resolved externally in
    // system settings, so re-check whenever the app comes back to the foreground rather
    // than only on first composition.
    val currentRefresh by rememberUpdatedState(::refresh)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return LocationAccessState(
        access = access,
        isPermanentlyDenied = permanentlyDenied,
        request = {
            hasRequested = true
            launcher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun currentLocationAccess(context: Context): LocationAccess {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (fineGranted) return LocationAccess.Precise

    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return if (coarseGranted) LocationAccess.ApproximateOnly else LocationAccess.Denied
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

/**
 * Shows [content] once precise location is granted; otherwise blocks with a rationale
 * (nothing requested yet or denied) or a message explaining approximate-only isn't enough.
 */
@Composable
fun LocationAccessGate(
    state: LocationAccessState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when (state.access) {
        LocationAccess.Precise -> content()

        LocationAccess.ApproximateOnly -> {
            val context = LocalContext.current
            LocationMessage(
                modifier = modifier,
                title = stringResource(R.string.location_approximate_title),
                body = stringResource(R.string.location_approximate_body),
                buttonText = stringResource(R.string.location_open_settings),
                onButtonClick = { openAppSettings(context) }
            )
        }

        LocationAccess.Denied -> {
            val context = LocalContext.current
            if (state.isPermanentlyDenied) {
                LocationMessage(
                    modifier = modifier,
                    title = stringResource(R.string.location_permanently_denied_title),
                    body = stringResource(R.string.location_permanently_denied_body),
                    buttonText = stringResource(R.string.location_open_settings),
                    onButtonClick = { openAppSettings(context) }
                )
            } else {
                LocationMessage(
                    modifier = modifier,
                    title = stringResource(R.string.location_rationale_title),
                    body = stringResource(R.string.location_rationale_body),
                    buttonText = stringResource(R.string.location_grant_access),
                    onButtonClick = state.request
                )
            }
        }
    }
}

@Composable
private fun LocationMessage(
    title: String,
    body: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text = body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onButtonClick, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(buttonText)
        }
    }
}