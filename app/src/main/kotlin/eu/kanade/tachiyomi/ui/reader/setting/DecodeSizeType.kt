package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehviewer.core.i18n.R

/**
 * Coil decode target relative to the shorter screen edge.
 * [scale] null = full file resolution ([coil3.size.Size.ORIGINAL]).
 */
@Stable
enum class DecodeSizeType(
    override val prefValue: Int,
    override val stringRes: Int,
    override val icon: ImageVector,
    /** Multiplier of min(widthPixels, heightPixels); null = original. */
    val scale: Float?,
) : PreferenceType {
    SCALE_1_5(0, R.string.pref_decode_size_1_5x, Icons.Default.PhotoSizeSelectSmall, 1.5f),
    SCALE_2(1, R.string.pref_decode_size_2x, Icons.Default.PhotoSizeSelectLarge, 2f),
    SCALE_2_5(2, R.string.pref_decode_size_2_5x, Icons.Default.PhotoSizeSelectLarge, 2.5f),
    SCALE_3(3, R.string.pref_decode_size_3x, Icons.Default.HighQuality, 3f),
    ORIGIN(4, R.string.pref_decode_size_origin, Icons.Default.HighQuality, null),
    ;

    val isOriginal: Boolean get() = scale == null

    companion object {
        fun fromPreference(preference: Int): DecodeSizeType =
            entries.find { it.prefValue == preference } ?: SCALE_1_5
    }
}
