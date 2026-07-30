package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.openBrowser
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LicenseScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.license)) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val context = LocalContext.current
        val libraries by produceLibraries(com.hippo.ehviewer.R.raw.aboutlibraries)
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
            padding = LibraryDefaults.libraryPadding(licensePadding = LibraryDefaults.chipPadding(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp))),
            textStyles = LibraryDefaults.libraryTextStyles(licensesTextStyle = MaterialTheme.typography.labelSmall),
            onLibraryClick = { library ->
                library.website?.let { context.openBrowser(it) }
            },
        )
    }
}
