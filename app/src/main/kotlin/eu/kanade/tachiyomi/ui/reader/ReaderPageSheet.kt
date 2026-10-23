package eu.kanade.tachiyomi.ui.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.image.hdr.supportsSource
import moe.tarsin.kt.andThen

@Composable
fun ReaderPageSheetMeta(
    retry: () -> Unit,
    retryOrigin: () -> Unit,
    share: () -> Unit,
    copy: () -> Unit,
    save: () -> Unit,
    saveTo: () -> Unit,
    convert: ((StillExportTarget) -> Unit)?,
    showAds: (() -> Unit)?,
    dismiss: () -> Unit,
    sourceName: String? = null,
) {
    var pickingConvert by remember { mutableStateOf(false) }

    @Composable
    fun Item(icon: ImageVector, @StringRes text: Int, onClick: () -> Unit, close: Boolean = true) = Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable(onClick = if (close) onClick andThen dismiss else onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.size(32.dp))
        Text(text = stringResource(id = text))
    }
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        if (pickingConvert && convert != null) {
            Item(icon = Icons.AutoMirrored.Filled.ArrowBack, text = R.string.convert_back, onClick = { pickingConvert = false }, close = false)
            Item(icon = Icons.Default.Save, text = R.string.convert_auto_original, onClick = { convert(StillExportTarget.AutoOriginal) })
            Item(icon = Icons.Default.Save, text = R.string.convert_jpeg, onClick = { convert(StillExportTarget.Jpeg) })
            Item(icon = Icons.Default.Save, text = R.string.convert_jpeg_hdr, onClick = { convert(StillExportTarget.JpegHdr) })
            if (StillExportTarget.JpegHdrBoost.supportsSource(sourceName)) {
                Item(icon = Icons.Default.Save, text = R.string.convert_jpeg_hdr_boost, onClick = { convert(StillExportTarget.JpegHdrBoost) })
                Item(icon = Icons.Default.Save, text = R.string.convert_jpeg_hdr_custom, onClick = { convert(StillExportTarget.JpegHdrCustom) })
            }
            if (StillExportTarget.Jxl.available) {
                Item(icon = Icons.Default.Save, text = R.string.convert_jxl, onClick = { convert(StillExportTarget.Jxl) })
                Item(icon = Icons.Default.Save, text = R.string.convert_jxl_hdr, onClick = { convert(StillExportTarget.JxlHdr) })
                if (StillExportTarget.JxlHdrBoost.supportsSource(sourceName)) {
                    Item(icon = Icons.Default.Save, text = R.string.convert_jxl_hdr_boost, onClick = { convert(StillExportTarget.JxlHdrBoost) })
                    Item(icon = Icons.Default.Save, text = R.string.convert_jxl_hdr_custom, onClick = { convert(StillExportTarget.JxlHdrCustom) })
                }
            }
        } else {
            showAds?.let { Item(icon = Icons.Default.Visibility, text = R.string.show_blocked_image, onClick = it) }
            Item(icon = Icons.Default.Refresh, text = R.string.refresh, onClick = retry)
            Item(icon = Icons.Default.Visibility, text = R.string.view_original, onClick = retryOrigin)
            Item(icon = Icons.Default.Share, text = R.string.action_share, onClick = share)
            Item(icon = Icons.Default.FileCopy, text = R.string.action_copy, onClick = copy)
            Item(icon = Icons.Default.Save, text = R.string.action_save, onClick = save)
            Item(icon = Icons.Default.Save, text = R.string.action_save_to, onClick = saveTo)
            if (convert != null) {
                Item(icon = Icons.Default.Save, text = R.string.convert_to, onClick = { pickingConvert = true }, close = false)
            }
        }
    }
}
