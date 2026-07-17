package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED

/** Synthetic token marking galleries that come from the local library. */
const val LOCAL_GALLERY_TOKEN = "local"

fun LocalGalleryEntity.toBaseGalleryInfo() = BaseGalleryInfo(
    gid = id,
    token = LOCAL_GALLERY_TOKEN,
    title = title,
    titleJpn = null,
    thumbKey = coverPath,
    category = if (kind == LOCAL_GALLERY_KIND_ARCHIVE) 1 else 0,
    posted = null,
    uploader = null,
    rating = -1f,
    simpleTags = null,
    pages = pageCount,
    simpleLanguage = null,
    favoriteSlot = NOT_FAVORITED,
)
