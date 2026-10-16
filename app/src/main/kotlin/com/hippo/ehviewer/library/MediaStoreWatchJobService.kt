package com.hippo.ehviewer.library

import android.app.job.JobParameters
import android.app.job.JobService
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes the process when MediaStore images or videos change and the app is
 * not running. Re-armed immediately so a change during this run is not lost.
 * Cannot be persisted or periodic — [MediaStoreWatch.start] schedules it again
 * on the next process start.
 */
class MediaStoreWatchJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val uris = params.triggeredContentUris?.toList().orEmpty()
        scope.launch {
            runCatching { MediaStoreWatch.handleTriggered(uris) }
                .onFailure { logcat(it) }
            // Re-arm after this run. Scheduling the same job id at the start
            // replaces and cancels the running job.
            MediaStoreWatch.scheduleJob(this@MediaStoreWatchJobService)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = Settings.libraryLiveUpdate.value
}
