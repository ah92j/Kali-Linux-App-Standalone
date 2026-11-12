package com.orailnoor.droiddesk.runtime

import java.nio.charset.StandardCharsets

/**
 * Normalizes member paths from official Linux rootfs archives.
 *
 * Leading ../ components are stripped like GNU tar does when extracting an
 * archive without --absolute-names. Parent components inside a path are
 * resolved lexically. Any attempt to move above the extraction root is rejected.
 */
internal object ArchivePathValidator {
    private const val MAX_COMPONENT_BYTES = 255
    private const val MAX_PATH_BYTES = 4096

    fun normalizeEntryName(rawName: String, stripLeadingParents: Boolean = true): String {
        val name = rawName.removeSuffix("\r")
        if (name.isEmpty()) return ""

        if (
            name.startsWith("/") ||
            Regex("^[A-Za-z]:").containsMatchIn(name) ||
            name.any { it.isISOControl() }
        ) {
            throw SecurityException("Blocked unsafe archive path: ${display(name)}")
        }

        val components = ArrayList<String>()
        var hasSeenNormalComponent = false

        for (component in name.split('/')) {
            when (component) {
                "", "." -> Unit
                ".." -> {
                    if (components.isNotEmpty()) {
                        components.removeAt(components.lastIndex)
                    } else if (stripLeadingParents && !hasSeenNormalComponent) {
                        // Match the safe default behavior of tar: strip leading
                        // parent traversals instead of resolving outside the root.
                        continue
                    } else {
                        throw SecurityException("Blocked archive path traversal: ${display(name)}")
                    }
                }
                else -> {
                    if (component.toByteArray(StandardCharsets.UTF_8).size > MAX_COMPONENT_BYTES) {
                        throw SecurityException("Archive path component is too long: ${display(name)}")
                    }
                    components.add(component)
                    hasSeenNormalComponent = true
                }
            }
        }

        val normalized = components.joinToString("/")
        if (normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_PATH_BYTES) {
            throw SecurityException("Archive path is too long: ${display(name)}")
        }
        return normalized
    }

    /**
     * Strip the expected top-level folder from an official rootfs archive.
     * Every real archive member must be inside that folder; the folder entry
     * itself becomes the extraction root and is omitted.
     */
    fun stripExpectedArchiveRoot(normalizedName: String, expectedRoot: String): String {
        val root = normalizeEntryName(expectedRoot, stripLeadingParents = false)
        if (root.isEmpty() || root.contains('/')) {
            throw IllegalArgumentException("Expected archive root must be one path component")
        }
        return when {
            normalizedName == root -> ""
            normalizedName.startsWith("$root/") -> normalizedName.removePrefix("$root/")
            else -> throw SecurityException(
                "Archive entry is outside the expected $root/ root: ${display(normalizedName)}"
            )
        }
    }

    /**
     * Hardlink names are archive-root-relative, but some tar producers omit
     * the enclosing root folder. Accept either safe spelling and normalize it.
     */
    fun stripExpectedArchiveRootFromHardLink(
        normalizedTarget: String,
        expectedRoot: String,
    ): String {
        val root = normalizeEntryName(expectedRoot, stripLeadingParents = false)
        return when {
            normalizedTarget == root -> ""
            normalizedTarget.startsWith("$root/") -> normalizedTarget.removePrefix("$root/")
            else -> normalizedTarget
        }
    }

    /**
     * Convert an archive-root hardlink target into a path relative to the new
     * symlink's parent directory. The result is checked to stay inside rootfs.
     */
    fun relativeSymlinkTarget(entryName: String, targetPath: String): String {
        val safeEntry = normalizeEntryName(entryName, stripLeadingParents = false)
        val safeTarget = normalizeEntryName(targetPath, stripLeadingParents = false)
        if (safeEntry.isEmpty() || safeTarget.isEmpty()) {
            throw SecurityException("Hardlink path resolves to the root directory")
        }

        val parentComponents = safeEntry.substringBeforeLast('/', "")
            .split('/').filter { it.isNotEmpty() }
        val targetComponents = safeTarget.split('/').filter { it.isNotEmpty() }

        var common = 0
        while (
            common < parentComponents.size &&
            common < targetComponents.size &&
            parentComponents[common] == targetComponents[common]
        ) {
            common++
        }

        val relative = buildList {
            repeat(parentComponents.size - common) { add("..") }
            addAll(targetComponents.drop(common))
        }.joinToString("/").ifEmpty { "." }

        val resolved = if (parentComponents.isEmpty()) {
            relative
        } else {
            parentComponents.joinToString("/") + "/" + relative
        }
        normalizeEntryName(resolved, stripLeadingParents = false)
        return relative
    }

    /**
     * Validate a symbolic-link target relative to the link's containing folder.
     * Guest-root absolute links are rewritten as safe relative links so archive
     * extraction never encounters a host-absolute symlink destination.
     */
    fun validateSymbolicLinkTarget(entryName: String, rawTarget: String): String {
        val target = rawTarget.removeSuffix("\r")
        if (
            target.isEmpty() ||
            target.any { it.isISOControl() } ||
            Regex("^[A-Za-z]:").containsMatchIn(target)
        ) {
            throw SecurityException("Blocked unsafe symbolic-link target: ${display(target)}")
        }

        if (target.startsWith("/")) {
            // Keep the same guest-root destination but encode it as a relative
            // symlink. This prevents the Android tar extractor from ever seeing
            // an absolute link that could point into the host filesystem.
            val guestComponents = normalizeEntryName(
                target.removePrefix("/"),
                stripLeadingParents = false,
            ).split('/').filter { it.isNotEmpty() }
            val parentComponents = entryName.substringBeforeLast('/', "")
                .split('/').filter { it.isNotEmpty() }

            var common = 0
            while (
                common < parentComponents.size &&
                common < guestComponents.size &&
                parentComponents[common] == guestComponents[common]
            ) {
                common++
            }

            val relative = buildList {
                repeat(parentComponents.size - common) { add("..") }
                addAll(guestComponents.drop(common))
            }.joinToString("/").ifEmpty { "." }

            val resolved = if (parentComponents.isEmpty()) {
                relative
            } else {
                parentComponents.joinToString("/") + "/" + relative
            }
            normalizeEntryName(resolved, stripLeadingParents = false)
            return relative
        }

        val parent = entryName.substringBeforeLast('/', "")
        val combined = if (parent.isEmpty()) target else "$parent/$target"
        normalizeEntryName(combined, stripLeadingParents = false)
        return target
    }

    private fun display(value: String): String =
        value.replace("\n", "\\n").replace("\t", "\\t").take(240)
}
