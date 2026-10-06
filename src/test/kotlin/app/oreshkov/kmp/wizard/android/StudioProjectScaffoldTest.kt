package app.oreshkov.kmp.wizard.android

import app.oreshkov.kmp.wizard.COMMIT_BACKUP_DIR_PREFIX
import app.oreshkov.kmp.wizard.KMPProjectSettings
import app.oreshkov.kmp.wizard.generateStagedThenCommit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

/**
 * Covers the Android Studio adapter layer's non-obvious behaviour: the dry-run protocol
 * that keeps Studio's two recipe passes from generating the project twice, the cleanup of
 * Studio's own project skeleton, and the Pro re-gating in the settings mapping.
 *
 * Plain JUnit — [StudioProjectScaffold] deliberately touches no Android or platform API.
 */
class StudioProjectScaffoldTest {

    private lateinit var rootDir: File

    @Before fun setUp() {
        rootDir = createTempDirectory("kmp_studio_test_").toFile()
    }

    @After fun tearDown() {
        rootDir.deleteRecursively()
    }

    // ── Dry-run protocol ──────────────────────────────────────────────────────

    @Test fun `first pass is the dry run and the second is not`() {
        assertTrue("Studio's first recipe pass must be reported as the dry run", isDryRunPass(rootDir))
        assertFalse("Studio's second recipe pass must be reported as the real one", isDryRunPass(rootDir))
    }

    @Test fun `the marker does not survive the real pass`() {
        isDryRunPass(rootDir)
        assertTrue("the dry run must leave the marker for the real pass to find",
            rootDir.resolve(DRY_RUN_MARKER_NAME).exists())

        isDryRunPass(rootDir)
        assertFalse("the marker must not be left behind in the generated project",
            rootDir.resolve(DRY_RUN_MARKER_NAME).exists())
    }

    @Test fun `the marker is created even when the root does not exist yet`() {
        val missing = rootDir.resolve("not-created-yet")

        assertTrue(isDryRunPass(missing))
        assertFalse(isDryRunPass(missing))
    }

    // ── Gallery thumbnail ─────────────────────────────────────────────────────

    @Test fun `the gallery thumbnail resolves from the plugin's own classloader`() {
        // Guards two things at once: that the PNG is actually packaged, and that it is
        // looked up against this plugin rather than the Android plugin — the template DSL
        // makes the latter mistake very easy to make and impossible to see in a build.
        assertNotNull("thumbnail missing from the plugin resources: $THUMB_RESOURCE", thumbnailUrl())
    }

    // ── Studio's default project files ────────────────────────────────────────

    // Removal happens inside generateStagedThenCommit (replacedPaths), so these drive the
    // real commit with the recipe's own path list and a stand-in for the rendered project.

    @Test fun `every default Studio path is superseded by the commit`() {
        writeStudioDefaults()

        runBlocking {
            generateStagedThenCommit(rootDir, replacedPaths = ANDROID_STUDIO_DEFAULT_PATHS) { staging ->
                staging.resolve("settings.gradle.kts").writeText("rootProject.name = \"kmp\"")
            }
        }

        assertEquals("rootProject.name = \"kmp\"", rootDir.resolve("settings.gradle.kts").readText())
        val survivors = ANDROID_STUDIO_DEFAULT_PATHS
            .filter { it != "settings.gradle.kts" }
            .filter { rootDir.resolve(it).exists() }
        assertEquals("Studio's own project files must not survive into the generated project",
            emptyList<String>(), survivors)
        assertEquals("no backup directory may be left behind",
            emptyList<String>(), rootDir.list()!!.filter { it.startsWith(COMMIT_BACKUP_DIR_PREFIX) })
    }

    @Test fun `absent defaults are not a failure`() {
        // Which of the paths Studio actually writes depends on the options picked on its
        // first page (Kotlin vs Groovy DSL in particular), so missing entries are normal.
        runBlocking {
            generateStagedThenCommit(rootDir, replacedPaths = ANDROID_STUDIO_DEFAULT_PATHS) { staging ->
                staging.resolve("build.gradle.kts").writeText("plugins { }")
            }
        }
        assertTrue(rootDir.resolve("build.gradle.kts").isFile)
    }

    @Test fun `unrelated files are left alone`() {
        rootDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"studio\"")
        rootDir.resolve("keep-me.txt").writeText("mine")
        rootDir.resolve("appearances").mkdirs() // prefix-collides with "app"

        runBlocking {
            generateStagedThenCommit(rootDir, replacedPaths = ANDROID_STUDIO_DEFAULT_PATHS) { }
        }

