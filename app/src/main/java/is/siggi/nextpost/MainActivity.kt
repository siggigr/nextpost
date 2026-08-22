package `is`.siggi.nextpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import `is`.siggi.nextpost.navigation.NextpostNavHost
import `is`.siggi.nextpost.ui.theme.NextpostTheme

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

    NextpostNavHost(modifier = Modifier.fillMaxSize())
}
