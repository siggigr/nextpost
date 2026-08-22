package `is`.siggi.nextpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import `is`.siggi.nextpost.data.repository.LocationRepository
import `is`.siggi.nextpost.ui.common.LocationAccessGate
import `is`.siggi.nextpost.ui.common.rememberLocationAccessState
import `is`.siggi.nextpost.ui.theme.NextpostTheme
import kotlinx.coroutines.CancellationException

private val REYKJAVIK = LatLng(64.1466, -21.9426)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NextpostTheme {
                NextpostApp()
            }
        }
    }
}

@Composable
private fun NextpostApp() {
    LaunchedEffect(Unit) {
        val auth = Firebase.auth
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }
    }

    val locationAccessState = rememberLocationAccessState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            LocationAccessGate(state = locationAccessState) {
                NextpostMap(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NextpostMap(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locationRepository = remember { LocationRepository(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(REYKJAVIK, 12f)
    }

    LaunchedEffect(Unit) {
        try {
            val location = locationRepository.getCurrentLocation()
            if (location != null) {
                cameraPositionState.position =
                    CameraPosition.fromLatLngZoom(LatLng(location.latitude, location.longitude), 16f)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No fix yet; the map stays on its default centre.
        }
    }

    // Zoom control buttons render bottom-right, which section 14.1 reserves for the
    // primary action and clue sheet. My-location button belongs to a later screen.
    val mapUiSettings = MapUiSettings(
        zoomGesturesEnabled = true,
        zoomControlsEnabled = false,
        mapToolbarEnabled = false,
        myLocationButtonEnabled = false
    )
    val mapProperties = MapProperties(isMyLocationEnabled = true)

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        uiSettings = mapUiSettings,
        properties = mapProperties
    )
}