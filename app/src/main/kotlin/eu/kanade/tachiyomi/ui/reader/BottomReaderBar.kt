package eu.kanade.tachiyomi.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import eu.kanade.tachiyomi.ui.reader.setting.AutoRotateMode
import eu.kanade.tachiyomi.ui.reader.setting.DecodeSizeType
import eu.kanade.tachiyomi.ui.reader.setting.LandscapeCoverCycle
import eu.kanade.tachiyomi.ui.reader.setting.PreferenceType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import eu.kanade.tachiyomi.ui.reader.setting.ScaleFitCycle

@Composable
fun BottomReaderBar(
    onClickSettings: () -> Unit,
    containerColor: Color,
    onClickPhotoGrid: (() -> Unit)? = null,
    showScaleFitCycle: Boolean = false,
    /** Single-page landscape with dual-page on: extra cover cycle. */
    showLandscapeCover: Boolean = false,
    /** PDF reader: chapter list instead of auto-rotate. */
    onClickContents: (() -> Unit)? = null,
) = FlexibleBottomAppBar(
    containerColor = containerColor,
    contentPadding = PaddingValues.Zero,
    horizontalArrangement = Arrangement.SpaceEvenly,
) {
    // 1. Reading mode
    val readingMode by Settings.readingMode.collectAsState { ReadingModeType.fromPreference(it) }
    DropdownIconButton(
        label = stringResource(R.string.viewer),
        menuItems = ReadingModeType.entries,
        selectedItem = readingMode,
        onSelectedItemChange = {
            Settings.readingMode.value = it.prefValue
        },
        minMenuWidth = 192.dp,
    )
    val slideshow = readingMode == ReadingModeType.SLIDESHOW
    val rotateOverride by Settings.slideshowRotateOverride.collectAsState()
    val scaleOverride by Settings.slideshowScaleOverride.collectAsState()
    val dualOverride by Settings.slideshowDualOverride.collectAsState()
    if (onClickContents != null) {
        ActionButton(
            onClick = onClickContents,
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = stringResource(R.string.pdf_reader_contents),
        )
    } else {
        val rotationPref = if (slideshow && rotateOverride) Settings.slideshowAutoRotateMode else Settings.autoRotateMode
        key(rotationPref) {
            val autoRotate by rotationPref.collectAsState { AutoRotateMode.fromPreference(it) }
            ActionButton(
                onClick = {
                    val modes = AutoRotateMode.entries
                    val next = modes[(modes.indexOf(autoRotate) + 1) % modes.size]
                    rotationPref.value = next.prefValue
                },
                imageVector = autoRotate.icon,
                contentDescription = stringResource(autoRotate.stringRes),
            )
        }
    }
    // 3. Fit width / height / screen — single page and dual no-gap
    if (showScaleFitCycle) {
        val scalePref = if (slideshow && scaleOverride) Settings.slideshowScaleType else Settings.imageScaleType
        key(scalePref) {
            val scaleFit by scalePref.collectAsState { ScaleFitCycle.fromPreference(it) }
            ActionButton(
                onClick = { scalePref.value = ScaleFitCycle.next(scaleFit.prefValue).prefValue },
                imageVector = scaleFit.icon,
                contentDescription = stringResource(scaleFit.stringRes),
            )
        }
    }
    // Dual pages: landscape only. Portrait keeps the preference but never shows two pages.
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        val dualPref = if (slideshow && dualOverride) Settings.slideshowDualPages else Settings.dualPageLandscape
        key(dualPref) {
            val dualPage by dualPref.collectAsState()
            ActionButton(
                onClick = { dualPref.value = !dualPage },
                imageVector = Icons.Outlined.ViewColumn,
                contentDescription = stringResource(R.string.pref_dual_page_landscape),
            )
        }
    }
    if (showLandscapeCover) {
        val cover by Settings.landscapeCover.collectAsState { LandscapeCoverCycle.fromPreference(it) }
        ActionButton(
            onClick = { Settings.landscapeCover.value = LandscapeCoverCycle.next(cover.prefValue).prefValue },
            imageVector = cover.icon,
            contentDescription = stringResource(R.string.pref_landscape_cover) + " " + stringResource(cover.stringRes),
        )
    }
    // 4. Photo grid (folder / ZIP / PDF) or decode size
    if (onClickPhotoGrid != null) {
        ActionButton(
            onClick = onClickPhotoGrid,
            imageVector = Icons.Default.GridView,
            contentDescription = stringResource(R.string.browse_menu_photo_grid),
        )
    } else {
        val hiResOptimize by Settings.readerHiResOptimize.collectAsState()
        val decodeSize by Settings.readerDecodeSize.collectAsState { DecodeSizeType.fromPreference(it) }
        if (!hiResOptimize) {
            DropdownIconButton(
                label = stringResource(R.string.pref_decode_size),
                menuItems = DecodeSizeType.entries,
                selectedItem = decodeSize,
                onSelectedItemChange = {
                    Settings.readerDecodeSize.value = it.prefValue
                },
                minMenuWidth = 160.dp,
            )
        }
    }
    // 5. Settings
    ActionButton(
        onClick = onClickSettings,
        imageVector = Icons.Outlined.Settings,
        contentDescription = stringResource(R.string.action_settings),
    )
}

@Composable
private fun DropdownIconButton(
    label: String,
    menuItems: List<PreferenceType>,
    selectedItem: PreferenceType,
    onSelectedItemChange: (PreferenceType) -> Unit,
    modifier: Modifier = Modifier,
    minMenuWidth: Dp = Dp.Unspecified,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        ActionButton(
            onClick = {},
            imageVector = selectedItem.icon,
            contentDescription = label,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = minMenuWidth),
            matchAnchorWidth = false,
        ) {
            menuItems.forEach {
                DropdownMenuItem(
                    text = { Text(stringResource(it.stringRes)) },
                    onClick = {
                        expanded = false
                        onSelectedItemChange(it)
                    },
                    leadingIcon = {
                        if (selectedItem == it) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)),
        shapes = IconButtonDefaults.shapes(IconButtonDefaults.mediumRoundShape, IconButtonDefaults.mediumPressedShape),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
        )
    }
}
