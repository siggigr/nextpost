package `is`.siggi.nextpost.ui.theme

import androidx.compose.ui.unit.dp

/** 4 dp grid, per section 14.4: spacing is a named constant, never a literal in layout code. */
object Spacing {
    val hairline = 1.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /** 48 dp minimum tap target everywhere, per section 14.1. */
    val minTouchTarget = 48.dp

    /** 56 dp for any primary action on the play screen, per section 14.1. */
    val primaryTouchTarget = 56.dp
}
