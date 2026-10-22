package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.WizardInputValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [WizardInputValidation.TEMPLATE_RESERVED_FIELDS] and
 * [WizardInputValidation.TEMPLATE_FIELD_DERIVED_SUFFIXES] in sync with the bundled
 * templates.
 *
 * Every class that declares `val {{FIELD_NAME}}:` is scanned for its other members: a
 * fixed one (`id`, `saveError`) is a name the user's field must not render to, and a
 * placeholder-derived one (`{{FIELD_NAME}}Touched`) is a suffix that can collide in turn.
 * A `ledgerVersion` bump that adds such a member then fails here instead of shipping a
 * wizard that accepts a field name whose project doesn't compile.
 */
class TemplateMemberDriftTest {

    @Test fun `reserved field names match the members declared next to the field`() {
        val fixed = sortedSetOf<String>()
        val suffixes = sortedSetOf<String>()
        var scopes = 0

        for (path in loadManifest().filter { it.endsWith(".kt") }) {
            // Placeholders become plain identifiers first: their braces are not brackets.
            val text = javaClass.getResourceAsStream("/templates/$path")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .replace(PLACEHOLDER, "PH_$1_PH")
            for (field in FIELD_DECLARATION.findAll(text)) {
                scopes++
                val members = declarationScope(text, field.range.first)
                MEMBER.findAll(members).map { it.groupValues[1] }.filterNot { "PH_" in it }.forEach { fixed += it }
                DERIVED_MEMBER.findAll(members).forEach { suffixes += it.groupValues[1] }
            }
        }

        assertTrue("expected classes declaring val {{FIELD_NAME}}", scopes > 0)
        assertEquals(
            "Members declared next to {{FIELD_NAME}} drifted from TEMPLATE_RESERVED_FIELDS",
            WizardInputValidation.TEMPLATE_RESERVED_FIELDS.toSortedSet(), fixed,
        )
        assertEquals(
            "Members derived from {{FIELD_NAME}} drifted from TEMPLATE_FIELD_DERIVED_SUFFIXES",
            WizardInputValidation.TEMPLATE_FIELD_DERIVED_SUFFIXES.toSortedSet(), suffixes,
        )
    }

    /**
     * The direct members of the class whose primary constructor or body holds the
     * declaration at [index]: that bracket's top-level text plus, for a constructor, the
     * body that follows it (and vice versa). Nested brackets — default values, getters,
     * nested classes — are blanked out so only same-scope declarations remain.
     */
    private fun declarationScope(text: String, index: Int): String {
        val open = enclosingBracket(text, index)
        val close = matchingBracket(text, open)
        val scope = StringBuilder(topLevel(text, open, close))
        if (text[open] == '(') {
            // `) : Supertype {` — the body that belongs to this constructor, if any.
            BODY_AFTER_CONSTRUCTOR.find(text, close + 1)?.takeIf { it.range.first == close + 1 }?.let { m ->
                val bodyOpen = m.range.last
                scope.append('\n').append(topLevel(text, bodyOpen, matchingBracket(text, bodyOpen)))
            }
        } else {
            val ctorClose = text.lastIndexOf(')', open)
            if (ctorClose >= 0 && CONSTRUCTOR_BEFORE_BODY.matches(text.substring(ctorClose + 1, open))) {
                val ctorOpen = enclosingBracket(text, ctorClose)
                scope.append('\n').append(topLevel(text, ctorOpen, ctorClose))
            }
        }
        return scope.toString()
    }

    /** Index of the innermost unclosed `(` or `{` before [index]. */
    private fun enclosingBracket(text: String, index: Int): Int {
        var depth = 0
        for (i in index - 1 downTo 0) {
            when (text[i]) {
                ')', '}' -> depth++
                '(', '{' -> if (depth == 0) return i else depth--
            }
        }
        error("no enclosing bracket before index $index")
    }

    private fun matchingBracket(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(', '{' -> depth++
                ')', '}' -> if (--depth == 0) return i
            }
        }
        error("unbalanced bracket at index $open")
    }

    /** The text strictly between [open] and [close], with nested bracket contents blanked. */
    private fun topLevel(text: String, open: Int, close: Int): String = buildString {
        var depth = 0
        for (i in open + 1 until close) {
            val c = text[i]
            when (c) {
                '(', '{' -> depth++
                ')', '}' -> depth--
            }
            append(if (depth == 0 && c != ')' && c != '}') c else ' ')
        }
    }

    private fun loadManifest(): List<String> =
        (javaClass.getResourceAsStream("/templates/MANIFEST.txt")
            ?: error("Template MANIFEST.txt not found on the test classpath"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && it != "MANIFEST.txt" }

    private companion object {
        val PLACEHOLDER = Regex("""\{\{([A-Z_]+)}}""")
        val FIELD_DECLARATION = Regex("""\b(?:val|var)\s+PH_FIELD_NAME_PH\s*:""")
        val MEMBER = Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*:""")
        val DERIVED_MEMBER = Regex("""\b(?:val|var)\s+PH_FIELD_NAME_PH(\w+)\s*:""")
        val BODY_AFTER_CONSTRUCTOR = Regex("""\s*(?::[^{}\n]*)?\{""")
        val CONSTRUCTOR_BEFORE_BODY = Regex("""\s*(?::[^{}\n]*)?""")
    }
}
