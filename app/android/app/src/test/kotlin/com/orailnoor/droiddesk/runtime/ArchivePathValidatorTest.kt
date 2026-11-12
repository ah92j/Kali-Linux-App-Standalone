package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchivePathValidatorTest {
    @Test
    fun stripsLeadingParentComponentsFromRootfsEntry() {
        assertEquals(
            "usr/bin/bash",
            ArchivePathValidator.normalizeEntryName("../.././usr/bin/bash"),
        )
    }

    @Test
    fun normalizesDotAndInternalParentComponents() {
        assertEquals(
            "usr/lib/example.so",
            ArchivePathValidator.normalizeEntryName("./usr/bin/../lib/example.so"),
        )
    }

    @Test
    fun rejectsTraversalThatWouldEscapeTheRoot() {
        assertThrows(SecurityException::class.java) {
            ArchivePathValidator.normalizeEntryName("usr/lib/../../../../data/passwd")
        }
    }

    @Test
    fun rejectsAbsoluteAndWindowsDrivePaths() {
        assertThrows(SecurityException::class.java) {
            ArchivePathValidator.normalizeEntryName("/etc/passwd")
        }
        assertThrows(SecurityException::class.java) {
            ArchivePathValidator.normalizeEntryName("C:/Windows/system.ini")
        }
    }

    @Test
    fun acceptsRelativeSymlinkThatStaysInsideRoot() {
        assertEquals(
            "../lib",
            ArchivePathValidator.validateSymbolicLinkTarget("usr/bin/tool", "../lib"),
        )
    }

    @Test
    fun rewritesGuestAbsoluteSymlinkAsRelativeLink() {
        assertEquals(
            "../run",
            ArchivePathValidator.validateSymbolicLinkTarget("var/run", "/run"),
        )
        assertEquals(
            "env",
            ArchivePathValidator.validateSymbolicLinkTarget("usr/bin/env", "/usr/bin/env"),
        )
    }

    @Test
    fun acceptsSystemdEscapedUnitNamesContainingBackslashes() {
        val name = "kali-arm64/usr/lib/systemd/system/systemd\\x2dmute\\x2dconsole.slice"
        assertEquals(
            name,
            ArchivePathValidator.normalizeEntryName(name),
        )
        assertEquals(
            "usr/lib/systemd/system/systemd\\x2dmute\\x2dconsole.slice",
            ArchivePathValidator.stripExpectedArchiveRoot(
                ArchivePathValidator.normalizeEntryName(name),
                "kali-arm64",
            ),
        )
    }

    @Test
    fun stripsKaliArchiveRootAndRejectsMembersOutsideIt() {
        assertEquals(
            "",
            ArchivePathValidator.stripExpectedArchiveRoot("kali-arm64", "kali-arm64"),
        )
        assertEquals(
            "etc/os-release",
            ArchivePathValidator.stripExpectedArchiveRoot("kali-arm64/etc/os-release", "kali-arm64"),
        )
        assertThrows(SecurityException::class.java) {
            ArchivePathValidator.stripExpectedArchiveRoot("ubuntu/etc/os-release", "kali-arm64")
        }
    }

    @Test
    fun acceptsBackslashAsAnOrdinaryPosixSymlinkTargetCharacter() {
        assertEquals(
            "../systemd\\x2dmute.slice",
            ArchivePathValidator.validateSymbolicLinkTarget(
                "usr/lib/systemd/system/multi-user.target",
                "../systemd\\x2dmute.slice",
            ),
        )
    }

    @Test
    fun convertsArchiveHardlinkTargetToSafeRelativeSymlinkPath() {
        assertEquals(
            "halt",
            ArchivePathValidator.relativeSymlinkTarget(
                "usr/lib/klibc/bin/reboot",
                "usr/lib/klibc/bin/halt",
            ),
        )
        assertEquals(
            "../lib/systemd/system/example.slice",
            ArchivePathValidator.relativeSymlinkTarget(
                "usr/bin/example",
                "usr/lib/systemd/system/example.slice",
            ),
        )
        assertThrows(SecurityException::class.java) {
            ArchivePathValidator.relativeSymlinkTarget(
                "usr/bin/example",
                "../../../../etc/passwd",
            )
        }
    }

    @Test
    fun rejectsRelativeSymlinkThatEscapesRoot() {
        assertThrows(SecurityException::class.java) {
            ArchivePathValidator.validateSymbolicLinkTarget("usr/bin/tool", "../../../../data")
        }
    }
}
