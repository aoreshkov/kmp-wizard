package app.oreshkov.kmp.wizard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createTempDirectory

/**
 * Contract tests for [generateStagedThenCommit]: whatever the generation lambda does,
 * the project root is only written on full success, the staging directory is always
 * cleaned up, and exceptions (including cancellation) propagate unchanged.
 */
class StagedGenerationTest {

    private lateinit var tempDir: File
    private lateinit var rootDir: File

    @Before fun setUp() {
        tempDir = createTempDirectory("kmp_staged_test_").toFile()
        // Deliberately not created up front — mirrors the wizard, where the project
        // root does not exist until the commit copy creates it.
        rootDir = tempDir.resolve("project")
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test fun `success commits the full staged tree to the root and deletes staging`() = runBlocking {
        var staging: File? = null

        generateStagedThenCommit(rootDir) { s ->
            staging = s
            s.resolve("settings.gradle.kts").writeText("rootProject.name = \"app\"\n")
            s.resolve("feature/impl").mkdirs()
            s.resolve("feature/impl/Screen.kt").writeText("class Screen\n")
            s.resolve("gradlew").writeText("#!/bin/sh\n")
        }

        assertEquals("rootProject.name = \"app\"\n", rootDir.resolve("settings.gradle.kts").readText())
        assertEquals("class Screen\n", rootDir.resolve("feature/impl/Screen.kt").readText())
        assertTrue("gradlew should be committed", rootDir.resolve("gradlew").isFile)
        if (!System.getProperty("os.name").startsWith("Windows")) {
            assertTrue("gradlew executable bit should be restored", rootDir.resolve("gradlew").canExecute())
        }
        assertNotNull(staging)
        assertFalse("staging must be deleted after success", staging!!.exists())
    }

    @Test fun `a generation failure leaves the root untouched, deletes staging, and propagates`() = runBlocking {
        var staging: File? = null

        try {
            generateStagedThenCommit(rootDir) { s ->
                staging = s
                s.resolve("half-written.kt").writeText("partial")
                throw IOException("disk full")
            }
            fail("expected the IOException to propagate")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }

        assertFalse("project root must not exist after a failed generation", rootDir.exists())
        assertFalse("staging must be deleted after failure", staging!!.exists())
    }

    @Test fun `a CancellationException from generation leaves the root untouched and cleans up`() = runBlocking {
        var staging: File? = null

        try {
            generateStagedThenCommit(rootDir) { s ->
                staging = s
                s.resolve("half-written.kt").writeText("partial")
                throw CancellationException("user cancelled")
            }
            fail("expected the CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected
        }

        assertFalse("project root must not exist after cancellation", rootDir.exists())
        assertFalse("staging must be deleted after cancellation", staging!!.exists())
    }

    @Test fun `cancellation after generation but before commit never touches the root`() = runBlocking {
        var staging: File? = null

        // Cancel the job from inside the lambda and return normally: ensureActive()
        // must then bail out before the commit copy.
        val job = launch {
            generateStagedThenCommit(rootDir) { s ->
                staging = s
                s.resolve("generated.kt").writeText("done")
                coroutineContext[Job]!!.cancel()
            }
        }
        job.join()

        assertTrue("job should end cancelled", job.isCancelled)
        assertFalse("project root must not exist — commit must not run", rootDir.exists())
        assertFalse("staging must be deleted on the NonCancellable path", staging!!.exists())
    }
}
