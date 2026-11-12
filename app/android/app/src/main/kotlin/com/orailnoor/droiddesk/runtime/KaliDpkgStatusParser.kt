package com.orailnoor.droiddesk.runtime

/** One package stanza from Kali's /var/lib/dpkg/status database. */
internal data class KaliDpkgPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val section: String,
)

/**
 * Reads dpkg's status database directly instead of depending on presentation-oriented
 * dpkg-query output. Only the fully configured install state is considered installed.
 */
internal object KaliDpkgStatusParser {
    private val safePackageName = Regex("[a-z0-9][a-z0-9+.-]*")

    fun parseInstalled(statusText: String): List<KaliDpkgPackage> {
        val normalized = statusText.replace("\r\n", "\n").replace('\r', '\n')
        val result = mutableListOf<KaliDpkgPackage>()

        for (stanza in normalized.split(Regex("\n[ \t]*\n+"))) {
            if (stanza.isBlank()) continue
            val fields = linkedMapOf<String, String>()
            for (line in stanza.lineSequence()) {
                if (line.isBlank() || line.first().isWhitespace()) continue
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val key = line.substring(0, separator).trim()
                if (key.isNotEmpty()) fields[key] = line.substring(separator + 1).trim()
            }

            val name = fields["Package"].orEmpty()
            if (!safePackageName.matches(name)) continue
            if (fields["Status"] != "install ok installed") continue
            val architecture = fields["Architecture"].orEmpty()
            if (architecture !in setOf("arm64", "all")) continue

            result += KaliDpkgPackage(
                name = name,
                version = fields["Version"].orEmpty(),
                architecture = architecture,
                section = fields["Section"].orEmpty(),
            )
        }

        return result.distinctBy { it.name to it.architecture }
    }
}
