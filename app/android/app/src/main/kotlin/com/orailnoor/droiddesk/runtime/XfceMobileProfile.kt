package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File

/** Installs DroidDesk's touch-friendly Ubuntu-inspired XFCE defaults once per home. */
object XfceMobileProfile {
    private const val TAG = "XfceMobileProfile"
    private const val PROFILE_MARKER = ".droiddesk-xfce-mobile-v25"
    private const val MONOSPACE_FONT = "DejaVu Sans Mono"
    private const val WALLPAPER_ASSET = "droiddesk/sunset-wave-wallpaper.jpg"

    /** Height of the bottom launcher dock in X11 pixels; Android overlays sit above it. */
    const val DOCK_SIZE_PX = 88

    /** True when the current profile still needs to be (re)installed in [homeDir]. */
    fun needsInstall(homeDir: File): Boolean = !File(homeDir, PROFILE_MARKER).exists()

    fun install(
        context: Context,
        homeDir: File,
        wallpaperFile: File,
        wallpaperPathInSession: String = wallpaperFile.absolutePath,
        iconThemeName: String = "Adwaita",
    ): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            wallpaperFile.parentFile?.mkdirs()
            File(homeDir, "Desktop").mkdirs()
            if (!wallpaperFile.isFile || wallpaperFile.length() == 0L) {
                context.assets.open(WALLPAPER_ASSET).use { input ->
                    wallpaperFile.outputStream().use(input::copyTo)
                }
            }

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            // This profile is now a safe additive migration. Existing XFCE XML contains
            // the user's wallpaper, dock/plugin order and desktop preferences; never replace it.
            writeConfigIfMissing(File(xfconfDir, "xfce4-panel.xml"), panelConfig())
            writeConfigIfMissing(
                File(xfconfDir, "xfce4-desktop.xml"),
                desktopConfig(xmlEscape(wallpaperPathInSession)),
            )
            writeConfigIfMissing(File(xfconfDir, "xfwm4.xml"), windowManagerConfig())
            writeConfigIfMissing(
                File(xfconfDir, "xsettings.xml"),
                xsettingsConfig(
                    if (iconThemeName in setOf("Papirus", "Adwaita", "hicolor")) iconThemeName else "hicolor",
                ),
            )
            writeConfigIfMissing(File(xfconfDir, "xfce4-terminal.xml"), terminalConfig())
            installMonospaceFontRule(homeDir)
            installPanelCss(homeDir)
            installAutoMaximize(homeDir)
            installFileManagerDefaults(homeDir)

            val panelDir = File(homeDir, ".config/xfce4/panel")
            writeLauncher(
                File(panelDir, "launcher-21/droiddesk-terminal.desktop"),
                name = "Terminal",
                comment = "Open the Linux terminal",
                exec = "xfce4-terminal",
                icon = "org.xfce.terminalemulator",
            )
            writeLauncher(
                File(panelDir, "launcher-22/droiddesk-files.desktop"),
                name = "Files",
                comment = "Browse files",
                exec = "/usr/bin/thunar %u",
                icon = "org.xfce.filemanager",
            )
            writeLauncher(
                File(panelDir, "launcher-23/droiddesk-browser.desktop"),
                name = "Web Browser",
                comment = "Browse the web",
                exec = "/home/kali/.local/bin/droiddesk-browser %U",
                icon = "org.xfce.webbrowser",
            )

