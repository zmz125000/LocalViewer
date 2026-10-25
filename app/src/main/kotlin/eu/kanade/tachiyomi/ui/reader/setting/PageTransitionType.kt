package eu.kanade.tachiyomi.ui.reader.setting

import com.ehviewer.core.i18n.R

/** Preference values stay stable when the menu order changes. */
enum class PageTransitionType(val prefValue: Int, val stringRes: Int) {
    DEFAULT(0, R.string.label_default),
    SLIDE(4, R.string.pref_reader_transition_slide),
    FADE(1, R.string.pref_reader_transition_fade),
    FADE_BLEND(3, R.string.pref_reader_transition_fade_blend),
    NONE(2, R.string.pref_reader_transition_none),
    ;

    val isFade: Boolean get() = this == FADE || this == FADE_BLEND
    fun needsPageReady(slideshow: Boolean): Boolean = slideshow || isFade

    companion object {
        fun fromPreference(value: Int): PageTransitionType = entries.find { it.prefValue == value } ?: DEFAULT

        fun resolve(general: Int, override: Int, slideshow: Boolean): PageTransitionType = entries.find { slideshow && it.prefValue == override } ?: fromPreference(general)
    }
}
