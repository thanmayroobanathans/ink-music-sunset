package app.ink.music

import android.app.job.JobParameters
import android.app.job.JobService

/** The background's look for an update (the editions', 2026-11-21, [Updates.background]): every four hours, off the main thread. */
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            runCatching { Updates.background(applicationContext) }.onFailure { android.util.Log.w("ink-update", "background: $it") }
            jobFinished(p, false)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters) = true
}