        assertTrue(rootDir.resolve("keep-me.txt").exists())
        assertTrue("a prefix collision must not drag an unrelated directory out",
            rootDir.resolve("appearances").exists())
    }

    @Test fun `a failed render leaves Studio's defaults untouched`() {
        writeStudioDefaults()

        runCatching {
            runBlocking {
                generateStagedThenCommit(rootDir, replacedPaths = ANDROID_STUDIO_DEFAULT_PATHS) {
                    throw IOException("render failed")
                }
            }
        }

        assertEquals("plugins { }", rootDir.resolve("app/build.gradle.kts").readText())
        assertTrue(rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").isFile)
        assertTrue(rootDir.resolve("settings.gradle.kts").isFile)
    }

    @Test fun `a commit that fails part-way restores Studio's defaults`() {
        writeStudioDefaults()
        var copies = 0

        val result = runCatching {
            runBlocking {
                generateStagedThenCommit(
                    rootDir,
                    replacedPaths = ANDROID_STUDIO_DEFAULT_PATHS,
                    copyFile = { source, target ->
                        if (++copies == 2) throw IOException("disk full")
                        Files.copy(source, target)
                    },
                ) { staging ->
                    staging.resolve("settings.gradle.kts").writeText("rootProject.name = \"kmp\"")
                    staging.resolve("shared").mkdirs()
                    staging.resolve("shared/build.gradle.kts").writeText("kotlin { }")
                }
            }
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("rootProject.name = \"studio\"", rootDir.resolve("settings.gradle.kts").readText())
        assertEquals("plugins { }", rootDir.resolve("app/build.gradle.kts").readText())
        assertTrue(rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").isFile)
        assertFalse("generated files must be rolled back", rootDir.resolve("shared").exists())
        assertEquals("no backup directory may be left behind",
            emptyList<String>(), rootDir.list()!!.filter { it.startsWith(COMMIT_BACKUP_DIR_PREFIX) })
    }

    /** The scaffold Studio writes before the recipe runs, with both directories populated. */
    private fun writeStudioDefaults() {
        rootDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"studio\"")
        rootDir.resolve("build.gradle.kts").writeText("// studio root")
        rootDir.resolve("gradlew").writeText("#!/bin/sh")
        rootDir.resolve(".gitignore").writeText("/build")
        rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").apply {
            parentFile.mkdirs()
            writeText("distributionUrl=…")
        }
        rootDir.resolve("app/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("plugins { }")
        }
    }

    // ── Settings mapping ──────────────────────────────────────────────────────

    @Test fun `pro flags are forced off without an entitlement`() {
        val settings = settings(includeAgentConfig = true, includeCi = true, pro = false)

        assertFalse("agent config must never be generated unlicensed", settings.includeAgentConfig)
        assertFalse("CI scaffolding must never be generated unlicensed", settings.includeCi)
    }

    @Test fun `pro flags are honoured with an entitlement`() {
        val settings = settings(includeAgentConfig = true, includeCi = false, pro = true)

        assertTrue(settings.includeAgentConfig)
        assertFalse("an entitlement must not force a deselected feature back on", settings.includeCi)
    }

    @Test fun `free-form feature and field names are normalized`() {
        val settings = valid(names(feature = "My Feature", field = "User Name"))

        assertEquals("my_feature", settings.featureName)
        assertEquals("user_name", settings.fieldName)
    }

    @Test fun `names that cannot become an identifier are rejected, naming the parameter`() {
        assertEquals(StudioInput.InvalidName(NameParameter.FEATURE, "2fa"), names(feature = "2fa", field = "content"))
        assertEquals(StudioInput.InvalidName(NameParameter.FEATURE, "заметка"), names(feature = "заметка", field = "content"))
        assertEquals(StudioInput.InvalidName(NameParameter.FIELD, "object"), names(feature = "note", field = "object"))
        assertEquals(StudioInput.InvalidName(NameParameter.FIELD, "!!!"), names(feature = "note", field = "!!!"))
    }

    @Test fun `studio's own first-page values and the platform flags carry through`() {
        val settings = valid(studioSettings(
            appName = "My Ledger",
            packageName = "com.example.ledger",
            featureName = "posting",
            fieldName = "narrative",
            testValueName = "Groceries",
            includeAndroid = true,
            includeDesktop = false,
            includeIos = true,
            includeAgentConfig = false,
            includeCi = false,
            pro = false,
        ))

        assertEquals("My Ledger", settings.appName)
        assertEquals("com.example.ledger", settings.packageName)
        assertEquals("posting", settings.featureName)
        assertEquals("narrative", settings.fieldName)
        assertEquals("Groceries", settings.testValueName)
        assertTrue(settings.includeAndroid)
        assertFalse(settings.includeDesktop)
        assertTrue(settings.includeIos)
    }

    @Test fun `deselecting every platform falls back to Android`() {
        // Studio's template DSL has no cross-parameter validator, so unlike the IDEA path
        // this cannot be rejected in the form — generating with nothing selected would
        // strip every platform module and leave an unbuildable project.
        val settings = platforms(android = false, desktop = false, ios = false)

        assertTrue(settings.includeAndroid)
        assertFalse(settings.includeDesktop)
        assertFalse(settings.includeIos)
    }

    @Test fun `an explicit selection is never widened`() {
        val settings = platforms(android = false, desktop = true, ios = false)

        assertFalse("Android must stay off when another platform was chosen", settings.includeAndroid)
        assertTrue(settings.includeDesktop)
        assertFalse(settings.includeIos)
    }

    private fun valid(input: StudioInput): KMPProjectSettings =
        (input as? StudioInput.Valid ?: error("expected valid input, got $input")).settings

    private fun names(feature: String, field: String) =
        studioSettings(
            appName = "MyApp",
            packageName = "com.example.myapp",
            featureName = feature,
            fieldName = field,
            testValueName = "Buy groceries",
            includeAndroid = true,
            includeDesktop = true,
            includeIos = true,
            includeAgentConfig = false,
            includeCi = false,
            pro = false,
        )

    private fun platforms(android: Boolean, desktop: Boolean, ios: Boolean) = valid(
        studioSettings(
            appName = "MyApp",
            packageName = "com.example.myapp",
            featureName = "note",
            fieldName = "content",
            testValueName = "Buy groceries",
            includeAndroid = android,
            includeDesktop = desktop,
            includeIos = ios,
            includeAgentConfig = false,
            includeCi = false,
            pro = false,
        ),
    )

    private fun settings(includeAgentConfig: Boolean, includeCi: Boolean, pro: Boolean) = valid(
        studioSettings(
            appName = "MyApp",
            packageName = "com.example.myapp",
            featureName = "note",
            fieldName = "content",
            testValueName = "Buy groceries",
            includeAndroid = true,
            includeDesktop = true,
            includeIos = true,
            includeAgentConfig = includeAgentConfig,
            includeCi = includeCi,
            pro = pro,
        ),
    )
}
