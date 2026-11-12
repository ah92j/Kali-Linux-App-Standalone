package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Core Linux runtime engine.
 *
 * Runs native Termux Linux on Android without PRoot.
 * Uses a C hook (libsocket_hook.so) via LD_PRELOAD to redirect file operations
 * and Unix socket connections from /data/data/com.termux/files/usr to DroidDesk's prefix.
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".bootstrap_extracted"
        private const val SHEBANG_MARKER = ".relocated_text_paths_v4"
        private const val ELF_PATCH_MARKER = ".elf_runpaths_patched"
        private const val DE_MARKER = ".de_installed"

        // ELF64 constants
        private const val ELFMAG0: Byte = 0x7f
        private const val ELFMAG1: Byte = 'E'.code.toByte()
        private const val ELFMAG2: Byte = 'L'.code.toByte()
        private const val ELFMAG3: Byte = 'F'.code.toByte()
        private const val ELFCLASS64: Byte = 2
        private const val ELFDATA2LSB: Byte = 1

        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2

        private const val DT_NULL = 0L
        private const val DT_STRTAB = 5L
        private const val DT_STRSZ = 10L
        private const val DT_RPATH = 15L
        private const val DT_RUNPATH = 29L

        private const val EI_MAG0 = 0
        private const val EI_MAG1 = 1
        private const val EI_MAG2 = 2
        private const val EI_MAG3 = 3
        private const val EI_CLASS = 4
        private const val EI_DATA = 5

        private const val E_PHOFF_OFFSET = 32
        private const val E_PHENTSIZE_OFFSET = 54
        private const val E_PHNUM_OFFSET = 56

        private const val P_TYPE_OFFSET = 0
        private const val P_OFFSET_OFFSET = 8
        private const val P_VADDR_OFFSET = 16
        private const val P_FILESZ_OFFSET = 32
        private const val PH_SIZE = 56

        private const val D_TAG_OFFSET = 0
        private const val D_VAL_OFFSET = 8
        private const val DYN_SIZE = 16

        // The desktop is launched from DesktopActivity but queried/stopped from
        // MainActivity. Keep process ownership application-wide so both runtime
        // wrappers operate on the same session.
        @Volatile private var sessionProcess: Process? = null
        @Volatile private var dbusProcess: Process? = null
        @Volatile private var pulseProcess: Process? = null
        private val kaliDesktopMigrationLock = Any()
        private val kaliAptTransactionLock = ReentrantLock(true)
        @Volatile private var kaliDesktopMigrationRunning = false
    }

    @Volatile private var activeCommandProcess: Process? = null
    @Volatile private var installLogSink: ((String) -> Unit)? = null
    @Volatile private var packageOperationCancelled = false
    @Volatile private var lastKaliDesktopDiagnostic: String = ""

    fun getLastKaliDesktopDiagnostic(): String = lastKaliDesktopDiagnostic

    fun setInstallLogSink(sink: ((String) -> Unit)?) {
        installLogSink = sink
    }

    fun beginPackageOperation() {
        // A previous App Store screen may have been closed while apt was still
        // running. Stop that process before accepting a new transaction.
        if (activeCommandProcess?.isAlive == true) interruptCommand()
        packageOperationCancelled = false
        clearStalePackageLocks()
    }

    fun finishPackageOperation() {
        packageOperationCancelled = false
    }

    fun cancelPackageOperation(): Boolean {
        packageOperationCancelled = true
        val wasRunning = activeCommandProcess?.isAlive == true
        interruptCommand()
        return wasRunning
    }

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val prefixDir: File get() = File(baseDir, "usr")
    private val binDir: File get() = File(prefixDir, "bin")
    private val libDir: File get() = File(prefixDir, "lib")
    // Shared with X11ServerService. X clients resolve /tmp/.X11-unix/X0 here
    // through libsocket_hook, so this must not be $PREFIX/tmp.
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val homeDir: File get() = File(baseDir, "home")

    /** Qualcomm exposes the Adreno render device through KGSL on Android. */
    private fun hasAdrenoGpu(): Boolean = File("/dev/kgsl-3d0").exists()

    // The non-root product ships one supported, touch-tuned desktop.
    // Ignore legacy callers that may still request an older DE option.
    private fun normalizedDesktop(@Suppress("UNUSED_PARAMETER") desktopEnv: String): String = "xfce4"

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, BOOTSTRAP_MARKER).exists() && File(prefixDir, "bin/bash").exists()
    }

    fun isRunning(): Boolean {
        return sessionProcess?.isAlive == true
    }

    fun getInstalledDE(): String {
        val marker = File(prefixDir, DE_MARKER)
        if (marker.exists()) return marker.readText().trim().ifEmpty { "xfce4" }
        // Individual desktop binaries may already exist after a partially failed
        // package transaction. Only the marker written at the end of the complete
        // setup flow means the runtime is ready to launch.
        return ""
    }

    fun getGraphicsMode(): String {
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            "Turnip + Zink"
        } else {
            "Software (llvmpipe)"
        }
    }

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(binDir, "firefox").exists(),
        "code_oss" to (File(binDir, "code-oss").exists() || File(binDir, "code").exists()),
        "nodejs" to (File(binDir, "node").exists() && File(binDir, "npm").exists()),
        "imagemagick" to (File(binDir, "magick").exists() || File(binDir, "convert").exists()),
        "proot_debian" to isMinimalDebianInstalled(),
    )

    private fun isMinimalDebianInstalled(): Boolean {
        val installed = debianRootfsMarkers().any(File::exists) &&
            File(binDir, "start-debian").exists()
        if (installed) {
            relocateProotExecutable()
            writeDebianLauncher()
            clearProotDownloadCache()
        }
        return installed
    }

    private fun relocateProotExecutable() {
        val proot = File(binDir, "proot")
        if (!proot.isFile) return
        patchElfFile(
            proot,
            "/data/data/com.termux/files/usr/lib",
            File(prefixDir, "lib").absolutePath,
            "/data/data/com.termux/files/usr/lib/dri",
        )
    }


    // ── Rootless Kali inside DroidDesk (PRoot) ──
    private data class KaliProotResult(val exitCode: Int, val output: String)

    private fun isManagedKaliRootfs(rootfs: File): Boolean = runCatching {
        val expected = File(baseDir, "rootfs").canonicalFile
        if (rootfs.canonicalFile != expected || !rootfs.isDirectory) return@runCatching false
        val id = File(rootfs, "etc/os-release").takeIf { it.isFile }?.readText().orEmpty()
        id.lineSequence().any { it.trim().equals("ID=kali", ignoreCase = true) } &&
            File(rootfs, "usr/bin/dpkg").isFile && File(rootfs, "usr/bin/bash").isFile
    }.getOrDefault(false)

    fun isKaliProotDesktopInstalled(rootfs: File): Boolean {
        if (!isManagedKaliRootfs(rootfs)) return false
        return runCatching {
            File(rootfs, ".chroot_de_installed").readText().trim() == "xfce4" &&
                File(rootfs, ".kali_default_tools_installed").readText().trim() == "kali-linux-default" &&
                File(rootfs, "usr/bin/startxfce4").isFile &&
                File(rootfs, "usr/bin/dbus-launch").isFile &&
                File(binDir, "proot").canExecute() &&
                File(prefixDir, "libexec/proot/loader").isFile
        }.getOrDefault(false)
    }

    /** Install Kali's default toolset and XFCE in DroidDesk's private rootfs without su/chroot or Termux. */
    fun installKaliProotDesktop(
        rootfs: File,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!KaliInstallCoordinator.tryStart()) {
            val active = KaliInstallCoordinator.snapshot()
            val currentStatus = active["status"]?.toString() ?: "Kali setup is already running"
            onProgress(
                (active["progress"] as? Number)?.toDouble() ?: 0.0,
                "Kali setup is already running: $currentStatus",
            )
            return false
        }

        var succeeded = false
        val report: (Double, String) -> Unit = { value, message ->
            KaliInstallCoordinator.report(value, message)
            onProgress(value, message)
        }

        return try {
            if (!isBootstrapped()) throw IllegalStateException("DroidDesk Linux bootstrap is not ready")
            if (!isManagedKaliRootfs(rootfs)) throw SecurityException("The private rootfs is not a verified Kali installation")

            report(0.04, "Preparing DroidDesk PRoot runtime...")
            if (!installOptionalPackages(listOf("proot"), report, 0.12)) {
                throw IllegalStateException("Could not install PRoot inside DroidDesk")
            }
            patchShebangs(force = true)
            relocateProotExecutable()
            configureKaliDns(rootfs)

            report(0.16, "Checking Kali rootless runtime...")
            val preflight = runKaliProotCommand(
                rootfs, "/root",
                listOf(
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null", "/bin/bash", "-c",
                    "test -x /bin/bash && test -x /usr/bin/apt-get && test -x /usr/bin/sudo",
                ),
                2, onLog,
            )
            if (preflight.exitCode != 0) {
                throw IllegalStateException("DroidDesk PRoot could not enter Kali: ${preflight.output.takeLast(1200)}")
            }

            val freeBytes = android.os.StatFs(rootfs.absolutePath).availableBytes
            val minimumFreeBytes = 15L * 1024L * 1024L * 1024L
            if (freeBytes < minimumFreeBytes) {
                val freeGiB = freeBytes / (1024.0 * 1024.0 * 1024.0)
                throw IllegalStateException(
                    "Kali default tools need at least 15 GB free before installation. Available: %.1f GB"
                        .format(java.util.Locale.US, freeGiB)
                )
            }

            report(0.18, "Preparing the embedded Kali deployment script...")
            val scriptDirectory = File(rootfs, "root/.droiddesk")
            if (!scriptDirectory.isDirectory && !scriptDirectory.mkdirs()) {
                throw IllegalStateException("Could not create Kali deployment-script directory")
            }
            val setupScript = File(scriptDirectory, "setup-kali-default.sh")
            context.assets.open("droiddesk/kali/setup-kali-default.sh").use { input ->
                setupScript.outputStream().use { output -> input.copyTo(output) }
            }
            if (!setupScript.setExecutable(true, false)) {
                throw IllegalStateException("Could not mark the Kali deployment script executable")
            }

            report(0.20, "Installing Kali default tools and XFCE...")
            val install = runKaliProotCommand(
                rootfs, "/root",
                listOf(
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM=dumb", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive", "BASH_ENV=/dev/null",
                    "/bin/bash", "-c", "/bin/bash /root/.droiddesk/setup-kali-default.sh",
                ),
                240, onLog,
            )
            if (install.exitCode != 0) {
                throw IllegalStateException(
                    "Kali default tools and XFCE installation failed (exit ${install.exitCode}): " +
                        install.output.takeLast(2400)
                )
            }

            report(0.92, "Verifying Kali default tools and desktop...")
            val verify = runKaliProotCommand(
                rootfs, "/root",
                listOf(
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null", "/bin/bash", "-c",
                    "dpkg-query -W -f='\${Status}' kali-linux-default | grep -qx 'install ok installed' && " +
                        "test -x /usr/bin/startxfce4 && test -x /usr/bin/dbus-launch",
                ),
                2, onLog,
            )
            if (verify.exitCode != 0) {
                throw IllegalStateException(
                    "Kali package verification failed: ${verify.output.takeLast(1200)}"
                )
            }
            if (!File(rootfs, "etc/passwd").readLines().any { it.startsWith("kali:") }) {
                throw IllegalStateException("The Kali rootfs does not contain the standard kali user")
            }
            File(rootfs, ".chroot_de_installed").writeText("xfce4\n")
            File(rootfs, ".kali_default_tools_installed").writeText("kali-linux-default\n")
            writeKaliLauncher(rootfs)
            report(1.0, "Kali default tools and XFCE installed inside DroidDesk")
            Log.i(TAG, "Rootless Kali default toolset and XFCE installed: ${rootfs.absolutePath}")
            succeeded = true
            true
        } catch (error: Exception) {
            Log.e(TAG, "Rootless Kali desktop installation failed", error)
            report(-1.0, error.message ?: "Kali PRoot installation failed")
            false
        } finally {
            KaliInstallCoordinator.finish(succeeded)
        }
    }

    private fun configureKaliDns(rootfs: File) {
        val dnsServers = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            network?.let { manager?.getLinkProperties(it) }?.dnsServers
                ?.mapNotNull { it.hostAddress?.substringBefore('%') }
                ?.filter { it.isNotBlank() }.orEmpty()
        }.getOrDefault(emptyList())
        val selected = dnsServers.distinct().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val resolvConf = File(rootfs, "etc/resolv.conf")
        if (Files.isSymbolicLink(resolvConf.toPath())) Files.deleteIfExists(resolvConf.toPath())
        if (resolvConf.exists() && !resolvConf.isFile) throw IllegalStateException("Kali /etc/resolv.conf is not a regular file")
        resolvConf.writeText(selected.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun prepareKaliProot(rootfs: File) {
        if (!isManagedKaliRootfs(rootfs)) throw SecurityException("Kali rootfs is missing or outside DroidDesk storage")
        val proot = File(binDir, "proot")
        val loader = File(prefixDir, "libexec/proot/loader")
        if (!proot.isFile || !loader.isFile) throw IllegalStateException("DroidDesk PRoot or its Android loader is missing")
        relocateProotExecutable()
        val temp = File(tmpDir, "proot-kali")
        val shm = File(tmpDir, "kali-shm")
        if (!temp.exists() && !temp.mkdirs()) throw IllegalStateException("Cannot create PRoot temp directory")
        if (!shm.exists() && !shm.mkdirs()) throw IllegalStateException("Cannot create Kali shared-memory directory")
    }

    private fun createKaliProotBuilder(
        rootfs: File,
        workingDirectory: String,
        guestCommand: List<String>,
    ): ProcessBuilder {
        prepareKaliProot(rootfs)
        val proot = File(binDir, "proot")
        val sharedMemory = File(tmpDir, "kali-shm")
        val command = KaliProotCommandLine.build(
            prootExecutable = proot.absolutePath,
            rootfsPath = rootfs.canonicalPath,
            workingDirectory = workingDirectory,
            bindMounts = listOf(
                "/dev",
                "/proc",
                "${tmpDir.absolutePath}:/tmp",
                "${sharedMemory.absolutePath}:/dev/shm",
            ),
            guestCommand = guestCommand,
        )
        val builder = ProcessBuilder(command).directory(homeDir.apply { mkdirs() }).redirectErrorStream(true)
        val environment = builder.environment()
        environment.clear()
        environment.putAll(getTermuxEnv())
        environment.remove("LD_PRELOAD")
        environment["PROOT_TMP_DIR"] = File(tmpDir, "proot-kali").absolutePath
        environment["PROOT_LOADER"] = File(prefixDir, "libexec/proot/loader").absolutePath
        val loader32 = File(prefixDir, "libexec/proot/loader32")
        if (loader32.isFile) environment["PROOT_LOADER_32"] = loader32.absolutePath
        environment["LD_LIBRARY_PATH"] = libDir.absolutePath
        return builder
    }

    private fun runKaliProotCommand(
        rootfs: File,
        workingDirectory: String,
        guestCommand: List<String>,
        timeoutMinutes: Long,
        onOutput: (String) -> Unit,
        onProcessStarted: (Process) -> Unit = {},
    ): KaliProotResult {
        val process = createKaliProotBuilder(rootfs, workingDirectory, guestCommand).start()
        onProcessStarted(process)
        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val chunk = "$line\n"
                        synchronized(output) {
                            output.append(chunk)
                            if (output.length > 24_000) output.delete(0, output.length - 24_000)
                        }
                        runCatching { onOutput(chunk) }
                    }
                }
            }.onFailure { Log.w(TAG, "Kali PRoot output reader failed", it) }
        }, "KaliProotOutput").apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(5_000)
        val captured = synchronized(output) { output.toString() }
        return KaliProotResult(if (finished) process.exitValue() else -1,
            if (finished) captured else "$captured\nPRoot operation timed out")
    }

    private fun writeKaliLauncher(rootfs: File) {
        val launcher = File(binDir, "start-kali")
        val proot = File(binDir, "proot")
        val loader = File(prefixDir, "libexec/proot/loader")
        val loader32 = File(prefixDir, "libexec/proot/loader32")
        val shm = File(tmpDir, "kali-shm")
        launcher.writeText(
            """
            #!/system/bin/sh
            set -e
            export PROOT_TMP_DIR='${File(tmpDir, "proot-kali").absolutePath}'
            export PROOT_LOADER='${loader.absolutePath}'
            ${if (loader32.isFile) "export PROOT_LOADER_32='${loader32.absolutePath}'" else ""}
            export LD_LIBRARY_PATH='${libDir.absolutePath}'
            unset LD_PRELOAD
            mkdir -p '${File(tmpDir, "proot-kali").absolutePath}' '${shm.absolutePath}'
            exec '${proot.absolutePath}' --link2symlink -0 -r '${rootfs.canonicalPath}' \
              -b /dev -b /proc -b '${tmpDir.absolutePath}:/tmp' -b '${shm.absolutePath}:/dev/shm' \
              -w /home/kali \
              /usr/bin/env -i HOME=/home/kali USER=kali LOGNAME=kali \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              SHELL=/usr/bin/zsh TERM=xterm-256color LANG=C.UTF-8 DISPLAY=:0 GDK_BACKEND=x11 \
              PULSE_SERVER=tcp:127.0.0.1:4713 PULSE_SINK=droiddesk_android PULSE_LATENCY_MSEC=80 \
              MOZ_DISABLE_CONTENT_SANDBOX=1 XDG_RUNTIME_DIR=/tmp/runtime-kali \
              /usr/bin/sudo -E -u kali /bin/bash --login
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)
    }

    /**
     * A live dbus-launch wrapper is not proof that XFCE is usable. Check the
     * actual window manager, panel and desktop processes before reusing it.
     */
    /**
     * Android restricts /proc/sys/kernel/overflowuid, so Bubblewrap cannot create
     * its nested sandbox inside this PRoot environment. Newer Glycin releases
     * provide an explicit opt-out for environments that cannot create a sandbox.
     *
     * Upgrade Glycin inside Kali and prove that GdkPixbuf can decode a real SVG
     * with that supported mode before XFCE is launched.
     */
    private fun ensureKaliGlycinAndroidCompatibility(rootfs: File): Boolean {
        val marker = File(rootfs, "root/.droiddesk/glycin-proot-compat-v1")
        if (marker.isFile) return true
        val minimumVersion = "2.2.2+ds-1"
        val script = """
            set -Eeuo pipefail
            export DEBIAN_FRONTEND=noninteractive
            minimum_version='2.2.2+ds-1'
            needs_update=0

            for package in glycin-loaders libglycin-2-0 glycin-thumbnailers; do
              status="${'$'}(dpkg-query -W -f='${'$'}{Status}' "${'$'}package" 2>/dev/null || true)"
              version="${'$'}(dpkg-query -W -f='${'$'}{Version}' "${'$'}package" 2>/dev/null || true)"
              if [ "${'$'}status" != "install ok installed" ]; then
                echo "[DroidDesk Kali] Required image-loader package is missing: ${'$'}package"
                needs_update=1
              elif ! dpkg --compare-versions "${'$'}version" ge "${'$'}minimum_version"; then
                echo "[DroidDesk Kali] ${'$'}package ${'$'}version is older than ${'$'}minimum_version"
                needs_update=1
              fi
            done

            if [ "${'$'}needs_update" -eq 1 ]; then
              echo "[DroidDesk Kali] Updating Kali Glycin packages for Android PRoot compatibility..."
              apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 \
                -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 update
              apt-get -o Acquire::Retries=3 \
                -o Dpkg::Options::=--force-confdef \
                -o Dpkg::Options::=--force-confold \
                install -y --no-install-recommends \
                glycin-loaders libglycin-2-0 glycin-thumbnailers
            fi

            for package in glycin-loaders libglycin-2-0 glycin-thumbnailers; do
              version="${'$'}(dpkg-query -W -f='${'$'}{Version}' "${'$'}package")"
              if ! dpkg --compare-versions "${'$'}version" ge "${'$'}minimum_version"; then
                echo "[DroidDesk Kali] ${'$'}package remains at ${'$'}version; need ${'$'}minimum_version or newer."
                exit 31
              fi
              echo "[DroidDesk Kali] ${'$'}package version: ${'$'}version"
            done

            dpkg --configure -a

            if ! command -v gdk-pixbuf-csource >/dev/null 2>&1; then
              echo "[DroidDesk Kali] Restoring the ARM64 GDK Pixbuf test utility package..."
              apt-get install -y --reinstall libgdk-pixbuf2.0-bin
            fi
            command -v gdk-pixbuf-csource >/dev/null 2>&1 || {
              echo "[DroidDesk Kali] gdk-pixbuf-csource is missing after reinstalling libgdk-pixbuf2.0-bin."
              exit 32
            }

            if command -v gdk-pixbuf-query-loaders >/dev/null 2>&1; then
              gdk-pixbuf-query-loaders --update-cache || true
            fi

            svg='/usr/share/icons/Adwaita/scalable/status/image-missing.svg'
            if [ ! -s "${'$'}svg" ]; then
              svg="${'$'}(find /usr/share/icons -type f -name '*.svg' -print -quit 2>/dev/null || true)"
            fi
            [ -n "${'$'}svg" ] && [ -s "${'$'}svg" ] || {
              echo "[DroidDesk Kali] No installed SVG icon is available for the image-loader test."
              exit 33
            }

            probe_dir='/tmp/droiddesk-glycin-probe'
            rm -rf "${'$'}probe_dir"
            install -d -m 0700 "${'$'}probe_dir" "${'$'}probe_dir/cache" "${'$'}probe_dir/runtime"
            chmod 0700 "${'$'}probe_dir/runtime"
            trap 'rm -rf "${'$'}probe_dir"' EXIT

            # GdkPixbuf 2.44 on ARM64 uses Glycin. Its legacy
            # gdk-pixbuf-thumbnailer command is intentionally not shipped here.
            # csource remains available and calls GdkPixbuf's real image loader.
            printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==' \
              | base64 -d > "${'$'}probe_dir/probe.png"
            test -s "${'$'}probe_dir/probe.png" || {
              echo "[DroidDesk Kali] Could not create the PNG decoder test image."
              exit 34
            }

            export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
            export HOME=/root
            export XDG_CACHE_HOME="${'$'}probe_dir/cache"
            export XDG_RUNTIME_DIR="${'$'}probe_dir/runtime"
            export XDG_DATA_HOME=/root/.local/share
            export XDG_CONFIG_HOME=/root/.config
            export XDG_DATA_DIRS=/usr/local/share:/usr/share

            echo "[DroidDesk Kali] Testing the actual GdkPixbuf PNG loader without nested Bubblewrap..."
            gdk-pixbuf-csource --raw --name=droiddesk_png_probe \
              "${'$'}probe_dir/probe.png" > "${'$'}probe_dir/png.c"
            test -s "${'$'}probe_dir/png.c" || {
              echo "[DroidDesk Kali] GdkPixbuf PNG decoding failed with Glycin sandbox disabled."
              exit 35
            }

            echo "[DroidDesk Kali] Testing the actual GdkPixbuf SVG loader without nested Bubblewrap..."
            gdk-pixbuf-csource --raw --name=droiddesk_svg_probe \
              "${'$'}svg" > "${'$'}probe_dir/svg.c"
            test -s "${'$'}probe_dir/svg.c" || {
              echo "[DroidDesk Kali] GdkPixbuf SVG decoding failed with Glycin sandbox disabled."
              exit 36
            }

            echo "[DroidDesk Kali] GdkPixbuf PNG and SVG decoding tests passed."
            echo "[DroidDesk Kali] Glycin nested sandbox is disabled only for this PRoot desktop session."
        """.trimIndent()

        val result = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/root",
            guestCommand = listOf(
                "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LOGNAME=root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null",
                "/bin/bash", "-c", script,
            ),
            timeoutMinutes = 8,
            onOutput = { line -> Log.i(TAG, line.trimEnd()) },
        )
        if (result.exitCode != 0) {
            lastKaliDesktopDiagnostic = buildString {
                append("Kali GdkPixbuf/Glycin compatibility test failed.\n")
                append("The nested Bubblewrap sandbox is intentionally disabled for Android PRoot.\n")
                append("Required Glycin packages: ").append(minimumVersion).append(" or newer.\n")
                append(result.output.takeLast(5000))
            }.takeLast(5600)
            Log.e(TAG, lastKaliDesktopDiagnostic)
            return false
        }

        File(rootfs, "home/kali/.cache/glycin").deleteRecursively()
        File(rootfs, "root/.cache/glycin").deleteRecursively()
        marker.parentFile?.mkdirs()
        marker.writeText("ok\n")
        lastKaliDesktopDiagnostic = ""
        Log.i(TAG, "Kali GdkPixbuf PNG/SVG tests passed with Glycin sandbox disabled")
        return true
    }

    /**
     * Repair only desktop support packages and generated caches. This does not
     * reinstall Kali or remove user files, downloaded files, or installed tools.
     */
    private fun repairKaliDesktopSupport(rootfs: File): Boolean {
        val marker = File(rootfs, "root/.droiddesk/xfce-support-v1")
        if (marker.isFile) return true
        val script = """
            set -Eeuo pipefail
            export DEBIAN_FRONTEND=noninteractive
            packages=(
              bubblewrap
              dbus-daemon
              dbus-x11
              shared-mime-info
              libgdk-pixbuf2.0-bin
              hicolor-icon-theme
              adwaita-icon-theme
              librsvg2-common
              glycin-thumbnailers
              glycin-loaders
              libglycin-2-0
            )
            install -d -m 1777 /tmp
            install -d -m 1777 /tmp/.ICE-unix
            install -d -m 0755 /run/dbus

            missing=()
            for package in "${'$'}{packages[@]}"; do
              status="${'$'}(dpkg-query -W -f='${'$'}{Status}' "${'$'}package" 2>/dev/null || true)"
              [ "${'$'}status" = "install ok installed" ] || missing+=("${'$'}package")
            done
            if [ "${'$'}{#missing[@]}" -gt 0 ]; then
              echo "[DroidDesk Kali] Repairing missing XFCE/GTK support packages: ${'$'}{missing[*]}"
              apt-get -o Acquire::Retries=3 update
              apt-get install -y --no-install-recommends "${'$'}{missing[@]}"
            fi

            dpkg --configure -a
            if command -v update-mime-database >/dev/null 2>&1; then
              update-mime-database /usr/share/mime
            fi
            if command -v gdk-pixbuf-query-loaders >/dev/null 2>&1; then
              gdk-pixbuf-query-loaders --update-cache || echo "[DroidDesk Kali] Warning: pixbuf loader cache refresh failed."
            fi
            if command -v gtk-update-icon-cache >/dev/null 2>&1; then
              [ ! -d /usr/share/icons/hicolor ] ||
                gtk-update-icon-cache -f -t /usr/share/icons/hicolor || true
              [ ! -d /usr/share/icons/Adwaita ] ||
                gtk-update-icon-cache -f /usr/share/icons/Adwaita || true
            fi
            echo "[DroidDesk Kali] GTK pixbuf, MIME, icon and D-Bus support checks completed."
        """.trimIndent()
        val result = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/root",
            guestCommand = listOf(
                "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LOGNAME=root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null",
                "/bin/bash", "-c", script,
            ),
            timeoutMinutes = 6,
            onOutput = { line -> Log.i(TAG, line.trimEnd()) },
        )
        if (result.exitCode != 0) {
            lastKaliDesktopDiagnostic = "Kali GTK/D-Bus support repair failed: " + result.output.takeLast(3000)
            Log.e(TAG, lastKaliDesktopDiagnostic)
            return false
        }
        marker.parentFile?.mkdirs()
        marker.writeText("ok\n")
        return true
    }

    /**
     * A live PID list is not enough: XFCE may leave stale processes behind while X11
     * still shows only its blank root window. Require the real window manager, a mapped
     * panel window, and xfdesktop's registered desktop surface before showing the UI.
     */
    private fun kaliDesktopHealthCheckScript(timeoutSeconds: Int = 0): String = """
        set -u
        export DISPLAY=:0

        has_live_process() {
          local name="${'$'}1" pid state
          while IFS= read -r pid; do
            [ -n "${'$'}pid" ] || continue
            state="${'$'}(awk '{print ${'$'}3}' "/proc/${'$'}pid/stat" 2>/dev/null || true)"
            case "${'$'}state" in Z|X|"") continue ;; esac
            return 0
          done < <(pgrep -x "${'$'}name" 2>/dev/null || true)
          return 1
        }

        has_viewable_window() {
          local wanted_class="${'$'}1" min_width="${'$'}2" min_height="${'$'}3"
          local window_id wm_class info width height
          for window_id in ${'$'}window_ids; do
            wm_class="${'$'}(xprop -id "${'$'}window_id" WM_CLASS 2>/dev/null || true)"
            printf '%s\n' "${'$'}wm_class" | grep -qi -- "${'$'}wanted_class" || continue
            info="${'$'}(xwininfo -id "${'$'}window_id" 2>/dev/null || true)"
            printf '%s\n' "${'$'}info" | grep -q 'Map State: IsViewable' || continue
            width="${'$'}(printf '%s\n' "${'$'}info" | sed -n 's/^[[:space:]]*Width: //p' | head -n 1)"
            height="${'$'}(printf '%s\n' "${'$'}info" | sed -n 's/^[[:space:]]*Height: //p' | head -n 1)"
            [ "${'$'}{width:-0}" -ge "${'$'}min_width" ] 2>/dev/null || continue
            [ "${'$'}{height:-0}" -ge "${'$'}min_height" ] 2>/dev/null || continue
            return 0
          done
          return 1
        }

        check_xfce_session() {
          local process wm_property wm_id wm_name window_tree window_ids
          for process in xfce4-session xfwm4 xfce4-panel xfdesktop; do
            if ! has_live_process "${'$'}process"; then
              echo "[DroidDesk Kali] Required process is missing or not live: ${'$'}process"
              return 1
            fi
          done

          wm_property="${'$'}(xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null || true)"
          wm_id="${'$'}(printf '%s\n' "${'$'}wm_property" | sed -nE 's/.*window id # (0x[[:xdigit:]]+).*/\1/p')"
          if [ -z "${'$'}wm_id" ] || [ "${'$'}wm_id" = "0x0" ]; then
            echo "[DroidDesk Kali] XFCE processes exist, but X11 has no active window-manager owner."
            return 1
          fi
          wm_name="${'$'}(xprop -id "${'$'}wm_id" _NET_WM_NAME 2>/dev/null || xprop -id "${'$'}wm_id" WM_NAME 2>/dev/null || true)"
          if ! printf '%s\n' "${'$'}wm_name" | grep -qiE 'xfwm4|xfwm'; then
            echo "[DroidDesk Kali] X11 window-manager owner is not xfwm4: ${'$'}wm_name"
            return 1
          fi

          window_tree="${'$'}(xwininfo -root -tree 2>/dev/null || true)"
          window_ids="${'$'}(printf '%s\n' "${'$'}window_tree" | sed -nE 's/^[[:space:]]*(0x[[:xdigit:]]+).*/\1/p' | sort -u)"
          if [ -z "${'$'}window_ids" ]; then
            echo "[DroidDesk Kali] X11 returned no child windows for the XFCE desktop."
            return 1
          fi
          if ! has_viewable_window xfce4-panel 100 20; then
            echo "[DroidDesk Kali] xfce4-panel is running but has no mapped, viewable panel window."
            return 1
          fi

          # xfdesktop publishes the actual desktop surface on the X11 root window.
          # Its manager-selection window is intentionally 1x1 pixels and is not the desktop.
          local desktop_property desktop_id desktop_info desktop_width desktop_height
          desktop_property="${'$'}(xprop -root XFCE_DESKTOP_WINDOW 2>/dev/null || true)"
          desktop_id="${'$'}(printf '%s\n' "${'$'}desktop_property" | sed -nE 's/.*window id # (0x[[:xdigit:]]+).*/\1/p')"
          if [ -z "${'$'}desktop_id" ] || [ "${'$'}desktop_id" = "0x0" ]; then
            echo "[DroidDesk Kali] xfdesktop has not registered its real desktop surface on the X11 root window."
            return 1
          fi
          desktop_info="${'$'}(xwininfo -id "${'$'}desktop_id" 2>/dev/null || true)"
          if ! printf '%s\n' "${'$'}desktop_info" | grep -q 'Map State: IsViewable'; then
            echo "[DroidDesk Kali] XFCE desktop surface is registered but not viewable."
            return 1
          fi
          desktop_width="${'$'}(printf '%s\n' "${'$'}desktop_info" | sed -n 's/^[[:space:]]*Width: //p' | head -n 1)"
          desktop_height="${'$'}(printf '%s\n' "${'$'}desktop_info" | sed -n 's/^[[:space:]]*Height: //p' | head -n 1)"
          [ "${'$'}{desktop_width:-0}" -ge 100 ] 2>/dev/null || {
            echo "[DroidDesk Kali] XFCE desktop surface width is invalid: ${'$'}{desktop_width:-missing}"
            return 1
          }
          [ "${'$'}{desktop_height:-0}" -ge 100 ] 2>/dev/null || {
            echo "[DroidDesk Kali] XFCE desktop surface height is invalid: ${'$'}{desktop_height:-missing}"
            return 1
          }

          echo "[DroidDesk Kali] XFCE is visually ready: xfwm4 owns the X11 root, the panel is mapped, and the registered desktop surface is visible."
          return 0
        }

        if [ ${timeoutSeconds} -gt 0 ]; then
          deadline=${'$'}((SECONDS + ${timeoutSeconds}))
          while [ "${'$'}SECONDS" -le "${'$'}deadline" ]; do
            if check_xfce_session; then exit 0; fi
            sleep 1
          done
        else
          if check_xfce_session; then exit 0; fi
        fi

        echo "[DroidDesk Kali] XFCE visual readiness failed. Current guest processes:"
        ps -eo pid,stat,comm,args 2>/dev/null | grep -E 'xfce4-session|xfce4-panel|xfdesktop|xfwm4|dbus' | grep -v grep || true
        echo "[DroidDesk Kali] X11 window-manager property:"
        xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null || true
        echo "[DroidDesk Kali] XFCE desktop-surface property:"
        xprop -root XFCE_DESKTOP_WINDOW 2>/dev/null || true
        echo "[DroidDesk Kali] ICE socket directory:"
        ls -ld /tmp/.ICE-unix /tmp/.X11-unix 2>/dev/null || true
        echo "[DroidDesk Kali] X11 window tree:"
        xwininfo -root -tree 2>/dev/null || true
        exit 1
    """.trimIndent()

    fun isKaliDesktopProcessSetReady(rootfs: File): Boolean {
        if (!isManagedKaliRootfs(rootfs)) return false
        val probe = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/home/kali",
            guestCommand = listOf(
                "/usr/bin/env", "-i",
                "HOME=/home/kali", "USER=kali", "LOGNAME=kali",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=dumb", "DISPLAY=:0", "BASH_ENV=/dev/null",
                "/bin/bash", "-c", kaliDesktopHealthCheckScript(),
            ),
            timeoutMinutes = 1,
            onOutput = { line -> Log.d(TAG, "Kali visual health probe: ${line.trimEnd()}") },
        )
        if (probe.exitCode != 0) {
            lastKaliDesktopDiagnostic = probe.output.takeLast(5_000)
            Log.w(TAG, "Kali XFCE visual health check failed: ${lastKaliDesktopDiagnostic.takeLast(1_800)}")
            return false
        }
        lastKaliDesktopDiagnostic = ""
        return true
    }

    /**
     * Stop only the failed XFCE session components. Do not kill the whole Kali
     * user session or unrelated terminal/application processes.
     */
    private fun stopKaliDesktopProcesses(rootfs: File) {
        val cleanup = listOf(
            "/usr/bin/env", "-i",
            "HOME=/home/kali", "USER=kali", "LOGNAME=kali",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "BASH_ENV=/dev/null", "/bin/bash", "-c",
            """
            for name in xfce4-panel xfdesktop xfwm4 xfce4-session startxfce4 dbus-launch xfsettingsd xfconfd; do
              pkill -TERM -x "${'$'}name" 2>/dev/null || true
            done
            sleep 1
            for name in xfce4-panel xfdesktop xfwm4 xfce4-session startxfce4 dbus-launch xfsettingsd xfconfd; do
              pkill -KILL -x "${'$'}name" 2>/dev/null || true
            done
            """.trimIndent(),
        )
        runCatching {
            runKaliProotCommand(rootfs, "/home/kali", cleanup, 1, onOutput = {})
        }.onFailure { Log.w(TAG, "Kali stale-session cleanup failed", it) }
    }

    /**
     * Retry the XFCE Applications menu independently from desktop-experience-v20.
     * Older builds could mark v17 complete while the D-Bus session was not ready.
     */
    private fun ensureKaliApplicationsMenu(rootfs: File): Boolean {
        val scriptDirectory = File(rootfs, "root/.droiddesk")
        if (!scriptDirectory.isDirectory && !scriptDirectory.mkdirs()) {
            Log.w(TAG, "Could not create the Applications-menu repair directory")
            return false
        }
        val script = File(scriptDirectory, "ensure-applications-menu.sh")
        val applicationMenu = File(scriptDirectory, "applications-menu.py")
        return try {
            context.assets.open("droiddesk/kali/ensure-applications-menu.sh").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open("droiddesk/kali/applications-menu.py").use { input ->
                applicationMenu.outputStream().use { output -> input.copyTo(output) }
            }
            if (!applicationMenu.setExecutable(true, false)) {
                Log.w(TAG, "Could not mark the Kali applications launcher executable")
                return false
            }
            if (!script.setExecutable(true, false)) {
                Log.w(TAG, "Could not mark the Applications-menu repair script executable")
                return false
            }
            val result = runKaliProotCommand(
                rootfs = rootfs,
                workingDirectory = "/root",
                guestCommand = listOf(
                    "/usr/bin/env", "-i",
                    "HOME=/root", "USER=root", "LOGNAME=root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null",
                    "/bin/bash", "/root/.droiddesk/ensure-applications-menu.sh",
                ),
                timeoutMinutes = 2,
                onOutput = { line -> Log.i(TAG, "Kali Applications menu: ${line.trimEnd()}") },
            )
            if (result.exitCode == 0) {
                Log.i(TAG, "Custom Kali installed-applications menu and desktop shortcut actions were verified")
                true
            } else {
                Log.w(TAG, "Kali installed-applications menu repair failed (exit ${result.exitCode}): ${result.output.takeLast(1800)}")
                false
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not execute the Kali Applications-menu repair", error)
            false
        }
    }

    /**
     * Upgrade an existing Kali rootfs in place with the user-facing desktop
     * additions that cannot be supplied by an APK update alone.
     */
    /**
     * Install the signed standalone Firefox 160 ARM64 build without rerunning
     * the full APT/XFCE migration if the first network attempt was unavailable.
     */
    private fun ensureKaliFirefox160(rootfs: File): Boolean {
        val installDir = File(rootfs, "opt/droiddesk-firefox-160.0")
        val installedVersion = File(installDir, ".droiddesk-managed-version")
        val browser = File(installDir, "firefox")
        if (browser.canExecute() && installedVersion.isFile &&
            runCatching { installedVersion.readText().trim() == "160.0" }.getOrDefault(false)
        ) {
            return true
        }

        val scriptDirectory = File(rootfs, "root/.droiddesk")
        if (!scriptDirectory.isDirectory && !scriptDirectory.mkdirs()) {
            Log.w(TAG, "Could not create Firefox 160 installer directory")
            return false
        }
        val script = File(scriptDirectory, "desktop-experience.sh")
        return try {
            context.assets.open("droiddesk/kali/desktop-experience.sh").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            if (!script.setExecutable(true, false)) {
                Log.w(TAG, "Could not mark Firefox 160 installer executable")
                return false
            }

            val result = runKaliProotCommand(
                rootfs = rootfs,
                workingDirectory = "/root",
                guestCommand = listOf(
                    "/usr/bin/env", "-i",
                    "HOME=/root", "USER=root", "LOGNAME=root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null",
                    "/bin/bash", "/root/.droiddesk/desktop-experience.sh", "--install-firefox-160",
                ),
                timeoutMinutes = 20,
                onOutput = { line -> Log.i(TAG, "Kali Firefox 160 installer: ${line.trimEnd()}") },
            )
            val installed = browser.canExecute() && installedVersion.isFile &&
                runCatching { installedVersion.readText().trim() == "160.0" }.getOrDefault(false)
            if (result.exitCode == 0 && installed) {
                Log.i(TAG, "Official Mozilla Firefox 160.0 ARM64 installation is ready")
                true
            } else {
                Log.w(
                    TAG,
                    "Firefox 160.0 installation is not ready (exit ${result.exitCode}): ${result.output.takeLast(1600)}",
                )
                false
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not run the Firefox 160.0 installer", error)
            false
        }
    }

    private fun ensureKaliDesktopExperience(rootfs: File): Boolean {
        val completionMarker = File(rootfs, "root/.droiddesk/desktop-experience-v20")
        if (completionMarker.isFile) {
            ensureKaliFirefox160(rootfs)
            return true
        }

        val scriptDirectory = File(rootfs, "root/.droiddesk")
        if (!scriptDirectory.isDirectory && !scriptDirectory.mkdirs()) {
            Log.w(TAG, "Could not create the Kali desktop-experience script directory")
            return false
        }

        val script = File(scriptDirectory, "desktop-experience.sh")
        return try {
            context.assets.open("droiddesk/kali/desktop-experience.sh").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            if (!script.setExecutable(true, false)) {
                Log.w(TAG, "Could not mark the Kali desktop-experience script executable")
                return false
            }

            val result = runKaliProotCommand(
                rootfs = rootfs,
                workingDirectory = "/root",
                guestCommand = listOf(
                    "/usr/bin/env", "-i",
                    "HOME=/root", "USER=root", "LOGNAME=root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8", "TERM=dumb", "DEBIAN_FRONTEND=noninteractive",
                    "BASH_ENV=/dev/null",
                    "/bin/bash", "/root/.droiddesk/desktop-experience.sh",
                ),
                timeoutMinutes = 30,
                onOutput = { line -> Log.i(TAG, "Kali desktop experience: ${line.trimEnd()}") },
            )
            if (result.exitCode != 0) {
                Log.w(
                    TAG,
                    "Kali desktop-experience migration failed (exit ${result.exitCode}): ${result.output.takeLast(1800)}",
                )
                false
            } else {
                val migrated = File(rootfs, "root/.droiddesk/desktop-experience-v20").isFile
                if (migrated) ensureKaliFirefox160(rootfs)
                migrated
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not apply Kali desktop-experience migration", error)
            false
        }
    }

    /**
     * Repair packages and migrate the desktop only after XFCE is visible.
     * A process-wide lock prevents duplicate APT transactions on activity resume.
     */
    fun scheduleKaliDesktopExperienceMigration(rootfs: File) {
        synchronized(kaliDesktopMigrationLock) {
            if (kaliDesktopMigrationRunning) return
            kaliDesktopMigrationRunning = true
        }
        Thread({
            try {
                kaliAptTransactionLock.withLock {
                    if (!repairKaliDesktopSupport(rootfs)) {
                        Log.w(TAG, "Kali post-launch GTK/D-Bus repair did not complete")
                    }
                    if (!ensureKaliGlycinAndroidCompatibility(rootfs)) {
                        Log.w(TAG, "Kali post-launch Glycin repair did not complete")
                    }
                    if (ensureKaliDesktopExperience(rootfs)) {
                        if (!ensureKaliApplicationsMenu(rootfs)) {
                            Log.w(TAG, "Custom Kali applications menu did not complete; it will retry on the next desktop launch")
                        }
                        applyKaliDesktopSettingsToRunningSession(rootfs)
                    } else {
                        Log.w(TAG, "Kali post-launch desktop migration did not complete")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Kali post-launch desktop migration failed", error)
            } finally {
                synchronized(kaliDesktopMigrationLock) {
                    kaliDesktopMigrationRunning = false
                }
            }
        }, "KaliDesktopPostLaunchRepair").apply {
            isDaemon = true
            start()
        }
    }

    private fun applyKaliDesktopSettingsToRunningSession(rootfs: File) {
        val addressFile = File(rootfs, "home/kali/.local/share/droiddesk/dbus-session-address")
        val address = runCatching { addressFile.readText().trim() }.getOrDefault("")
        if (address.isBlank()) {
            Log.w(TAG, "XFCE D-Bus address was not saved; settings will refresh next launch")
            return
        }
        // Reload the desktop surface without changing any persisted XFCE setting.
        // In particular, never write last-image, panel properties or desktop icon positions
        // here: this method runs after every desktop launch, not only after an APK upgrade.
        val refreshScript = """
            set +e
            export DISPLAY=:0 HOME=/home/kali XDG_RUNTIME_DIR=/tmp/runtime-kali
            xfdesktop --reload
            echo '[Kali Linux] Desktop refreshed without modifying user settings.'
        """.trimIndent()
        val guestCommand = listOf(
            "/usr/bin/env", "-i",
            "HOME=/home/kali", "USER=kali", "LOGNAME=kali",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "TERM=xterm-256color", "DISPLAY=:0",
            "XDG_RUNTIME_DIR=/tmp/runtime-kali", "DBUS_SESSION_BUS_ADDRESS=$address",
            "/usr/bin/sudo", "-E", "-u", "kali", "/bin/bash", "-c", refreshScript,
        )
        val result = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/home/kali",
            guestCommand = guestCommand,
            timeoutMinutes = 1,
            onOutput = { line -> Log.i(TAG, line.trimEnd()) },
        )
        if (result.exitCode != 0) {
            Log.w(TAG, "Could not apply live XFCE settings: ${result.output.takeLast(1200)}")
        }
    }


    /** Start Kali XFCE through PRoot and DroidDesk's existing X11/audio services. */
    fun startProotKaliSession(rootfs: File, desktopEnv: String = "xfce4") {
        require(desktopEnv == "xfce4") { "Only XFCE is configured for rootless Kali" }
        if (!isKaliProotDesktopInstalled(rootfs)) {
            throw IllegalStateException("Kali XFCE is not installed inside DroidDesk")
        }
        // GTK/Glycin package repair and desktop migration run after the desktop
        // is visible, rather than extending the initial loading screen.

        // Keep the foreground startup path lightweight. Package repair and the
        // first-run migration are deferred until XFCE is visibly ready.
        if (isKaliDesktopProcessSetReady(rootfs)) {
            Log.i(TAG, "Kali XFCE session is visually healthy; reusing the existing desktop")
            return
        }
        Log.w(TAG, "Kali XFCE is not visually ready; clearing any orphaned or incomplete session before restart")
        stopKaliDesktopProcesses(rootfs)
        sessionProcess?.let { stale ->
            runCatching { stale.destroy() }
            runCatching { if (!stale.waitFor(2, TimeUnit.SECONDS)) stale.destroyForcibly() }
        }
        sessionProcess = null

        val kaliHome = File(rootfs, "home/kali")
        if (!kaliHome.isDirectory) {
            throw IllegalStateException("Kali user home /home/kali is missing")
        }

        // Do not restore a half-written XFCE session after a failed/interrupted launch.
        // This removes only desktop session metadata, never user files or installed Kali tools.
        File(kaliHome, ".cache/sessions").deleteRecursively()
        val kaliWallpaper = File(
            kaliHome,
            ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
        )
        val kaliIconTheme = listOf("Papirus", "Adwaita", "hicolor").firstOrNull { theme ->
            File(rootfs, "usr/share/icons/$theme/index.theme").isFile
        } ?: "hicolor"
        val profileInstalled = XfceMobileProfile.install(
            context = context,
            homeDir = kaliHome,
            wallpaperFile = kaliWallpaper,
            wallpaperPathInSession = "/home/kali/.local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
            iconThemeName = kaliIconTheme,
        )
        if (!profileInstalled || !kaliWallpaper.isFile || kaliWallpaper.length() == 0L) {
            throw IllegalStateException("Could not install the XFCE wallpaper and profile inside Kali")
        }
        kaliWallpaper.setReadable(true, false)
        prepareKaliXfceHomePermissions(rootfs)

        val displayProbe = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/root",
            guestCommand = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=dumb", "DISPLAY=:0", "BASH_ENV=/dev/null",
                "/usr/bin/xdpyinfo", "-display", ":0",
            ),
            timeoutMinutes = 1,
            onOutput = { line -> Log.d(TAG, "Kali X11 display probe: ${line.trimEnd()}") },
        )
        if (displayProbe.exitCode != 0) {
            lastKaliDesktopDiagnostic = "Kali cannot reach the embedded X11 display :0. " + displayProbe.output.takeLast(2500)
            throw IllegalStateException(lastKaliDesktopDiagnostic)
        }

        File(tmpDir, ".X11-unix").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(tmpDir, ".ICE-unix").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        if (!startPulseAudio()) {
            Log.w(TAG, "Kali desktop will start without a confirmed audio sink")
        }

        val sessionScript = listOf(
            "set -Eeuo pipefail",
            "export HOME=/home/kali USER=kali LOGNAME=kali",
            "export DISPLAY=:0 LANG=C.UTF-8 TERM=xterm-256color",
            "export PULSE_SERVER=tcp:127.0.0.1:4713 PULSE_SINK=droiddesk_android PULSE_LATENCY_MSEC=80",
            "export MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_ENABLE_WAYLAND=0",
            "export XDG_RUNTIME_DIR=/tmp/runtime-kali",
            "export XDG_CONFIG_HOME=/home/kali/.config XDG_CACHE_HOME=/home/kali/.cache",
            "export XDG_DATA_HOME=/home/kali/.local/share XDG_DATA_DIRS=/usr/local/share:/usr/share",
            "export XDG_CURRENT_DESKTOP=XFCE XDG_SESSION_DESKTOP=xfce XDG_SESSION_TYPE=x11",
            "export NO_AT_BRIDGE=1 GTK_A11Y=none GTK_USE_PORTAL=0 GDK_BACKEND=x11",
            "export GIO_USE_VFS=gvfs GIO_USE_VOLUME_MONITOR=unix",
            "export GLYCIN_DISABLE_SANDBOX=i-know-the-risks",
            "rm -rf /tmp/runtime-kali",
            "install -d -m 0700 /tmp/runtime-kali",
            "install -d -m 1777 /tmp/.ICE-unix",
            "install -d -m 0755 /home/kali/.local/share/droiddesk",
            "install -d -m 0755 /home/kali/.cache/glycin",
            "touch /home/kali/.ICEauthority",
            "chmod 0600 /home/kali/.ICEauthority",
            "touch /home/kali/.local/share/droiddesk/xfce-session.log",
            "unset DBUS_SESSION_BUS_ADDRESS SESSION_MANAGER",
            "if command -v pactl >/dev/null 2>&1; then",
            "  pactl set-default-sink droiddesk_android >/dev/null 2>&1 || true",
            "  pactl set-sink-mute droiddesk_android 0 >/dev/null 2>&1 || true",
            "  if pactl info >/dev/null 2>&1; then echo \"[Kali Linux] Android audio bridge is connected\"; else echo \"[Kali Linux] Audio server is not responding yet\"; fi",
            "fi",
            "echo \"[Kali Linux] Starting XFCE with private D-Bus, audio and local GIO VFS...\"",
            "cd /home/kali",
            "if [ -x /usr/bin/dbus-run-session ]; then",
            "  exec /usr/bin/dbus-run-session -- /bin/bash -c 'printf \"%s\" \"${'$'}DBUS_SESSION_BUS_ADDRESS\" > /home/kali/.local/share/droiddesk/dbus-session-address; chmod 0600 /home/kali/.local/share/droiddesk/dbus-session-address; exec /usr/bin/startxfce4 >> /home/kali/.local/share/droiddesk/xfce-session.log 2>&1'",
            "else",
            "  exec /usr/bin/dbus-launch --exit-with-session /bin/bash -c 'printf \"%s\" \"${'$'}DBUS_SESSION_BUS_ADDRESS\" > /home/kali/.local/share/droiddesk/dbus-session-address; chmod 0600 /home/kali/.local/share/droiddesk/dbus-session-address; exec /usr/bin/startxfce4 >> /home/kali/.local/share/droiddesk/xfce-session.log 2>&1'",
            "fi",
        ).joinToString("\n")
        val guestCommand = listOf(
            "/usr/bin/env", "-i",
            "HOME=/home/kali", "USER=kali", "LOGNAME=kali",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "SHELL=/usr/bin/zsh", "TERM=xterm-256color", "LANG=C.UTF-8",
            "DISPLAY=:0", "PULSE_SERVER=tcp:127.0.0.1:4713",
            "PULSE_SINK=droiddesk_android", "PULSE_LATENCY_MSEC=80",
            "MOZ_DISABLE_CONTENT_SANDBOX=1", "MOZ_ENABLE_WAYLAND=0",
            "XDG_RUNTIME_DIR=/tmp/runtime-kali",
            "XDG_CURRENT_DESKTOP=XFCE", "XDG_SESSION_DESKTOP=xfce", "XDG_SESSION_TYPE=x11",
            "NO_AT_BRIDGE=1", "GTK_A11Y=none", "BASH_ENV=/dev/null",
            "/usr/bin/sudo", "-E", "-u", "kali", "/bin/bash", "-c", sessionScript,
        )
        val process = createKaliProotBuilder(rootfs, "/home/kali", guestCommand).start()
        sessionProcess = process
        Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine {
                    Log.d(TAG, "KALI PROOT DESKTOP: $it")
                }
            }.onFailure { Log.w(TAG, "Kali desktop output reader failed", it) }
        }, "KaliProotDesktopOutput").apply { isDaemon = true; start() }
        Thread({
            runCatching { process.waitFor() }
            if (sessionProcess === process) {
                sessionProcess = null
                stopPulseAudio()
                Log.w(TAG, "Kali XFCE process exited; the next desktop entry can start a fresh session")
            }
        }, "KaliProotDesktopExit").apply { isDaemon = true; start() }
        Log.i(TAG, "Started rootless Kali XFCE through DroidDesk PRoot")
    }

    /**
     * Check readiness inside Kali's PRoot namespace, not in native Termux.
     * A partially running desktop is not considered ready without its panel.
     */
    fun waitForKaliDesktopReady(rootfs: File, timeoutSeconds: Int = 70): Boolean {
        val probeScript = kaliDesktopHealthCheckScript(timeoutSeconds)
        val result = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/home/kali",
            guestCommand = listOf(
                "/usr/bin/env", "-i",
                "HOME=/home/kali", "USER=kali", "LOGNAME=kali",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=dumb", "DISPLAY=:0",
                "BASH_ENV=/dev/null", "/bin/bash", "-c", probeScript,
            ),
            timeoutMinutes = 2,
            onOutput = { line -> Log.i(TAG, line.trimEnd()) },
        )
        val sessionLog = File(rootfs, "home/kali/.local/share/droiddesk/xfce-session.log")
        val logTail = runCatching {
            if (sessionLog.isFile) sessionLog.readText().takeLast(8_000) else "XFCE session log has not been created"
        }.getOrDefault("Could not read the XFCE session log")
        if (result.exitCode != 0) {
            lastKaliDesktopDiagnostic = buildString {
                append(result.output.takeLast(3_500))
                append("\n--- XFCE session log ---\n")
                append(logTail)
            }.takeLast(10_000)
            Log.e(TAG, "Kali XFCE readiness probe failed: " + lastKaliDesktopDiagnostic)
            stopKaliDesktopProcesses(rootfs)
            sessionProcess?.let { stale ->
                runCatching { stale.destroy() }
                runCatching { if (!stale.waitFor(2, TimeUnit.SECONDS)) stale.destroyForcibly() }
            }
            sessionProcess = null
            stopPulseAudio()
            return false
        }
        lastKaliDesktopDiagnostic = ""
        return true
    }

    private fun prepareKaliXfceHomePermissions(rootfs: File) {
        val script = """
            set -Eeuo pipefail
            install -d -m 0755               /home/kali/.config/xfce4               /home/kali/.config/gtk-3.0               /home/kali/.config/fontconfig               /home/kali/.cache/sessions               /home/kali/.local/share/backgrounds               /home/kali/.local/share/droiddesk               /home/kali/Desktop
            chown -R kali:kali               /home/kali/.config/xfce4               /home/kali/.config/gtk-3.0               /home/kali/.config/fontconfig               /home/kali/.config/mimeapps.list               /home/kali/.cache/sessions               /home/kali/.local/share/backgrounds               /home/kali/.local/share/droiddesk               /home/kali/Desktop
        """.trimIndent()
        val prepared = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/root",
            guestCommand = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=dumb", "BASH_ENV=/dev/null",
                "/bin/bash", "-c", script,
            ),
            timeoutMinutes = 1,
            onOutput = { line -> Log.d(TAG, "Kali XFCE profile permissions: ${line.trimEnd()}") },
        )
        if (prepared.exitCode != 0) {
            lastKaliDesktopDiagnostic = "Could not make Kali XFCE home configuration writable: " +
                prepared.output.takeLast(2500)
            throw IllegalStateException(lastKaliDesktopDiagnostic)
        }
        Log.i(TAG, "Kali XFCE config, cache, desktop and wallpaper paths are owned by kali:kali")
    }

    private fun writeDebianLauncher() {
        val launcher = File(binDir, "start-debian")
        launcher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                env DISPLAY="${'$'}DISPLAY" TERM="${'$'}{TERM:-xterm-256color}" bash -l
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val appsLauncher = File(binDir, "debian-apps")
        appsLauncher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            mode="${'$'}{1:-gui}"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                bash -s -- "${'$'}mode" <<'DROIDDESK_DEBIAN_APPS'
            mode="${'$'}1"
            case "${'$'}mode" in
                --all)
                    dpkg-query -W -f='${'$'}{binary:Package}\t${'$'}{Version}\n' | sort
                    ;;
                --manual)
                    apt-mark showmanual | sort
                    ;;
                gui)
                    for desktop in \
                        /usr/share/applications/*.desktop \
                        /usr/local/share/applications/*.desktop
                    do
                        [ -f "${'$'}desktop" ] || continue
                        no_display=${'$'}(sed -n 's/^NoDisplay=//p' "${'$'}desktop" | head -n 1)
                        [ "${'$'}no_display" = "true" ] && continue
                        name=${'$'}(sed -n 's/^Name=//p' "${'$'}desktop" | head -n 1)
                        command=${'$'}(sed -n 's/^Exec=//p' "${'$'}desktop" | head -n 1)
                        [ -n "${'$'}name" ] && [ -n "${'$'}command" ] &&
                            printf '%-32s %s\n' "${'$'}name" "${'$'}command"
                    done | sort -f
                    ;;
                *)
                    echo "Usage: debian-apps [--manual|--all]" >&2
                    exit 2
                    ;;
            esac
            DROIDDESK_DEBIAN_APPS
            """.trimIndent() + "\n",
        )
        appsLauncher.setExecutable(true, false)
    }

    private fun clearProotDownloadCache() {
        File(prefixDir, "var/lib/proot-distro/dlcache").deleteRecursively()
        File(prefixDir, "var/lib/proot-distro/cache").deleteRecursively()
    }

    private fun debianRootfsMarkers(): List<File> = listOf(
        // proot-distro 5.4+
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/usr/lib/os-release"),
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/etc/os-release"),
        // Legacy proot-distro releases
        File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian/etc/os-release"),
    )

    // ── Bootstrap ──

    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")
        listOf(prefixDir, binDir, libDir, tmpDir, homeDir).forEach { it.mkdirs() }
        Log.i(TAG, "Bootstrap directories ready. Base: ${baseDir.absolutePath}")
    }

    fun extractBootstrapIfNeeded(context: Context) {
        val bashBin = File(prefixDir, "bin/bash")
        if (bashBin.exists()) {
            Log.i(TAG, "Bootstrap already extracted at ${prefixDir.absolutePath}")
            // Refresh wrapper/config in case the app was updated
            createAptConfigOverride()
            ensureAptDirectories()
            wrapDpkgForPath()
            wrapUpdateAlternatives()
            ensureSocketHookPrebuilt()
            return
        }

        val marker = File(baseDir, BOOTSTRAP_MARKER)
        if (marker.exists()) {
            marker.delete()
        }

        Log.i(TAG, "Extracting bootstrap from assets to ${prefixDir.absolutePath}...")

        // Remove any partial extraction to ensure a clean bootstrap
        if (prefixDir.exists()) {
            prefixDir.deleteRecursively()
        }
        prefixDir.mkdirs()

        // Flutter assets are located under "flutter_assets/" in the APK asset tree
        val assetName = "flutter_assets/assets/bootstrap-aarch64.zip"
        val tmpZip = File(tmpDir, "bootstrap-aarch64.zip")
        tmpZip.parentFile?.mkdirs()

        try {
            context.assets.open(assetName).use { input ->
                tmpZip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bootstrap asset: ${e.message}", e)
            return
        }

                // Extract the bootstrap and restore its symlinks safely.
        try {
            extractZip(tmpZip, prefixDir)
            restoreSymlinks(prefixDir)
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap extraction failed: ${e.message}", e)
            prefixDir.deleteRecursively()
            tmpZip.delete()
            return
        }

        // Create apt config override so apt uses our prefix
        createAptConfigOverride()

        // Ensure apt cache/state directories exist (bootstrap zip may omit empty dirs)
        ensureAptDirectories()

        // Set executable permissions on binaries
        setExecutableRecursively(binDir)
        setExecutableRecursively(File(prefixDir, "libexec"))

        // Wrap dpkg so it and its children (e.g. dpkg-split) always see the right PATH/env
        wrapDpkgForPath()

        // Wrap update-alternatives so its postinst invocations don't fail under
        // our relocated root (it otherwise double-prefixes absolute paths).
        wrapUpdateAlternatives()

        // Copy prebuilt socket hook from jniLibs to prefix/lib
        ensureSocketHookPrebuilt()

        marker.writeText("DroidDesk native bootstrap\n")
        tmpZip.delete()
        Log.i(TAG, "Bootstrap extraction complete")
    }

    private fun ensureSocketHookPrebuilt() {
        try {
            val libDir = File(prefixDir, "lib")
            libDir.mkdirs()
            val destHook = File(libDir, "libsocket_hook.so")
            if (destHook.exists()) return

            // Find the hook in jniLibs
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val jniDir = File(context.applicationInfo.nativeLibraryDir)
            val srcHook = File(jniDir, "libsocket_hook.so")
            if (srcHook.exists()) {
                srcHook.inputStream().use { input ->
                    destHook.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destHook.setExecutable(true, false)
                Log.i(TAG, "Copied prebuilt socket hook to ${destHook.absolutePath}")
            } else {
                Log.w(TAG, "Prebuilt socket hook not found in jniLibs at ${srcHook.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy socket hook: ${e.message}")
        }
    }

        private fun extractZip(zipFile: File, destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("Could not create ZIP destination directory")
        }

        if (!destDir.isDirectory) {
            throw IllegalStateException("ZIP destination is not a directory")
        }

        val canonicalRoot = destDir.canonicalFile
        val rootPath = canonicalRoot.path
        val rootPrefix = rootPath + File.separator

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry

            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                val segments = entryName.split('/')

                if (
                    entryName.isBlank() ||
                    entryName.startsWith("/") ||
                    Regex("^[A-Za-z]:").containsMatchIn(entryName) ||
                    segments.any { it == ".." }
                ) {
                    throw SecurityException("Blocked unsafe ZIP entry: ${entry.name}")
                }

                val outFile = File(canonicalRoot, entryName).canonicalFile
                val outputPath = outFile.path

                if (
                    outputPath != rootPath &&
                    !outputPath.startsWith(rootPrefix)
                ) {
                    throw SecurityException("ZIP entry escaped destination: ${entry.name}")
                }

                if (outputPath == rootPath && !entry.isDirectory) {
                    throw SecurityException("ZIP entry targets destination root")
                }

                if (outputPath != rootPath) {
                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            throw IllegalStateException("Could not create ZIP directory")
                        }

                        if (!outFile.isDirectory) {
                            throw IllegalStateException("ZIP directory conflicts with a file")
                        }
                    } else {
                        val parent = outFile.parentFile
                            ?: throw IllegalStateException("ZIP entry has no parent directory")

                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IllegalStateException("Could not create ZIP entry parent")
                        }

                        if (!parent.isDirectory || outFile.isDirectory) {
                            throw IllegalStateException("Invalid ZIP extraction target")
                        }

                        outFile.outputStream().use { output ->
                            zis.copyTo(output)
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun createAptConfigOverride() {
        try {
            val aptConfDir = File(prefixDir, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val confFile = File(aptConfDir, "99-droiddesk-paths.conf")
            confFile.writeText(
                """
                Dir "${prefixDir.absolutePath}";
                Dir::Etc "${prefixDir.absolutePath}/etc/apt";
                Dir::State "${prefixDir.absolutePath}/var/lib/apt";
                Dir::State::dpkg "${prefixDir.absolutePath}/var/lib/dpkg";
                Dir::Cache "${prefixDir.absolutePath}/var/cache/apt";
                Dir::Log "${prefixDir.absolutePath}/var/log/apt";
                Dir::Bin::Methods "${prefixDir.absolutePath}/lib/apt/methods";
                Dir::Bin::dpkg "${prefixDir.absolutePath}/bin/dpkg";
                Dir::Bin::apt-key "${prefixDir.absolutePath}/bin/apt-key";
                Acquire::gpgv::Options:: "--homedir=${prefixDir.absolutePath}/etc/apt/trusted.gpg.d";
                Acquire::Retries "3";
                Acquire::http::Timeout "30";
                Acquire::https::Timeout "30";
                DPkg::Lock::Timeout "60";
                """.trimIndent()
            )
            Log.i(TAG, "Created apt config override at ${confFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create apt config override: ${e.message}")
        }
    }

    private fun wrapDpkgForPath() {
        try {
            val dpkgBin = File(prefixDir, "bin/dpkg")
            val dpkgReal = File(prefixDir, "bin/dpkg.real")
            val relocateShebangs = File(prefixDir, "bin/droiddesk-relocate-shebangs")

            // Build a mini root tree so dpkg can use Termux-style paths internally
            // while the actual files land in our private prefix.
            val dpkgRoot = File(baseDir, "dpkgroot")
            mapOf(
                File(dpkgRoot, "data/data/com.termux/files/usr") to prefixDir.absolutePath,
                File(dpkgRoot, "var/lib/dpkg") to File(prefixDir, "var/lib/dpkg").absolutePath,
                File(dpkgRoot, "tmp") to tmpDir.absolutePath
            ).forEach { (linkDir, target) ->
                linkDir.parentFile?.mkdirs()
                // Remove an existing symlink so we can update its target on app upgrades.
                if (linkDir.exists()) {
                    linkDir.delete()
                }
                if (!linkDir.exists()) {
                    android.system.Os.symlink(target, linkDir.absolutePath)
                }
            }

            // Make sure the real dpkg binary is saved as dpkg.real, then always
            // rewrite the wrapper so updates take effect on app upgrade/reinstall.
            if (!dpkgReal.exists() && dpkgBin.exists()) {
                dpkgBin.renameTo(dpkgReal)
            }

            // dpkg enumerates its compile-time configuration directory through
            // libc APIs that are not consistently intercepted by the path hook
            // on every Android build. Point that one embedded path at the
            // wrapper's working directory instead.
            patchEmbeddedCommand(
                dpkgReal,
                "/data/data/com.termux/files/usr/etc/dpkg",
                "/proc/self/cwd/etc/dpkg",
            )

            // Package installations started inside the XFCE terminal bypass the
            // Kotlin installation flow. Run this after every dpkg transaction so
            // newly unpacked scripts never keep Termux's inaccessible prefix.
            // dpkg unpacks and configures in separate dpkg runs, so maintainer
            // scripts are fixed before they execute. The whole script is
            // rewritten, not just the #! line: bodies such as cups' postinst
            // ("mkdir -p /data/data/com.termux/...") or the code-oss and
            // hangover-wine launchers otherwise fail with "Permission denied".
            relocateShebangs.writeText(
                """
                #!/system/bin/sh
                old_prefix="/data/data/com.termux/files/usr"
                new_prefix="${prefixDir.absolutePath}"
                old_home="/data/data/com.termux/files/home"
                new_home="${homeDir.absolutePath}"
                # "--all" rescans everything once (repairs older installs);
                # otherwise only files changed since the dpkg run began.
                if [ "${'$'}1" = "--all" ]; then
                    changed=""
                else
                    [ -f "${'$'}1" ] || exit 0
                    changed="-cnewer ${'$'}1"
                fi
                for root in \
                    "${prefixDir.absolutePath}/bin" \
                    "${prefixDir.absolutePath}/libexec" \
                    "${prefixDir.absolutePath}/lib" \
                    "${prefixDir.absolutePath}/opt" \
                    "${prefixDir.absolutePath}/share" \
                    "${prefixDir.absolutePath}/var/lib/dpkg/info"
                do
                    [ -d "${'$'}root" ] || continue
                    "${prefixDir.absolutePath}/bin/find" "${'$'}root" \
                        -type f -perm -100 -size -2M ${'$'}changed \
                        ! -name droiddesk-relocate-shebangs \
                        -exec "${prefixDir.absolutePath}/bin/grep" -lIF "/data/data/com.termux/files" {} + 2>/dev/null |
                    while IFS= read -r file; do
                        first_line=$("${prefixDir.absolutePath}/bin/head" -n 1 "${'$'}file" 2>/dev/null)
                        # dpkg's *.list/*.md5sums must keep Termux paths; only
                        # maintainer scripts and other #! scripts are rewritten.
                        case "${'$'}file:${'$'}first_line" in
                            "${prefixDir.absolutePath}/var/lib/dpkg/info/"*.list:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.md5sums:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.conffiles:*)
                                ;;
                            "${prefixDir.absolutePath}/var/lib/dpkg/info/"*inst:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*rm:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.config:*|*":#!"*)
                                "${prefixDir.absolutePath}/bin/sed" -i \
                                    -e "s|${'$'}old_prefix|${'$'}new_prefix|g" \
                                    -e "s|${'$'}old_home|${'$'}new_home|g" "${'$'}file"
                                ;;
                        esac
                    done
                done
                exit 0
                """.trimIndent() + "\n",
            )
            relocateShebangs.setExecutable(true, false)

            val wrapper = """
                #!/system/bin/sh
                export PATH="${prefixDir.absolutePath}/bin:${'$'}PATH"
                export LD_LIBRARY_PATH="${prefixDir.absolutePath}/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
                export LD_PRELOAD="${prefixDir.absolutePath}/lib/libsocket_hook.so${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
                # dpkg requires admindir to be inside root. Strip any caller-provided
                # --root/--admindir (and their values) and prepend our own before any
                # trailing filenames/apt separators so dpkg parses them as options.
                caller_dir="${'$'}PWD"
                args=""
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        --admindir=*|--root=*)
                            ;;
                        --admindir|--root)
                            shift
                            ;;
                        *)
                            arg="${'$'}1"
                            case "${'$'}arg" in
                                /*)
                                    ;;
                                *)
                                    if [ -e "${'$'}caller_dir/${'$'}arg" ]; then
                                        arg="${'$'}caller_dir/${'$'}arg"
                                    fi
                                    ;;
                            esac
                            args="${'$'}args ${'$'}arg"
                            ;;
                    esac
                    shift
                done
                cd "${prefixDir.absolutePath}" || exit 1
                scan_marker="${tmpDir.absolutePath}/dpkg-shebang-scan-${'$'}${'$'}"
                : > "${'$'}scan_marker"
                "${dpkgReal.absolutePath}" --force-not-root --force-script-chrootless --root="${dpkgRoot.absolutePath}" --admindir="${dpkgRoot.absolutePath}/var/lib/dpkg" ${'$'}args
                status=${'$'}?
                "${relocateShebangs.absolutePath}" "${'$'}scan_marker"
                rm -f "${'$'}scan_marker"
                exit ${'$'}status
            """.trimIndent()

            dpkgBin.writeText(wrapper)
            dpkgBin.setExecutable(true, false)
            Log.i(TAG, "Installed dpkg wrapper at ${dpkgBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install dpkg wrapper: ${e.message}")
        }
    }

    private fun wrapUpdateAlternatives() {
        try {
            val uaBin = File(prefixDir, "bin/update-alternatives")
            val uaReal = File(prefixDir, "bin/update-alternatives.real")

            if (!uaBin.exists() || uaReal.exists()) return

            uaBin.renameTo(uaReal)
            uaBin.writeText(
                """
                #!/system/bin/sh
                # update-alternatives is not needed for DroidDesk's single-prefix
                # environment and fails when dpkg is run with a relocated root.
                exit 0
                """.trimIndent()
            )
            uaBin.setExecutable(true, false)
            Log.i(TAG, "Installed update-alternatives wrapper at ${uaBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update-alternatives wrapper: ${e.message}")
        }
    }

    private fun ensureAptDirectories() {
        listOf(
            "etc/dpkg/dpkg.cfg.d",
            "var/cache/apt/archives/partial",
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { relativePath ->
            File(prefixDir, relativePath).mkdirs()
        }
        // dpkg requires these files to exist even if empty
        listOf(
            "var/lib/dpkg/status",
            "var/lib/dpkg/available",
            "var/lib/dpkg/diversions"
        ).forEach { relativePath ->
            val f = File(prefixDir, relativePath)
            if (!f.exists()) f.createNewFile()
        }
        Log.i(TAG, "Ensured apt/dpkg cache/state directories exist")
    }

    private fun setExecutableRecursively(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
            }
        }
    }

        private fun restoreSymlinks(prefixDir: File) {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.isFile) {
            Log.w(TAG, "SYMLINKS.txt not found, skipping symlink restoration")
            return
        }

        val canonicalRoot = prefixDir.canonicalFile
        val rootPath = canonicalRoot.path
        val rootPrefix = rootPath + File.separator
        val termuxPrefix = "/data/data/com.termux/files/usr"

        val allowedExternalRoots = listOf(
            "/system",
            "/apex",
            "/vendor",
            "/product",
            "/odm",
            "/proc",
            "/dev",
            "/sys"
        )

        fun isInsidePrefix(file: File): Boolean {
            val path = file.canonicalPath
            return path == rootPath || path.startsWith(rootPrefix)
        }

        var created = 0

        symlinksFile.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            val arrow = "\u2190"
            val arrowIndex = trimmed.indexOf(arrow)

            if (arrowIndex <= 0 || arrowIndex != trimmed.lastIndexOf(arrow)) {
                throw SecurityException("Malformed symlink entry at line ${index + 1}")
            }

            val targetPath = trimmed.substring(0, arrowIndex).trim()
            val linkPath = trimmed.substring(arrowIndex + arrow.length).trim()

            if (
                targetPath.isBlank() ||
                linkPath.isBlank() ||
                targetPath.any { it.isISOControl() } ||
                linkPath.any { it.isISOControl() } ||
                targetPath.contains('\\') ||
                linkPath.contains('\\')
            ) {
                throw SecurityException("Invalid symlink entry at line ${index + 1}")
            }

            if (!linkPath.startsWith("./")) {
                throw SecurityException("Symlink path must be relative at line ${index + 1}")
            }

            val cleanLinkPath = linkPath.removePrefix("./")
            val segments = cleanLinkPath.split('/')

            if (
                cleanLinkPath.isBlank() ||
                segments.any { it.isBlank() || it == "." || it == ".." }
            ) {
                throw SecurityException("Unsafe symlink path at line ${index + 1}")
            }

            val linkFile = File(canonicalRoot, cleanLinkPath).canonicalFile

            if (
                linkFile.path == rootPath ||
                !linkFile.path.startsWith(rootPrefix)
            ) {
                throw SecurityException("Symlink escaped prefix at line ${index + 1}")
            }

            val linkParent = linkFile.parentFile
                ?: throw SecurityException("Symlink has no parent directory")

            if (!linkParent.exists() && !linkParent.mkdirs()) {
                throw IllegalStateException("Could not create symlink parent directory")
            }

            if (!linkParent.isDirectory) {
                throw IllegalStateException("Symlink parent is not a directory")
            }

            if (
                linkFile.exists() ||
                java.nio.file.Files.isSymbolicLink(linkFile.toPath())
            ) {
                throw SecurityException("Symlink destination already exists")
            }

            val newTarget = when {
                targetPath == termuxPrefix ||
                    targetPath.startsWith("$termuxPrefix/") -> {

                    val suffix = targetPath
                        .removePrefix(termuxPrefix)
                        .removePrefix("/")

                    val mappedTarget = if (suffix.isEmpty()) {
                        canonicalRoot
                    } else {
                        File(canonicalRoot, suffix).canonicalFile
                    }

                    if (!isInsidePrefix(mappedTarget)) {
                        throw SecurityException("Symlink target escaped prefix")
                    }

                    mappedTarget.path
                }

                targetPath.startsWith("/") -> {
                    val canonicalTarget = File(targetPath).canonicalFile

                    val allowed = allowedExternalRoots.any {
                        canonicalTarget.path == it ||
                            canonicalTarget.path.startsWith(it + File.separator)
                    }

                    if (!allowed) {
                        throw SecurityException("Blocked external symlink target")
                    }

                    canonicalTarget.path
                }

                else -> {
                    val resolvedTarget = File(linkParent, targetPath).canonicalFile

                    if (!isInsidePrefix(resolvedTarget)) {
                        throw SecurityException("Relative symlink target escaped prefix")
                    }

                    targetPath
                }
            }

            android.system.Os.symlink(newTarget, linkFile.absolutePath)
            created++
        }

        Log.i(TAG, "Restored $created symlinks")
    }

    // ── Shebang Patching ──
    fun patchShebangs(force: Boolean = false) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        if (oldPrefix == newPrefix) return

        val markerFile = File(prefixDir, SHEBANG_MARKER)
        val migrating = !markerFile.exists()
        if (!force && !migrating) {
            Log.i(TAG, "Shebangs already patched, skipping")
            return
        }

        Log.i(TAG, "Patching shebangs: $oldPrefix -> $newPrefix")
        var patchCount = 0

        val dirsToScan = listOf("bin", "libexec", "share", "etc", "var/lib/dpkg/info")
        for (dirName in dirsToScan) {
            val dir = File(prefixDir, dirName)
            if (!dir.exists()) continue

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.canRead()) {
                    try {
                        val bytes = file.inputStream().use {
                            val buf = ByteArray(256)
                            val n = it.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }

                        val isScript = bytes.size >= 2 &&
                            bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()
                        val isPathConfig = file.extension.lowercase() in setOf(
                            "service", "desktop", "conf", "xml", "pc", "cmake",
                            "la", "prl", "sh", "pl", "py", "rb", "json", "ini",
                        )
                        if (isScript || isPathConfig) {
                            val content = file.readText()
                            if (content.contains(oldPrefix)) {
                                val updated = content.replace(oldPrefix, newPrefix)
                                file.writeText(updated)
                                patchCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore binary or read-only files
                    }
                }
            }
        }
        // New dpkg runs relocate their own files; the full scan is a one-time migration.
        if (migrating) relocateAllScripts()
        markerFile.writeText("done")
        Log.i(TAG, "Patched $patchCount scripts.")
    }

    private fun hasUnfinishedPackages(): Boolean = runCatching {
        File(prefixDir, "var/lib/dpkg/status").useLines { lines ->
            lines.any { it.startsWith("Status: ") && !it.endsWith(" installed") && !it.endsWith(" not-installed") && !it.endsWith(" config-files") }
        }
    }.getOrDefault(false)

    /**
     * Older releases only relocated the #! line of new scripts, so script bodies (and launchers
     * under lib/ and opt/) could still point at Termux and fail with "Permission denied".
     */
    private fun relocateAllScripts() {
        val relocate = File(prefixDir, "bin/droiddesk-relocate-shebangs")
        if (!relocate.canExecute()) return
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", relocate.absolutePath, "--all")
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(getTermuxEnv())
                }
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }.onFailure { Log.w(TAG, "Full script relocation failed: ${it.message}") }
    }

    /** Relocate commands that Xfce compiled as absolute Termux paths. */
    private fun patchEmbeddedXfcePaths() {
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-session"),
            "/data/data/com.termux/files/usr/bin/iceauth",
            "iceauth",
        )
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-panel"),
            "/data/data/com.termux/files/usr/lib/xfce4/panel/migrate",
            "migrate",
        )
    }

    private fun patchEmbeddedCommand(file: File, oldCommand: String, newCommand: String) {
        if (!file.exists() || newCommand.length > oldCommand.length) return
        val oldValue = oldCommand.toByteArray()
        val newValue = newCommand.toByteArray()
        val bytes = file.readBytes()
        var patched = false
        var offset = 0
        while (offset <= bytes.size - oldValue.size) {
            var matches = true
            for (index in oldValue.indices) {
                if (bytes[offset + index] != oldValue[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                bytes.fill(0, offset, offset + oldValue.size)
                newValue.copyInto(bytes, offset)
                patched = true
                offset += oldValue.size
            } else {
                offset++
            }
        }
        if (patched) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            Log.i(TAG, "Relocated embedded command in ${file.name}: $newCommand")
        }
    }

    // ── ELF RUNPATH/RPATH Patching ──

    fun patchElfRunpaths(prefixDir: File) {
        val marker = File(prefixDir, ELF_PATCH_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "ELF runpaths already patched, skipping")
            return
        }

        val libDir = File(prefixDir, "lib")
        if (!libDir.exists()) {
            Log.w(TAG, "lib directory not found, skipping ELF patch")
            return
        }

        val oldPath = "/data/data/com.termux/files/usr/lib"
        val newPath = libDir.absolutePath
        val driOldPath = "/data/data/com.termux/files/usr/lib/dri"

        var patched = 0
        libDir.walkTopDown()
            .filter { it.isFile && it.canRead() && isElf64(it) }
            .forEach { file ->
                try {
                    if (patchElfFile(file, oldPath, newPath, driOldPath)) {
                        patched++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch ${file.absolutePath}: ${e.message}")
                }
            }

        marker.writeText("patched $patched files")
        Log.i(TAG, "Patched ELF runpaths in $patched files")
    }

    private fun isElf64(file: File): Boolean {
        if (file.length() < 6) return false
        return file.inputStream().use { input ->
            val buf = ByteArray(6)
            val n = input.read(buf)
            if (n < 6) return@use false
            buf[EI_MAG0] == ELFMAG0 &&
                    buf[EI_MAG1] == ELFMAG1 &&
                    buf[EI_MAG2] == ELFMAG2 &&
                    buf[EI_MAG3] == ELFMAG3 &&
                    buf[EI_CLASS] == ELFCLASS64 &&
                    buf[EI_DATA] == ELFDATA2LSB
        }
    }

    private fun patchElfFile(file: File, oldPath: String, newPath: String, driOldPath: String): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 64) return false

        val ePhoff = getLongLe(bytes, E_PHOFF_OFFSET)
        val ePhentsize = getShortLe(bytes, E_PHENTSIZE_OFFSET).toInt() and 0xFFFF
        val ePhnum = getShortLe(bytes, E_PHNUM_OFFSET).toInt() and 0xFFFF

        if (ePhoff < 0 || ePhoff + ePhnum * ePhentsize.toLong() > bytes.size) return false

        var strTabAddr: Long? = null
        var strTabSize: Long? = null
        val runpathOffsets = mutableListOf<Long>()
        val rpathOffsets = mutableListOf<Long>()

        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_DYNAMIC) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (pOffset < 0 || pOffset + pFilesz > bytes.size) continue

                val dynCount = pFilesz / DYN_SIZE
                for (j in 0 until dynCount) {
                    val dynOffset = (pOffset + j * DYN_SIZE).toInt()
                    val dTag = getLongLe(bytes, dynOffset + D_TAG_OFFSET)
                    val dVal = getLongLe(bytes, dynOffset + D_VAL_OFFSET)
                    when (dTag) {
                        DT_STRTAB -> strTabAddr = dVal
                        DT_STRSZ -> strTabSize = dVal
                        DT_RPATH -> rpathOffsets.add(dVal)
                        DT_RUNPATH -> runpathOffsets.add(dVal)
                        DT_NULL -> break
                    }
                }
            }
        }

        if (strTabAddr == null || strTabSize == null || strTabSize <= 0) return false

        var strTabFileOffset: Long? = null
        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_LOAD) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pVaddr = getLongLe(bytes, phOffset + P_VADDR_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (strTabAddr >= pVaddr && strTabAddr < pVaddr + pFilesz) {
                    strTabFileOffset = pOffset + (strTabAddr - pVaddr)
                    break
                }
            }
        }

        if (strTabFileOffset == null) return false

        val origin = "\$ORIGIN"
        val relativeLib = "\$ORIGIN/../lib"
        val replacement = when {
            newPath.length <= oldPath.length -> newPath
            file.parentFile?.absolutePath == newPath -> origin
            else -> relativeLib
        }
        var modified = false

        for (offset in runpathOffsets + rpathOffsets) {
            val fileOffset = (strTabFileOffset + offset).toInt()
            if (fileOffset < 0 || fileOffset >= bytes.size) continue
            val endOffset = minOf((strTabFileOffset + strTabSize).toInt(), bytes.size)
            val current = readNullTerminatedString(bytes, fileOffset, endOffset)

            when {
                current == driOldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, origin, current.length)
                    modified = true
                }
                current == oldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, replacement, current.length)
                    modified = true
                }
                current.contains(driOldPath) -> {
                    val replaced = current.replace(driOldPath, origin)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
                current.contains(oldPath) -> {
                    val replaced = current.replace(oldPath, replacement)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
            }
        }

        if (modified) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            return true
        }
        return false
    }

    private fun readNullTerminatedString(bytes: ByteArray, start: Int, end: Int): String {
        var i = start
        while (i < end && bytes[i] != 0.toByte()) i++
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    private fun writeNullTerminatedString(bytes: ByteArray, offset: Int, value: String, padTo: Int) {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        for (i in valueBytes.indices) {
            bytes[offset + i] = valueBytes[i]
        }
        for (i in valueBytes.size until padTo) {
            bytes[offset + i] = 0
        }
    }

    private fun getLongLe(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun getIntLe(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 3 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return result
    }

    private fun getShortLe(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset + 1].toInt() and 0xFF) shl 8 or (bytes[offset].toInt() and 0xFF)).toShort()
    }

    // ── C Socket Hook Native Compilation ──
    fun compileSocketHook() {
        val hookC = File(tmpDir, "socket_hook.c")
        val hookBuildC = File(tmpDir, "socket_hook_build.c")
        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        val buildMarker = File(prefixDir, "lib/.socket_hook_build")
        File(prefixDir, "lib").mkdirs()

        try {
            context.assets.open("flutter_assets/assets/socket_hook.c").use { input ->
                hookC.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy socket_hook.c asset: ${e.message}")
            return
        }

        val buildSignature = java.security.MessageDigest.getInstance("SHA-256")
            .digest((hookC.readText() + "\n" + prefixDir.absolutePath).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hookSo.isFile && buildMarker.readTextOrNull() == buildSignature) {
            Log.d(TAG, "Native socket hook is current; skipping compilation")
            return
        }

        hookBuildC.writeText(
            """
            #define _GNU_SOURCE
            #define NEW_PREFIX "${prefixDir.absolutePath}"
            #include "${hookC.absolutePath}"
            """.trimIndent()
        )

        val clang = File(prefixDir, "bin/clang")
        if (clang.exists()) {
            Log.i(TAG, "Compiling socket_hook.c natively using clang...")
            val compileCmd = listOf(
                clang.absolutePath,
                "-shared", "-fPIC",
                hookBuildC.absolutePath,
                "-I", tmpDir.absolutePath,
                "-o", hookSo.absolutePath,
                "-ldl", "-llog"
            )
            try {
                val pb = ProcessBuilder(compileCmd)
                    .redirectErrorStream(true)
                    .also {
                        it.environment().clear()
                        it.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                        it.environment()["PATH"] = "${prefixDir.absolutePath}/bin:${System.getenv("PATH")}"
                        it.environment()["TMPDIR"] = tmpDir.absolutePath
                    }
                val process = pb.start()
                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    buildMarker.writeText(buildSignature)
                    Log.i(TAG, "Native compilation of libsocket_hook.so successful!")
                } else {
                    Log.e(TAG, "Native compilation failed (code $exitCode): $log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compiling natively: ${e.message}")
            }
        } else {
            Log.w(TAG, "clang binary not found yet. Cannot compile socket_hook.c.")
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    // ── Environment Configuration ──

    private fun getTermuxEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["EXTERNAL_STORAGE"] = System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"

        env["PREFIX"] = prefixDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        // proot-distro 5.x derives all container paths from these variables.
        // Without them it falls back to Termux's original com.termux sandbox.
        env["TERMUX_APP__PACKAGE_NAME"] = context.packageName
        env["TERMUX__PREFIX"] = prefixDir.absolutePath
        env["TERMUX__HOME"] = homeDir.absolutePath
        env["TERMUX_VERSION"] = "DroidDesk"
        env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
        env["PATH"] = listOf(
            "${prefixDir.absolutePath}/bin",
            "${prefixDir.absolutePath}/lib/xfce4/panel",
            System.getenv("PATH") ?: "/system/bin",
        ).joinToString(":")
        env["HOME"] = homeDir.absolutePath
        // VTE-based terminals use SHELL to spawn their child process. Android's
        // account database points at /system/bin/sh, which is not the relocated
        // Termux userspace expected by XFCE Terminal.
        env["SHELL"] = File(binDir, "bash").absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"

        env["DISPLAY"] = ":0"
        env["XDG_RUNTIME_DIR"] = tmpDir.absolutePath
        // Keep XFCE applications on DroidDesk-local PulseAudio.
        env["PULSE_SERVER"] = "tcp:127.0.0.1:4713"
        env["PULSE_RUNTIME_PATH"] = File(tmpDir, "pulse").absolutePath

        env["PERL5LIB"] = "${prefixDir.absolutePath}/lib/perl5/core_perl:${prefixDir.absolutePath}/lib/perl5/site_perl:${prefixDir.absolutePath}/lib/perl5/vendor_perl:${prefixDir.absolutePath}/lib/perl5"
        env["PYTHONHOME"] = prefixDir.absolutePath
        env["PIP_CONFIG_FILE"] = "${prefixDir.absolutePath}/etc/pip.conf"
        env["XDG_DATA_DIRS"] = "${prefixDir.absolutePath}/share"
        // Prevent GLib from falling back to the inaccessible original Termux helper path.
        env["GIO_LAUNCH_DESKTOP"] =
            File(prefixDir, "libexec/gio-launch-desktop").absolutePath
        // Xfce validates that its compile-time SYSCONFDIR is present literally,
        // even though actual file access is relocated to this app's prefix.
        env["XDG_CONFIG_DIRS"] = listOf(
            "${prefixDir.absolutePath}/etc/xdg",
            "${prefixDir.absolutePath}/etc",
            "/data/data/com.termux/files/usr/etc",
        ).joinToString(":")
        env["GDK_PIXBUF_MODULEDIR"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders"
        env["GDK_PIXBUF_MODULE_FILE"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"

        // The official Mesa package contains both Zink and llvmpipe. Qualcomm
        // devices pair Zink with the Freedreno/Turnip Vulkan ICD; every other
        // GPU uses llvmpipe and must not inherit a vendor Vulkan selection.
        env["LIBGL_DRIVERS_PATH"] = "${prefixDir.absolutePath}/lib/dri"
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            env["VK_ICD_FILENAMES"] = freedrenoIcd.absolutePath
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["GALLIUM_DRIVER"] = "zink"
        } else {
            env["LIBGL_ALWAYS_SOFTWARE"] = "true"
            // Mesa exposes llvmpipe through swrast_dri.so. "llvmpipe" is the
            // Gallium driver name, not a DRI loader module name.
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "swrast"
            env["GALLIUM_DRIVER"] = "llvmpipe"
        }

        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${tmpDir.absolutePath}/dbus-session"

        env["DPKG_ADMINDIR"] = "${prefixDir.absolutePath}/var/lib/dpkg"
        env["APT_CONFIG"] = "${prefixDir.absolutePath}/etc/apt/apt.conf.d/99-droiddesk-paths.conf"

        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        if (hookSo.exists()) {
            env["LD_PRELOAD"] = hookSo.absolutePath
        }

        return env
    }

    /**
     * Keep the graphics stack aligned with the device GPU.
     *
     * The old TUR mesa-zink package conflicts with and removes the current Mesa
     * package. That left non-Adreno devices configured for llvmpipe without the
     * llvmpipe driver. Installing official Mesa on every device provides both
     * Zink and llvmpipe; only Adreno devices additionally need Turnip/Freedreno.
     */
    private fun ensureGraphicsPackages(
        onProgress: ((Double, String) -> Unit)? = null,
        progress: Double = 0.72,
    ): Boolean {
        if (!isDpkgPackageInstalled("mesa")) {
            onProgress?.invoke(progress, "Installing Mesa graphics runtime...")
            // libc++ too: apt keeps an older installed copy, which Mesa can't load against.
            if (!installPackageGroup("pkg install -y mesa libc++")) {
                Log.e(TAG, "Mesa graphics runtime installation failed")
                return false
            }
        }
        if (mesaNeedsNewerLibcxx()) {
            onProgress?.invoke(progress, "Updating the C++ runtime for graphics...")
            Log.w(TAG, "Mesa needs a newer libc++ than installed; upgrading it")
            if (!installPackageGroup("pkg install -y libc++") || mesaNeedsNewerLibcxx()) {
                Log.e(TAG, "libc++ is still too old for Mesa; OpenGL will be unavailable")
            }
        }

        if (hasAdrenoGpu()) {
            val freedrenoIcd = File(
                prefixDir,
                "share/vulkan/icd.d/freedreno_icd.aarch64.json",
            )
            if (!isDpkgPackageInstalled("mesa-vulkan-icd-freedreno") || !freedrenoIcd.exists()) {
                onProgress?.invoke(progress, "Installing Adreno Turnip acceleration...")
                if (!installPackageGroup("pkg install -y mesa-vulkan-icd-freedreno")) {
                    Log.e(TAG, "Adreno Turnip/Freedreno installation failed")
                    return false
                }
            }
            Log.i(TAG, "Graphics mode: Mesa Zink with Turnip/Freedreno")
        } else {
            Log.i(TAG, "Graphics mode: Mesa llvmpipe software rendering")
        }
        return true
    }

    /**
     * Mesa from the current repo links against libc++ symbols that older installed
     * libc++_shared.so builds don't export (seen: std::__ndk1::__hash_memory). Then
     * libGLX_mesa fails to load and every OpenGL client gets no context at all.
     */

    private fun mesaNeedsNewerLibcxx(): Boolean {
        val lib = File(prefixDir, "lib")
        val libcxx = File(lib, "libc++_shared.so")
        if (!libcxx.isFile) return false
        val mesaLibs = (lib.listFiles { f -> f.name.startsWith("libgallium-") && f.name.endsWith(".so") }
            ?.toList().orEmpty()) + listOf(File(lib, "libvulkan_freedreno.so")).filter { it.isFile }
        if (mesaLibs.isEmpty()) return false
        val symbol = "_ZNSt6__ndk113__hash_memoryEPKvm"
        val quoted = mesaLibs.joinToString(" ") { "'${it.absolutePath}'" }
        val needed = runQuickCommand("grep -lqa '$symbol' $quoted && echo yes").contains("yes")
        if (!needed) return false
        return !runQuickCommand("grep -qa '$symbol' '${libcxx.absolutePath}' && echo yes").contains("yes")
    }

    // ── Native Package Installation ──

    private fun installRepoPackages(): Boolean {
        val pkgs = listOf("x11-repo", "tur-repo")

        // Ensure main package list is up to date before downloading the repo packages.
        if (executeCommand("apt-get update").startsWith("Error:")) {
            Log.e(TAG, "apt-get update failed before installing repo packages")
            return false
        }

        // Download the .debs to the prefix root.
        val downloadCmd = "cd \"${prefixDir.absolutePath}\" && apt-get download ${pkgs.joinToString(" ")}"
        if (executeCommand(downloadCmd).startsWith("Error:")) {
            Log.e(TAG, "Failed to download x11-repo/tur-repo .debs")
            return false
        }

        // Unpack without configuring so we can edit the maintainer scripts first.
        val debs = pkgs.joinToString(" ") { "${it}_*.deb" }
        if (executeCommand("dpkg --unpack $debs").startsWith("Error:")) {
            Log.e(TAG, "Failed to unpack x11-repo/tur-repo .debs")
            return false
        }

        // Replace the postinst scripts with no-ops. The originals just run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter.
        for (pkg in pkgs) {
            val postinst = File(prefixDir, "var/lib/dpkg/info/$pkg.postinst")
            postinst.writeText("#!/system/bin/sh\nexit 0\n")
            postinst.setExecutable(true, false)
        }

        // Now configure the repo packages and refresh apt's package lists.
        if (executeCommand("dpkg --configure ${pkgs.joinToString(" ")}").startsWith("Error:")) {
            Log.e(TAG, "Failed to configure x11-repo/tur-repo")
            return false
        }
        if (executeCommand("pkg update -y").startsWith("Error:")) {
            Log.e(TAG, "Failed to update package lists after installing repo packages")
            return false
        }

        return true
    }

    private fun installPackageGroup(cmd: String): Boolean {
        // pkg install downloads, unpacks and configures in one go. Newly unpacked
        // maintainer scripts still contain the original Termux shebang, so after
        // the command finishes (successfully or not) we patch them and run a
        // configuration pass to finish any half-configured packages. The first
        // command is therefore allowed to fail specifically when that recovery
        // pass succeeds and all requested packages are present.
        Log.i(TAG, "Running: $cmd")
        val boundedCommand = if (cmd.startsWith("dpkg --configure")) {
            "timeout --signal=TERM --kill-after=5s 90s $cmd"
        } else {
            cmd
        }
        val installOutput = executeCommand(boundedCommand)
        patchShebangs(force = true)
        if (packageOperationCancelled) return false
        val configureOutput = if (cmd.startsWith("dpkg --configure")) {
            installOutput
        } else {
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
        }
        if (configureOutput.startsWith("Error:")) {
            return false
        }
        if (!installOutput.startsWith("Error:")) {
            return true
        }

        // A configure command is itself the recovery operation. If the patched
        // second pass completed, dpkg has no remaining unconfigured packages.
        if (cmd.startsWith("dpkg --configure")) {
            Log.i(TAG, "dpkg configuration recovered after shebang patching")
            return true
        }

        val installPrefix = "pkg install -y "
        if (!cmd.startsWith(installPrefix)) {
            return false
        }

        val requestedPackages = cmd.removePrefix(installPrefix).trim()
        if (requestedPackages.isEmpty()) {
            return false
        }

        // Do not mistake an unrelated successful `dpkg --configure -a` for a
        // recovered install: every package requested by this group must now be
        // registered in dpkg's database.
        val queryOutput = executeCommand("dpkg-query -W $requestedPackages")
        val recovered = !queryOutput.startsWith("Error:")
        if (recovered) {
            Log.i(TAG, "Package group recovered after shebang patching: $requestedPackages")
        }
        return recovered
    }

    /**
     * Installs optional packages without `pkg`'s implicit repository refresh.
     *
     * Desktop setup already creates the package indexes. Refreshing every repo for
     * every optional app made an unrelated, temporarily syncing TUR mirror block
     * packages from the healthy main/X11 repositories. apt-get uses the last
     * verified indexes and only refreshes as a recovery step if installation fails.
     */
    private fun installOptionalPackages(
        packages: List<String>,
        onProgress: ((Double, String) -> Unit)? = null,
        retryProgress: Double = 0.5,
    ): Boolean {
        if (packages.isEmpty()) return true
        val packageNames = packages.joinToString(" ")

        fun installAndRecover(): Boolean {
            val installOutput = executeCommand(
                "DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold install -y $packageNames",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            // A relocated package may already be unpacked while dependencies are
            // still missing (notably Node.js -> c-ares). Let apt complete that
            // dependency transaction after its maintainer scripts are patched.
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 120s " +
                    "env DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold --fix-broken install -y",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            val configureOutput = executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
            val installed = packages.all(::isDpkgPackageInstalled)
            return installed && !configureOutput.startsWith("Error:") &&
                (!installOutput.startsWith("Error:") || installed)
        }

        if (installAndRecover()) return true
        if (packageOperationCancelled) return false

        onProgress?.invoke(retryProgress, "Refreshing package repositories and retrying...")
        // apt may update main/X11 successfully while a third-party repository is
        // temporarily inconsistent. Retrying is still useful with those newly
        // refreshed lists and apt's last verified TUR index.
        executeCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update")
        if (packageOperationCancelled) return false
        return installAndRecover()
    }

    private fun isDpkgPackageInstalled(packageName: String): Boolean {
        val result = executeCommand(
            "dpkg-query -W -f='${'$'}{Status}' $packageName",
        )
        return !result.startsWith("Error:") && result.trim() == "install ok installed"
    }

    /**
     * Node.js currently ships a preinst script with Termux's absolute shebang.
     * Preinst runs before dpkg exposes the script in its admin directory, so it
     * must be relocated inside the deb before installation.
     */
    private fun installRelocatedNodejs(): Boolean {
        // Install Node's native dependency before unpacking the relocated deb.
        // Running apt --fix-broken with an unpacked local Node package can make
        // apt replace it with the repository deb, whose absolute preinst shebang
        // is exactly what this relocation path must avoid.
        if (!isDpkgPackageInstalled("c-ares") &&
            !installOptionalPackages(listOf("c-ares"))) return false

        val workDir = File(tmpDir, "nodejs-relocated-deb")
        workDir.deleteRecursively()
        workDir.mkdirs()

        if (executeCommand("cd \"${workDir.absolutePath}\" && apt-get download nodejs")
                .startsWith("Error:")) return false
        val sourceDeb = workDir.listFiles()
            ?.firstOrNull { it.name.startsWith("nodejs_") && it.extension == "deb" }
            ?: return false
        val unpacked = File(workDir, "unpacked")
        if (executeCommand(
                "dpkg-deb -R \"${sourceDeb.absolutePath}\" \"${unpacked.absolutePath}\""
            ).startsWith("Error:")) return false

        val oldPrefix = "/data/data/com.termux/files/usr"
        val controlDir = File(unpacked, "DEBIAN")
        controlDir.listFiles()?.filter { it.isFile }?.forEach { script ->
            val content = script.readText()
            if (content.contains(oldPrefix)) {
                script.writeText(content.replace(oldPrefix, prefixDir.absolutePath))
                script.setExecutable(true, false)
            }
        }

        val rebuiltDeb = File(workDir, "nodejs-relocated.deb")
        if (executeCommand(
                "dpkg-deb -b \"${unpacked.absolutePath}\" \"${rebuiltDeb.absolutePath}\""
            ).startsWith("Error:")) return false
        if (executeCommand("dpkg --unpack \"${rebuiltDeb.absolutePath}\"")
                .startsWith("Error:")) return false

        patchShebangs(force = true)
        if (executeCommand("dpkg --configure nodejs").startsWith("Error:")) return false
        return isDpkgPackageInstalled("nodejs")
    }

    /** Installs only PRoot, proot-distro and Debian's base rootfs. */
    private fun installMinimalDebian(
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        onProgress?.invoke(0.12, "Installing lightweight PRoot runtime...")
        if (!installOptionalPackages(listOf("proot", "proot-distro"), onProgress, 0.28)) {
            return false
        }

        patchShebangs(force = true)
        relocateProotExecutable()
        if (executeCommand("proot --version").startsWith("Error:")) {
            Log.e(TAG, "Installed PRoot executable could not start")
            return false
        }

        if (debianRootfsMarkers().none(File::exists)) {
            // Remove an interrupted extraction so proot-distro can safely retry.
            File(prefixDir, "var/lib/proot-distro/containers/debian").deleteRecursively()
            File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian").deleteRecursively()
            onProgress?.invoke(0.38, "Downloading minimal Debian base system...")
            if (executeCommand("proot-distro install debian").startsWith("Error:")) {
                Log.e(TAG, "Minimal Debian rootfs installation failed")
                return false
            }
        }

        onProgress?.invoke(0.9, "Creating Debian shell shortcut...")
        writeDebianLauncher()

        // The downloaded archive is not needed after extraction.
        clearProotDownloadCache()
        onProgress?.invoke(1.0, "Minimal Debian compatibility is ready")
        return isMinimalDebianInstalled()
    }

    fun installDesktopEnvironmentNative(
        desktopEnv: String = "xfce4",
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        val marker = File(prefixDir, DE_MARKER)

        if (getInstalledDE() == selectedDesktop) {
            onProgress?.invoke(1.0, "$selectedDesktop is already installed")
            Log.i(TAG, "$selectedDesktop desktop environment already installed")
            return true
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot install DE — bootstrap not extracted")
            return false
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        onProgress?.invoke(0.12, "Configuring X11 and TUR repositories...")

        // Install the x11/tur repository packages. Their postinst scripts run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter, so we
        // unpack the .debs, neutralise the postinst scripts, configure them, and
        // then update the package lists ourselves.
        if (!installRepoPackages()) {
            Log.e(TAG, "Failed to install x11-repo/tur-repo")
            return false
        }
        onProgress?.invoke(0.24, "Updating native package database...")

        // Finish configuring anything left over from a previous run, then install
        // the desktop, GPU drivers, and build tools. Each install is followed by a
        // shebang patch + configure pass so postinst scripts find our prefix.
        if (!installPackageGroup("dpkg --configure -a")) {
            Log.e(TAG, "Initial dpkg --configure -a failed")
            return false
        }
        if (!installPackageGroup("pkg update -y")) {
            Log.e(TAG, "pkg update failed")
            return false
        }
        onProgress?.invoke(0.34, "Installing X11 and audio packages...")
        // DroidDesk embeds the X server, so termux-x11-nightly is deliberately
        // not installed. All desktops connect to the service's DISPLAY=:0.
        if (!installPackageGroup("pkg install -y xorg-xrandr pulseaudio xclip")) {
            Log.e(TAG, "Native X11 runtime package install failed")
            return false
        }
        onProgress?.invoke(0.46, "Installing $selectedDesktop desktop packages...")

        val desktopPackages =
            "xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd thunar mousepad devilspie2"
        if (!installPackageGroup("pkg install -y $desktopPackages")) {
            Log.e(TAG, "$selectedDesktop package install failed")
            return false
        }
        onProgress?.invoke(0.70, "Configuring graphics for this device...")
        if (!ensureGraphicsPackages(onProgress, 0.74)) return false

        val nativeTools = "git wget curl openssh htop python clang"
        onProgress?.invoke(
            0.84,
            "Installing Desktop Essentials tools...",
        )
        if (!installPackageGroup("pkg install -y $nativeTools")) {
            Log.e(TAG, "Native Termux utility package install failed")
            return false
        }
        onProgress?.invoke(0.94, "Finalizing native Linux environment...")

        // Rebuild the hook with the installed clang, then persist the selected DE.
        compileSocketHook()
        patchEmbeddedXfcePaths()
        marker.writeText(selectedDesktop)
        onProgress?.invoke(1.0, "Native Linux setup complete")
        Log.i(TAG, "Native Termux $selectedDesktop installation complete")
        return true
    }

    fun installOptionalApp(
        appId: String,
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        if (getInstalledDE().isEmpty()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress?.invoke(1.0, "Already installed")
            return true
        }

        // Code OSS can pull npm, and npm can be left unpacked while Node's
        // original Termux preinst path is invalid in our relocated prefix.
        // Repair/install Node before the generic dpkg configure pass.
        if (appId == "nodejs" || appId == "code_oss") {
            onProgress?.invoke(0.08, "Preparing relocated Node.js dependency...")
            if (!isDpkgPackageInstalled("nodejs") && !installRelocatedNodejs()) return false
        }

        onProgress?.invoke(0.18, "Repairing interrupted packages...")
        if (!installPackageGroup("dpkg --configure -a")) return false

        val ok = when (appId) {
            "firefox" -> {
                onProgress?.invoke(0.25, "Installing Firefox...")
                installOptionalPackages(listOf("firefox"), onProgress, 0.55)
            }
            "code_oss" -> {
                onProgress?.invoke(0.45, "Installing npm dependency...")
                installOptionalPackages(listOf("npm"), onProgress, 0.55) && run {
                    onProgress?.invoke(0.65, "Installing Code OSS...")
                    installOptionalPackages(listOf("code-oss"), onProgress, 0.78)
                }
            }
            "nodejs" -> {
                onProgress?.invoke(0.65, "Installing npm...")
                installOptionalPackages(listOf("npm"), onProgress, 0.78)
            }
            "imagemagick" -> {
                onProgress?.invoke(0.25, "Installing ImageMagick...")
                installOptionalPackages(listOf("imagemagick"), onProgress, 0.55)
            }
            "proot_debian" -> installMinimalDebian(onProgress)
            else -> false
        }

        val verified = ok && getOptionalAppsStatus()[appId] == true
        if (verified) {
            patchShebangs(force = true)
            onProgress?.invoke(1.0, "Installation complete")
        } else {
            onProgress?.invoke(-1.0, "Installation failed. Review the package log and retry.")
        }
        return verified
    }

    // ── Kali PRoot package store (the Add applications screen) ──

    private val kaliStoreVisiblePackages = setOf(
        "firefox-esr", "chromium", "code-oss", "libreoffice", "gimp", "blender",
        "vlc", "mpv", "thunderbird", "filezilla", "geany", "inkscape", "audacity",
        "evince", "htop", "btop", "fastfetch", "git", "python3", "python3-pip",
        "nodejs", "imagemagick", "tmux", "zsh", "fish", "p7zip-full",
        "file-roller", "kali-tweaks",
    )

    private fun kaliStoreCommand(script: String): List<String> = listOf(
        "/usr/bin/env", "-i",
        "HOME=/root", "USER=root", "LOGNAME=root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG=C.UTF-8", "TERM=dumb", "DEBIAN_FRONTEND=noninteractive",
        "BASH_ENV=/dev/null", "/bin/bash", "-c", script,
    )

    private fun runKaliStoreCommand(
        rootfs: File,
        script: String,
        timeoutMinutes: Long = 30,
        onProgress: (Double, String) -> Unit,
    ): KaliProotResult = kaliAptTransactionLock.withLock {
        var reportedProgress = 0.0
        val result = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/root",
            guestCommand = kaliStoreCommand(script),
            timeoutMinutes = timeoutMinutes,
            onOutput = { line ->
                installLogSink?.invoke(line)
                val message = line.trim()
                if (message.isNotEmpty()) {
                    Log.i(TAG, "Kali App Store: ${line.trimEnd()}")
                    val databasePercent = Regex("Reading database.*?([0-9]{1,3})%")
                        .find(message)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    val stageProgress = when {
                        databasePercent != null ->
                            0.28 + (databasePercent.coerceIn(0.0, 100.0) / 100.0) * 0.34
                        message.startsWith("Preparing to unpack ") -> 0.68
                        message.startsWith("Unpacking ") -> 0.76
                        message.startsWith("Setting up ") -> 0.87
                        message.startsWith("Processing triggers for ") -> 0.95
                        message.contains("Verified ") && message.contains("installed for ARM64") -> 0.98
                        else -> 0.0
                    }
                    if (stageProgress > reportedProgress) reportedProgress = stageProgress
                    // Unknown APT output is indeterminate; never show a fabricated 55% freeze.
                    onProgress(reportedProgress, message.take(180))
                }
            },
            onProcessStarted = { process -> activeCommandProcess = process },
        )
        if (activeCommandProcess != null && !activeCommandProcess!!.isAlive) activeCommandProcess = null
        result
    }

    /**
     * Try the configured official Kali mirror, then the approved HTTPS archive fallback.
     * The chosen URI is retained in DroidDesk's dedicated Kali source for the install.
     */
    private fun kaliAptMirrorFailoverScript(): String = """
refresh_kali_indexes() {
  local source_file="/etc/apt/sources.list.d/droiddesk-kali.sources"
  local original_uri=""
  local candidate=""
  local update_log=""
  local seen_uris=" "
  local successful=0

  if [[ ! -f "${'$'}source_file" ]]; then
    echo "[DroidDesk App Store] Missing DroidDesk Kali source file: ${'$'}source_file"
    return 1
  fi
  original_uri="$(sed -n 's/^URIs:[[:space:]]*//p' "${'$'}source_file" | head -n 1)"
  case "${'$'}original_uri" in
    "https://kali.download/kali/"|"https://archive.kali.org/kali/") ;;
    *)
      echo "[DroidDesk App Store] Refusing unapproved Kali source URI: ${'$'}original_uri"
      return 1
      ;;
  esac

  for candidate in "${'$'}original_uri" "https://kali.download/kali/" "https://archive.kali.org/kali/"; do
    case "${'$'}seen_uris" in *" ${'$'}candidate "*) continue ;; esac
    seen_uris="${'$'}{seen_uris}${'$'}{candidate} "
    echo "[DroidDesk App Store] Trying official Kali HTTPS repository: ${'$'}candidate"
    sed -i "s|^URIs:.*|URIs: ${'$'}candidate|" "${'$'}source_file"
    update_log="$(mktemp /tmp/droiddesk-kali-update.XXXXXX)"
    if apt-get \
      -o "Dir::Etc::sourcelist=${'$'}source_file" \
      -o Dir::Etc::sourceparts=- \
      -o APT::Get::List-Cleanup=0 \
      -o Acquire::ForceIPv4=true \
      -o Acquire::Retries=2 \
      -o Acquire::http::Timeout=20 \
      -o Acquire::https::Timeout=35 \
      -o Acquire::https::Verify-Peer=true \
      -o Acquire::https::Verify-Host=true \
      -o Acquire::AllowInsecureRepositories=false \
      -o Dpkg::Use-Pty=0 \
      update 2>&1 | tee "${'$'}update_log"; then
      if ! grep -Eq '^Err:[0-9]+ https://(kali\.download|archive\.kali\.org)/kali([[:space:]]|/)' "${'$'}update_log" &&
         grep -Eq '^(Hit|Get):[0-9]+ https://(kali\.download|archive\.kali\.org)/kali([[:space:]]|/)' "${'$'}update_log" &&
         find /var/lib/apt/lists -maxdepth 1 -type f -name '*binary-arm64_Packages*' -size +0c -print -quit | grep -q .; then
        echo "[DroidDesk App Store] Kali ARM64 repository is ready: ${'$'}candidate"
        successful=1
        rm -f "${'$'}update_log"
        break
      fi
    fi
    echo "[DroidDesk App Store] Repository did not provide a complete ARM64 index: ${'$'}candidate"
    rm -f "${'$'}update_log"
  done

  if [[ "${'$'}successful" -eq 1 ]]; then return 0; fi
  sed -i "s|^URIs:.*|URIs: ${'$'}original_uri|" "${'$'}source_file"
  echo "[DroidDesk App Store] Both approved Kali HTTPS repositories failed; restored the original URI."
  return 1
}
""".trimIndent()
    private fun refreshKaliPackageIndexes(
        rootfs: File,
        force: Boolean = false,
        onProgress: (Double, String) -> Unit = { _, _ -> },
    ): Boolean = kaliAptTransactionLock.withLock {
        val listsDir = File(rootfs, "var/lib/apt/lists")
        val hasPackageLists = listsDir.listFiles()?.any {
            it.isFile && it.length() > 0L &&
                it.name.contains("binary-arm64") && it.name.contains("Packages")
        } == true
        val cacheDir = File(rootfs, "root/.cache/droiddesk").apply { mkdirs() }
        val stamp = File(cacheDir, "kali-package-index-updated")
        val attempt = File(cacheDir, "kali-package-index-attempt")
        val now = System.currentTimeMillis()
        val fresh = stamp.isFile && now - stamp.lastModified() in 0..(6L * 60L * 60L * 1000L)
        if (!force && hasPackageLists && fresh) return@withLock true
        if (!force && attempt.isFile && now - attempt.lastModified() in 0..(3L * 60L * 1000L)) return@withLock hasPackageLists
        attempt.writeText(now.toString())
        onProgress(0.12, "Refreshing Kali ARM64 application catalog...")
        val script = """
            set -Eeuo pipefail
            native_arch="${'$'}(/usr/bin/dpkg --print-architecture)"
            if [ "${'$'}native_arch" != "arm64" ]; then
              echo "Expected Kali arm64, found ${'$'}native_arch."
              exit 31
            fi
            install -d -m 0755 /home/kali/.local/share/droiddesk
            exec > >(tee -a /home/kali/.local/share/droiddesk/package-store-install.log) 2>&1
            trap 'chmod 0644 /home/kali/.local/share/droiddesk/package-store-install.log 2>/dev/null || true; chown kali:kali /home/kali/.local/share/droiddesk/package-store-install.log 2>/dev/null || true' EXIT
            ${kaliAptMirrorFailoverScript()}
            echo "[DroidDesk App Store] Refreshing Kali ARM64 package indexes..."
            refresh_kali_indexes || exit 45
            chmod 0644 /home/kali/.local/share/droiddesk/package-store-install.log 2>/dev/null || true
            chown kali:kali /home/kali/.local/share/droiddesk/package-store-install.log 2>/dev/null || true
        """.trimIndent()
        val result = runKaliProotCommand(
            rootfs = rootfs, workingDirectory = "/root",
            guestCommand = kaliStoreCommand(script), timeoutMinutes = 15,
            onOutput = { line ->
                installLogSink?.invoke(line)
                if (line.isNotBlank()) onProgress(0.45, line.trim().take(180))
            },
        )
        if (result.exitCode == 0) {
            stamp.writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "Kali ARM64 package indexes refreshed successfully")
            return@withLock true
        }
        Log.w(TAG, "Kali package index refresh failed: ${result.output.takeLast(1400)}")
        if (hasPackageLists) {
            onProgress(0.20, "Repository refresh failed; using the cached ARM64 package list.")
            return@withLock true
        }
        onProgress(-1.0, "Kali ARM64 catalog could not be refreshed. Check internet and package-store-install.log.")
        false
    }

    private fun kaliInstalledPackageRecords(rootfs: File): List<Map<String, Any>> {
        val statusFile = File(rootfs, "var/lib/dpkg/status")
        val installed = runCatching {
            KaliDpkgStatusParser.parseInstalled(statusFile.readText())
        }.onFailure {
            Log.e(TAG, "Could not parse Kali dpkg status database", it)
        }.getOrDefault(emptyList())

        return installed.map { record ->
            val name = record.name
            val packageFiles = File(rootfs, "var/lib/dpkg/info/$name.list")
            val gui = packageFiles.isFile && packageFiles.useLines { entries ->
                entries.any { line ->
                    (line.startsWith("/usr/share/applications/") ||
                        line.startsWith("/usr/local/share/applications/")) &&
                        line.endsWith(".desktop")
                }
            }
            mapOf(
                "name" to name,
                "description" to "",
                "version" to record.version,
                "architecture" to record.architecture,
                "section" to record.section.ifBlank { categoryForPackage(name, "") },
                "installed" to true,
                "gui" to gui,
                "removable" to !isKaliStoreProtectedPackage(name),
            )
        }
    }

    fun searchKaliPackages(rootfs: File, query: String, limit: Int = 60): List<Map<String, Any>> =
        kaliAptTransactionLock.withLock {
            if (!isKaliProotDesktopInstalled(rootfs)) return@withLock emptyList()
            val cleanQuery = query.trim().take(60)
            if (!cleanQuery.matches(Regex("[A-Za-z0-9+._ -]*"))) return@withLock emptyList()
            if (!refreshKaliPackageIndexes(rootfs)) return@withLock emptyList()
            val pattern = cleanQuery.ifEmpty { "." }
            val script = "test \"$(dpkg --print-architecture)\" = arm64 && apt-cache search --names-only '$pattern'"
            val result = runKaliProotCommand(
                rootfs = rootfs,
                workingDirectory = "/root",
                guestCommand = kaliStoreCommand(script),
                timeoutMinutes = 3,
                onOutput = { line -> Log.d(TAG, "Kali package search: ${line.trimEnd()}") },
            )
            val installed = kaliInstalledPackageRecords(rootfs).associateBy { it["name"] as String }
            result.output.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(" - ")
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator).substringBefore(':').trim()
                if (!isSafePackageName(name)) return@mapNotNull null
                val description = line.substring(separator + 3).trim()
                val record = installed[name]
                mapOf(
                    "name" to name,
                    "description" to description,
                    "version" to (record?.get("version") ?: ""),
                    "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                    "installed" to (record != null),
                    "gui" to (record?.get("gui") ?: false),
                    "architecture" to "arm64",
                    "removable" to (record?.get("removable") ?: true),
                )
            }.distinctBy { it["name"] }.take(limit.coerceIn(1, 100)).toList()
        }

    fun getInstalledKaliPackages(rootfs: File, limit: Int = 500): List<Map<String, Any>> {
        if (!isKaliProotDesktopInstalled(rootfs)) return emptyList()
        val trackedFile = File(rootfs, "home/kali/.local/share/droiddesk/app-store-packages")
        val tracked = runCatching { trackedFile.readLines().filter(::isSafePackageName).toSet() }
            .getOrDefault(emptySet())
        return kaliInstalledPackageRecords(rootfs)
            .filter { record ->
                val name = record["name"] as? String ?: return@filter false
                name in kaliStoreVisiblePackages || name in tracked
            }
            .take(limit.coerceIn(1, 1000))
    }

    private fun resolveKaliStorePackage(packageName: String): String = when (packageName) {
        "firefox" -> "firefox-esr"
        "python" -> "python3"
        else -> packageName
    }

    private fun isKaliStoreProtectedPackage(packageName: String): Boolean =
        packageName in setOf(
            "apt", "bash", "base-files", "base-passwd", "coreutils", "dpkg",
            "kali-linux-default", "kali-desktop-core", "libc6", "login",
            "python3", "python3-minimal", "sudo", "systemd",
            "xfdesktop4", "xfwm4",
        )

    fun installKaliStorePackage(
        rootfs: File,
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean = kaliAptTransactionLock.withLock {
        installKaliStorePackageLocked(rootfs, packageName, onProgress, upgradeOnly = false)
    }

    fun upgradeKaliStorePackage(
        rootfs: File,
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean = kaliAptTransactionLock.withLock {
        installKaliStorePackageLocked(rootfs, packageName, onProgress, upgradeOnly = true)
    }

    private fun installKaliStorePackageLocked(
        rootfs: File,
        packageName: String,
        onProgress: (Double, String) -> Unit,
        upgradeOnly: Boolean,
    ): Boolean {
        val selected = resolveKaliStorePackage(packageName)
        if (!isSafePackageName(selected) || !isKaliProotDesktopInstalled(rootfs)) {
            onProgress(-1.0, "Invalid package or Kali desktop is not ready")
            return false
        }

        // A normal Get request is idempotent: an installed ARM64 package is already a success.
        val alreadyInstalledForArm64 = kaliInstalledPackageRecords(rootfs).any {
            it["name"] == selected && (it["architecture"] as? String) in setOf("arm64", "all")
        }
        if (!upgradeOnly && alreadyInstalledForArm64) {
            onProgress(1.0, "$selected is already installed for ARM64.")
            Log.i(TAG, "Skipping duplicate Kali App Store install; $selected is already installed.")
            return true
        }
        if (upgradeOnly && !alreadyInstalledForArm64) {
            onProgress(-1.0, "$selected is not installed for ARM64; update was not started.")
            return false
        }

        // Reuse the six-hour cache when valid; refresh only missing or stale ARM64 indexes.
        if (!refreshKaliPackageIndexes(rootfs, force = false, onProgress = onProgress)) return false

        onProgress(
            0.18,
            if (upgradeOnly) "Checking update for $selected..." else "Checking Kali ARM64 package availability...",
        )
        val aptAction = if (upgradeOnly) "install --only-upgrade" else "install"
        val script = """
            set -Eeuo pipefail
            ${kaliAptMirrorFailoverScript()}
            native_arch="${'$'}(/usr/bin/dpkg --print-architecture)"
            if [ "${'$'}native_arch" != "arm64" ]; then
              echo "Refusing package transaction: Kali architecture is ${'$'}native_arch, expected arm64."
              exit 31
            fi

            install -d -m 0755 /home/kali/.local/share/droiddesk
            exec > >(tee -a /home/kali/.local/share/droiddesk/package-store-install.log) 2>&1
            trap 'chmod 0644 /home/kali/.local/share/droiddesk/package-store-install.log 2>/dev/null || true; chown kali:kali /home/kali/.local/share/droiddesk/package-store-install.log 2>/dev/null || true' EXIT

            package_name="$selected"
            candidate_arch="${'$'}(apt-cache show "${'$'}package_name" 2>/dev/null |
              awk '/^Architecture:/ {
                if (${'$'}2 == "arm64") native=1
                else if (${'$'}2 == "all") common=1
              } END {
                if (native) print "arm64"
                else if (common) print "all"
              }')"
            if [ -z "${'$'}candidate_arch" ]; then
              available_arches="${'$'}(apt-cache show "${'$'}package_name" 2>/dev/null |
                awk '/^Architecture:/ { print ${'$'}2 }' | sort -u | paste -sd, -)"
              echo "No ARM64 or architecture-independent build exists for ${'$'}package_name."
              echo "Repository architectures for this package: ${'$'}{available_arches:-not found}."
              echo "Choose an ARM64 alternative from Add applications > Browse."
              exit 32
            fi

            if [ "${'$'}candidate_arch" = "arm64" ]; then
              package_spec="${'$'}package_name:arm64"
            else
              package_spec="${'$'}package_name"
            fi
            echo "Installing ${'$'}package_spec from Kali ARM64 repositories."
            apt_options=(
              -o Acquire::ForceIPv4=true
              -o Acquire::Retries=3
              -o Acquire::http::Timeout=25
              -o Acquire::https::Timeout=45
              -o Acquire::https::Verify-Peer=true
              -o Acquire::https::Verify-Host=true
              -o APT::Keep-Downloaded-Packages=true
              -o Dpkg::Use-Pty=0
              -o Dpkg::Options::=--force-confdef
              -o Dpkg::Options::=--force-confold
            )

            # PRoot has no system init; do not let package scripts wait for services.
            if [ ! -e /usr/sbin/policy-rc.d ]; then
              cat > /usr/sbin/policy-rc.d <<'POLICY'
#!/bin/sh
exit 101
POLICY
              chmod 0755 /usr/sbin/policy-rc.d
            fi

            run_package_heartbeat() {
              local label="$1"
              shift
              "$@" &
              local command_pid=$!
              (
                while kill -0 "${'$'}command_pid" 2>/dev/null; do
                  sleep 15
                  if kill -0 "${'$'}command_pid" 2>/dev/null; then
                    latest_event="$(tail -n 1 /var/log/dpkg.log 2>/dev/null || true)"
                    echo "[DroidDesk App Store] Still working: ${'$'}label. Last dpkg activity: ${'$'}{latest_event:-No dpkg event recorded yet}"
                  fi
                done
              ) &
              local heartbeat_pid=$!
              local command_status=0
              if wait "${'$'}command_pid"; then command_status=0; else command_status=$?; fi
              kill "${'$'}heartbeat_pid" 2>/dev/null || true
              wait "${'$'}heartbeat_pid" 2>/dev/null || true
              return "${'$'}command_status"
            }

            echo "[DroidDesk App Store] Checking the existing dpkg state before starting another install."
            if ! run_package_heartbeat "finishing interrupted package configuration" dpkg --configure -a; then
              echo "[DroidDesk App Store] dpkg configuration failed; repairing package dependencies."
              run_package_heartbeat "repairing broken dependencies" env DEBIAN_FRONTEND=noninteractive apt-get "${'$'}{apt_options[@]}" --fix-broken install -y --no-install-recommends
              run_package_heartbeat "completing repaired package configuration" dpkg --configure -a
            fi
            if dpkg --audit | grep -q .; then
              echo "[DroidDesk App Store] dpkg still reports incomplete packages; repairing them before the selected app."
              run_package_heartbeat "repairing incomplete package state" env DEBIAN_FRONTEND=noninteractive apt-get "${'$'}{apt_options[@]}" --fix-broken install -y --no-install-recommends
              run_package_heartbeat "finishing repaired package configuration" dpkg --configure -a
            fi
            if dpkg --audit | grep -q .; then
              echo "[DroidDesk App Store] Package database remains incomplete. See the last dpkg.log event."
              exit 33
            fi

            if ! run_package_heartbeat "installing ${'$'}package_name without optional recommendations" env DEBIAN_FRONTEND=noninteractive apt-get "${'$'}{apt_options[@]}" --no-install-recommends ${aptAction} -y "${'$'}package_spec"; then
              echo "[DroidDesk App Store] First install attempt failed; repairing dpkg and refreshing repositories before retry."
              run_package_heartbeat "resuming dpkg configuration" dpkg --configure -a || true
              run_package_heartbeat "repairing broken dependencies" env DEBIAN_FRONTEND=noninteractive apt-get "${'$'}{apt_options[@]}" --fix-broken install -y --no-install-recommends
              refresh_kali_indexes
              run_package_heartbeat "retrying ${'$'}package_name installation" env DEBIAN_FRONTEND=noninteractive apt-get "${'$'}{apt_options[@]}" --no-install-recommends ${aptAction} -y "${'$'}package_spec"
            fi
            if dpkg --audit | grep -q .; then
              echo "[DroidDesk App Store] Installation left incomplete package configuration."
              exit 34
            fi
            dpkg-query -W -f='${'$'}{Status}' "${'$'}package_name" | grep -qx 'install ok installed'

            # Persist every selected package and expose its GUI launchers on the XFCE desktop.
            store_dir="/home/kali/.local/share/droiddesk"
            tracked_packages="${'$'}store_dir/app-store-packages"
            disabled_shortcuts="${'$'}store_dir/desktop-shortcuts-disabled"
            desktop_dir="/home/kali/Desktop"
            install -d -m 0755 "${'$'}store_dir" "${'$'}desktop_dir"
            touch "${'$'}tracked_packages" "${'$'}disabled_shortcuts"
            grep -Fqx "${'$'}package_name" "${'$'}tracked_packages" || echo "${'$'}package_name" >> "${'$'}tracked_packages"
            while IFS= read -r desktop_file; do
              [[ -f "${'$'}desktop_file" ]] || continue
              if grep -Eiq '^(NoDisplay|Hidden)=true' "${'$'}desktop_file"; then continue; fi
              desktop_name="$(basename "${'$'}desktop_file")"
              if grep -Fqx "${'$'}desktop_name" "${'$'}disabled_shortcuts"; then
                echo "[DroidDesk App Store] Respecting the user desktop-shortcut preference for ${'$'}desktop_name"
                continue
              fi
              managed_shortcut="${'$'}desktop_dir/${'$'}package_name-${'$'}desktop_name"
              temporary_shortcut="${'$'}managed_shortcut.tmp"
              {
                printf '# DroidDesk App Store package: %s\n' "${'$'}package_name"
                cat "${'$'}desktop_file"
              } > "${'$'}temporary_shortcut"
              chmod 0755 "${'$'}temporary_shortcut"
              chown kali:kali "${'$'}temporary_shortcut"
              mv -f "${'$'}temporary_shortcut" "${'$'}managed_shortcut"
              echo "[DroidDesk App Store] Added desktop launcher: ${'$'}managed_shortcut"
            done < <(
              dpkg-query -L "${'$'}package_name" 2>/dev/null |
                grep -E '^/(usr/share|usr/local/share)/applications/[^/]+\.desktop$' || true
            )
            chown kali:kali "${'$'}tracked_packages" "${'$'}disabled_shortcuts" "${'$'}store_dir" 2>/dev/null || true
            update-desktop-database /usr/share/applications 2>/dev/null || true
            update-mime-database /usr/share/mime 2>/dev/null || true
            session_address_file="/home/kali/.local/share/droiddesk/dbus-session-address"
            if [[ -s "${'$'}session_address_file" ]] && pgrep -x xfdesktop >/dev/null 2>&1; then
              session_address="$(cat "${'$'}session_address_file")"
              sudo -E -u kali env DISPLAY=:0 XDG_RUNTIME_DIR=/tmp/runtime-kali \
                DBUS_SESSION_BUS_ADDRESS="${'$'}session_address" xfdesktop --reload >/dev/null 2>&1 || true
            fi
            echo "[DroidDesk App Store] Verified ${'$'}package_name is installed for ARM64."
        """.trimIndent()

        onProgress(0.28, if (upgradeOnly) "Updating $selected for ARM64..." else "Installing $selected for ARM64...")
        val result = runKaliStoreCommand(rootfs, script, timeoutMinutes = 40, onProgress = onProgress)
        val installedForArm64 = kaliInstalledPackageRecords(rootfs).any {
            it["name"] == selected && (it["architecture"] as? String) in setOf("arm64", "all")
        }
        // For a fresh install, dpkg's final installed state is authoritative even if a
        // post-install maintenance step returned non-zero. A requested upgrade must exit cleanly.
        val installed = installedForArm64 && (!upgradeOnly || result.exitCode == 0)

        if (packageOperationCancelled) {
            onProgress(-1.0, "Installation cancelled")
            return false
        }
        if (!installed) {
            Log.e(TAG, "Kali App Store install failed for $selected (exit ${result.exitCode}): ${result.output.takeLast(2200)}")
        }
        onProgress(
            if (installed) 1.0 else -1.0,
            if (installed) {
                if (upgradeOnly) "$selected updated for ARM64" else "$selected installed for ARM64"
            } else
                "Could not install $selected (APT exit ${result.exitCode}). Details: /home/kali/.local/share/droiddesk/package-store-install.log",
        )
        return installed
    }

    fun removeKaliStorePackage(
        rootfs: File,
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        val selected = resolveKaliStorePackage(packageName)
        if (!isSafePackageName(selected) || isKaliStoreProtectedPackage(selected)) {
            onProgress(-1.0, "This package is protected because Kali needs it to run")
            return false
        }
        onProgress(0.2, "Removing $selected from Kali...")
        val removeScript = """
            set -Eeuo pipefail
            DEBIAN_FRONTEND=noninteractive apt-get remove -y '$selected'
            tracked="/home/kali/.local/share/droiddesk/app-store-packages"
            if [ -f "${'$'}tracked" ]; then
              grep -Fvx '$selected' "${'$'}tracked" > "${'$'}tracked.tmp" || true
              mv -f "${'$'}tracked.tmp" "${'$'}tracked"
            fi
            for shortcut in /home/kali/Desktop/"$selected"-*.desktop /home/kali/Desktop/store-"$selected"-*.desktop; do
              [ -f "${'$'}shortcut" ] || continue
              if grep -Fq '# DroidDesk App Store package: $selected' "${'$'}shortcut"; then
                rm -f "${'$'}shortcut"
              fi
            done
            chown kali:kali "${'$'}tracked" 2>/dev/null || true
            update-desktop-database /usr/share/applications 2>/dev/null || true
        """.trimIndent()
        val result = runKaliStoreCommand(
            rootfs,
            removeScript,
            timeoutMinutes = 20,
            onProgress = onProgress,
        )
        val removed = result.exitCode == 0 &&
            kaliInstalledPackageRecords(rootfs).none { it["name"] == selected }
        onProgress(if (removed) 1.0 else -1.0, if (removed) "$selected removed" else "$selected could not be removed")
        return removed
    }

    fun getKaliUpgradablePackages(
        rootfs: File,
        refresh: Boolean = false,
    ): List<Map<String, Any>> = kaliAptTransactionLock.withLock {
        if (!isKaliProotDesktopInstalled(rootfs)) return@withLock emptyList()
        if (refresh && !refreshKaliPackageIndexes(rootfs, force = true)) return@withLock emptyList()
        val result = runKaliProotCommand(
            rootfs = rootfs,
            workingDirectory = "/root",
            guestCommand = kaliStoreCommand(
                "test \"${'$'}(dpkg --print-architecture)\" = arm64 && apt list --upgradable 2>/dev/null || true",
            ),
            timeoutMinutes = if (refresh) 10 else 3,
            onOutput = {},
        )
        val installed = kaliInstalledPackageRecords(rootfs).associateBy { it["name"] as String }
        result.output.lineSequence().mapNotNull { line ->
            if (!line.contains("[upgradable from:")) return@mapNotNull null
            val name = line.substringBefore('/').trim()
            if (!isSafePackageName(name) || isKaliStoreProtectedPackage(name)) return@mapNotNull null
            val newVersion = line.substringAfter('/').trim().substringBefore(' ')
            val current = line.substringAfter("upgradable from: ").substringBefore(']').trim()
            val record = installed[name]
            mapOf(
                "name" to name,
                "version" to newVersion,
                "currentVersion" to current,
                "description" to "",
                "section" to (record?.get("section") ?: categoryForPackage(name, "")),
                "installed" to true,
                "gui" to (record?.get("gui") ?: false),
                "architecture" to "arm64",
            )
        }.distinctBy { it["name"] }.toList()
    }

    fun upgradeKaliStorePackages(
        rootfs: File,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isKaliProotDesktopInstalled(rootfs)) {
            onProgress(-1.0, "Kali desktop is not ready")
            return false
        }
        onProgress(0.1, "Refreshing Kali ARM64 repositories...")
        // Update only user-facing packages. Never run a blanket apt upgrade that can
        // replace libc, dpkg, the Kali metapackage, XFCE or the PRoot desktop base.
        val packages = getKaliUpgradablePackages(rootfs, refresh = true)
            .mapNotNull { it["name"]?.toString() }
            .filter { isSafePackageName(it) && !isKaliStoreProtectedPackage(it) }
            .distinct()
        if (packages.isEmpty()) {
            onProgress(1.0, "All available Kali applications are up to date")
            return true
        }
        val packageArgs = packages.joinToString(" ") { "'$it'" }
        val script = "set -Eeuo pipefail; test \"$(dpkg --print-architecture)\" = arm64; " +
            "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=3 --only-upgrade install -y $packageArgs"
        val result = runKaliStoreCommand(
            rootfs,
            script,
            timeoutMinutes = 60,
            onProgress = onProgress,
        )
        val succeeded = result.exitCode == 0
        onProgress(
            if (succeeded) 1.0 else -1.0,
            if (succeeded) "Kali application updates completed" else "Some Kali application updates failed",
        )
        return succeeded
    }

    // ── Native package store ──

    fun searchNativePackages(query: String, limit: Int = 60): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        val cleanQuery = query.trim().take(60)
        if (!cleanQuery.matches(Regex("[A-Za-z0-9+._ -]*"))) return emptyList()
        val output = runQuickCommand("apt-cache search --names-only '${cleanQuery.ifEmpty { "." }}'")
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        return output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).substringBefore(':').trim()
            if (!isSafePackageName(name)) return@mapNotNull null
            val description = line.substring(separator + 3).trim()
            val record = installed[name]
            mapOf(
                "name" to name,
                "description" to description,
                "version" to (record?.get("version") ?: ""),
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to (record != null),
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.take(limit.coerceIn(1, 100)).toList()
    }

    fun getInstalledNativePackages(limit: Int = 500): List<Map<String, Any>> {
        val visible = storeInstalledPackages() + setOf(
            "firefox", "code-oss", "libreoffice", "gimp", "blender", "vlc",
            "nodejs", "python", "imagemagick",
        )
        return installedPackageRecords()
            .filter { record ->
                val name = record["name"] as? String ?: return@filter false
                visible.contains(name)
            }
            .take(limit.coerceIn(1, 1000))
    }

    fun installStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName)) {
            onProgress(-1.0, "Invalid package name")
            return false
        }
        onProgress(0.08, "Repairing interrupted package operations...")
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) {
            onProgress(-1.0, "Installation cancelled")
            return false
        }
        onProgress(0.22, "Installing $packageName and dependencies...")
        val optionalId = when (packageName) {
            "firefox" -> "firefox"
            "code-oss", "code" -> "code_oss"
            "nodejs" -> "nodejs"
            "imagemagick" -> "imagemagick"
            else -> null
        }
        val ok = if (optionalId != null) {
            installOptionalApp(optionalId) { progress, status ->
                onProgress(0.22 + progress.coerceIn(0.0, 1.0) * 0.68, status)
            }
        } else {
            installOptionalPackages(listOf(packageName), onProgress, 0.5)
        }
        if (packageOperationCancelled) {
            onProgress(-1.0, "Installation cancelled")
            return false
        }
        patchShebangs(force = true)
        refreshDesktopMenus()
        val installed = ok && isDpkgPackageInstalled(packageName)
        if (installed) setStorePackageInstalled(packageName, true)
        onProgress(if (installed) 1.0 else -1.0, if (installed) "$packageName installed" else "$packageName installation failed")
        return installed
    }

    fun removeStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName) || isProtectedPackage(packageName)) {
            onProgress(-1.0, "This package is required by DroidDesk")
            return false
        }
        onProgress(0.15, "Removing $packageName...")
        val output = executeCommand("apt-get remove -y $packageName")
        if (packageOperationCancelled) {
            onProgress(-1.0, "Removal cancelled")
            return false
        }
        patchShebangs(force = true)
        executeCommand("dpkg --configure -a")
        refreshDesktopMenus()
        val removed = !isDpkgPackageInstalled(packageName)
        if (removed) setStorePackageInstalled(packageName, false)
        onProgress(if (removed) 1.0 else -1.0, if (removed) "$packageName removed" else "$packageName removal failed")
        return removed && !output.startsWith("Error:")
    }

    /**
     * Installed packages with a newer version available. Core packages DroidDesk depends on
     * (see [isProtectedPackage]) are left out: upgrading them can break the relocated runtime.
     */
    fun getUpgradablePackages(refresh: Boolean): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        if (refresh) {
            runQuickCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update >/dev/null 2>&1; true")
        }
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        // Lines look like: name/stable 1.2-1 aarch64 [upgradable from: 1.1-1]
        return runQuickCommand("apt list --upgradable 2>/dev/null").lineSequence().mapNotNull { line ->
            if (!line.contains("[upgradable from:")) return@mapNotNull null
            val name = line.substringBefore('/').trim()
            if (!isSafePackageName(name) || isProtectedPackage(name)) return@mapNotNull null
            val newVersion = line.substringAfter('/').split(' ').getOrNull(1).orEmpty()
            val current = line.substringAfter("upgradable from: ").substringBefore(']').trim()
            val record = installed[name]
            val description = record?.get("description") as? String ?: ""
            mapOf(
                "name" to name,
                "version" to newVersion,
                "currentVersion" to current,
                "description" to description,
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to true,
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.toList()
    }

    /** Upgrades every package from [getUpgradablePackages]; core packages are never touched. */
    fun upgradeAllPackages(onProgress: (Double, String) -> Unit): Boolean {
        onProgress(0.06, "Repairing interrupted package operations...")
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) return cancelled(onProgress)
        onProgress(0.14, "Checking for updates...")
        val packages = getUpgradablePackages(refresh = true).map { it["name"] as String }
        if (packageOperationCancelled) return cancelled(onProgress)
        if (packages.isEmpty()) {
            onProgress(1.0, "Everything is up to date")
            return true
        }
        onProgress(0.3, "Updating ${packages.size} package${if (packages.size == 1) "" else "s"}...")
        val output = executeCommand(
            "DEBIAN_FRONTEND=noninteractive apt-get " +
                "-o Dpkg::Options::=--force-confdef " +
                "-o Dpkg::Options::=--force-confold " +
                "install --only-upgrade -y ${packages.joinToString(" ")}",
        )
        if (packageOperationCancelled) return cancelled(onProgress)
        onProgress(0.88, "Finishing up...")
        patchShebangs(force = true)
        executeCommand("timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a")
        refreshDesktopMenus()
        val remaining = getUpgradablePackages(refresh = false).size
        val ok = !output.startsWith("Error:") && remaining < packages.size
        onProgress(
            if (ok) 1.0 else -1.0,
            if (!ok) "Update failed" else if (remaining == 0) "All packages updated" else "$remaining package(s) could not be updated",
        )
        return ok
    }

    private fun cancelled(onProgress: (Double, String) -> Unit): Boolean {
        onProgress(-1.0, "Update cancelled")
        return false
    }

    /** Disk usage of the Linux runtime and the device, in bytes. */
    fun getStorageInfo(): Map<String, Any> {
        val stat = android.os.StatFs(baseDir.absolutePath)
        val info = mutableMapOf<String, Any>(
            "deviceFree" to stat.availableBytes,
            "deviceTotal" to stat.totalBytes,
            "available" to isBootstrapped(),
        )
        if (!isBootstrapped()) return info
        // Separate du runs: one du call counts each inode once, so a nested path listed after
        // its parent would report 0.
        val output = runQuickCommand(
            "cd '${baseDir.absolutePath}' && for d in usr home usr/var/cache/apt usr/var/lib/apt/lists; " +
                "do du -sk \"\$d\" 2>/dev/null; done; true",
        )
        val sizes = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val kb = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            parts.getOrNull(1)?.let { it to kb * 1024 }
        }.toMap()
        val cache = (sizes["usr/var/cache/apt"] ?: 0L) + (sizes["usr/var/lib/apt/lists"] ?: 0L)
        info["system"] = ((sizes["usr"] ?: 0L) - cache).coerceAtLeast(0L)
        info["home"] = sizes["home"] ?: 0L
        info["cache"] = cache
        info["packages"] = installedPackageRecords().size
        return info
    }

    /** Deletes downloaded package files (apt-get clean). Returns bytes freed, or -1 on failure. */
    fun cleanPackageCache(): Long {
        val before = (getStorageInfo()["cache"] as? Long) ?: 0L
        val output = executeCommand("apt-get clean")
        if (output.startsWith("Error:")) return -1L
        val after = (getStorageInfo()["cache"] as? Long) ?: 0L
        return (before - after).coerceAtLeast(0L)
    }

    private fun installedPackageRecords(): List<Map<String, Any>> {
        val statusFile = File(prefixDir, "var/lib/dpkg/status")
        if (!statusFile.isFile) return emptyList()
        return statusFile.readText().split(Regex("\\n\\s*\\n")).mapNotNull { paragraph ->
            val fields = mutableMapOf<String, String>()
            var currentKey: String? = null
            paragraph.lineSequence().forEach { line ->
                if (line.startsWith(" ") && currentKey != null) {
                    fields[currentKey!!] = fields[currentKey!!].orEmpty() + " " + line.trim()
                } else {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        currentKey = line.substring(0, colon)
                        fields[currentKey!!] = line.substring(colon + 1).trim()
                    }
                }
            }
            if (fields["Status"] != "install ok installed") return@mapNotNull null
            val name = fields["Package"]?.substringBefore(':') ?: return@mapNotNull null
            val description = fields["Description"].orEmpty().substringBefore(" . ").trim()
            mapOf(
                "name" to name,
                "description" to description,
                "version" to fields["Version"].orEmpty(),
                "section" to fields["Section"].orEmpty().ifEmpty { categoryForPackage(name, description) },
                "installed" to true,
                "gui" to providesDesktopEntry(name),
                "removable" to !isProtectedPackage(name),
            )
        }.sortedBy { (it["name"] as String).lowercase() }
    }

    private fun providesDesktopEntry(packageName: String): Boolean {
        val fileList = File(prefixDir, "var/lib/dpkg/info/$packageName.list")
        return fileList.isFile && fileList.useLines { lines ->
            lines.any { it.contains("/share/applications/") && it.endsWith(".desktop") }
        }
    }

    private fun categoryForPackage(name: String, description: String): String {
        val text = "$name $description".lowercase()
        return when {
            listOf("browser", "firefox", "chromium").any(text::contains) -> "Internet"
            listOf("editor", "compiler", "git", "python", "nodejs", "ide").any(text::contains) -> "Development"
            listOf("image", "photo", "graphics", "gimp", "blender").any(text::contains) -> "Graphics"
            listOf("audio", "video", "media", "music", "vlc").any(text::contains) -> "Multimedia"
            listOf("office", "document", "spreadsheet", "pdf").any(text::contains) -> "Office"
            else -> "System"
        }
    }

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.matches(Regex("[a-z0-9][a-z0-9+.-]{0,127}"))

    private fun isProtectedPackage(packageName: String): Boolean =
        packageName in setOf(
            "apt", "bash", "coreutils", "dpkg", "termux-tools", "termux-am", "glibc-repo",
            "x11-repo", "tur-repo", "pulseaudio", "dbus", "python", "xfce4", "xfce4-session",
            "xfce4-panel", "xfdesktop", "xfwm4", "xfconf", "thunar", "xorg-xrandr",
        )

    private fun storeInstalledPackages(): Set<String> =
        context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
            .getStringSet("installed", emptySet())
            ?.toSet()
            .orEmpty()

    private fun setStorePackageInstalled(packageName: String, installed: Boolean) {
        val preferences = context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
        val packages = preferences.getStringSet("installed", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (installed) packages.add(packageName) else packages.remove(packageName)
        preferences.edit().putStringSet("installed", packages).commit()
    }

    private fun runQuickCommand(command: String): String = runCatching {
        ProcessBuilder(File(binDir, "bash").absolutePath, "-c", command)
            .directory(prefixDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
            }
            .start().let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                if (process.exitValue() == 0) output else ""
            }
    }.getOrDefault("")

    private fun refreshDesktopMenus() {
        executeCommand(
            "update-desktop-database ${prefixDir.absolutePath}/share/applications >/dev/null 2>&1 || true",
        )
    }

    fun readLinuxClipboard(): String? = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return null
        ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-o")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }
            .start().let { process ->
                val value = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) value else ""
            }
    }.getOrNull()

    fun writeLinuxClipboard(value: String): Boolean = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return false
        val process = ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-in")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }.start()
        process.outputStream.use { it.write(value.toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    // ── Session Management ──

    fun startSession(desktopEnv: String = "xfce4", mode: String = "x11", width: Int = 1920, height: Int = 1080) {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        extractBootstrapIfNeeded(context)

        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot start session — not bootstrapped")
            return
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        patchEmbeddedXfcePaths()

        // Packages whose maintainer scripts failed on older releases stay half-configured and
        // block every later apt install (and with it the Adreno driver). Finish them once
        // their scripts have been relocated.
        if (hasUnfinishedPackages()) {
            Log.i(TAG, "Finishing packages left half-configured by an earlier install")
            installPackageGroup("dpkg --configure -a")
        }

        // Repair installations made by older releases, where mesa-zink could
        // remove Mesa and leave the configured llvmpipe fallback unavailable.
        if (!ensureGraphicsPackages()) {
            Log.e(TAG, "Cannot start desktop without a working Mesa runtime")
            return
        }

        val clipboardTool = File(binDir, "xclip")
        if (!clipboardTool.canExecute()) {
            Log.i(TAG, "Installing the desktop clipboard compatibility helper")
            if (installPackageGroup("pkg install -y xclip")) {
                patchShebangs(force = true)
            } else {
                Log.w(TAG, "Clipboard helper unavailable; clipboard sync will retry next session")
            }
        }

        if (selectedDesktop == "xfce4") {
            // Older installs predate the auto-maximize helper; try once while upgrading the profile.
            if (XfceMobileProfile.needsInstall(homeDir) && !File(binDir, "devilspie2").canExecute()) {
                Log.i(TAG, "Installing the window fitting helper")
                if (installPackageGroup("pkg install -y devilspie2")) {
                    patchShebangs(force = true)
                } else {
                    Log.w(TAG, "Window fitting helper unavailable; windows will keep their default size")
                }
            }
            XfceMobileProfile.install(
                context = context,
                homeDir = homeDir.apply { mkdirs() },
                wallpaperFile = File(
                    homeDir,
                    ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
                ),
            )
            AndroidAppBridge.syncLaunchers(
                context = context,
                homeDir = homeDir,
                python = File(prefixDir, "bin/python3"),
            )
        }

        // X11ServerService owns this socket. Never delete it from the client runtime.
        File(tmpDir, ".X11-unix").mkdirs()

        // Remove stale Xfce ICE listeners left by a killed/restarted activity.
        tmpDir.listFiles { file -> file.name.startsWith(".xfsm-ICE-") }
            ?.forEach { it.delete() }

        // Start a session dbus-daemon and keep it as a child process. Do not use
        // --fork: a forked daemon can outlive the Android activity and leave an
        // orphaned bus behind after its socket is replaced.
        val dbusSocket = File(tmpDir, "dbus-session")
        try {
            dbusProcess?.destroyForcibly()
            if (dbusSocket.exists()) dbusSocket.delete()
            val dbusConfig = File(tmpDir, "dbus-session.conf")
            dbusConfig.writeText(
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <keep_umask/>
                  <listen>unix:path=${dbusSocket.absolutePath}</listen>
                  <auth>EXTERNAL</auth>
                  <servicedir>${File(prefixDir, "share/dbus-1/services").absolutePath}</servicedir>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n",
            )
            val dbusCmd = listOf(
                File(prefixDir, "bin/dbus-daemon").absolutePath,
                "--config-file=${dbusConfig.absolutePath}",
                "--nofork",
                "--nopidfile"
            )
            val startedDbus = ProcessBuilder(dbusCmd)
                .directory(homeDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()
            dbusProcess = startedDbus

            Thread {
                try {
                    startedDbus.inputStream.bufferedReader().forEachLine {
                        Log.d(TAG, "DBUS: $it")
                    }
                } catch (error: java.io.IOException) {
                    // Closing the pipe is expected when Stop Server terminates dbus.
                    Log.d(TAG, "D-Bus output stream closed")
                }
            }.start()

            val readyDeadline = System.currentTimeMillis() + 2_000
            while (!dbusSocket.exists() && startedDbus.isAlive &&
                System.currentTimeMillis() < readyDeadline) {
                Thread.sleep(20)
            }
            check(dbusSocket.exists() && startedDbus.isAlive) {
                "dbus-daemon did not create its session socket"
            }
            Log.i(TAG, "Session dbus-daemon ready")
            if (!startPulseAudio()) {
                Log.w(TAG, "PulseAudio did not become ready; inspect ${tmpDir.absolutePath}/pulse/pulseaudio.log")
            }
        } catch (e: Exception) {
            Log.e(TAG, "dbus-daemon start failed: ${e.message}")
            dbusProcess?.destroyForcibly()
            dbusProcess = null
            return
        }

        // The Termux startxfce4 wrapper falls back to Android's /bin/sh when an
        // X server already exists, which corrupts DISPLAY on recent Android
        // releases. The session binary starts the same XFCE components and
        // preserves DroidDesk's prepared environment.
        val desktopCommand = "xfce4-session"

        val runScript = """
            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export DISPLAY=:0

            # Use the session bus DroidDesk already started
            export DBUS_SESSION_BUS_ADDRESS="unix:path=${dbusSocket.absolutePath}"

            # Audio is started and supervised by LinuxRuntime in the Android process.
    export PULSE_SERVER="tcp:127.0.0.1:4713"
    export PULSE_RUNTIME_PATH="${tmpDir.absolutePath}/pulse"
    echo "DIAG: Using app-managed AAudio PulseAudio on 127.0.0.1:4713"
                echo "DIAG: Launching $desktopCommand natively on DISPLAY=:0 ..."
            exec $desktopCommand
        """.trimIndent()

        Log.i(TAG, "Starting native Termux session for $selectedDesktop")

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val command = listOf(bashBin, "-c", runScript)

        val startedSession = ProcessBuilder(command)
            .directory(homeDir.apply { mkdirs() })
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(getTermuxEnv())
            }
            .start()
        sessionProcess = startedSession

        Thread {
            try {
                val reader = java.io.InputStreamReader(startedSession.inputStream)
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "DESKTOP: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                // destroyForcibly() closes the pipe during a normal shutdown.
                Log.d(TAG, "Desktop output stream closed")
            }
        }.start()

        Log.i(TAG, "Termux session started")
    }

    /** Wait until the desktop shell processes that paint the first desktop frame exist. */
    fun waitForDesktopReady(desktopEnv: String = "xfce4", timeoutMs: Long = 45_000): Boolean {
        val processes = when (normalizedDesktop(desktopEnv)) {
            "lxqt" -> listOf("lxqt-session", "lxqt-panel")
            "mate" -> listOf("mate-session", "mate-panel")
            "kde" -> listOf("plasmashell")
            else -> listOf("xfdesktop", "xfce4-panel")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val check = processes.joinToString(" && ") { "pgrep -x '$it' >/dev/null" }
            val ready = runCatching {
                ProcessBuilder(File(binDir, "bash").absolutePath, "-c", check)
                    .directory(homeDir)
                    .redirectErrorStream(true)
                    .also { builder ->
                        builder.environment().clear()
                        builder.environment().putAll(getTermuxEnv())
                    }
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            if (ready) {
                Thread.sleep(650)
                return true
            }
            Thread.sleep(150)
        }
        Log.w(TAG, "Timed out waiting for $desktopEnv to paint its first desktop frame")
        return false
    }

    /**
     * Return PulseAudio's real output sink names. A successful TCP connection to
     * PulseAudio is not enough: on Android 16 it can answer while exposing only
     * the silent auto_null sink.
     */
    private fun getPulseAudioSinkNames(): List<String>? {
        val pactl = File(binDir, "pactl")
        if (!pactl.canExecute()) {
            Log.w(TAG, "pactl is missing; PulseAudio output cannot be verified")
            return null
        }

        return try {
            val probe = ProcessBuilder(
                pactl.absolutePath,
                "-s", "tcp:127.0.0.1:4713",
                "list", "short", "sinks",
            ).directory(homeDir)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(getPulseAudioEnvironment(File(tmpDir, "pulse")))
                }
                .start()

            if (!probe.waitFor(2, TimeUnit.SECONDS)) {
                probe.destroyForcibly()
                probe.waitFor()
                Log.w(TAG, "Timed out while checking PulseAudio output sinks")
                return null
            }

            val output = probe.inputStream.bufferedReader().use { it.readText() }
            if (probe.exitValue() != 0) {
                Log.w(TAG, "PulseAudio sink query failed: ${output.trim().take(500)}")
                return null
            }

            output.lineSequence().mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                columns.getOrNull(1)?.takeIf { it.isNotBlank() }
            }.toList()
        } catch (error: Exception) {
            Log.w(TAG, "Could not query PulseAudio output sinks", error)
            null
        }
    }

    private fun isPulseAudioReady(requiredSink: String? = null): Boolean {
        val sinks = getPulseAudioSinkNames() ?: return false
        if (requiredSink != null) {
            val ready = requiredSink in sinks
            if (!ready) Log.w(TAG, "Required Android PCM sink is not available yet")
            return ready
        }

        val realSinks = sinks.filterNot { it.equals("auto_null", ignoreCase = true) }
        if (realSinks.isEmpty()) {
            Log.w(TAG, "PulseAudio exposes only the silent auto_null sink")
            return false
        }
        Log.i(TAG, "PulseAudio output sinks: ${realSinks.joinToString()}")
        return true
    }

    /**
     * Retain the relocated Termux environment. The socket hook is needed by
     * this bundled userspace and does not redirect the app-private PCM FIFO.
     */
    private fun getPulseAudioEnvironment(pulseDir: File): MutableMap<String, String> {
        return getTermuxEnv().toMutableMap().apply {
            this["PULSE_SERVER"] = "tcp:127.0.0.1:4713"
            this["PULSE_RUNTIME_PATH"] = pulseDir.absolutePath
            this["PULSE_SINK"] = AndroidPcmAudioOutput.SINK_NAME
        }
    }

    private fun setPulseAudioDefaultSink(sinkName: String): Boolean {
        val pactl = File(binDir, "pactl")
        if (!pactl.canExecute()) return false

        return runCatching {
            val process = ProcessBuilder(
                pactl.absolutePath,
                "-s", "tcp:127.0.0.1:4713",
                "set-default-sink", sinkName,
            ).directory(homeDir)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(getPulseAudioEnvironment(File(tmpDir, "pulse")))
                }
                .start()

            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.exitValue() != 0) {
                    Log.w(TAG, "Could not select PulseAudio sink $sinkName: ${output.trim().take(400)}")
                    false
                } else {
                    true
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to select PulseAudio sink $sinkName", it)
            false
        }
    }

    private fun startPulseAudio(): Boolean {
        val pulseDir = File(tmpDir, "pulse").apply { mkdirs() }
        val pulseLog = File(pulseDir, "pulseaudio.log")
        val audioPipe = File(pulseDir, "android-audio.pcm")
        val existing = pulseProcess

        if (!AndroidPcmAudioOutput.start(audioPipe)) {
            Log.e(TAG, "Android AudioTrack bridge could not be initialized")
            return false
        }

        if (existing?.isAlive == true) {
            val existingDeadline = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < existingDeadline) {
                if (isPulseAudioReady(AndroidPcmAudioOutput.SINK_NAME)) {
                    setPulseAudioDefaultSink(AndroidPcmAudioOutput.SINK_NAME)
                    return true
                }
                Thread.sleep(150)
            }

            Log.w(TAG, "Replacing the previous PulseAudio server with the Android PCM pipe sink")
            runCatching {
                existing.destroy()
                if (!existing.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                    existing.destroyForcibly()
                    existing.waitFor()
                }
            }.onFailure { Log.w(TAG, "Could not stop the previous PulseAudio process", it) }
        }

        pulseProcess = null
        if (isPulseAudioReady(AndroidPcmAudioOutput.SINK_NAME)) {
            setPulseAudioDefaultSink(AndroidPcmAudioOutput.SINK_NAME)
            Log.i(TAG, "Reusing the existing DroidDesk Android PCM sink")
            return true
        }

        val pulseBinary = File(binDir, "pulseaudio")
        if (!pulseBinary.canExecute()) {
            Log.e(TAG, "PulseAudio executable is missing: ${pulseBinary.absolutePath}")
            AndroidPcmAudioOutput.stop()
            return false
        }

        val moduleDir = File(prefixDir, "lib/pulseaudio/modules")
        val pipeModule = File(moduleDir, "module-pipe-sink.so")
        if (!pipeModule.isFile) {
            Log.e(TAG, "PulseAudio pipe-sink module is missing: ${pipeModule.absolutePath}")
            AndroidPcmAudioOutput.stop()
            return false
        }

        File(pulseDir, "native").delete()
        val command = listOf(
            pulseBinary.absolutePath,
            "--daemonize=no",
            "--use-pid-file=no",
            "--exit-idle-time=-1",
            "--dl-search-path=${moduleDir.absolutePath}",
            "--log-level=debug",
            "-n",
            "-L",
            "module-pipe-sink file=${audioPipe.absolutePath} " +
                "sink_name=${AndroidPcmAudioOutput.SINK_NAME} " +
                "format=s16le rate=${AndroidPcmAudioOutput.SAMPLE_RATE} channels=2",
            "-L",
            "module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 port=4713",
            "-L",
            "module-native-protocol-unix socket=${File(pulseDir, "native").absolutePath}",
        )

        runCatching {
            pulseLog.writeText("===== DroidDesk PulseAudio PCM pipe startup =====\\n")
        }.onFailure { Log.w(TAG, "Could not reset PulseAudio log", it) }

        val started = try {
            val builder = ProcessBuilder(command)
                .directory(homeDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(pulseLog))
            val environment = builder.environment()
            environment.clear()
            environment.putAll(getPulseAudioEnvironment(pulseDir))
            builder.start()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch PulseAudio with the Android PCM pipe sink", error)
            AndroidPcmAudioOutput.stop()
            return false
        }

        pulseProcess = started
        Log.i(TAG, "PulseAudio started with Android PCM sink (pid=${processPid(started) ?: -1})")

        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && started.isAlive) {
            if (isPulseAudioReady(AndroidPcmAudioOutput.SINK_NAME)) {
                val selected = setPulseAudioDefaultSink(AndroidPcmAudioOutput.SINK_NAME)
                Log.i(TAG, "Android PCM sink is ready; default sink selected=$selected")
                return true
            }
            Thread.sleep(200)
        }

        val exitCode = runCatching { started.exitValue() }.getOrDefault(-1)
        val logTail = runCatching {
            pulseLog.readLines().takeLast(60).joinToString("\\n")
        }.getOrDefault("could not read PulseAudio log")
        Log.e(TAG, "PulseAudio did not create the DroidDesk PCM sink (exit=$exitCode): $logTail")

        runCatching {
            if (started.isAlive) {
                started.destroy()
                if (!started.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                    started.destroyForcibly()
                    started.waitFor()
                }
            }
        }.onFailure { Log.w(TAG, "Could not stop failed PulseAudio PCM server", it) }

        pulseProcess = null
        AndroidPcmAudioOutput.stop()
        return false
    }

    private fun stopPulseAudio() {
        val process = pulseProcess
        pulseProcess = null
        if (process?.isAlive == true) {
            Log.i(TAG, "Stopping app-managed PulseAudio process")
            process.destroy()
            if (!process.waitFor(1_500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        AndroidPcmAudioOutput.stop()
    }

    fun stopSession() {
        Log.i(TAG, "Stopping Linux session...")
        val process = sessionProcess
        sessionProcess = null
        try {
            if (process?.isAlive == true) {
                // Do not wait forever for a stuck PRoot process: a blocked wait here
                // left the Stop Server MethodChannel call pending and the dashboard
                // displaying stale controls.
                process.destroy()
                if (!process.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        Log.e(TAG, "PRoot process did not exit after forced termination")
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { process?.destroyForcibly() }
            Log.w(TAG, "Linux session stop was interrupted", error)
        } finally {
            runCatching { dbusProcess?.destroyForcibly() }
            dbusProcess = null
            stopPulseAudio()
        }
        Log.i(TAG, "Linux session stopped")
    }

    // ── Command Execution ──

    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (packageOperationCancelled) return "Error: Package operation cancelled"
        activeCommandProcess?.let { process ->
            try {
                Log.d(TAG, "Routing input to active command: $command")
                val os = process.outputStream
                os.write((command + "\n").toByteArray())
                os.flush()
                return ""
            } catch (e: Exception) {
                Log.w(TAG, "Active process closed or failed to receive input: ${e.message}")
            }
        }

        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        compileSocketHook()

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val fullCommand = listOf(bashBin, "-c", command)

        return try {
            Log.d(TAG, "Executing command natively: $command")

            val process = ProcessBuilder(fullCommand)
                .directory(prefixDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()

            activeCommandProcess = process

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                installLogSink?.invoke(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            activeCommandProcess = null
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")

            if (process.exitValue() != 0) {
                throw Exception("Command failed with exit code ${process.exitValue()}. Output: \n$output")
            }

            output.toString()
        } catch (e: Exception) {
            activeCommandProcess = null
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    fun interruptCommand() {
        activeCommandProcess?.let { process ->
            Log.d(TAG, "Interrupting active command...")
            val rootPid = processPid(process)
            val descendants = rootPid?.let(::descendantPids).orEmpty()
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 15) }
            rootPid?.let { android.os.Process.sendSignal(it, 15) } ?: process.destroy()
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 9) }
            if (process.isAlive) process.destroyForcibly()
        }
        activeCommandProcess = null
    }

    private fun processPid(process: Process): Int? = runCatching {
        var type: Class<*>? = process.javaClass
        var pidField: java.lang.reflect.Field? = null
        while (type != null && pidField == null) {
            pidField = runCatching { type.getDeclaredField("pid") }.getOrNull()
            type = type.superclass
        }
        requireNotNull(pidField).apply { isAccessible = true }.getInt(process)
    }.onFailure { Log.w(TAG, "Could not inspect package process PID", it) }
        .getOrNull()

    private fun descendantPids(parentPid: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun collect(pid: Int) {
            val children = runCatching {
                File("/proc/$pid/task/$pid/children").readText()
                    .trim().split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
            children.forEach { child ->
                result += child
                collect(child)
            }
        }
        collect(parentPid)
        return result
    }

    private fun clearStalePackageLocks() {
        listOf(
            File(prefixDir, "var/lib/dpkg/lock"),
            File(prefixDir, "var/lib/dpkg/lock-frontend"),
            File(prefixDir, "var/cache/apt/archives/lock"),
            File(prefixDir, "var/lib/apt/lists/lock"),
        ).forEach { lock ->
            if (lock.exists() && !lock.delete()) {
                Log.w(TAG, "Could not remove stale package lock: ${lock.absolutePath}")
            }
        }
    }
}
