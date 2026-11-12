package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

object AndroidAppSync {
    private const val TAG = "AndroidAppSync"

    fun syncApps(context: Context, rootfsPath: String) {
        thread(name = "android-app-sync") {
            try {
                Log.i(TAG, "Starting Android App Sync to Linux Desktop...")

                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                // Get all apps that appear in a normal launcher
                val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    pm.queryIntentActivities(intent, 0)
                }

                val rootDir = File(rootfsPath, "root")
                if (!rootDir.exists()) {
                    Log.w(TAG, "Root directory not found in rootfs, skipping sync.")
                    return@thread
                }

                // Setup Staging Directories in Cache
                val stagingDir = File(context.cacheDir, "android-apps-staging")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                val iconsDir = File(stagingDir, ".icons/android")
                iconsDir.mkdirs()

                val appsDir = File(stagingDir, ".local/share/applications/android")
                appsDir.mkdirs()

                val desktopDir = File(stagingDir, "Desktop/Android Apps")
                desktopDir.mkdirs()

                for (app in apps) {
                    val packageName = app.activityInfo.packageName
                    val appName = app.loadLabel(pm).toString()
                                        val safeAppName = appName
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .replace(Regex("\\p{Cc}"), "_")
                        .trim()
                        .ifEmpty { packageName }
                    val iconDrawable = app.loadIcon(pm)

                    // 1. Save Icon
                    val iconFile = File(iconsDir, "$packageName.png")
                    saveIcon(iconDrawable, iconFile)

                    // 2. Generate .desktop file
                    val desktopContent = """
                        [Desktop Entry]
                        Version=1.0
                        Type=Application
                        Name=${escapeDesktopEntryValue(appName)}
                        Comment=Launch Android App: $packageName
                        Icon=/root/.icons/android/$packageName.png
                        Exec=sh -c "echo '$packageName' > /tmp/android_intent_pipe"
                        Categories=Android;Utility;
                        Terminal=false
                    """.trimIndent()

                    val appFile = File(appsDir, "$safeAppName.desktop")
                    appFile.writeText(desktopContent)

                    // 3. Create shortcut on Desktop folder
                    val shortcutFile = File(desktopDir, "$safeAppName.desktop")
                    shortcutFile.writeText(desktopContent)

                    // Make them executable
                    appFile.setExecutable(true, false)
                    shortcutFile.setExecutable(true, false)

                    // Make readable to all
                    appFile.setReadable(true, false)
                    shortcutFile.setReadable(true, false)
                    iconFile.setReadable(true, false)
                }

                iconsDir.setReadable(true, false)
                iconsDir.setExecutable(true, false)
                appsDir.setReadable(true, false)
                appsDir.setExecutable(true, false)
                desktopDir.setReadable(true, false)
                desktopDir.setExecutable(true, false)

                // 4. Use su to copy the staging directory into the rootfs /root directory
                val rootDesktopPath = File(rootDir, "Desktop/Android Apps").absolutePath
                val rootIconsPath = File(rootDir, ".icons/android").absolutePath
                val rootAppsPath = File(rootDir, ".local/share/applications/android").absolutePath

                val copyCmd = """
                    mkdir -p '$rootDesktopPath' '$rootIconsPath' '$rootAppsPath'
                    cp -a '${desktopDir.absolutePath}/.' '$rootDesktopPath/'
                    cp -a '${iconsDir.absolutePath}/.' '$rootIconsPath/'
                    cp -a '${appsDir.absolutePath}/.' '$rootAppsPath/'
                    chown -R 0:0 '$rootDesktopPath' '$rootIconsPath' '$rootAppsPath'
                    chmod +x '$rootDesktopPath'/*.desktop 2>/dev/null || true
                """.trimIndent()

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", copyCmd))
                process.waitFor()

                Log.i(TAG, "Synced ${apps.size} Android apps to Linux.")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync Android apps", e)
            }
        }
    }
        private fun escapeDesktopEntryValue(value: String): String {
        return value
            .replace(Regex("\\p{Cc}"), " ")
            .replace("\\", "\\\\")
    }

    private fun saveIcon(drawable: Drawable, file: File) {
        val bitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            is AdaptiveIconDrawable -> {
                val b = Bitmap.createBitmap(
                    drawable.intrinsicWidth.takeIf { it > 0 } ?: 108,
                    drawable.intrinsicHeight.takeIf { it > 0 } ?: 108,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                b
            }
            else -> {
                val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                b
            }
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
