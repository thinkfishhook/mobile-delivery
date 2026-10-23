package delivery

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock

// File locks coordinate processes; this guard prevents overlapping locks in one JVM.
private val signingGuard = ReentrantLock()

fun <T> withSigningLock(home: Path, action: () -> T): T {
    check(!signingGuard.isHeldByCurrentThread) { "Signing sessions cannot be nested" }
    signingGuard.lockInterruptibly()
    try {
        val directory = home.resolve(".mobile-delivery")
        Files.createDirectories(
            directory,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        // Keep this inode between runs: deleting it would let waiters lock different files.
        FileChannel.open(
                directory.resolve("signing.lock"),
                setOf(CREATE, WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
            .use { channel ->
                channel.lock().use {
                    return action()
                }
            }
    } finally {
        signingGuard.unlock()
    }
}
