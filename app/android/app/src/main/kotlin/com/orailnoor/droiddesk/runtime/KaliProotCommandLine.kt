package com.orailnoor.droiddesk.runtime

/**
 * Builds argv for the bundled PRoot version.
 *
 * This PRoot build does not implement getopt's standalone "--" terminator.
 * PRoot treats the first guest executable after its options as the command,
 * so the guest argv must follow "-w <working-directory>" directly.
 */
internal object KaliProotCommandLine {
    fun build(
        prootExecutable: String,
        rootfsPath: String,
        workingDirectory: String,
        bindMounts: List<String>,
        guestCommand: List<String>,
    ): List<String> {
        require(prootExecutable.isNotBlank()) { "PRoot executable is required" }
        require(rootfsPath.startsWith("/")) { "PRoot rootfs path must be absolute" }
        require(workingDirectory.startsWith("/")) { "PRoot working directory must be absolute" }
        require(guestCommand.isNotEmpty()) { "PRoot guest command cannot be empty" }
        require(bindMounts.none { it.isBlank() }) { "PRoot bind mount cannot be blank" }

        return buildList {
            add(prootExecutable)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfsPath)
            bindMounts.forEach { mount ->
                add("-b")
                add(mount)
            }
            add("-w")
            add(workingDirectory)
            addAll(guestCommand)
        }
    }
}
