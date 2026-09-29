package com.hippo.ehviewer.image

/** Long edge used when [com.hippo.ehviewer.Settings.readerHiResOptimize] caps a decode. */
const val HI_RES_PREVIEW_EDGE = 4096

/**
 * [Normal] is the user's decode size with no further step.
 * [Preview] is a capped decode; pinch-zoom past [HI_RES_PREVIEW_EDGE] loads [Full].
 * [Full] is that second decode. Zooming back out replaces it with [Preview].
 */
enum class HiResTier {
    Normal,
    Preview,
    Full,
}
