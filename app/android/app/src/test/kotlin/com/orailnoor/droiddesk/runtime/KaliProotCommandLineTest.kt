package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaliProotCommandLineTest {
    @Test
    fun placesGuestCommandImmediatelyAfterWorkingDirectory() {
        val command = KaliProotCommandLine.build(
            prootExecutable = "/data/user/0/app/files/usr/bin/proot",
            rootfsPath = "/data/user/0/app/files/rootfs",
            workingDirectory = "/home/kali",
            bindMounts = listOf(
                "/dev",
                "/proc",
                "/data/user/0/app/cache/tmp:/tmp",
                "/data/user/0/app/cache/shm:/dev/shm",
            ),
            guestCommand = listOf("/usr/bin/env", "-i", "HOME=/home/kali"),
        )

        val workingDirectoryIndex = command.indexOf("/home/kali")
        assertTrue(workingDirectoryIndex > 0)
        assertEquals("/usr/bin/env", command[workingDirectoryIndex + 1])
        assertEquals("-i", command[workingDirectoryIndex + 2])
        assertEquals("HOME=/home/kali", command[workingDirectoryIndex + 3])
        assertFalse(command.contains("--"))
    }

    @Test
    fun preservesGuestArgumentsThatStartWithDashes() {
        val command = KaliProotCommandLine.build(
            prootExecutable = "/app/proot",
            rootfsPath = "/app/kali",
            workingDirectory = "/root",
            bindMounts = emptyList(),
            guestCommand = listOf("/usr/bin/env", "-i", "PATH=/usr/bin:/bin"),
        )

        assertEquals(listOf("/usr/bin/env", "-i", "PATH=/usr/bin:/bin"), command.takeLast(3))
        assertFalse(command.drop(1).takeWhile { it.startsWith("-") }.contains("--"))
    }

    @Test
    fun rejectsInvalidPathsAndEmptyGuestCommand() {
        try {
            KaliProotCommandLine.build(
                prootExecutable = "/app/proot",
                rootfsPath = "relative/root",
                workingDirectory = "/root",
                bindMounts = emptyList(),
                guestCommand = listOf("/bin/sh"),
            )
            throw AssertionError("Expected invalid rootfs path to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        try {
            KaliProotCommandLine.build(
                prootExecutable = "/app/proot",
                rootfsPath = "/app/kali",
                workingDirectory = "/root",
                bindMounts = emptyList(),
                guestCommand = emptyList(),
            )
            throw AssertionError("Expected empty guest command to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
