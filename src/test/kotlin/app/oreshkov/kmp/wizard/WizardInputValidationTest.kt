package app.oreshkov.kmp.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardInputValidationTest {

    // ── Package names ─────────────────────────────────────────────────────────

    @Test fun `well-formed package names are accepted`() {
        assertTrue(WizardInputValidation.isValidPackageName("com.example.app"))
        assertTrue(WizardInputValidation.isValidPackageName("app.oreshkov.ledger"))
        assertTrue(WizardInputValidation.isValidPackageName("a.b"))
        assertTrue(WizardInputValidation.isValidPackageName("com.example2.app3"))
    }

    @Test fun `malformed package names are rejected`() {
        assertFalse("single segment", WizardInputValidation.isValidPackageName("com"))
        assertFalse("blank", WizardInputValidation.isValidPackageName(""))
        assertFalse("leading digit in a segment", WizardInputValidation.isValidPackageName("com.1example.app"))
        assertFalse("uppercase", WizardInputValidation.isValidPackageName("com.Example.app"))
        assertFalse("trailing dot", WizardInputValidation.isValidPackageName("com.example."))
        assertFalse("leading dot", WizardInputValidation.isValidPackageName(".com.example"))
        assertFalse("consecutive dots", WizardInputValidation.isValidPackageName("com..example"))
        assertFalse("underscore not allowed in packages", WizardInputValidation.isValidPackageName("com.my_app.core"))
        assertFalse("hyphen", WizardInputValidation.isValidPackageName("com.my-app.core"))
        assertFalse("whitespace", WizardInputValidation.isValidPackageName("com.example .app"))
    }

    @Test fun `packages with a Kotlin or Java keyword segment are rejected`() {
        for (pkg in listOf("com.example.in", "app.object", "com.example.new", "com.example.class", "com.fun.app", "com.example.true")) {
            assertTrue("$pkg is well-formed", WizardInputValidation.isWellFormedPackageName(pkg))
            assertFalse("$pkg has a reserved segment", WizardInputValidation.isValidPackageName(pkg))
        }
    }

    @Test fun `reserved segments are reported in order, and only those`() {
        assertEquals(listOf("new", "int"), WizardInputValidation.reservedPackageSegments("com.new.int.app"))
        assertEquals(emptyList<String>(), WizardInputValidation.reservedPackageSegments("com.example.internal"))
    }

    @Test fun `soft and modifier keywords stay legal`() {
        // Only hard keywords break compilation; these are ordinary identifiers.
        for (word in listOf("data", "open", "value", "inner", "internal", "get", "set", "by", "where", "record")) {
            assertTrue("com.example.$word should be accepted", WizardInputValidation.isValidPackageName("com.example.$word"))
            assertTrue("$word should be accepted", WizardInputValidation.isValidIdentifier(word))
        }
    }

    // ── Default package derived from the project name ─────────────────────────

    @Test fun `the default package is always valid, whatever the project is called`() {
        for (name in listOf("Object", "Class", "Fun", "2FA", "!!!", "", "My App", "заметка", "ledger")) {
            val pkg = WizardInputValidation.defaultPackageName(name)
            assertTrue("default for \"$name\" must be valid, was $pkg", WizardInputValidation.isValidPackageName(pkg))
            assertTrue(pkg.startsWith(WizardInputValidation.DEFAULT_PACKAGE_PREFIX))
        }
    }

    @Test fun `the default package keeps an ordinary name as-is and repairs broken ones`() {
        assertEquals("com.example.myapp", WizardInputValidation.defaultPackageName("My App"))
        assertEquals("com.example.objectapp", WizardInputValidation.defaultPackageName("Object"))
        assertEquals("com.example.app2fa", WizardInputValidation.defaultPackageName("2FA"))
        assertEquals("com.example.app", WizardInputValidation.defaultPackageName("!!!"))
    }

    // ── Feature / field identifiers ───────────────────────────────────────────

    @Test fun `identifiers that render as a keyword are rejected`() {
        for (name in listOf("val", "when", "object", "in", "is", "class", "new", "null")) {
            assertTrue("$name is well-formed", WizardInputValidation.isWellFormedIdentifier(name))
            assertFalse("$name is reserved", WizardInputValidation.isValidIdentifier(name))
        }
        // Well-formed, but the snake/camel forms the templates use collapse to a keyword.
        assertFalse("is_ renders as `is`", WizardInputValidation.isValidIdentifier("is_"))
        assertFalse("when__ renders as `when`", WizardInputValidation.isValidIdentifier("when__"))
        // A keyword as one part of a longer name is fine: `isActive`, `in_stock` → `inStock`.
        assertTrue(WizardInputValidation.isValidIdentifier("in_stock"))
        assertTrue(WizardInputValidation.isValidIdentifier("object_id"))
    }

    // ── Studio-path normalization ─────────────────────────────────────────────

    @Test fun `free-form names normalize to the canonical snake_case form`() {
        assertEquals("note", WizardInputValidation.normalizeIdentifier("note"))
        assertEquals("note", WizardInputValidation.normalizeIdentifier("Note"))
        assertEquals("my_feature", WizardInputValidation.normalizeIdentifier("My Feature"))
        assertEquals("my_feature", WizardInputValidation.normalizeIdentifier("myFeature"))
        assertEquals("my_feature", WizardInputValidation.normalizeIdentifier("  my-feature  "))
    }

    @Test fun `names that cannot become a valid identifier normalize to null`() {
        for (input in listOf("!!!", "---", "_", "заметка", "1note", "2fa", "object", "Val", "")) {
            assertEquals("\"$input\"", null, WizardInputValidation.normalizeIdentifier(input))
        }
    }

    @Test fun `well-formed identifiers are accepted`() {
        assertTrue(WizardInputValidation.isValidIdentifier("note"))
        assertTrue(WizardInputValidation.isValidIdentifier("my_feature"))
        assertTrue(WizardInputValidation.isValidIdentifier("v2"))
        assertTrue(WizardInputValidation.isValidIdentifier("a"))
    }

    @Test fun `malformed identifiers are rejected`() {
        assertFalse("blank", WizardInputValidation.isValidIdentifier(""))
        assertFalse("leading digit", WizardInputValidation.isValidIdentifier("2note"))
        assertFalse("leading underscore", WizardInputValidation.isValidIdentifier("_note"))
        assertFalse("uppercase", WizardInputValidation.isValidIdentifier("Note"))
        assertFalse("camelCase", WizardInputValidation.isValidIdentifier("myFeature"))
        assertFalse("hyphen", WizardInputValidation.isValidIdentifier("my-feature"))
        assertFalse("space", WizardInputValidation.isValidIdentifier("my feature"))
    }

    // ── sanitize ──────────────────────────────────────────────────────────────

    @Test fun `sanitize lowercases and strips everything outside a-z0-9`() {
        assertEquals("myapp", WizardInputValidation.sanitize("My App"))
        assertEquals("kmpproject2", WizardInputValidation.sanitize("KMP-Project_2!"))
        assertEquals("ledger", WizardInputValidation.sanitize("ledger"))
        assertEquals("", WizardInputValidation.sanitize("___"))
        assertEquals("", WizardInputValidation.sanitize(""))
    }

    // ── Platform selection rule ───────────────────────────────────────────────

    @Test fun `at least one platform must be selected`() {
        assertFalse(WizardInputValidation.isAtLeastOnePlatformSelected(android = false, desktop = false, ios = false))
        assertTrue(WizardInputValidation.isAtLeastOnePlatformSelected(android = true, desktop = false, ios = false))
        assertTrue(WizardInputValidation.isAtLeastOnePlatformSelected(android = false, desktop = true, ios = false))
        assertTrue(WizardInputValidation.isAtLeastOnePlatformSelected(android = false, desktop = false, ios = true))
        assertTrue(WizardInputValidation.isAtLeastOnePlatformSelected(android = true, desktop = true, ios = true))
    }
}
