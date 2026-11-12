package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaliInstallCoordinatorTest {
    @Test
    fun preventsASecondInstallWhileTheFirstOneIsRunning() {
        assertTrue(KaliInstallCoordinator.tryStart())
        try {
            KaliInstallCoordinator.report(0.42, "Installing Kali default packages...")
            assertFalse(KaliInstallCoordinator.tryStart())

            val state = KaliInstallCoordinator.snapshot()
            assertEquals(true, state["running"])
            assertEquals(0.42, state["progress"] as Double, 0.0001)
            assertEquals("Installing Kali default packages...", state["status"])
        } finally {
            KaliInstallCoordinator.finish(false)
        }

        assertFalse(KaliInstallCoordinator.snapshot()["running"] as Boolean)
        assertTrue(KaliInstallCoordinator.tryStart())
        KaliInstallCoordinator.finish(false)
    }

    @Test
    fun preservesSuccessfulCompletionState() {
        assertTrue(KaliInstallCoordinator.tryStart())
        KaliInstallCoordinator.report(0.99, "Verifying Kali packages...")
        KaliInstallCoordinator.finish(true)

        val state = KaliInstallCoordinator.snapshot()
        assertFalse(state["running"] as Boolean)
        assertTrue(state["succeeded"] as Boolean)
        assertEquals(1.0, state["progress"] as Double, 0.0001)
    }
}
