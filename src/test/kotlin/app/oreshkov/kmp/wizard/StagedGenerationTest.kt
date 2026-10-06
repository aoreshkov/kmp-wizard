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
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

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
        assertNotNull(staging)
        assertFalse("staging must be deleted after success", staging!!.exists())
    }

    @Test fun `the committed gradlew is executable`() = runBlocking {
        // Reported as skipped (not silently passed) where there is no POSIX exec bit.
        assumeFalse("Windows has no POSIX executable bit", isWindows)
        generateStagedThenCommit(rootDir) { s -> s.resolve("gradlew").writeText("#!/bin/sh\n") }
        assertTrue("gradlew executable bit should be restored", rootDir.resolve("gradlew").canExecute())
    }

    // ── Commit atomicity ──────────────────────────────────────────────────────

    @Test fun `a commit that fails part-way removes everything it wrote, root included`() = runBlocking {
        var copies = 0
        val failing: (Path, Path) -> Unit = { source, target ->
            if (++copies == 3) throw IOException("disk full")
            Files.copy(source, target)
        }

        try {
            generateStagedThenCommit(rootDir, copyFile = failing) { s -> writeProject(s) }
            fail("expected the IOException to propagate")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }

        assertEquals("two files were copied before the failure", 3, copies)
        assertFalse("a root the commit created must be removed again", rootDir.exists())
    }

    @Test fun `a commit that fails part-way restores the root's existing files`() = runBlocking {
        // Mirrors the IDE having already written files into the project root.
        rootDir.resolve(".idea").mkdirs()
        rootDir.resolve(".idea/workspace.xml").writeText("<ide/>")
        rootDir.resolve("settings.gradle.kts").writeText("// pre-existing")
        var copies = 0

        val result = runCatching {
            generateStagedThenCommit(rootDir, copyFile = { source, target ->
                if (++copies == 3) throw IOException("disk full")
                Files.copy(source, target)
            }) { s -> writeProject(s) }
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("<ide/>", rootDir.resolve(".idea/workspace.xml").readText())
        assertEquals("an overwritten file must be put back", "// pre-existing", rootDir.resolve("settings.gradle.kts").readText())
        assertEquals("nothing else may remain in the root",
            setOf(".idea", "settings.gradle.kts"), rootDir.list()!!.toSet())
    }

    @Test fun `a successful commit over existing files overwrites them and leaves no backup`() = runBlocking {
        rootDir.mkdirs()
        rootDir.resolve("settings.gradle.kts").writeText("// pre-existing")
        rootDir.resolve("keep.txt").writeText("mine")

        generateStagedThenCommit(rootDir) { s -> writeProject(s) }

        assertEquals("rootProject.name = \"app\"\n", rootDir.resolve("settings.gradle.kts").readText())
        assertEquals("mine", rootDir.resolve("keep.txt").readText())
        assertEquals(emptyList<String>(), rootDir.list()!!.filter { it.startsWith(COMMIT_BACKUP_DIR_PREFIX) })
    }

    @Test fun `replaced paths are removed on success and restored on failure`() = runBlocking {
        rootDir.resolve("app").mkdirs()
        rootDir.resolve("app/build.gradle.kts").writeText("plugins { }")

        runCatching {
            generateStagedThenCommit(rootDir, replacedPaths = listOf("app", "absent")) { throw IOException("render failed") }
        }
        assertEquals("a render failure must not touch replaced paths",
            "plugins { }", rootDir.resolve("app/build.gradle.kts").readText())

        generateStagedThenCommit(rootDir, replacedPaths = listOf("app", "absent")) { s -> writeProject(s) }
        assertFalse("a replaced path must be gone after a successful commit", rootDir.resolve("app").exists())
        assertTrue(rootDir.resolve("feature/impl/Screen.kt").isFile)
    }

    private fun writeProject(staging: File) {
        staging.resolve("settings.gradle.kts").writeText("rootProject.name = \"app\"\n")
        staging.resolve("feature/impl").mkdirs()
        staging.resolve("feature/impl/Screen.kt").writeText("class Screen\n")
        staging.resolve("feature/impl/Model.kt").writeText("class Model\n")
        staging.resolve("gradlew").writeText("#!/bin/sh\n")
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
