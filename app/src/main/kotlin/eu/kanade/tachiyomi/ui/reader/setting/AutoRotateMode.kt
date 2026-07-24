package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehviewer.core.i18n.R

/**
 * Fit-rotate when image orientation ≠ screen: off / 90° CW / 90° CCW.
 */
@Stable
enum class AutoRotateMode(
    override val prefValue: Int,
    override val stringRes: Int,
    override val icon: ImageVector,
) : PreferenceType {
    OFF(0, R.string.pref_auto_rotate_off, Icons.Default.ScreenRotation),
    CW(1, R.string.pref_auto_rotate_cw, Icons.AutoMirrored.Filled.RotateRight),
    CCW(2, R.string.pref_auto_rotate_ccw, Icons.AutoMirrored.Filled.RotateLeft),
    ;

    val enabled: Boolean get() = this != OFF
    val clockwise: Boolean get() = this == CW

    companion object {
        fun fromPreference(preference: Int): AutoRotateMode =
            entries.find { it.prefValue == preference } ?: CW
    }
}
