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

    // ── Feature / field identifiers ───────────────────────────────────────────

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
