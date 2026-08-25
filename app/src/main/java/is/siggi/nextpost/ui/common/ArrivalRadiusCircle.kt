package `is`.siggi.nextpost.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import `is`.siggi.nextpost.data.model.Post

private const val ARRIVAL_RADIUS_STROKE_WIDTH_PX = 4f
private const val ARRIVAL_RADIUS_FILL_ALPHA = 0.15f
private const val ARRIVAL_RADIUS_HALO_STROKE_WIDTH_PX = 8f
private const val ARRIVAL_RADIUS_HALO_ALPHA = 0.35f

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
    // A wider, dark halo drawn first (lower zIndex) so the orange stroke on top of it keeps
    // an edge against dark satellite/hybrid imagery, where flat orange alone can blend into
    // the ground. fillColor must be set explicitly here — Circle's default fillColor is
    // opaque Color.Black, and an unset one was previously painting a solid black disc under
    // the ring rather than a stroke-only outline. Harmless on the vector map too — just a
    // faint ring outside the orange one. See the create screen's map type control.
    Circle(
        center = center,
        radius = radiusMeters,
        strokeColor = Color.Black.copy(alpha = ARRIVAL_RADIUS_HALO_ALPHA),
        strokeWidth = ARRIVAL_RADIUS_HALO_STROKE_WIDTH_PX,
        fillColor = Color.Transparent,
        zIndex = 0f
    )
    // zIndex above the halo: Circle overlays at equal zIndex draw in an arbitrary order per
    // the Maps SDK, so without this the halo could just as easily land on top and hide the
    // ring this is meant to keep visible.
    Circle(
        center = center,
        radius = radiusMeters,
        strokeColor = MaterialTheme.colorScheme.primary,
        strokeWidth = ARRIVAL_RADIUS_STROKE_WIDTH_PX,
        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = ARRIVAL_RADIUS_FILL_ALPHA),
        zIndex = 1f
    )
}
