package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.image.isAnimatedReaderExtension as imageIsAnimatedReaderExtension

internal fun isAnimatedReaderExtension(ext: String?): Boolean = imageIsAnimatedReaderExtension(ext)
