package com.orailnoor.droiddesk.runtime

/**
 * Process-wide state for the long Kali setup transaction.
 *
 * Leaving a Flutter route must not start a second apt/dpkg process when the
 * user returns to the installer screen. This coordinator lets the UI reattach
 * to the existing native operation while its worker thread continues.
 */
internal object KaliInstallCoordinator {
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var progress = 0.0
    @Volatile private var status = "Kali installation has not started"
    @Volatile private var succeeded = false

    fun tryStart(): Boolean = synchronized(lock) {
        if (running) return@synchronized false
        running = true
        progress = 0.0
        status = "Preparing Kali Linux..."
        succeeded = false
        true
    }

    fun report(value: Double, message: String) = synchronized(lock) {
        progress = if (value < 0.0) -1.0 else value.coerceIn(0.0, 1.0)
        status = message
    }

    fun finish(success: Boolean) = synchronized(lock) {
        running = false
        succeeded = success
        if (success) {
            progress = 1.0
            status = "Kali default tools and XFCE are installed"
        } else if (progress >= 0.0) {
            status = "Kali installation stopped. Reopening the installer can resume package configuration."
        }
    }

    fun snapshot(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "running" to running,
            "progress" to progress,
            "status" to status,
            "succeeded" to succeeded,
        )
    }
}
