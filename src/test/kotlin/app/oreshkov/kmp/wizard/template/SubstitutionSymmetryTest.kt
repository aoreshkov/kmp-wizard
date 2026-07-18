package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the forward/reverse substitution-symmetry invariant documented in CLAUDE.md.
 *
 * The `generateTemplates` Gradle task (forward) writes `{{PLACEHOLDER}}` tokens into the
 * bundled templates; [TemplateRenderer.buildSubstitutions] (reverse) must define a value
 * for every one of them. If the two drift, the renderer leaves literal `{{...}}` text in
 * generated projects. This test scans every templated file (path + UTF-8 content) for
 * placeholder tokens and asserts each is a key produced by `buildSubstitutions`.
 */
class SubstitutionSymmetryTest {

    // Mirrors TemplateRenderer.BINARY_EXTENSIONS — binary files are copied verbatim and
    // are not scanned for placeholders.
    private val binaryExtensions = setOf(
        "png", "webp", "jpg", "jpeg", "gif",
        "jar", "zip", "keystore", "ico", "icns",
    )

    private val placeholderRegex = Regex("""\{\{[A-Z_]+\}\}""")

    @Test fun `every placeholder in templates has a buildSubstitutions key`() {
        val validKeys = TemplateRenderer.buildSubstitutions(KMPProjectSettings()).keys

        val manifest = loadManifest()
        assertTrue("MANIFEST.txt should list templated files", manifest.isNotEmpty())

        // token -> first template path it was seen in, for a diagnosable failure message.
        val foundTokens = mutableMapOf<String, String>()

        for (path in manifest) {
            placeholderRegex.findAll(path).forEach { foundTokens.putIfAbsent(it.value, path) }

            if (path.substringAfterLast('.', "").lowercase() in binaryExtensions) continue

            val content = javaClass.getResourceAsStream("/templates/$path")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Template resource not found: /templates/$path")
            placeholderRegex.findAll(content).forEach { foundTokens.putIfAbsent(it.value, path) }
        }

        assertTrue("expected to find at least one placeholder across the templates", foundTokens.isNotEmpty())

        val unknown = foundTokens.filterKeys { it !in validKeys }
        assertTrue(
            "Template placeholders with no buildSubstitutions key (forward/reverse drift):\n" +
                unknown.entries.joinToString("\n") { (token, path) -> "  $token  (in $path)" },
            unknown.isEmpty(),
        )
    }

    private fun loadManifest(): List<String> =
        (javaClass.getResourceAsStream("/templates/MANIFEST.txt")
            ?: error("Template MANIFEST.txt not found on the test classpath"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && it != "MANIFEST.txt" }
}
