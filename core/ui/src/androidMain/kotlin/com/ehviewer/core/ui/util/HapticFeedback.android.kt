package com.ehviewer.core.ui.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.ehviewer.core.util.isAtLeastU

@Composable
actual fun rememberHapticFeedback(): HapticFeedback {
    val view = LocalView.current
    return remember(view) { AndroidHapticFeedback(view) }
}

class AndroidHapticFeedback(private val view: View) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        // minSdk 32 includes R constants; U adds finer drag/segment feedback.
        val feedbackConstant = when (hapticFeedbackType) {
            HapticFeedbackType.START -> if (isAtLeastU) {
                HapticFeedbackConstants.DRAG_START
            } else {
                HapticFeedbackConstants.GESTURE_START
            }
            HapticFeedbackType.MOVE -> if (isAtLeastU) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            }
            HapticFeedbackType.END -> HapticFeedbackConstants.GESTURE_END
        }
        view.performHapticFeedback(feedbackConstant)
    }
}