            marker.writeText("1\n")
            Log.i(TAG, "Installed Ubuntu-inspired XFCE mobile profile in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile", error)
            false
        }
    }

    private fun writeConfigIfMissing(file: File, contents: String) {
        if (file.isFile && file.length() > 0L) return
        file.parentFile?.mkdirs()
        file.writeText(contents)
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
    ) {
        // Preserve user edits to a dock or desktop launcher during app upgrades.
        if (file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$name
            Comment=$comment
            Exec=$exec
            Icon=$icon
            TryExec=${exec.substringBefore(' ')}
            StartupNotify=true
            Terminal=false
            """.trimIndent() + "\n",
        )
    }

    /**
     * Set Thunar as XFCE's explicit directory handler for Home, File System,
     * folder links and the DroidDesk dock launcher.
     */
    private fun installFileManagerDefaults(homeDir: File) {
        val configDir = File(homeDir, ".config/xfce4").apply { mkdirs() }
        val appDir = File(homeDir, ".local/share/applications").apply { mkdirs() }
        val helperDir = File(homeDir, ".local/share/xfce4/helpers").apply { mkdirs() }
        val browser = File(homeDir, ".local/bin/droiddesk-browser")
        browser.parentFile?.mkdirs()
        browser.writeText(
            """
            #!/bin/sh
            if [ -x /home/kali/.local/bin/droiddesk-firefox ]; then
              if /home/kali/.local/bin/droiddesk-firefox "$@"; then exit 0; fi
            fi
            if [ -x /usr/bin/google-chrome-stable ]; then
              exec /usr/bin/google-chrome-stable --no-sandbox --disable-dev-shm-usage --ozone-platform=x11 "$@"
            fi
            if [ -x /usr/bin/chromium ]; then exec /usr/bin/chromium "$@"; fi
            exit 127
            """.trimIndent() + "\n",
        )
        browser.setExecutable(true, false)

        // Handle URL launches directly. The system wrappers in desktop-experience-v20
        // provide the same behavior globally; these user-level copies cover first boot.
        val xdgOpen = File(homeDir, ".local/bin/xdg-open")
        xdgOpen.writeText(
            """
            #!/bin/sh
            case "${'$'}{1:-}" in
              http://*|https://*) exec /home/kali/.local/bin/droiddesk-browser "${'$'}@" ;;
            esac
            exec /usr/bin/xdg-open "${'$'}@"
            """.trimIndent() + "\n",
        )
        xdgOpen.setExecutable(true, false)

        val exoOpen = File(homeDir, ".local/bin/exo-open")
        exoOpen.writeText(
            """
            #!/bin/sh
            if [ "${'$'}{1:-}" = "--launch" ] && [ "${'$'}{2:-}" = "WebBrowser" ]; then
              shift 2
              exec /home/kali/.local/bin/droiddesk-browser "${'$'}@"
            fi
            case "${'$'}{1:-}" in
              http://*|https://*) exec /home/kali/.local/bin/droiddesk-browser "${'$'}@" ;;
            esac
            exec /usr/bin/exo-open "${'$'}@"
            """.trimIndent() + "\n",
        )
        exoOpen.setExecutable(true, false)

        val shellConfig = File(homeDir, ".profile")
        val currentProfile = if (shellConfig.isFile) shellConfig.readText() else ""
        val additions = buildString {
            if (!currentProfile.contains("HOME/.local/bin")) append("export PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\"\n")
            if (!currentProfile.contains("BROWSER=/home/kali/.local/bin/droiddesk-browser")) {
                append("export BROWSER=/home/kali/.local/bin/droiddesk-browser\n")
            }
        }
        if (additions.isNotEmpty()) {
            shellConfig.parentFile?.mkdirs()
            shellConfig.appendText(if (currentProfile.isNotEmpty() && !currentProfile.endsWith("\n")) "\n$additions" else additions)
        }
        File(appDir, "droiddesk-browser.desktop").writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=Web Browser
            Comment=Open the installed Kali browser
            Exec=/home/kali/.local/bin/droiddesk-browser %U
            TryExec=/home/kali/.local/bin/droiddesk-browser
            Icon=web-browser
            Terminal=false
            StartupNotify=true
            Categories=Network;WebBrowser;
            MimeType=x-scheme-handler/http;x-scheme-handler/https;text/html;application/xhtml+xml;
            """.trimIndent() + "\n",
        )
        File(helperDir, "droiddesk-browser.desktop").writeText(
            """
            [Desktop Entry]
            NoDisplay=true
            Version=1.0
            Encoding=UTF-8
            Type=X-XFCE-Helper
            X-XFCE-Category=WebBrowser
            X-XFCE-Commands=/home/kali/.local/bin/droiddesk-browser
            X-XFCE-CommandsWithParameter=/home/kali/.local/bin/droiddesk-browser "%s"
            Icon=web-browser
            Name=Kali Linux Web Browser
            """.trimIndent() + "\n",
        )

        val helpers = File(configDir, "helpers.rc")
        val helperLines = if (helpers.isFile) helpers.readLines().toMutableList() else mutableListOf()
        if (helperLines.none { it.startsWith("FileManager=") }) helperLines += "FileManager=Thunar"
        val withoutOldBrowser = helperLines.filterNot { it.startsWith("WebBrowser=") }
        helpers.writeText((withoutOldBrowser + "WebBrowser=droiddesk-browser").joinToString("\n", postfix = "\n"))

        updateDefaultMimeAssociations(
            File(homeDir, ".config/mimeapps.list"),
            linkedMapOf(
                "inode/directory" to "thunar.desktop;",
                "application/x-gnome-saved-search" to "thunar.desktop;",
                "x-scheme-handler/http" to "droiddesk-browser.desktop;",
                "x-scheme-handler/https" to "droiddesk-browser.desktop;",
                "text/html" to "droiddesk-browser.desktop;",
                "application/xhtml+xml" to "droiddesk-browser.desktop;",
            ),
        )
        Log.i(TAG, "Installed the XFCE WebBrowser helper and HTTP/HTTPS defaults; preserved Thunar directory handling")
    }

    private fun updateDefaultMimeAssociations(file: File, defaults: Map<String, String>) {
        val source = if (file.isFile) file.readLines() else emptyList()
        val output = mutableListOf<String>()
        var inDefaultSection = false
        var foundDefaultSection = false
        var insertedDefaults = false

        fun appendDefaults() {
            if (insertedDefaults) return
            defaults.forEach { (mimeType, desktopFile) -> output += "$mimeType=$desktopFile" }
            insertedDefaults = true
        }

        source.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inDefaultSection) appendDefaults()
                inDefaultSection = trimmed == "[Default Applications]"
                if (inDefaultSection) foundDefaultSection = true
                output += line
            } else if (inDefaultSection && defaults.containsKey(line.substringBefore("=").trim())) {
                // Replace only managed browser entries; leave unrelated user MIME choices intact.
            } else {
                output += line
            }
        }

        if (inDefaultSection) appendDefaults()
        if (!foundDefaultSection) {
            if (output.isNotEmpty() && output.last().isNotBlank()) output += ""
            output += "[Default Applications]"
            appendDefaults()
        }

        file.parentFile?.mkdirs()
        file.writeText(output.joinToString("\n", postfix = "\n"))
    }
    private fun installPanelCss(homeDir: File) {
        val cssFile = File(homeDir, ".config/gtk-3.0/gtk.css")
        cssFile.parentFile?.mkdirs()
        val startMarker = "/* DroidDesk mobile panel start */"
        val endMarker = "/* DroidDesk mobile panel end */"
        val managedBlock = """
            $startMarker
            .xfce4-panel button {
              padding: 4px 8px;
            }
            $endMarker
        """.trimIndent()
        val existing = if (cssFile.exists()) cssFile.readText() else ""
        val withoutOldBlock = existing.replace(
            Regex(
                Regex.escape(startMarker) + ".*?" + Regex.escape(endMarker),
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            "",
        ).trimEnd()
        cssFile.writeText(
            if (withoutOldBlock.isEmpty()) "$managedBlock\n"
            else "$withoutOldBlock\n\n$managedBlock\n",
        )
    }

    /** Maximizes every normal window so apps never open wider than a portrait phone screen. */
    private fun installAutoMaximize(homeDir: File) {
        val maximize = File(homeDir, ".config/devilspie2/droiddesk-maximize.lua")
        if (!maximize.isFile) {
            maximize.parentFile?.mkdirs()
            maximize.writeText(
                """
                -- Managed by DroidDesk: dialogs, menus and splash screens keep their own size.
                if get_window_type() == "WINDOW_TYPE_NORMAL" then
                  if get_screen_geometry then
                    local screen_w, screen_h = get_screen_geometry()
                    local w = math.floor(screen_w * 0.9)
                    local h = math.floor(screen_h * 0.8)
                    set_window_geometry(math.floor((screen_w - w) / 2), math.floor((screen_h - h) / 2), w, h)
                  end
                  maximize()
                end
                """.trimIndent() + "\n",
            )
        }
        val autostart = File(homeDir, ".config/autostart/droiddesk-automax.desktop")
        if (!autostart.isFile) {
            autostart.parentFile?.mkdirs()
            autostart.writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=DroidDesk window fitting
                Comment=Maximize new windows on small screens
                Exec=sh -c 'command -v devilspie2 >/dev/null 2>&1 && exec devilspie2'
                OnlyShowIn=XFCE;
                NoDisplay=true
                """.trimIndent() + "\n",
            )
        }
    }

    private fun windowManagerConfig(): String = """
        <?xml version="1.0" encoding="UTF-8"?>

        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="theme" type="string" value="Default-hdpi"/>
            <property name="title_font" type="string" value="Sans Bold 11"/>
            <property name="button_layout" type="string" value="O|HMC"/>
            <property name="placement_ratio" type="int" value="100"/>
            <property name="tile_on_move" type="bool" value="true"/>
            <property name="snap_to_border" type="bool" value="true"/>
            <property name="wrap_windows" type="bool" value="false"/>
            <property name="easy_click" type="string" value="Alt"/>
            <property name="show_dock_shadow" type="bool" value="false"/>
            <!-- Required for the alpha-transparent bottom launcher panel. -->
            <property name="use_compositing" type="bool" value="true"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xsettingsConfig(iconThemeName: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>

        <channel name="xsettings" version="1.0">
          <property name="Xft" type="empty">
            <property name="DPI" type="int" value="140"/>
            <property name="Antialias" type="int" value="1"/>
            <property name="HintStyle" type="string" value="hintslight"/>
            <property name="RGBA" type="string" value="rgb"/>
          </property>
          <property name="Net" type="empty">
            <property name="ThemeName" type="string" value="Adwaita"/>
            <property name="IconThemeName" type="string" value="$iconThemeName"/>
            <property name="CursorSize" type="int" value="32"/>
          </property>
          <property name="Gtk" type="empty">
            <property name="CursorThemeSize" type="int" value="32"/>
            <property name="FontName" type="string" value="Sans 12"/>
            <property name="ButtonImages" type="bool" value="false"/>
            <property name="DialogsUseHeader" type="bool" value="true"/>
            <property name="MonospaceFontName" type="string" value="$MONOSPACE_FONT 10"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun terminalConfig(): String = """
        <?xml version="1.0" encoding="UTF-8"?>

        <channel name="xfce4-terminal" version="1.0">
          <property name="font-use-system" type="bool" value="false"/>
          <property name="font-name" type="string" value="$MONOSPACE_FONT 10"/>
        </channel>
    """.trimIndent() + "\n"

    /**
     * The native runtime's fontconfig also scans Android's /system/fonts, so "monospace" can
     * resolve to a proportional Android font and terminals draw every letter in a wide cell.
     */
    private fun installMonospaceFontRule(homeDir: File) {
        val file = File(homeDir, ".config/fontconfig/conf.d/60-droiddesk-monospace.conf")
        if (file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText(
            """
            <?xml version="1.0"?>
            <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
            <!-- Managed by DroidDesk -->
            <fontconfig>
              <alias binding="strong">
                <family>monospace</family>
                <prefer><family>$MONOSPACE_FONT</family></prefer>
              </alias>
            </fontconfig>
            """.trimIndent() + "\n",
        )
    }

    private fun panelConfig(): String = """
        <?xml version="1.0" encoding="UTF-8"?>

        <channel name="xfce4-panel" version="1.0">
          <property name="configver" type="int" value="2"/>
          <property name="panels" type="array">
            <value type="int" value="1"/>
            <value type="int" value="2"/>
            <property name="dark-mode" type="bool" value="false"/>
            <property name="panel-1" type="empty">
              <property name="position" type="string" value="p=6;x=0;y=0"/>
              <property name="length" type="uint" value="1"/>
              <property name="length-adjust" type="bool" value="true"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="1"/>
              <property name="size" type="uint" value="45"/>
              <property name="icon-size" type="uint" value="30"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.965000"/>
                <value type="double" value="0.961000"/>
                <value type="double" value="0.957000"/>
                <value type="double" value="0.000000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="2"/>
              </property>
            </property>
            <property name="panel-2" type="empty">
              <property name="position" type="string" value="p=10;x=0;y=0"/>
              <property name="mode" type="uint" value="0"/>
              <property name="length" type="uint" value="1"/>
              <property name="length-adjust" type="bool" value="true"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="1"/>
              <property name="size" type="uint" value="$DOCK_SIZE_PX"/>
              <property name="icon-size" type="uint" value="60"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.965000"/>
                <value type="double" value="0.961000"/>
                <value type="double" value="0.957000"/>
                <value type="double" value="0.000000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="20"/>
                <value type="int" value="21"/>
                <value type="int" value="22"/>
                <value type="int" value="23"/>
                <value type="int" value="24"/>
              </property>
            </property>
          </property>
          <property name="plugins" type="empty">
            <property name="plugin-2" type="string" value="tasklist">
              <property name="show-labels" type="bool" value="false"/>
              <property name="grouping" type="uint" value="1"/>
              <property name="flat-buttons" type="bool" value="true"/>
            </property>
            <property name="plugin-20" type="string" value="applicationsmenu">
              <property name="show-button-title" type="bool" value="false"/>
            </property>
            <property name="plugin-21" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-terminal.desktop"/>
              </property>
            </property>
            <property name="plugin-22" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-files.desktop"/>
              </property>
            </property>
            <property name="plugin-23" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-browser.desktop"/>
              </property>
            </property>
            <property name="plugin-24" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun desktopConfig(wallpaperPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>

        <channel name="xfce4-desktop" version="1.0">
          <property name="last-settings-migration-version" type="uint" value="1"/>
          <property name="backdrop" type="empty">
            <property name="screen0" type="empty">
              <property name="monitorbuiltin" type="empty">
                <property name="image-path" type="string" value="/usr/share/xfce4/backdrops/Kali Linux"/>
                <property name="workspace0" type="empty">
                  <property name="color-style" type="int" value="0"/>
                  <property name="image-style" type="int" value="5"/>
                  <property name="last-image" type="string" value="$wallpaperPath"/>
                </property>
              </property>
            </property>
          </property>
          <property name="desktop-icons" type="empty">
            <property name="style" type="int" value="2"/>
            <property name="icon-size" type="uint" value="72"/>
            <property name="file-icons" type="empty">
              <property name="show-home" type="bool" value="true"/>
              <property name="show-filesystem" type="bool" value="true"/>
              <property name="show-trash" type="bool" value="true"/>
              <property name="show-removable" type="bool" value="true"/>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
