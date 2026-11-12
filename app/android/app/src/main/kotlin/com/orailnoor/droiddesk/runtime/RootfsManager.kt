package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Downloads, verifies, extracts and validates ARM64 Linux root filesystems.
 *
 * Root filesystems are stored under the app's private files directory. Downloads
 * are SHA-256 pinned, HTTPS-only (including redirects), resumable, and bounded.
 * Extraction is staged separately; the current rootfs is replaced only after
 * the new tree passes validation and configuration.
 *
 * Desktop package installation belongs to ChrootRuntime. The legacy installer
 * overload accepting LinuxRuntime is retained for source compatibility, but
 * refuses to run commands in the wrong runtime.
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_ARCHIVE_ENTRIES = 1_000_000
        private const val MAX_EXPANDED_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
        private const val NETWORK_CONNECT_TIMEOUT_MS = 30_000
        private const val NETWORK_READ_TIMEOUT_MS = 60_000
        private const val ARCHIVE_PROCESS_TIMEOUT_MINUTES = 90L
        private const val MAX_LOG_TAIL_BYTES = 8 * 1024L
        private const val ROOTFS_SIZE_CACHE_TTL_MS = 5 * 60 * 1000L

        /** Official ARM64 rootfs archives. */
        val DISTRO_URLS: Map<String, String> = mapOf(
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz",
            "kali" to "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
        )

        /** SHA-256 values published by the respective distribution sources. */
        val DISTRO_SHA256: Map<String, String> = mapOf(
            "ubuntu" to "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            "alpine" to "83a79199bf3112c65e62ddf1732b051daf62ed2ddf5a8413c440dc25956116f0",
            "kali" to "96aeced81a857a987715a14417af05c2934e3e19052d6151c398a1c215448b60",
        )

        val DISTRO_NAMES: Map<String, String> = mapOf(
            "ubuntu" to "Ubuntu 24.04 Base",
            "alpine" to "Alpine Linux 3.20",
            "kali" to "Kali NetHunter Minimal",
        )

        private val operationLock = ReentrantLock()
        private val supportedDesktopEnvironments = setOf("xfce4", "lxqt", "mate", "kde")
    }

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val stagingRootfsDir: File get() = File(baseDir, "rootfs.staging")
    private val backupRootfsDir: File get() = File(baseDir, "rootfs.previous")
    private val downloadDir: File get() = File(baseDir, "downloads")
    private val configFile: File get() = File(baseDir, "distro.conf")
    private val deConfigFile: File get() = File(baseDir, "de.conf")
    private val setupMarker: File get() = File(baseDir, "SETUP_COMPLETE")

    private val rootfsSizeCacheFile: File get() = File(baseDir, ".rootfs-size-cache")
    @Volatile private var cachedRootfsSizeMB: Long? = null
    @Volatile private var cachedRootfsSizeAt: Long = 0L
    @Volatile private var rootfsSizeCacheLoaded = false
    private val rootfsSizeRefreshActive = AtomicBoolean(false)

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    fun getInstalledDistro(): String {
        val configured = readPrivateText(configFile)
        if (configured in DISTRO_URLS.keys) return configured

        val markerValue = readPrivateText(setupMarker)
        return markerValue.takeIf { it in DISTRO_URLS.keys }.orEmpty()
    }

    fun getInstalledDE(): String {
        return readPrivateText(deConfigFile)
            .takeIf { it in supportedDesktopEnvironments }
            .orEmpty()
    }

    fun getMissingPackages(): List<String> {
        if (!isRootfsReady()) return listOf("rootfs")

        val de = getInstalledDE().ifEmpty { "xfce4" }
        val deBinary = when (de) {
            "lxqt" -> "usr/bin/lxqt-session"
            "mate" -> "usr/bin/mate-session"
            "kde" -> "usr/bin/startplasma-x11"
            else -> "usr/bin/xfce4-session"
        }

        return if (File(rootfsDir, deBinary).canExecute()) emptyList() else listOf(de)
    }

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getRootfsSizeMB(): Long {
        val cached = loadRootfsSizeCache()
        val stale = System.currentTimeMillis() - cachedRootfsSizeAt > ROOTFS_SIZE_CACHE_TTL_MS
        if (cached == null || stale) refreshRootfsSizeInBackground()
        return cached ?: 0L
    }

    private fun loadRootfsSizeCache(): Long? {
        if (rootfsSizeCacheLoaded) return cachedRootfsSizeMB
        synchronized(this) {
            if (rootfsSizeCacheLoaded) return cachedRootfsSizeMB
            rootfsSizeCacheLoaded = true
            runCatching {
                val lines = rootfsSizeCacheFile.readLines()
                if (lines.size >= 2) {
                    val timestamp = lines[0].toLongOrNull() ?: 0L
                    val size = lines[1].toLongOrNull()
                    if (size != null && size >= 0L && timestamp > 0L) {
                        cachedRootfsSizeAt = timestamp
                        cachedRootfsSizeMB = size
                    }
                }
            }.onFailure { Log.d(TAG, "Rootfs size cache unavailable: ${it.message}") }
            return cachedRootfsSizeMB
        }
    }

    private fun refreshRootfsSizeInBackground() {
        if (!rootfsSizeRefreshActive.compareAndSet(false, true)) return
        thread(name = "rootfs-size-cache", isDaemon = true) {
            try {
                if (!rootfsDir.isDirectory || Files.isSymbolicLink(rootfsDir.toPath())) return@thread
                val sizeMb = calculateDirSize(rootfsDir) / (1024L * 1024L)
                val timestamp = System.currentTimeMillis()
                cachedRootfsSizeMB = sizeMb
                cachedRootfsSizeAt = timestamp
                runCatching {
                    rootfsSizeCacheFile.writeText(timestamp.toString() + "\n" + sizeMb.toString() + "\n")
                }.onFailure { Log.w(TAG, "Could not persist rootfs size cache", it) }
            } catch (error: Throwable) {
                Log.w(TAG, "Background rootfs size calculation failed", error)
            } finally {
                rootfsSizeRefreshActive.set(false)
            }
        }
    }

    private fun invalidateRootfsSizeCache() {
        cachedRootfsSizeMB = null
        cachedRootfsSizeAt = 0L
        rootfsSizeCacheLoaded = true
        runCatching { Files.deleteIfExists(rootfsSizeCacheFile.toPath()) }
    }

    /**
     * A rootfs is ready only when its completion marker matches the selected
     * distribution and its critical operating-system files are present.
     * The old marker value "done" is accepted for existing installations.
     */
    fun isRootfsReady(): Boolean {
        return runCatching {
            val distro = getInstalledDistro()
            if (distro !in DISTRO_URLS.keys) return@runCatching false
            if (!rootfsDir.isDirectory || Files.isSymbolicLink(rootfsDir.toPath())) {
                return@runCatching false
            }

            val marker = readPrivateText(setupMarker)
            if (marker != distro && marker != "done") return@runCatching false

            validateRootfsTree(rootfsDir, distro)
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    /** Downloads and verifies a distribution archive on a worker thread. */
    fun downloadRootfs(
        distro: String,
        onProgress: (progress: Double, status: String) -> Unit,
    ) {
        thread(name = "rootfs-download") {
            if (!operationLock.tryLock()) {
                report(onProgress, -1.0, "Another rootfs operation is already running")
                return@thread
            }

            try {
                val url = DISTRO_URLS[distro]
                    ?: throw IllegalArgumentException("Unknown or unsupported distribution")
                val expectedSha256 = DISTRO_SHA256[distro]
                    ?: throw IllegalStateException("SHA-256 is not configured for $distro")
                val archive = archiveFile(distro)

                ensureDownloadDirectory()
                ensureManagedFile(archive, downloadDir)

                report(onProgress, 0.0, "Preparing ${DISTRO_NAMES[distro]} download...")

                // If an older installation used the legacy "done" marker, bind
                // that marker to its current distro before changing distro.conf.
                preserveLegacyRootfsMarker()

                if (archive.isFile && matchesSha256(archive, expectedSha256)) {
                    writeAtomically(configFile, distro)
                    report(onProgress, 1.0, "${DISTRO_NAMES[distro]} is already downloaded and verified")
                    return@thread
                }

                var downloadedBytes = if (archive.isFile) archive.length() else 0L
                if (downloadedBytes > MAX_DOWNLOAD_BYTES) {
                    deletePath(archive)
                    downloadedBytes = 0L
                }

                report(onProgress, 0.02, "Connecting securely to download server...")
                val connection = openHttpsConnection(url, downloadedBytes)

                try {
                    val responseCode = connection.responseCode

                    if (responseCode == 416) {
                        connection.disconnect()
                        if (archive.isFile && matchesSha256(archive, expectedSha256)) {
                            writeAtomically(configFile, distro)
                            report(onProgress, 1.0, "${DISTRO_NAMES[distro]} is already downloaded and verified")
                            return@thread
                        }
                        deletePath(archive)
                        throw IllegalStateException("Server rejected the resume request; the partial archive was removed")
                    }

                    if (responseCode == HttpURLConnection.HTTP_OK && downloadedBytes > 0L) {
                        if (!archive.delete()) {
                            throw IllegalStateException("Could not restart the partial download")
                        }
                        downloadedBytes = 0L
                    }

                    downloadFromConnection(
                        connection = connection,
                        targetFile = archive,
                        distroName = DISTRO_NAMES[distro] ?: distro,
                        initialDownloadedBytes = downloadedBytes,
                        onProgress = onProgress,
                    )
                } finally {
                    connection.disconnect()
                }

                verifySha256(archive, expectedSha256)
                preserveLegacyRootfsMarker()
                writeAtomically(configFile, distro)

                report(onProgress, 1.0, "${DISTRO_NAMES[distro]} downloaded and SHA-256 verified")
                Log.i(TAG, "Verified rootfs download for $distro (${archive.length()} bytes)")
            } catch (error: Exception) {
                Log.e(TAG, "Rootfs download failed: ${error.message}", error)
                report(onProgress, -1.0, "Download failed: ${error.message ?: "unknown error"}")
            } finally {
                operationLock.unlock()
            }
        }
    }

    /** Open an HTTPS connection while validating every redirect hop. */
    private fun openHttpsConnection(rawUrl: String, rangeStart: Long): HttpURLConnection {
        require(rangeStart >= 0L) { "Invalid resume offset" }
        var currentUrl = URL(rawUrl)

        for (redirectCount in 0..5) {
            validateHttpsUrl(currentUrl)

            val connection = currentUrl.openConnection() as? HttpURLConnection
                ?: throw SecurityException("HTTP connection could not be created")

            if (connection !is javax.net.ssl.HttpsURLConnection) {
                connection.disconnect()
                throw SecurityException("HTTPS is required for rootfs downloads")
            }

            connection.instanceFollowRedirects = false
            connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            connection.readTimeout = NETWORK_READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "DroidDesk-RootfsManager")
            connection.setRequestProperty("Connection", "close")

            if (rangeStart > 0L) {
                connection.setRequestProperty("Range", "bytes=$rangeStart-")
            }

            val responseCode = try {
                connection.responseCode
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }

            if (responseCode in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()

                if (location.isNullOrBlank()) {
                    throw SecurityException("Download redirect did not include a location")
                }
                if (redirectCount == 5) {
                    throw SecurityException("Too many download redirects")
                }

                currentUrl = URL(currentUrl, location)
                validateHttpsUrl(currentUrl)
                continue
            }

            return connection
        }

        throw IllegalStateException("Download redirect limit exceeded")
    }

    private fun validateHttpsUrl(url: URL) {
        if (
            !url.protocol.equals("https", ignoreCase = true) ||
            url.host.isBlank() ||
            url.userInfo != null ||
            (url.port != -1 && url.port != 443)
        ) {
            throw SecurityException("Blocked unsafe rootfs download URL")
        }
    }

    private fun downloadFromConnection(
        connection: HttpURLConnection,
        targetFile: File,
        distroName: String,
        initialDownloadedBytes: Long,
        onProgress: (Double, String) -> Unit,
    ) {
        try {
            if (initialDownloadedBytes < 0L || initialDownloadedBytes > MAX_DOWNLOAD_BYTES) {
                deletePath(targetFile)
                throw IllegalStateException("Invalid initial download size")
            }

            val responseCode = connection.responseCode
            if (initialDownloadedBytes > 0L && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Server did not honor the download resume request (HTTP $responseCode)")
            }
            if (initialDownloadedBytes == 0L && responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Unexpected download HTTP status: $responseCode")
            }

            val contentEncoding = connection.getHeaderField("Content-Encoding")
            if (!contentEncoding.isNullOrBlank() && !contentEncoding.equals("identity", ignoreCase = true)) {
                deletePath(targetFile)
                throw SecurityException("Unexpected encoded response; refusing ambiguous archive length")
            }

            val responseLength = connection.contentLengthLong
            var expectedResponseBytes: Long? = null
            var expectedTotalBytes: Long? = null

            if (initialDownloadedBytes > 0L) {
                val match = Regex(
                    "^bytes ([0-9]+)-([0-9]+)/([0-9]+)$",
                    RegexOption.IGNORE_CASE,
                ).matchEntire(connection.getHeaderField("Content-Range")?.trim().orEmpty())
                    ?: run {
                        deletePath(targetFile)
                        throw IllegalStateException("Missing or invalid Content-Range")
                    }

                val rangeStart = match.groupValues[1].toLongOrNull()
                val rangeEnd = match.groupValues[2].toLongOrNull()
                val rangeTotal = match.groupValues[3].toLongOrNull()

                if (
                    rangeStart == null || rangeEnd == null || rangeTotal == null ||
                    rangeStart != initialDownloadedBytes ||
                    rangeEnd < rangeStart ||
                    rangeTotal <= rangeEnd ||
                    rangeEnd != rangeTotal - 1L
                ) {
                    deletePath(targetFile)
                    throw IllegalStateException("Invalid Content-Range values")
                }

                expectedResponseBytes = rangeEnd - rangeStart + 1L
                expectedTotalBytes = rangeTotal

                if (responseLength >= 0L && responseLength != expectedResponseBytes) {
                    deletePath(targetFile)
                    throw IllegalStateException("Content-Length does not match Content-Range")
                }
            } else {
                if (responseLength == 0L) {
                    deletePath(targetFile)
                    throw IllegalStateException("Server returned an empty archive")
                }
                if (responseLength > 0L) {
                    expectedResponseBytes = responseLength
                    expectedTotalBytes = responseLength
                }
            }

            if (
                responseLength > MAX_DOWNLOAD_BYTES ||
                (expectedTotalBytes != null && expectedTotalBytes > MAX_DOWNLOAD_BYTES) ||
                initialDownloadedBytes > MAX_DOWNLOAD_BYTES - (expectedResponseBytes ?: 0L)
            ) {
                deletePath(targetFile)
                throw IllegalStateException("Rootfs archive exceeds the configured download size limit")
            }

            var downloadedBytes = initialDownloadedBytes
            var receivedBytes = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            connection.inputStream.use { input ->
                FileOutputStream(targetFile, initialDownloadedBytes > 0L).use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) continue

                        val expected = expectedResponseBytes
                        if (
                            downloadedBytes > MAX_DOWNLOAD_BYTES - bytesRead ||
                            (expected != null && receivedBytes > expected - bytesRead)
                        ) {
                            deletePath(targetFile)
                            throw IllegalStateException("Server sent more data than expected")
                        }

                        output.write(buffer, 0, bytesRead)
                        receivedBytes += bytesRead
                        downloadedBytes += bytesRead

                        val total = expectedTotalBytes
                        if (total != null && total > 0L) {
                            val progress = (downloadedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 0.99)
                            val downloadedMb = downloadedBytes / (1024L * 1024L)
                            val totalMb = total / (1024L * 1024L)
                            report(
                                onProgress,
                                progress,
                                "Downloading $distroName: ${downloadedMb} MB / ${totalMb} MB",
                            )
                        } else {
                            report(onProgress, 0.5, "Downloading $distroName: ${downloadedBytes / (1024L * 1024L)} MB")
                        }
                    }
                }
            }

            if (expectedResponseBytes != null && receivedBytes != expectedResponseBytes) {
                deletePath(targetFile)
                throw IllegalStateException("Downloaded byte count does not match the server response")
            }
            if (expectedTotalBytes != null && downloadedBytes != expectedTotalBytes) {
                deletePath(targetFile)
                throw IllegalStateException("Total downloaded size does not match Content-Range")
            }
            if (!targetFile.isFile || targetFile.length() <= 0L) {
                deletePath(targetFile)
                throw IllegalStateException("Downloaded archive is empty")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha256(file: File, expectedSha256: String) {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            throw IllegalStateException("Rootfs archive is missing or is not a regular file")
        }
        if (!expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            throw IllegalArgumentException("Configured SHA-256 is invalid")
        }

        val actual = sha256(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            throw SecurityException("Rootfs SHA-256 verification failed; the archive was removed")
        }
        Log.i(TAG, "Rootfs SHA-256 verification passed")
    }

    private fun matchesSha256(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return false
        return runCatching { sha256(file).equals(expectedSha256, ignoreCase = true) }
            .getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // -------------------------------------------------------------------------
    // Extraction
    // -------------------------------------------------------------------------

    /** Verifies and extracts the selected archive, preserving the old rootfs on failure. */
    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        thread(name = "rootfs-extract") {
            if (!operationLock.tryLock()) {
                report(onProgress, -1.0, "Another rootfs operation is already running")
                return@thread
            }

            var extractionSucceeded = false
            var sanitizedArchive: File? = null
            try {
                val distro = getInstalledDistro()
                if (distro !in DISTRO_URLS.keys) {
                    throw IllegalStateException("No supported distribution is selected")
                }

                val expectedSha256 = DISTRO_SHA256[distro]
                    ?: throw IllegalStateException("SHA-256 is not configured for $distro")
                val archive = archiveFile(distro)

                ensureDownloadDirectory()
                ensureManagedFile(archive, downloadDir)
                if (!archive.isFile || archive.length() <= 0L || archive.length() > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException("Rootfs archive is missing, empty, or too large. Download it again")
                }

                report(onProgress, 0.05, "Verifying rootfs archive...")
                verifySha256(archive, expectedSha256)

                ensureSafeManagedDirectory(stagingRootfsDir)
                ensureSafeManagedDirectory(backupRootfsDir)
                deletePath(stagingRootfsDir)
                if (!stagingRootfsDir.mkdirs()) {
                    throw IllegalStateException("Could not create temporary extraction directory")
                }

                val extractionLog = File(context.cacheDir, "rootfs-extract.log")
                val outputArchive = File(context.cacheDir, "rootfs-${distro}-sanitized.tar")
                sanitizedArchive = outputArchive
                val xzArchive = archive.extension.equals("xz", ignoreCase = true)

                report(onProgress, 0.12, "Validating and normalizing archive paths...")
                val sanitized = RootfsArchiveSanitizer.sanitize(
                    sourceArchive = archive,
                    targetTar = outputArchive,
                    xzCompressed = xzArchive,
                    stripTopLevelDirectory = if (distro == "kali") "kali-arm64" else null,
                    maxEntries = MAX_ARCHIVE_ENTRIES,
                    maxExpandedBytes = MAX_EXPANDED_ARCHIVE_BYTES,
                    bufferSize = BUFFER_SIZE,
                )
                Log.i(
                    TAG,
                    "Sanitized $distro archive: entries=${sanitized.entries}, files=${sanitized.regularFiles}, " +
                        "directories=${sanitized.directories}, links=${sanitized.links}, " +
                        "specialSkipped=${sanitized.skippedSpecialFiles}, expanded=${sanitized.expandedBytes}",
                )

                report(onProgress, 0.22, "Extracting the verified root filesystem...")
                val extractExit = runLoggedProcess(
                    listOf(
                        "tar", "-x", "-f", outputArchive.absolutePath,
                        "-C", stagingRootfsDir.absolutePath, "--no-same-owner",
                    ),
                    extractionLog,
                    ARCHIVE_PROCESS_TIMEOUT_MINUTES,
                )
                if (extractExit != 0) {
                    throw IllegalStateException("tar extraction failed (exit $extractExit): ${readLogTail(extractionLog)}")
                }

                report(onProgress, 0.78, "Checking extracted Linux system...")
                if (!validateRootfsTree(stagingRootfsDir, distro)) {
                    throw IllegalStateException("Extracted archive is incomplete or is not a valid $distro rootfs")
                }

                report(onProgress, 0.84, "Configuring Linux environment...")
                configureRootfs(stagingRootfsDir, distro)

                if (!validateRootfsTree(stagingRootfsDir, distro)) {
                    throw IllegalStateException("Rootfs validation failed after configuration")
                }

                report(onProgress, 0.92, "Activating the verified rootfs...")
                replaceRootfsAtomically(distro)
                extractionSucceeded = true

                if (!archive.delete()) {
                    Log.w(TAG, "Verified rootfs archive could not be removed after extraction")
                }

                extractionLog.delete()
                report(onProgress, 1.0, "${DISTRO_NAMES[distro]} setup complete")
                Log.i(TAG, "Rootfs extraction completed for $distro (${getRootfsSizeMB()} MB)")
            } catch (error: Exception) {
                Log.e(TAG, "Rootfs extraction failed: ${error.message}", error)
                report(onProgress, -1.0, "Extraction failed: ${error.message ?: "unknown error"}")
            } finally {
                sanitizedArchive?.let { temporaryArchive ->
                    runCatching { deletePath(temporaryArchive) }
                        .onFailure { Log.w(TAG, "Could not remove sanitized rootfs archive", it) }
                }
                if (!extractionSucceeded) {
                    runCatching { deletePath(stagingRootfsDir) }
                }
                operationLock.unlock()
            }
        }
    }

    private fun runLoggedProcess(command: List<String>, outputFile: File, timeoutMinutes: Long): Int {
        outputFile.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create process log directory")
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw IllegalStateException("Could not clear previous process log")
        }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .start()

        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            throw IllegalStateException("Archive process timed out")
        }
        return process.exitValue()
    }

    private fun readLogTail(file: File): String {
        if (!file.isFile) return "no diagnostic output"
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val start = (input.length() - MAX_LOG_TAIL_BYTES).coerceAtLeast(0L)
                input.seek(start)
                val bytes = ByteArray((input.length() - start).toInt())
                input.readFully(bytes)
                String(bytes, Charsets.UTF_8).trim().takeLast(MAX_LOG_TAIL_BYTES.toInt())
            }
        }.getOrDefault("could not read process log")
    }

    private fun validateRootfsTree(root: File, distro: String): Boolean {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return false

        val etc = File(root, "etc")
        val usr = File(root, "usr")
        if (!etc.isDirectory || !usr.isDirectory) return false

        val osRelease = File(etc, "os-release").isFile || File(usr, "lib/os-release").isFile
        if (!osRelease) return false

        val shellExists = if (distro == "alpine") {
            File(root, "bin/sh").isFile
        } else {
            File(root, "bin/bash").isFile || File(root, "usr/bin/bash").isFile
        }
        if (!shellExists) return false

        val packageManagerExists = when (distro) {
            "alpine" -> File(root, "sbin/apk").isFile || File(root, "usr/sbin/apk").isFile
            "ubuntu", "kali" -> File(root, "usr/bin/dpkg").isFile || File(root, "bin/dpkg").isFile
            else -> false
        }
        return packageManagerExists
    }

    /** Replace an existing rootfs only after the staged one passes all checks. */
    private fun replaceRootfsAtomically(distro: String) {
        val oldMarker = readPrivateText(setupMarker)
        var oldRootfsMoved = false
        var newRootfsMoved = false

        ensureSafeManagedDirectory(rootfsDir)
        ensureSafeManagedDirectory(backupRootfsDir)
        deletePath(backupRootfsDir)

        try {
            if (rootfsDir.exists()) {
                movePath(rootfsDir, backupRootfsDir)
                oldRootfsMoved = true
            }

            movePath(stagingRootfsDir, rootfsDir)
            newRootfsMoved = true

            writeAtomically(setupMarker, distro)
            invalidateRootfsSizeCache()

            if (oldRootfsMoved) {
                runCatching { deletePath(backupRootfsDir) }
                    .onFailure { Log.w(TAG, "Old rootfs backup could not be removed", it) }
            }
        } catch (error: Exception) {
            if (newRootfsMoved) runCatching { deletePath(rootfsDir) }
            if (oldRootfsMoved && backupRootfsDir.exists()) {
                runCatching { movePath(backupRootfsDir, rootfsDir) }
                    .onFailure { Log.e(TAG, "Could not restore previous rootfs", it) }
            }
            runCatching {
                if (oldMarker.isNotEmpty()) writeAtomically(setupMarker, oldMarker)
                else deletePath(setupMarker)
            }
            throw error
        }
    }

    private fun movePath(source: File, destination: File) {
        if (!source.exists()) throw IllegalStateException("Move source does not exist")
        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
            throw IllegalStateException("Move destination already exists")
        }
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(destination)) {
                throw IllegalStateException("Could not move ${source.name} into place")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rootfs configuration
    // -------------------------------------------------------------------------

    private fun configureRootfs(root: File, distro: String) {
        ensureRootfsDirectory(root, "etc")
        ensureRootfsDirectory(root, "etc/profile.d")
        ensureRootfsDirectory(root, "tmp")
        ensureRootfsDirectory(root, "tmp/runtime-root")
        ensureRootfsDirectory(root, "run")
        ensureRootfsDirectory(root, "dev/shm")

        writeRootfsFile(
            root,
            "etc/resolv.conf",
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n",
            replaceSymlink = true,
        )
        writeRootfsFile(root, "etc/hostname", "droiddesk\n", replaceSymlink = true)
        writeRootfsFile(
            root,
            "etc/hosts",
            "127.0.0.1 localhost\n127.0.0.1 droiddesk\n::1 localhost ip6-localhost ip6-loopback\n",
            replaceSymlink = true,
        )

        // Alpine uses apk and has no APT configuration directory.
        if (distro != "alpine") {
            ensureRootfsDirectory(root, "etc/apt/apt.conf.d")
            writeRootfsFile(
                root,
                "etc/apt/apt.conf.d/99-disable-sandbox",
                "APT::Sandbox::User \"root\";\n",
                replaceSymlink = true,
            )
        }

        // Keep this profile portable; graphics drivers are selected by the
        // active runtime, not forced globally during rootfs extraction.
        val profile = """
            #!/bin/sh
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export PULSE_SERVER="${'$'}{PULSE_SERVER:-127.0.0.1}"
            export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-root}"
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export LANG="${'$'}{LANG:-C.UTF-8}"
        """.trimIndent() + "\n"
        writeRootfsFile(root, "etc/profile.d/droiddesk.sh", profile, replaceSymlink = true)

        Log.i(TAG, "Rootfs configuration completed for $distro")
    }

    private fun ensureRootfsDirectory(root: File, relativePath: String) {
        val directory = File(root, relativePath)
        val parent = directory.parentFile
            ?: throw IllegalStateException("Rootfs directory has no parent: $relativePath")

        // Check the parent before mkdirs(), because mkdirs() follows symlinked
        // parents. A standard rootfs may contain links such as /var/run -> /run;
        // for the small set of directories DroidDesk manages, replace the link
        // itself with a real directory instead of following its target.
        if (!isInsideRoot(root, parent.canonicalFile)) {
            throw SecurityException("Rootfs parent escapes extraction root: $relativePath")
        }
        if (Files.isSymbolicLink(directory.toPath())) {
            Files.delete(directory.toPath())
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create rootfs directory: $relativePath")
        }
        if (!directory.isDirectory || !isInsideRoot(root, directory.canonicalFile)) {
            throw SecurityException("Unsafe rootfs directory: $relativePath")
        }
    }

    private fun writeRootfsFile(
        root: File,
        relativePath: String,
        contents: String,
        replaceSymlink: Boolean = false,
    ) {
        val file = File(root, relativePath)
        val parent = file.parentFile ?: throw IllegalStateException("Rootfs file has no parent")
        ensureRootfsDirectory(root, parent.relativeTo(root).invariantSeparatorsPath)

        if (Files.isSymbolicLink(file.toPath())) {
            if (!replaceSymlink) throw SecurityException("Refusing to write through a rootfs symlink: $relativePath")
            Files.delete(file.toPath())
        }
        if (file.isDirectory) throw IllegalStateException("Rootfs file path is a directory: $relativePath")
        if (!isInsideRoot(root, file.canonicalFile)) {
            throw SecurityException("Rootfs file path escapes extraction root: $relativePath")
        }

        file.writeText(contents, Charsets.UTF_8)
    }

    private fun isInsideRoot(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    // -------------------------------------------------------------------------
    // Legacy desktop installer compatibility
    // -------------------------------------------------------------------------

    /**
     * This old overload received LinuxRuntime, which executes in the native
     * Termux prefix, not inside this rootfs. Running apt commands through it
     * could modify the wrong environment. Keep the signature for compatibility
     * and direct callers to ChrootRuntime, which owns the actual chroot session.
     */
    @Deprecated("Use ChrootRuntime.installDesktopEnvironment for a rooted rootfs")
    fun installDesktopEnvironment(
        @Suppress("UNUSED_PARAMETER") de: String,
        @Suppress("UNUSED_PARAMETER") runtime: LinuxRuntime,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ) {
        thread(name = "rootfs-de-install-unsupported") {
            val message = "Desktop installation must be run through ChrootRuntime, not the native LinuxRuntime"
            Log.w(TAG, message)
            runCatching { onLog(message) }
            report(onProgress, -1.0, message)
        }
    }

    // -------------------------------------------------------------------------
    // Filesystem and metadata helpers
    // -------------------------------------------------------------------------

    private fun archiveFile(distro: String): File {
        val extension = if (distro == "kali") "xz" else "gz"
        return File(downloadDir, "$distro-rootfs.tar.$extension")
    }

    private fun ensureDownloadDirectory() {
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw IllegalStateException("Could not create rootfs download directory")
        }
        if (!downloadDir.isDirectory || Files.isSymbolicLink(downloadDir.toPath())) {
            throw SecurityException("Rootfs download path is not a private directory")
        }
        if (downloadDir.canonicalFile.parentFile != baseDir.canonicalFile) {
            throw SecurityException("Rootfs download directory escaped app storage")
        }
    }

    private fun ensureManagedFile(file: File, parent: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw SecurityException("Refusing a symbolic link in the rootfs download path")
        }
        if (file.exists() && !file.isFile) {
            throw SecurityException("Rootfs download target is not a regular file")
        }
        if (file.canonicalFile.parentFile != parent.canonicalFile) {
            throw SecurityException("Rootfs download target escaped its directory")
        }
    }

    private fun ensureSafeManagedDirectory(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw SecurityException("Refusing a symbolic link for ${file.name}")
        }
        if (file.exists() && !file.isDirectory) {
            throw SecurityException("Managed rootfs path is not a directory: ${file.name}")
        }
        if (file.canonicalFile.parentFile != baseDir.canonicalFile) {
            throw SecurityException("Managed rootfs directory escaped app storage")
        }
    }

    private fun preserveLegacyRootfsMarker() {
        if (readPrivateText(setupMarker) != "done") return
        val previousDistro = readPrivateText(configFile)
        if (previousDistro in DISTRO_URLS.keys) {
            writeAtomically(setupMarker, previousDistro)
        }
    }

    private fun readPrivateText(file: File): String {
        return runCatching {
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@runCatching ""
            if (file.canonicalFile.parentFile != baseDir.canonicalFile) return@runCatching ""
            file.readText(Charsets.UTF_8).trim()
        }.getOrDefault("")
    }

    private fun writeAtomically(file: File, contents: String) {
        val parent = file.parentFile ?: throw IllegalStateException("Metadata file has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Could not create metadata directory")
        }
        if (parent.canonicalFile != baseDir.canonicalFile) {
            throw SecurityException("Metadata path is outside app storage")
        }

        val temporary = File(parent, ".${file.name}.tmp-${System.currentTimeMillis()}-${Thread.currentThread().hashCode()}")
        if (temporary.exists()) deletePath(temporary)
        FileOutputStream(temporary).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }

        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deletePath(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath())
            return
        }
        if (file.exists() && !file.deleteRecursively()) {
            throw IllegalStateException("Could not remove ${file.name}")
        }
    }

    private fun calculateDirSize(directory: File): Long {
        if (!directory.isDirectory) return 0L
        var total = 0L
        directory.walkTopDown().forEach { entry ->
            if (entry.isFile && !Files.isSymbolicLink(entry.toPath())) {
                val length = entry.length()
                if (Long.MAX_VALUE - total < length) return Long.MAX_VALUE
                total += length
            }
        }
        return total
    }

    private fun report(
        callback: (Double, String) -> Unit,
        progress: Double,
        message: String,
    ) {
        runCatching { callback(progress, message) }
            .onFailure { Log.w(TAG, "Progress callback failed", it) }
    }

    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308,
    )
}
