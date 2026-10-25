package eu.kanade.tachiyomi.ui.reader.setting

import com.ehviewer.core.i18n.R

/** Mutually exclusive playback behavior after the current slide/gallery. */
enum class SlideshowPlaybackMode(val prefValue: Int, val stringRes: Int) {
    PLAY_ONCE(0, R.string.pref_slideshow_play_once),
    SHUFFLE(1, R.string.pref_slideshow_shuffle),
    REPEAT(2, R.string.pref_slideshow_loop),
    NEXT_GALLERY(3, R.string.pref_slideshow_next_gallery),
    ;

    companion object {
        fun fromPreference(value: Int): SlideshowPlaybackMode = entries.find { it.prefValue == value } ?: REPEAT
    }
}
