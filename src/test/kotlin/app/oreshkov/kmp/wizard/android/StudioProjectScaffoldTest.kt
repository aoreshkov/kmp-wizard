package app.oreshkov.kmp.wizard.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
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

    @Test fun `every default Studio path is removed`() {
        ANDROID_STUDIO_DEFAULT_PATHS.forEach { rootDir.resolve(it).mkdirs() }
        // The two that Studio writes as directories, populated so the delete has to recurse.
        rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").apply {
            parentFile.mkdirs()
            writeText("distributionUrl=…")
        }
        rootDir.resolve("app/build.gradle.kts").writeText("plugins { }")

        assertTrue(deleteAndroidStudioDefaults(rootDir))

        val survivors = ANDROID_STUDIO_DEFAULT_PATHS.filter { rootDir.resolve(it).exists() }
        assertEquals("Studio's own project files must not survive into the generated project",
            emptyList<String>(), survivors)
    }

    @Test fun `absent defaults are not a failure`() {
        // Which of the paths Studio actually writes depends on the options picked on its
        // first page (Kotlin vs Groovy DSL in particular), so missing entries are normal.
        assertTrue(deleteAndroidStudioDefaults(rootDir))
    }

    @Test fun `unrelated files are left alone`() {
        rootDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"studio\"")
        rootDir.resolve("keep-me.txt").writeText("mine")
        rootDir.resolve("appearances").mkdirs() // prefix-collides with "app"

        deleteAndroidStudioDefaults(rootDir)

        assertTrue(rootDir.resolve("keep-me.txt").exists())
        assertTrue("a prefix collision must not drag an unrelated directory out",
            rootDir.resolve("appearances").exists())
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

    @Test fun `studio's own first-page values and the platform flags carry through`() {
        val settings = studioSettings(
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
        )

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

    private fun platforms(android: Boolean, desktop: Boolean, ios: Boolean) =
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
        )

    private fun settings(includeAgentConfig: Boolean, includeCi: Boolean, pro: Boolean) =
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
        )
}
