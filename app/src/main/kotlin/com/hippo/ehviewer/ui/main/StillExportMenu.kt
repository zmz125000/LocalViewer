package com.hippo.ehviewer.ui.main

import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.image.hdr.StillExportTarget

internal val StillExportTarget.conversionLabelRes: Int
    get() = when (this) {
        StillExportTarget.AutoOriginal -> R.string.convert_auto_original
        StillExportTarget.Jpeg -> R.string.convert_jpeg
        StillExportTarget.JpegHdr -> R.string.convert_jpeg_hdr
        StillExportTarget.Jxl -> R.string.convert_jxl
        StillExportTarget.JxlHdr -> R.string.convert_jxl_hdr
        StillExportTarget.JxlPq -> R.string.convert_jxl_pq
        StillExportTarget.Avif -> R.string.convert_avif
        StillExportTarget.AvifGainMap -> R.string.convert_avif_gain_map
        StillExportTarget.AvifPq -> R.string.convert_avif_pq
    }
