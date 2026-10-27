package com.hippo.ehviewer.ui.reader

import android.text.format.DateFormat
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.statusObserved
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
internal fun BoxScope.ReaderScreenWidget(page: Page?, slideshow: Boolean, sourceName: String?) {
    val position by (if (slideshow) Settings.slideshowScreenWidgetPosition else Settings.readerScreenWidgetPosition).collectAsState()
    if (position !in 1..5) return
    val showClock by (if (slideshow) Settings.slideshowShowClock else Settings.readerShowClock).collectAsState()
    val useExifDate by (if (slideshow) Settings.slideshowUseExifDate else Settings.readerUseExifDate).collectAsState()
    val showImageInfo by (if (slideshow) Settings.slideshowShowImageInfo else Settings.readerShowImageInfo).collectAsState()
    val showColorSpace by (if (slideshow) Settings.slideshowShowColorSpace else Settings.readerShowColorSpace).collectAsState()
    val showExifInfo by (if (slideshow) Settings.slideshowShowExifInfo else Settings.readerShowExifInfo).collectAsState()
    val showLensModel by (if (slideshow) Settings.slideshowShowLensModel else Settings.readerShowLensModel).collectAsState()
    if (!showClock && !showImageInfo && !showExifInfo && !showColorSpace) return
    val status = page?.statusObserved
    val metadata = (status as? PageStatus.Ready)?.image?.metadata
    val photoDate = if (useExifDate) metadata?.date?.let(::Date) else null
    val corner = remember(position, page, slideshow) { if (position == 5) Random.nextInt(1, 5) else position }
    val alignment = when (corner) {
        1 -> AbsoluteAlignment.TopLeft
        2 -> AbsoluteAlignment.TopRight
        3 -> AbsoluteAlignment.BottomLeft
        else -> AbsoluteAlignment.BottomRight
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale, photoDate != null) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (photoDate != null) "yEEE MMM d" else "EEE MMM d"), locale)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(lifecycleOwner, showClock, photoDate != null) {
        if (!showClock || photoDate != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = Date()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    val style = TextStyle(color = Color.White, shadow = Shadow(Color.Black, Offset(1f, 2f), 6f), textAlign = TextAlign.Center)
    Column(
        modifier = Modifier.align(alignment).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showClock) {
            val date = photoDate ?: now
            Text(DateFormat.getTimeFormat(context).format(date), style = style, fontSize = 52.sp, lineHeight = 60.sp)
            Text(dateFormat.format(date), style = style, fontSize = 20.sp, lineHeight = 26.sp)
        }
        if (showExifInfo && metadata != null) {
            metadata.cameraModel?.let { Text(it, style = style, fontSize = 16.sp, lineHeight = 22.sp) }
            if (showLensModel) {
                metadata.lensModel?.let { Text(it, style = style, fontSize = 16.sp, lineHeight = 22.sp) }
            }
        }
        if (showImageInfo && metadata != null) {
            val dimensions = if (metadata.width != null && metadata.height != null) "${metadata.width} × ${metadata.height}" else null
            val depth = metadata.bitDepth?.let { stringResource(R.string.slideshow_info_bit_depth, it) }
            val extension = sourceName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
            val imageInfo = listOfNotNull(dimensions, depth, extension).joinToString(" · ")
            if (imageInfo.isNotEmpty()) Text(imageInfo, style = style, fontSize = 16.sp, lineHeight = 22.sp)
        }
        if (showExifInfo && metadata != null) {
            val number = remember(locale) { DecimalFormat("0.####") }
            val camera = listOfNotNull(
                metadata.aperture?.let { "f/${number.format(it)}" },
                metadata.exposureSeconds?.let {
                    if (it < 0.5) "1/${number.format(ceil(1.0 / it))}" else number.format(it)
                },
                metadata.iso?.let { stringResource(R.string.slideshow_info_iso, it) },
                (metadata.focalLength35mm?.toDouble() ?: metadata.focalLength)?.let {
                    stringResource(R.string.slideshow_info_focal_length, number.format(it))
                },
            ).joinToString(" · ")
            if (camera.isNotEmpty()) Text(camera, style = style, fontSize = 16.sp, lineHeight = 22.sp)
        }
        if (showColorSpace && metadata != null) {
            val colorSpace = metadata.colorSpace?.trim()?.takeUnless { it.isEmpty() || it.equals("Unknown", ignoreCase = true) }?.let {
                when {
                    it.startsWith("sRGB IEC61966-2.1", ignoreCase = true) -> "sRGB"
                    it.startsWith("scRGB", ignoreCase = true) -> "scRGB"
                    else -> it
                }
            }
            val colorInfo = colorSpace?.let { space ->
                val dynamicRange = metadata.hdr?.let { if (it) "HDR" else "SDR" }
                listOfNotNull(space, dynamicRange).joinToString(" · ")
            }
            colorInfo?.let { Text(it, style = style, fontSize = 16.sp, lineHeight = 22.sp) }
        }
    }
}
