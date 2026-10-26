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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
internal fun BoxScope.SlideshowClockOverlay(page: Int) {
    val position by Settings.slideshowClockPosition.collectAsState()
    if (position !in 1..5) return
    val corner = remember(position, page) { if (position == 5) Random.nextInt(1, 5) else position }
    val alignment = when (corner) {
        1 -> AbsoluteAlignment.TopLeft
        2 -> AbsoluteAlignment.TopRight
        3 -> AbsoluteAlignment.BottomLeft
        else -> AbsoluteAlignment.BottomRight
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEE MMM d"), locale) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(lifecycleOwner) {
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
        Text(DateFormat.getTimeFormat(context).format(now), style = style, fontSize = 52.sp, lineHeight = 60.sp)
        Text(dateFormat.format(now), style = style, fontSize = 20.sp, lineHeight = 26.sp)
    }
}
