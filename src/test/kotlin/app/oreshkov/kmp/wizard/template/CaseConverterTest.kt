package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.template.TemplateRenderer.toCamelCase
import app.oreshkov.kmp.wizard.template.TemplateRenderer.toPascalCase
import app.oreshkov.kmp.wizard.template.TemplateRenderer.toSnakeCase
import app.oreshkov.kmp.wizard.template.TemplateRenderer.toUpperSnakeCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Table-driven contract for the case converters, one reported test node per input
 * form so a single broken row never hides the health of the rest.
 *
 * The converters accept ANY input form (see the doc comment in [TemplateRenderer]);
 * the acronym and digit→Upper rows below are the contract for inputs broader than
 * the wizard UI's `^[a-z][a-z0-9_]*$` validation.
 */
@RunWith(Parameterized::class)
class CaseConverterTest(
    private val input: String,
    private val pascal: String,
    private val camel: String,
    private val snake: String,
    private val upper: String,
) {

    companion object {
        /** input, PascalCase, camelCase, snake_case, UPPER_SNAKE. */
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: \"{0}\"")
        fun cases(): List<Array<String>> = listOf(
            // Supported input forms.
            arrayOf("posting", "Posting", "posting", "posting", "POSTING"),
            arrayOf("my_feature", "MyFeature", "myFeature", "my_feature", "MY_FEATURE"),
            arrayOf("my-cool-feature", "MyCoolFeature", "myCoolFeature", "my_cool_feature", "MY_COOL_FEATURE"),
            arrayOf("My Cool Feature", "MyCoolFeature", "myCoolFeature", "my_cool_feature", "MY_COOL_FEATURE"),
            arrayOf("myCoolFeature", "MyCoolFeature", "myCoolFeature", "my_cool_feature", "MY_COOL_FEATURE"),
            // Edge cases.
            arrayOf("", "", "", "", ""),
            arrayOf("_feature_", "Feature", "feature", "feature", "FEATURE"),
            arrayOf("-feature-", "Feature", "feature", "feature", "FEATURE"),
            // Letter→digit is deliberately not a boundary: "v2" / "feature2name" stay whole.
            arrayOf("feature2name", "Feature2name", "feature2name", "feature2name", "FEATURE2NAME"),
            arrayOf("v2Api", "V2Api", "v2Api", "v2_api", "V2_API"),
            // Acronyms split on the acronym→Word boundary and round-trip cleanly.
            arrayOf("myJSONParser", "MyJsonParser", "myJsonParser", "my_json_parser", "MY_JSON_PARSER"),
            arrayOf("HTTPServer", "HttpServer", "httpServer", "http_server", "HTTP_SERVER"),
        )
    }

    @Test fun `toPascalCase`() = assertEquals(pascal, input.toPascalCase())
    @Test fun `toCamelCase`() = assertEquals(camel, input.toCamelCase())
    @Test fun `toSnakeCase`() = assertEquals(snake, input.toSnakeCase())
    @Test fun `toUpperSnakeCase`() = assertEquals(upper, input.toUpperSnakeCase())
}
