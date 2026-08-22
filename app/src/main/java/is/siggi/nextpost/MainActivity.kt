package `is`.siggi.nextpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import `is`.siggi.nextpost.ui.theme.NextpostTheme

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
    var signedInUid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val auth = Firebase.auth
        val existingUser = auth.currentUser
        if (existingUser != null) {
            signedInUid = existingUser.uid
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result -> signedInUid = result.user?.uid }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val cameraPositionState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(REYKJAVIK, 12f)
            }
            // Zoom control buttons render bottom-right, which section 14.1 reserves for the
            // primary action and clue sheet. My-location belongs in M1, with the permission flow.
            val mapUiSettings = MapUiSettings(
                zoomGesturesEnabled = true,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false
            )
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = mapUiSettings
            )
            Surface(
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            ) {
                Text(
                    text = signedInUid?.let { "Signed in: $it" } ?: "Signing in…",
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
