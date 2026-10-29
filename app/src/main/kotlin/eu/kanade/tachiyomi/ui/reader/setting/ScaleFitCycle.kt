package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehviewer.core.i18n.R

/**
 * Bottom-chrome toggle between fit screen and fill screen.
 * Other scale types remain available in settings.
 */
@Stable
enum class ScaleFitCycle(
    override val prefValue: Int,
    override val stringRes: Int,
    override val icon: ImageVector,
) : PreferenceType {
    FIT_SCREEN(1, R.string.scale_type_fit_screen, Icons.Filled.FitScreen),
    FILL_SCREEN(7, R.string.scale_type_fill_screen, Icons.Filled.FitScreen),
    ;

    companion object {
        fun fromPreference(preference: Int): ScaleFitCycle = entries.find { it.prefValue == preference } ?: FIT_SCREEN

        fun next(preference: Int): ScaleFitCycle = if (preference == FIT_SCREEN.prefValue) FILL_SCREEN else FIT_SCREEN
    }
}
