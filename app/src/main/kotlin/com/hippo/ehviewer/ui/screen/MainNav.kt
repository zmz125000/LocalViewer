package com.hippo.ehviewer.ui.screen

import androidx.annotation.MainThread
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** EH URL deep links removed — always returns false. */
@MainThread
context(_: DestinationsNavigator)
fun navWithUrl(url: String): Boolean = false
