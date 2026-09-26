package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings

/**
 * Dual-page landscape cover, same values as [Settings.landscapeCover].
 * Extra bottom-bar button in landscape single-page dual mode. Tap cycles Auto → On → Off.
 */
@Stable
enum class LandscapeCoverCycle(
    override val prefValue: Int,
    override val stringRes: Int,
    override val icon: ImageVector,
) : PreferenceType {
    AUTO(Settings.LANDSCAPE_COVER_AUTO, R.string.pref_landscape_cover_auto, Icons.Filled.AutoAwesome),
    ON(Settings.LANDSCAPE_COVER_ON, R.string.pref_landscape_cover_on, Icons.Filled.CropLandscape),
    OFF(Settings.LANDSCAPE_COVER_OFF, R.string.pref_landscape_cover_off, Icons.Filled.FilterNone),
    ;

    companion object {
        fun fromPreference(preference: Int): LandscapeCoverCycle = entries.find { it.prefValue == preference } ?: OFF

        fun next(preference: Int): LandscapeCoverCycle {
            val index = entries.indexOfFirst { it.prefValue == preference }
            return entries[(index + 1) % entries.size]
        }
    }
}
