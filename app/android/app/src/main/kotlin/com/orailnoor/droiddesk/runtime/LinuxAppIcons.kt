package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a real icon for a Linux package.
 *
 * Installed packages use the icon named by their own .desktop entry. Anything else falls back
 * to the Papirus icon theme (fetched once from jsDelivr and cached), which covers most Linux GUI
 * apps by name. Command-line tools usually have neither and return null.
 */
object LinuxAppIcons {
    private const val TAG = "LinuxAppIcons"
    private const val PAPIRUS_URL =
        "https://cdn.jsdelivr.net/gh/PapirusDevelopmentTeam/papirus-icon-theme@20260801/Papirus/64x64/apps/"
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private val SAFE_NAME = Regex("[A-Za-z0-9._+-]{1,128}")

    /** Returns `{"format": "png"|"svg", "bytes": ByteArray}` or null when no icon is known. */
    fun find(
        context: Context,
        prefixDir: File?,
        packageName: String,
        iconNames: List<String>,
    ): Map<String, Any>? {
        if (!SAFE_NAME.matches(packageName)) return null
        val localIconNames = prefixDir?.let { desktopIconNames(it, packageName) }.orEmpty()
        for (name in localIconNames) {
            prefixDir?.let { localIcon(it, name) }?.let { return it }
        }
        val candidates = (iconNames + localIconNames + packageName)
            .filter { SAFE_NAME.matches(it) }
            .distinct()
        for (name in candidates) {
            papirusIcon(context, name)?.let { return it }
        }
        return null
    }

    /** Icon names from the package's installed .desktop files, via dpkg's file list. */
    private fun desktopIconNames(prefixDir: File, packageName: String): List<String> {
        val infoDir = File(prefixDir, "var/lib/dpkg/info")
        val listFile = listOf("$packageName.list", "$packageName:aarch64.list")
            .map { File(infoDir, it) }
            .firstOrNull { it.isFile } ?: return emptyList()
        return runCatching {
            listFile.readLines()
                .filter { it.contains("/share/applications/") && it.endsWith(".desktop") }
                .mapNotNull { path -> relocate(prefixDir, path) }
                .filter { it.isFile }
                .mapNotNull { desktop ->
                    desktop.readLines()
                        .firstOrNull { it.startsWith("Icon=") }
                        ?.removePrefix("Icon=")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** dpkg lists Termux's original /data/data/com.termux/files/usr paths; map them onto our prefix. */
    private fun relocate(prefixDir: File, path: String): File? {
        val index = path.indexOf("/usr/")
        return if (index < 0) null else File(prefixDir, path.substring(index + "/usr/".length))
    }

    private fun localIcon(prefixDir: File, iconName: String): Map<String, Any>? {
        val direct = if (iconName.startsWith("/")) relocate(prefixDir, iconName) ?: File(iconName) else null
        val candidates = buildList {
            direct?.let(::add)
            if (SAFE_NAME.matches(iconName)) {
                val themes = listOf("hicolor", "Papirus", "Adwaita")
                val sizes = listOf("256x256", "128x128", "96x96", "64x64", "48x48", "scalable")
                for (theme in themes) for (size in sizes) for (ext in listOf("png", "svg")) {
                    add(File(prefixDir, "share/icons/$theme/$size/apps/$iconName.$ext"))
                }
                add(File(prefixDir, "share/pixmaps/$iconName.png"))
                add(File(prefixDir, "share/pixmaps/$iconName.svg"))
            }
        }
        val file = candidates.firstOrNull { it.isFile && it.length() in 1..2_000_000 } ?: return null
        val format = if (file.extension.equals("svg", ignoreCase = true)) "svg" else "png"
        return runCatching { mapOf("format" to format, "bytes" to file.readBytes()) }.getOrNull()
    }

    private fun papirusIcon(context: Context, iconName: String): Map<String, Any>? {
        val cacheDir = File(context.cacheDir, "linux-icons").apply { mkdirs() }
        val cached = File(cacheDir, "$iconName.svg")
        val miss = File(cacheDir, "$iconName.miss")
        if (cached.isFile && cached.length() > 0) {
            return mapOf("format" to "svg", "bytes" to cached.readBytes())
        }
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return null

        var notFound = false
        val bytes = runCatching {
            val connection = URL("$PAPIRUS_URL$iconName.svg").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            try {
                when (connection.responseCode) {
                    200 -> connection.inputStream.use { it.readBytes() }
                    404 -> null.also { notFound = true }
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.d(TAG, "Papirus fetch failed for $iconName: ${it.message}") }
            .getOrNull()

        val isSvg = bytes != null && bytes.size < 500_000 &&
            String(bytes, 0, minOf(bytes.size, 200)).contains("<svg")
        if (!isSvg) {
            // Remember real misses only; network errors retry next time.
            if (notFound) miss.writeText("")
            return null
        }
        cached.writeBytes(bytes!!)
        return mapOf("format" to "svg", "bytes" to bytes)
    }
}
