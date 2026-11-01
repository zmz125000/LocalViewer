package com.hippo.ehviewer.library

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Older builds scheduled a MediaStore content trigger under this component.
 * A trigger that is already pending can still start the process once. Cancel
 * it and do not reschedule: live updates run only while the library is resumed.
 */
class MediaStoreWatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MediaStoreWatch.cancelBackgroundTrigger(this)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
