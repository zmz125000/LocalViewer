package com.ehviewer.core.util

import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast

// minSdk is 32 (Android 12L). Only gates that can still be false on supported devices remain.

@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
val isAtLeastT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@ChecksSdkIntAtLeast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
val isAtLeastU = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** Cronet system-library availability (S extension 7+). minSdk 32 always has S; extension may lag. */
@ChecksSdkIntAtLeast(api = 7, extension = Build.VERSION_CODES.S)
val isAtLeastSExtension7 =
    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
