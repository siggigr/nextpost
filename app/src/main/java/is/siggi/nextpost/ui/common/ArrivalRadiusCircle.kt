package `is`.siggi.nextpost.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import `is`.siggi.nextpost.data.model.Post

private const val ARRIVAL_RADIUS_STROKE_WIDTH_PX = 4f
private const val ARRIVAL_RADIUS_FILL_ALPHA = 0.15f

/**
 * The arrival radius drawn to true scale in metres, so it shrinks and grows with zoom like
 * any other map overlay rather than the creator having to imagine it. Used while placing a
 * post (section 5.2) and reusable as-is on the M5 play screen, which needs the same overlay.
 *
 * Must be called from inside a GoogleMap content block.
 */
@Composable
fun ArrivalRadiusCircle(
    center: LatLng,
    radiusMeters: Double = Post.DEFAULT_RADIUS_METERS.toDouble()
) {
    Circle(
        center = center,
        radius = radiusMeters,
        strokeColor = MaterialTheme.colorScheme.primary,
        strokeWidth = ARRIVAL_RADIUS_STROKE_WIDTH_PX,
        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = ARRIVAL_RADIUS_FILL_ALPHA)
    )
}
