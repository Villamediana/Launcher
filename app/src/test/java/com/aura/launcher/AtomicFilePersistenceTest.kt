package com.aura.launcher

import android.util.AtomicFile
import com.aura.launcher.data.readTextAtomically
import com.aura.launcher.data.writeTextAtomically
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AtomicFilePersistenceTest {

    @Test
    fun `an interrupted replacement restores the last complete value`() {
        val directory = Files.createTempDirectory("aura-atomic").toFile()
        try {
            val file = directory.resolve("store.json")
            file.writeTextAtomically("complete")

            // Simulate a process dying after startWrite but before finishWrite.
            AtomicFile(file).startWrite().use { it.write("partial".toByteArray()) }

            assertEquals("complete", file.readTextAtomically())
        } finally {
            directory.deleteRecursively()
        }
    }
}
