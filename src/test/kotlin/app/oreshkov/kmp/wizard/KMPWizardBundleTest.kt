package app.oreshkov.kmp.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.MessageFormat
import java.util.ResourceBundle

/**
 * Guards the message bundle against key/property drift. The UI and notification
 * strings aren't exercised by other tests, so a missing or mis-escaped key would
 * otherwise only surface at runtime. Loads the bundle directly (not via
 * DynamicBundle) so it needs no running IDE application.
 */
class KMPWizardBundleTest {

    private val bundle = ResourceBundle.getBundle("messages.KMPWizardBundle")

    /**
     * Every bundle key referenced anywhere in main sources, discovered by scanning the
     * source tree (working dir is the module root under Gradle). Deriving these from source
     * — rather than a hand-maintained list — means a newly referenced key that lacks a
     * property fails the test automatically. Two reference channels are scanned:
     *  - Kotlin `KMPWizardBundle.message("…")` calls, and
     *  - `plugin.xml` `key="…"` attributes (e.g. the `notificationGroup` display name).
     */
    private val referencedKeys: Set<String> by lazy {
        val mainSrc = File("src/main/kotlin")
        assertTrue("source dir not found: ${mainSrc.absolutePath}", mainSrc.isDirectory)
        val messagePattern = Regex("""message\(\s*"([^"]+)"""")
        val codeKeys = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { messagePattern.findAll(it.readText()).map { m -> m.groupValues[1] } }

        val pluginXml = File("src/main/resources/META-INF/plugin.xml")
        assertTrue("plugin.xml not found: ${pluginXml.absolutePath}", pluginXml.isFile)
        val keyAttrPattern = Regex("""key="([^"]+)"""")
        val xmlKeys = keyAttrPattern.findAll(pluginXml.readText()).map { it.groupValues[1] }

        (codeKeys + xmlKeys).toSet()
    }

    @Test fun `every referenced key is present and non-blank`() {
        assertTrue("No message(...) references found — scan likely broken", referencedKeys.isNotEmpty())
        val missing = referencedKeys.filterNot { bundle.containsKey(it) }
        assertTrue("Missing message keys: $missing", missing.isEmpty())
        val blank = referencedKeys.filter { bundle.getString(it).isBlank() }
        assertTrue("Blank message values: $blank", blank.isEmpty())
    }

    @Test fun `no bundle key is orphaned (defined but never referenced)`() {
        val orphans = bundle.keySet() - referencedKeys
        assertTrue("Orphaned bundle keys (defined but unused): $orphans", orphans.isEmpty())
    }

    @Test fun `parameterized messages escape literal quotes correctly`() {
        // With a {0} argument, MessageFormat treats single quotes specially, so the
        // properties must double them to render a literal quote around the name.
        val success = MessageFormat.format(bundle.getString("notify.success.content"), "Ledger")
        assertEquals("KMP Project 'Ledger' was created successfully.", success)

        val progress = MessageFormat.format(bundle.getString("progress.generating"), "Ledger")
        assertTrue("progress should embed the name in quotes", progress.contains("'Ledger'"))
    }
}
