package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileSystemException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test

class AmbientWorkerOwnerLockSymlinkTest {
    private fun tempDir() = Files.createTempDirectory("ambient-lock-symlink").toFile()

    private fun symlink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (failure: FileSystemException) {
            val reason = failure.reason.orEmpty()
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows") ||
                !(reason.contains("privilege", ignoreCase = true) || reason.contains("特权"))) throw failure
            assumeNoException("Windows host lacks symbolic-link privilege; Linux CI runs these checks", failure)
        }
    }

    @Test fun aPlantedSymlinkIsRefusedAndItsTargetIsNotCreated() {
        val dir = tempDir()
        try {
            val victim = File(dir, "victim")
            val lock = File(dir, "lock")
            symlink(lock, victim)
            try {
                AmbientWorkerOwnerLock.tryAcquire(lock)?.close()
                fail("a symlinked lock path must be refused")
            } catch (_: IOException) { }
            assertFalse("the worker created a file through the symlink", victim.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSymlinkToAnExistingFileIsRefused() {
        val dir = tempDir()
        try {
            val victim = File(dir, "victim").apply { writeText("keep") }
            val lock = File(dir, "lock")
            symlink(lock, victim)
            try {
                AmbientWorkerOwnerLock.tryAcquire(lock)?.close()
                fail("a symlinked lock path must be refused")
            } catch (_: IOException) { }
            assertEquals("keep", victim.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRegularFileInsideASymlinkedDirectoryStillLocks() {
        val dir = tempDir()
        try {
            val real = File(dir, "real").apply { mkdir() }
            val linked = File(dir, "linked")
            symlink(linked, real)
            AmbientWorkerOwnerLock.tryAcquire(File(linked, "lock")).also { assertNotNull(it) }?.close()
        } finally { dir.deleteRecursively() }
    }
}
