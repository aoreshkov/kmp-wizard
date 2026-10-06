package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Exercises the full manifest-driven [TemplateRenderer.render] against the
 * templates bundled on the test classpath (`/templates/MANIFEST.txt`, produced by
 * `generateTemplates` and copied by `processResources`). This complements the pure
 * unit tests in [TemplateRendererTest] by covering resource loading plus path and
 * content substitution end to end.
 */
class TemplateRenderIntegrationTest {

    private lateinit var tempDir: File

    @Before fun setUp() {
        tempDir = createTempDirectory("kmp_render_test_").toFile()
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test fun `render produces a project skeleton with path and content substitutions applied`() {
        val settings = KMPProjectSettings(
            appName = "Ledger",
            packageName = "com.example.ledger",
            featureName = "posting",
            fieldName = "narrative",
            testValueName = "Groceries",
        )

        TemplateRenderer.render(settings, tempDir)

        // Core build files are rendered at the project root.
        val settingsGradle = tempDir.resolve("settings.gradle.kts")
        assertTrue("settings.gradle.kts should be rendered", settingsGradle.exists())
        assertTrue("root build.gradle.kts should be rendered", tempDir.resolve("build.gradle.kts").exists())

        // Content substitution applied, with no unresolved placeholders left behind.
        // rootProject.name = "{{APP_NAME}}" -> PascalCase "Ledger"; feature includes
        // use {{FEATURE_NAME_LOWER}} -> "posting".
        val settingsText = settingsGradle.readText()
        assertTrue("app name should be substituted", settingsText.contains("Ledger"))
        assertTrue("feature name should be substituted", settingsText.contains("posting"))
        assertFalse("no unresolved {{...}} placeholders should remain", settingsText.contains("{{"))

        // Path substitution applied: the feature module dir is renamed from the placeholder.
        assertTrue(
            "feature/posting module directory should exist",
            tempDir.resolve("feature/posting").isDirectory,
        )
        assertFalse(
            "no template path should keep the literal placeholder directory",
            tempDir.resolve("feature/{{FEATURE_NAME_LOWER}}").exists(),
        )

        // The pipeline's own metadata files must never be emitted into the generated project.
        assertFalse("MANIFEST.txt must not be rendered", tempDir.resolve("MANIFEST.txt").exists())
        assertFalse("SUBSTITUTIONS.txt must not be rendered", tempDir.resolve("SUBSTITUTIONS.txt").exists())
        assertFalse("RENAMES.txt must not be rendered", tempDir.resolve("RENAMES.txt").exists())
    }

    @Test fun `every build-time dotfile rename is reversed by the renderer`() {
        // generateTemplates ships its templateRenamedFiles table as RENAMES.txt; a name
        // added there but not to RESTORED_FILE_NAMES would ship e.g. `gitmodules.txt`
        // in generated projects in place of the real dotfile.
        val renames = (javaClass.getResourceAsStream("/templates/RENAMES.txt")
            ?: error("RENAMES.txt not found on the test classpath — rerun generateTemplates"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line -> line.split('\t').also { require(it.size == 2) { "Malformed RENAMES.txt line: $line" } } }
            .associate { (name, stored) -> name to stored }
        assertTrue("RENAMES.txt should list the dotfile renames", renames.isNotEmpty())

        val drift = renames.filter { (name, stored) ->
            TemplateRenderer.restoreFileName(stored) != name ||
                TemplateRenderer.restoreFileName("nested/dir/$stored") != "nested/dir/$name"
        }
        assertTrue("Renames not reversed by TemplateRenderer.RESTORED_FILE_NAMES: $drift", drift.isEmpty())
        assertEquals(
            "RESTORED_FILE_NAMES must not restore names the build never renames",
            renames.entries.associate { (name, stored) -> stored to name },
            TemplateRenderer.RESTORED_FILE_NAMES,
        )
    }

    @Test fun `no BCV ABI dumps are shipped in the templates`() {
        // Dumps (*.api, *.klib.api) are compiler-output snapshots of the ledger's
        // code — a substituted copy can never match what apiDump produces for the
        // user's names (generated symbol names, declaration order, Compose lambda
        // keys), so the wizard creates them via apiDump after the first sync.
        // This pins the generateTemplates exclusion.
        val dumps = loadManifest().filter { it.endsWith(".api") }
        assertTrue(
            "MANIFEST.txt must not list BCV dump files, found:\n" + dumps.joinToString("\n"),
            dumps.isEmpty(),
        )
    }

    @Test fun `no unresolved placeholders survive anywhere in the rendered tree`() {
        TemplateRenderer.render(defaultSettings, tempDir)

        val offenders = mutableListOf<String>()
        tempDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relPath = file.relativeTo(tempDir).path
            if (PLACEHOLDER.containsMatchIn(relPath)) {
                offenders += "path: $relPath"
            }
            // Only text files carry substitutable content; mirror the renderer's binary
            // rule (allow-listed extension OR a NUL byte) so we don't decode real binaries.
            val bytes = file.readBytes()
            if (!isBinary(file.name, bytes)) {
                val text = bytes.toString(Charsets.UTF_8)
                PLACEHOLDER.findAll(text).map { it.value }.distinct().forEach { token ->
                    offenders += "content: $relPath -> $token"
                }
            }
        }

        assertTrue("Unresolved {{...}} placeholders remain:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test fun `special files are rendered with their template-pipeline mechanics`() {
        TemplateRenderer.render(defaultSettings, tempDir)

        // Dotfiles Ant's default excludes would drop are stored under a neutral name and
        // restored here (and the storage name is gone).
        assertTrue(".gitignore should be restored", tempDir.resolve(".gitignore").isFile)
        assertFalse("gitignore.txt should not be emitted", tempDir.resolve("gitignore.txt").exists())
        assertTrue(".gitattributes should be restored", tempDir.resolve(".gitattributes").isFile)
        assertFalse(
            "gitattributes.txt should not be emitted",
            tempDir.resolve("gitattributes.txt").exists(),
        )

        assertTrue("gradlew should be rendered", tempDir.resolve("gradlew").isFile)

        // A binary template must be copied byte-for-byte (no UTF-8 round-trip). Picked
        // dynamically from the manifest so a ledger-side asset rename can't break the
        // test — it asserts the mechanism, not a specific filename.
        val binaryPath = loadManifest()
            .firstOrNull { it.substringAfterLast('.', "").lowercase() in binaryExtensions }
            ?: error("Expected at least one binary template in MANIFEST.txt")
        val templateBytes = (javaClass.getResourceAsStream("/templates/$binaryPath")
            ?: error("Missing binary template resource: /templates/$binaryPath"))
            .use { it.readBytes() }
        // Resolve the rendered location the same way the renderer does: placeholders
        // in the path are substituted (a no-op for paths without any).
        val renderedPath = with(TemplateRenderer) {
            binaryPath.applySubstitutions(buildSubstitutions(defaultSettings))
        }
        val renderedBinary = tempDir.resolve(renderedPath)
        assertTrue("binary template $binaryPath should be rendered", renderedBinary.isFile)
        assertArrayEquals("binary must be byte-identical to its template", templateBytes, renderedBinary.readBytes())
    }

    @Test fun `the rendered gradlew is executable`() {
        // Reported as skipped (not silently passed) where there is no POSIX exec bit.
        assumeFalse("Windows has no POSIX executable bit", System.getProperty("os.name").startsWith("Windows"))
        TemplateRenderer.render(defaultSettings, tempDir)
        assertTrue("gradlew should be executable", tempDir.resolve("gradlew").canExecute())
    }

    private fun loadManifest(): List<String> =
        (javaClass.getResourceAsStream("/templates/MANIFEST.txt")
            ?: error("Template MANIFEST.txt not found on the test classpath"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && it != "MANIFEST.txt" }

    /** [TemplateRenderer]'s own binary rule: an allow-listed extension or a NUL byte. */
    private fun isBinary(name: String, bytes: ByteArray): Boolean = TemplateRenderer.isBinaryContent(name, bytes)

    private companion object {
        val defaultSettings = KMPProjectSettings(
            appName = "Ledger",
            packageName = "com.example.ledger",
            featureName = "posting",
            fieldName = "narrative",
            testValueName = "Groceries",
        )
        val binaryExtensions = TemplateRenderer.BINARY_EXTENSIONS

        // A leftover template placeholder is specifically `{{UPPER_SNAKE}}`; this avoids
        // false positives on GitHub Actions `${{ ... }}` expressions in workflow YAML.
        val PLACEHOLDER = Regex("""\{\{[A-Z_]+}}""")
    }
}
