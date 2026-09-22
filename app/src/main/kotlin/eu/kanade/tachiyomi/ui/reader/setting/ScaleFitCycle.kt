package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehviewer.core.i18n.R

/**
 * Bottom-chrome cycle of pager scale types: fit width → fit height → fit screen.
 * Other [com.hippo.ehviewer.Settings.imageScaleType] values (stretch, original, smart)
 * are unchanged until the user taps; the first tap enters this trio.
 */
@Stable
enum class ScaleFitCycle(
    override val prefValue: Int,
    override val stringRes: Int,
    override val icon: ImageVector,
) : PreferenceType {
    FIT_WIDTH(3, R.string.scale_type_fit_width, Icons.Filled.CropLandscape),
    FIT_HEIGHT(4, R.string.scale_type_fit_height, Icons.Filled.CropPortrait),
    FIT_SCREEN(1, R.string.scale_type_fit_screen, Icons.Filled.FitScreen),
    ;

    companion object {
        fun fromPreference(preference: Int): ScaleFitCycle =
            entries.find { it.prefValue == preference } ?: FIT_SCREEN

        fun next(preference: Int): ScaleFitCycle {
            val index = entries.indexOfFirst { it.prefValue == preference }
            return entries[(index + 1) % entries.size]
        }
    }
}
