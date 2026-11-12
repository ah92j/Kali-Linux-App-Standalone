package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaliDpkgStatusParserTest {
    @Test
    fun parsesFullyInstalledArm64AndArchitectureIndependentPackages() {
        val status = """
            Package: firefox-esr
            Status: install ok installed
            Version: 153.5.1esr-1
            Architecture: arm64
            Section: web

            Package: fonts-dejavu-core
            Status: install ok installed
            Version: 2.37-8
            Architecture: all
            Section: fonts
        """.trimIndent()

        val parsed = KaliDpkgStatusParser.parseInstalled(status)
        assertEquals(listOf("firefox-esr", "fonts-dejavu-core"), parsed.map { it.name })
        assertEquals(listOf("arm64", "all"), parsed.map { it.architecture })
    }

    @Test
    fun ignoresUnpackedPackages() {
        val status = """
            Package: python3
            Status: install ok unpacked
            Version: 3.13.7-1
            Architecture: arm64

            Package: geany
            Status: install ok installed
            Version: 2.1-2
            Architecture: arm64
        """.trimIndent()

        assertEquals(listOf("geany"), KaliDpkgStatusParser.parseInstalled(status).map { it.name })
    }

    @Test
    fun ignoresHalfConfiguredRemovedAndNonArmPackages() {
        val status = """
            Package: broken-app
            Status: install ok half-configured
            Version: 1
            Architecture: arm64

            Package: removed-app
            Status: deinstall ok config-files
            Version: 1
            Architecture: arm64

            Package: wrong-arch
            Status: install ok installed
            Version: 1
            Architecture: amd64
        """.trimIndent()

        assertTrue(KaliDpkgStatusParser.parseInstalled(status).isEmpty())
    }

    @Test
    fun supportsCrLfAndDoesNotTreatIndentedContinuationAsAField() {
        val status = "Package: app\r\nStatus: install ok installed\r\nVersion: 1\r\nArchitecture: arm64\r\nDescription: first line\r\n second line\r\n"
        val parsed = KaliDpkgStatusParser.parseInstalled(status)

        assertEquals(1, parsed.size)
        assertEquals("app", parsed.single().name)
        assertEquals("1", parsed.single().version)
    }
}
