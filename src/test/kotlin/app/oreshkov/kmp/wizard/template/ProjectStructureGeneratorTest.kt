package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ProjectStructureGeneratorTest {

    private lateinit var tempDir: File

    @Before fun setUp() {
        tempDir = createTempDirectory("kmp_ledger_test_").toFile()
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Platform directory removal ───────────────────────────────────────────

    @Test fun `androidApp directory is removed when Android is excluded`() {
        seedPlatformDirs()
        generatorWith(includeAndroid = false).postProcess(tempDir)
        assertFalse(tempDir.resolve("androidApp").exists())
        assertTrue(tempDir.resolve("desktopApp").exists())
        assertTrue(tempDir.resolve("iosApp").exists())
    }

    @Test fun `desktopApp directory is removed when Desktop is excluded`() {
        seedPlatformDirs()
        generatorWith(includeDesktop = false).postProcess(tempDir)
        assertTrue(tempDir.resolve("androidApp").exists())
        assertFalse(tempDir.resolve("desktopApp").exists())
        assertTrue(tempDir.resolve("iosApp").exists())
    }

    @Test fun `iosApp and iosExport directories are removed when iOS is excluded`() {
        seedPlatformDirs()
        generatorWith(includeIos = false).postProcess(tempDir)
        assertTrue(tempDir.resolve("androidApp").exists())
        assertTrue(tempDir.resolve("desktopApp").exists())
        assertFalse(tempDir.resolve("iosApp").exists())
        assertFalse(tempDir.resolve("iosExport").exists())
    }

    @Test fun `all platform directories are present when all are selected`() {
        seedPlatformDirs()
        generatorWith().postProcess(tempDir)
        assertTrue(tempDir.resolve("androidApp").exists())
        assertTrue(tempDir.resolve("desktopApp").exists())
        assertTrue(tempDir.resolve("iosApp").exists())
        assertTrue(tempDir.resolve("iosExport").exists())
    }

    // ── settings.gradle.kts patching ─────────────────────────────────────────

    @Test fun `androidApp include is removed from settings when Android is excluded`() {
        seedSettingsGradle()
        generatorWith(includeAndroid = false).postProcess(tempDir)

        val content = tempDir.resolve("settings.gradle.kts").readText()
        assertFalse(content.contains(":androidApp"))
        assertTrue(content.contains(":desktopApp"))
        assertTrue(content.contains(":core:model"))
    }

    @Test fun `desktopApp include is removed from settings when Desktop is excluded`() {
        seedSettingsGradle()
        generatorWith(includeDesktop = false).postProcess(tempDir)

        val content = tempDir.resolve("settings.gradle.kts").readText()
        assertTrue(content.contains(":androidApp"))
        assertFalse(content.contains(":desktopApp"))
        assertTrue(content.contains(":core:model"))
    }

    @Test fun `iosExport include is removed from settings when iOS is excluded`() {
        seedSettingsGradle()
        generatorWith(includeIos = false).postProcess(tempDir)

        val content = tempDir.resolve("settings.gradle.kts").readText()
        assertTrue(content.contains(":androidApp"))
        assertTrue(content.contains(":desktopApp"))
        assertFalse(content.contains(":iosExport"))
        assertTrue(content.contains(":core:model"))
    }

    @Test fun `settings gradle is not modified when all platforms are selected`() {
        seedSettingsGradle()
        val before = tempDir.resolve("settings.gradle.kts").readText()
        generatorWith().postProcess(tempDir)
        val after = tempDir.resolve("settings.gradle.kts").readText()
        assertEquals(before, after)
    }

    @Test fun `missing settings gradle does not throw`() {
        // settings.gradle.kts intentionally absent — must not crash
        generatorWith(includeAndroid = false).postProcess(tempDir)
    }

    @Test fun `a prefix-colliding module is not stripped along with the excluded one`() {
        tempDir.resolve("settings.gradle.kts").writeText(
            """
            include(":androidApp")
            include(":androidAppSample")
            include(":core:model")
            """.trimIndent()
        )
        generatorWith(includeAndroid = false).postProcess(tempDir)

        val content = tempDir.resolve("settings.gradle.kts").readText()
        assertFalse(":androidApp include should be stripped", content.contains("\":androidApp\""))
        assertTrue(":androidAppSample must survive", content.contains(":androidAppSample"))
        assertTrue(content.contains(":core:model"))
    }

    @Test fun `known limitation - a multi-module include line is dropped whole`() {
        // The templates emit one include() per line, so line-based filtering is the
        // contract; this test encodes the limitation so a change to multi-module
        // include lines in the templates surfaces as a deliberate decision.
        tempDir.resolve("settings.gradle.kts").writeText(
            """
            include(":core:model", ":androidApp")
            include(":desktopApp")
            """.trimIndent()
        )
        generatorWith(includeAndroid = false).postProcess(tempDir)

        val content = tempDir.resolve("settings.gradle.kts").readText()
        assertFalse(content.contains(":androidApp"))
        assertFalse("collateral module on the same line is lost", content.contains(":core:model"))
        assertTrue(content.contains(":desktopApp"))
    }

    @Test fun `patched settings gradle keeps a trailing newline`() {
        seedSettingsGradle()
        generatorWith(includeAndroid = false).postProcess(tempDir)
        assertTrue(
            "patched file must end with a newline",
            tempDir.resolve("settings.gradle.kts").readText().endsWith("\n"),
        )
    }

    @Test fun `all platform directories are removed and settings is patched when all are excluded`() {
        seedPlatformDirs()
        seedSettingsGradle()
        generatorWith(includeAndroid = false, includeDesktop = false, includeIos = false).postProcess(tempDir)

        assertFalse(tempDir.resolve("androidApp").exists())
        assertFalse(tempDir.resolve("desktopApp").exists())
        assertFalse(tempDir.resolve("iosApp").exists())
        assertFalse(tempDir.resolve("iosExport").exists())

        val content = tempDir.resolve("settings.gradle.kts").readText()
        assertFalse(content.contains(":androidApp"))
        assertFalse(content.contains(":desktopApp"))
        assertFalse(content.contains(":iosExport"))
        assertTrue(content.contains(":core:model"))
    }

    // ── Pro-tier scaffolding removal ─────────────────────────────────────────

    @Test fun `agent config is removed when the agent config flag is off`() {
        seedProScaffolding()
        generatorWith(includeAgentConfig = false).postProcess(tempDir)
        assertFalse(tempDir.resolve("CLAUDE.md").exists())
        assertFalse(tempDir.resolve(".claude").exists())
        assertTrue(tempDir.resolve(".github").exists())
    }

    @Test fun `ci scaffolding is removed when the ci flag is off`() {
        seedProScaffolding()
        generatorWith(includeCi = false).postProcess(tempDir)
        assertTrue(tempDir.resolve("CLAUDE.md").exists())
        assertTrue(tempDir.resolve(".claude").exists())
        assertFalse(tempDir.resolve(".github").exists())
    }

    @Test fun `pro scaffolding is kept when both flags are on`() {
        seedProScaffolding()
        generatorWith().postProcess(tempDir)
        assertTrue(tempDir.resolve("CLAUDE.md").exists())
        assertTrue(tempDir.resolve(".claude").exists())
        assertTrue(tempDir.resolve(".github").exists())
    }

    @Test fun `missing pro scaffolding does not throw`() {
        // CLAUDE.md / .claude / .github intentionally absent — must not crash
        generatorWith(includeAgentConfig = false, includeCi = false).postProcess(tempDir)
    }

    // ── Public generate() end to end (real templates + post-process) ──────────

    @Test fun `generate renders real templates and prunes the excluded platform`() {
        generatorWith(includeIos = false).generate(tempDir)

        // Excluded platform is gone; included platforms remain.
        assertFalse("iosApp should be pruned", tempDir.resolve("iosApp").exists())
        assertFalse("iosExport should be pruned", tempDir.resolve("iosExport").exists())
        assertTrue("androidApp should remain", tempDir.resolve("androidApp").isDirectory)
        assertTrue("desktopApp should remain", tempDir.resolve("desktopApp").isDirectory)

        // settings.gradle.kts was both rendered and patched.
        val settings = tempDir.resolve("settings.gradle.kts").readText()
        assertFalse("iosExport include should be stripped", settings.contains(":iosExport"))
        assertTrue("androidApp include should survive", settings.contains(":androidApp"))
    }

    @Test fun `generate keeps every platform when all are selected`() {
        generatorWith().generate(tempDir)

        assertTrue(tempDir.resolve("androidApp").isDirectory)
        assertTrue(tempDir.resolve("desktopApp").isDirectory)
        assertTrue(tempDir.resolve("iosApp").isDirectory)
        assertTrue(tempDir.resolve("iosExport").isDirectory)

        val settings = tempDir.resolve("settings.gradle.kts").readText()
        assertTrue(settings.contains(":androidApp"))
        assertTrue(settings.contains(":desktopApp"))
        assertTrue(settings.contains(":iosExport"))
    }

    @Test fun `postProcess is idempotent — a second run is a no-op and does not throw`() {
        val generator = generatorWith(includeIos = false, includeAgentConfig = false)
        generator.generate(tempDir)

        val firstPass = snapshot(tempDir)
        // Re-running over an already-pruned tree must not throw on the missing dirs/files.
        generator.postProcess(tempDir)
        assertEquals("second post-process must not change the tree", firstPass, snapshot(tempDir))
    }

    /** Sorted relative paths of every file/dir under [root], for tree-equality assertions. */
    private fun snapshot(root: File): List<String> =
        root.walkTopDown().map { it.relativeTo(root).path }.filter { it.isNotEmpty() }.sorted().toList()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generatorWith(
        includeAndroid: Boolean = true,
        includeDesktop: Boolean = true,
        includeIos:     Boolean = true,
        includeAgentConfig: Boolean = true,
        includeCi:          Boolean = true,
    ) = ProjectStructureGenerator(
        KMPProjectSettings(
            includeAndroid = includeAndroid,
            includeDesktop = includeDesktop,
            includeIos     = includeIos,
            includeAgentConfig = includeAgentConfig,
            includeCi          = includeCi,
        )
    )

    private fun seedProScaffolding() {
        tempDir.resolve("CLAUDE.md").writeText("# agent guide")
        tempDir.resolve(".claude/skills/commit").mkdirs()
        tempDir.resolve(".github/workflows").mkdirs()
    }

    private fun seedPlatformDirs() {
        tempDir.resolve("androidApp/src").mkdirs()
        tempDir.resolve("desktopApp/src").mkdirs()
        tempDir.resolve("iosApp/src").mkdirs()
        tempDir.resolve("iosExport/src").mkdirs()
    }

    private fun seedSettingsGradle() {
        tempDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "{{APP_NAME_LOWER}}"

            include(":androidApp")
            include(":desktopApp")
            include(":iosExport")
            include(":core:model")
            include(":core:database")
            include(":feature:{{FEATURE_NAME_LOWER}}:api")
            include(":feature:{{FEATURE_NAME_LOWER}}:impl")
            """.trimIndent()
        )
    }
}