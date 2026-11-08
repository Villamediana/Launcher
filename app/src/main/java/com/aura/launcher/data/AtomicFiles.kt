package com.aura.launcher.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * The same scheme as android.util.AtomicFile, written out so it runs off the
 * phone too and reads what the phone already wrote: the new copy goes to
 * "<name>.new" and is renamed over the file once it is on disk, and a
 * "<name>.bak" left by older Android versions mid-write is the good copy.
 */

/** Replaces a small text file without exposing a truncated intermediate copy. */
fun File.writeTextAtomically(text: String) {
    val parent = parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
        throw IOException("Could not create ${parent.absolutePath}")
    }
    recoverLegacyBackup()
    val fresh = File(path + NEW_SUFFIX)
    val output = FileOutputStream(fresh)
    try {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.fd.sync()
        output.close()
    } catch (failure: Throwable) {
        runCatching { output.close() }
        fresh.delete()
        throw failure
    }
    try {
        replaceWith(fresh)
    } catch (failure: Throwable) {
        fresh.delete()
        throw IOException("Could not replace $this", failure)
    }
}

/** Reads the file, first recovering whatever an interrupted previous write left behind. */
fun File.readTextAtomically(): String {
    recoverLegacyBackup()
    val fresh = File(path + NEW_SUFFIX)
    if (fresh.exists() && exists()) fresh.delete()
    return FileInputStream(this).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private fun File.recoverLegacyBackup() {
    val backup = File(path + LEGACY_BACKUP_SUFFIX)
    if (backup.exists()) {
        replaceWith(backup)
    }
}

/**
 * [File.renameTo] cannot replace an existing destination on Windows.  Use the
 * NIO operation that explicitly requests replacement, first atomically and
 * then with short retries for scanners/indexers that momentarily hold a file.
 */
private fun File.replaceWith(fresh: File) {
    val source = fresh.toPath()
    val destination = toPath()
    try {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return
    } catch (_: AtomicMoveNotSupportedException) {
        // Some Android filesystems and Windows network locations do not offer it.
    } catch (_: IOException) {
        // Windows Defender/indexers can briefly keep the destination busy.
    }

    var lastFailure: IOException? = null
    for (delayMs in REPLACE_RETRY_DELAYS_MS) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while replacing $this", interrupted)
            }
        }
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (failure: IOException) {
            lastFailure = failure
        }
    }
    throw lastFailure ?: IOException("Could not replace $this")
}

private const val NEW_SUFFIX = ".new"
private const val LEGACY_BACKUP_SUFFIX = ".bak"
private val REPLACE_RETRY_DELAYS_MS = longArrayOf(0, 25, 75, 150)
