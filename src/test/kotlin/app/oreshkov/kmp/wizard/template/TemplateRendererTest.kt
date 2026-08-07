package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import app.oreshkov.kmp.wizard.template.TemplateRenderer.applySubstitutions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRendererTest {

    private val defaultSettings = KMPProjectSettings(
        appName = "Ledger",
        packageName = "com.example.ledger",
        featureName = "posting",
        includeAndroid = true,
        includeDesktop = true,
    )

    private val subs by lazy { TemplateRenderer.buildSubstitutions(defaultSettings) }

    // ── buildSubstitutions ───────────────────────────────────────────────────

    @Test fun `package name is mapped correctly`() =
        assertEquals("com.example.ledger", subs["{{PACKAGE_NAME}}"])

    @Test fun `package path uses forward slashes`() =
        assertEquals("com/example/ledger", subs["{{PACKAGE_PATH}}"])

    @Test fun `app name is mapped correctly and forced to PascalCase`() =
        assertEquals("Ledger", subs["{{APP_NAME}}"])

    @Test fun `app name lower is lowercase`() =
        assertEquals("ledger", subs["{{APP_NAME_LOWER}}"])

    @Test fun `single-word feature name is PascalCase`() =
        assertEquals("Posting", subs["{{FEATURE_NAME}}"])

    @Test fun `feature name lower is lowercase`() =
        assertEquals("posting", subs["{{FEATURE_NAME_LOWER}}"])

    @Test fun `feature name camel is camelCase`() =
        assertEquals("posting", subs["{{FEATURE_NAME_CAMEL}}"])

    // Case converters are covered row-by-row in the parameterized CaseConverterTest.

    // ── buildSubstitutions wiring for field / app / test value ────────────────

    @Test fun `field name is converted to camelCase, PascalCase and UpperCase`() {
        val s = TemplateRenderer.buildSubstitutions(
            defaultSettings.copy(fieldName = "User Name")
        )
        assertEquals("userName", s["{{FIELD_NAME}}"])
        assertEquals("UserName", s["{{FIELD_NAME_PASCAL}}"])
        assertEquals("USER_NAME", s["{{FIELD_NAME_UPPER}}"])
    }

    @Test fun `app name is forced to PascalCase for consistent class names`() {
        val s = TemplateRenderer.buildSubstitutions(
            defaultSettings.copy(appName = "my awesome app")
        )
        assertEquals("MyAwesomeApp", s["{{APP_NAME}}"])
    }

    @Test fun `app name lower uses snake_case`() {
        val s = TemplateRenderer.buildSubstitutions(
            defaultSettings.copy(appName = "My Awesome App")
        )
        assertEquals("my_awesome_app", s["{{APP_NAME_LOWER}}"])
    }

    @Test fun `test value name is mapped correctly`() {
        val s = TemplateRenderer.buildSubstitutions(
            defaultSettings.copy(testValueName = "Rent")
        )
        assertEquals("Rent", s["{{TEST_VALUE_NAME}}"])
    }

    // ── applySubstitutions ───────────────────────────────────────────────────

    @Test fun `placeholder in file content is replaced`() {
        val template = "package {{PACKAGE_NAME}}.core\n\nclass {{APP_NAME}}Database"
        val result   = template.applySubstitutions(subs)
        assertEquals("package com.example.ledger.core\n\nclass LedgerDatabase", result)
    }

    @Test fun `placeholder in file path is replaced`() {
        val path   = "src/{{PACKAGE_PATH}}/{{APP_NAME_LOWER}}/Database.kt"
        val result = path.applySubstitutions(subs)
        assertEquals("src/com/example/ledger/ledger/Database.kt", result)
    }

    @Test fun `feature placeholders in path are replaced`() {
        val path   = "feature/{{FEATURE_NAME_LOWER}}/impl/{{FEATURE_NAME}}Screen.kt"
        val result = path.applySubstitutions(subs)
        assertEquals("feature/posting/impl/PostingScreen.kt", result)
    }

    @Test fun `string with no placeholders is returned unchanged`() {
        val content = "plugins { kotlin(\"jvm\") }"
        assertEquals(content, content.applySubstitutions(subs))
    }

    @Test fun `empty substitution map is a no-op`() {
        val content = "class {{APP_NAME}}Database"
        assertEquals(content, content.applySubstitutions(emptyMap()))
    }

    @Test fun `multibyte UTF-8 content survives substitution intact`() {
        val template = "// Grüße → 世界 🚀\nclass {{APP_NAME}}Database"
        assertEquals("// Grüße → 世界 🚀\nclass LedgerDatabase", template.applySubstitutions(subs))
    }

    // ── isBinaryContent (the text-vs-binary decision behind rendering) ────────

    @Test fun `allowlisted extension is binary regardless of content`() {
        assertTrue(TemplateRenderer.isBinaryContent("icons/app.png", "not really binary".toByteArray()))
    }

    @Test fun `NUL byte marks a file binary even with no or unknown extension`() {
        val withNul = byteArrayOf(0x4B, 0x4D, 0x00, 0x50)
        assertTrue(TemplateRenderer.isBinaryContent("gradlew", withNul))
        assertTrue(TemplateRenderer.isBinaryContent("data.bin", withNul))
    }

    @Test fun `plain text with unknown extension is not binary`() {
        assertFalse(TemplateRenderer.isBinaryContent("settings.gradle.kts", "include(\":app\")\n".toByteArray()))
        assertFalse(TemplateRenderer.isBinaryContent("gradlew", "#!/bin/sh\n".toByteArray()))
    }

    @Test fun `all placeholders in a realistic Kotlin file are replaced`() {
        val template = """
            package {{PACKAGE_NAME}}.feature.{{FEATURE_NAME_LOWER}}.impl

            import {{PACKAGE_NAME}}.core.domain.Get{{FEATURE_NAME}}UseCase

            class {{FEATURE_NAME}}ViewModel(
                private val get{{FEATURE_NAME}}UseCase: Get{{FEATURE_NAME}}UseCase,
            )
        """.trimIndent()

        val expected = """
            package com.example.ledger.feature.posting.impl

            import com.example.ledger.core.domain.GetPostingUseCase

            class PostingViewModel(
                private val getPostingUseCase: GetPostingUseCase,
            )
        """.trimIndent()

        assertEquals(expected, template.applySubstitutions(subs))
    }
}