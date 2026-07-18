package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import app.oreshkov.kmp.wizard.template.TemplateRenderer.applySubstitutions
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the *value* half of the forward/reverse substitution invariant.
 *
 * [SubstitutionSymmetryTest] proves every placeholder token in the templates has a
 * [TemplateRenderer.buildSubstitutions] key. This test closes the loop it leaves
 * open: fed the ledger's own canonical settings, the reverse map must reproduce the
 * exact literal each forward rule (build.gradle.kts `templateSubstitutions`, shipped
 * as `SUBSTITUTIONS.txt`) replaced. If a case converter drifts (e.g.
 * `{{FEATURE_NAME_UPPER}}` stops yielding `POSTING`), rendering the templates with
 * the ledger's values would no longer reconstruct the ledger — a subtly wrong
 * project with no other failing test.
 */
class SubstitutionValueSymmetryTest {

    /** The concrete values the forward pass replaced in the kmp-ledger source. */
    private val ledgerSettings = KMPProjectSettings(
        appName = "Ledger",
        packageName = "app.oreshkov.ledger",
        featureName = "posting",
        fieldName = "narrative",
        testValueName = "Groceries",
    )

    @Test fun `reverse substitutions reproduce every forward literal for the ledger's own settings`() {
        val reverse = TemplateRenderer.buildSubstitutions(ledgerSettings)
        val forwardPairs = loadForwardPairs()
        assertTrue("SUBSTITUTIONS.txt should list forward pairs", forwardPairs.isNotEmpty())

        // Composite placeholders ({{FEATURE_NAME_CAMEL}}Id) round-trip naturally:
        // rendering the placeholder side with the ledger settings must yield the
        // literal the forward pass started from.
        val mismatches = forwardPairs.mapNotNull { (literal, placeholder) ->
            val roundTripped = placeholder.applySubstitutions(reverse)
            if (roundTripped != literal) "  $placeholder -> \"$roundTripped\", expected \"$literal\"" else null
        }

        assertTrue(
            "Forward/reverse substitution VALUE drift (build.gradle.kts vs buildSubstitutions):\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    private fun loadForwardPairs(): List<Pair<String, String>> =
        (javaClass.getResourceAsStream("/templates/SUBSTITUTIONS.txt")
            ?: error("SUBSTITUTIONS.txt not found on the test classpath — rerun generateTemplates"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val (literal, placeholder) = line.split('\t', limit = 2)
                    .also { require(it.size == 2) { "Malformed SUBSTITUTIONS.txt line: $line" } }
                literal to placeholder
            }
}
