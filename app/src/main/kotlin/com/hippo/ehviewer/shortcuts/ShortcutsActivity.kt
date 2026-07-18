package com.hippo.ehviewer.shortcuts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hippo.ehviewer.ui.MainActivity

/** Legacy download shortcuts → open main activity (EH download service removed). */
class ShortcutsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finish()
    }
}
