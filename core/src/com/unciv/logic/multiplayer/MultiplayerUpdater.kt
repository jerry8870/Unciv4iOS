package com.unciv.logic.multiplayer

import kotlinx.coroutines.Job
import yairm210.purity.annotations.Readonly

/** Owns the single foreground multiplayer updater job. */
class MultiplayerUpdater(private val startUpdater: (forceUpdate: Boolean) -> Job) {
    private val lock = Any()
    private var job: Job? = null
    private var restartWhenCompleted = false

    /** Starts the updater unless one is already running. */
    fun resume() = synchronized(lock) {
        val currentJob = job
        when {
            currentJob == null -> startUpdaterLocked()
            currentJob.isActive -> Unit
            currentJob.isCompleted -> {
                job = null
                startUpdaterLocked()
            }
            else -> restartWhenCompleted = true
        }
    }

    /** Cancels the updater and any cancellable request running in its coroutine. */
    fun pause() = synchronized(lock) {
        restartWhenCompleted = false
        job?.cancel()
    }

    @Readonly
    fun isRunning(): Boolean = synchronized(lock) { job?.isActive == true }

    private fun startUpdaterLocked() {
        restartWhenCompleted = false
        val newJob = startUpdater(true)
        job = newJob
        newJob.invokeOnCompletion { updaterCompleted(newJob) }
    }

    private fun updaterCompleted(completedJob: Job) = synchronized(lock) {
        if (job !== completedJob) return@synchronized
        job = null
        if (restartWhenCompleted) startUpdaterLocked()
    }
}
