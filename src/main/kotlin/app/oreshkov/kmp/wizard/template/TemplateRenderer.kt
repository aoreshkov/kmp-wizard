package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import java.io.File

object TemplateRenderer {

    private val BINARY_EXTENSIONS = setOf(
        "png", "webp", "jpg", "jpeg", "gif",
        "jar", "zip", "keystore", "ico", "icns",
    )

    /**
     * Template file name → the real name it is written out under. Dotfiles that Ant's
     * default excludes would drop from the plugin jar are stored under a neutral name
     * by the `generateTemplates` build task; this is the reverse of its
     * `templateRenamedFiles` table, and the two must stay in sync.
     */
    private val RESTORED_FILE_NAMES = mapOf(
        "gitignore.txt" to ".gitignore",
        "gitattributes.txt" to ".gitattributes",
    )

    // Segment boundaries: lower→Upper (camelCase), acronym→Word (HTTPServer → HTTP|Server),
    // digit→Upper (v2Api → v2|Api), and runs of non-alphanumerics. Letter→digit is
    // deliberately NOT a boundary so "v2" and "feature2name" stay single segments.
    private val IDENTIFIER_SEGMENT_REGEX =
        Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[0-9])(?=[A-Z])|[^a-zA-Z0-9]+")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Renders every template listed in the bundled MANIFEST.txt into [targetDir],
     * applying all placeholder substitutions derived from [settings].
     */
    fun render(settings: KMPProjectSettings, targetDir: File) {
        val substitutions = buildSubstitutions(settings)

        loadManifest()
            .forEach { templatePath ->
                renderSingleFile(templatePath, substitutions, targetDir)
            }
    }

    // ── Internal helpers (internal so they are reachable from unit tests) ─────

    internal fun buildSubstitutions(settings: KMPProjectSettings): Map<String, String> {
        val featurePascal = settings.featureName.toPascalCase()
        val featureCamel  = settings.featureName.toCamelCase()
        val featureSnake  = settings.featureName.toSnakeCase()

        val fieldCamel  = settings.fieldName.toCamelCase()
        val fieldPascal = settings.fieldName.toPascalCase()
        val fieldUpper  = settings.fieldName.toUpperSnakeCase()

        return mapOf(
            "{{PACKAGE_NAME}}"       to settings.packageName,
            "{{PACKAGE_PATH}}"       to settings.packageName.replace('.', '/'),
            "{{APP_NAME}}"           to settings.appName.toPascalCase(),
            "{{APP_NAME_LOWER}}"     to settings.appName.toSnakeCase(),
            "{{FEATURE_NAME}}"       to featurePascal,
            "{{FEATURE_NAME_CAMEL}}" to featureCamel,
            "{{FEATURE_NAME_LOWER}}" to featureSnake,
            "{{FEATURE_NAME_UPPER}}" to settings.featureName.toUpperSnakeCase(),
            "{{FIELD_NAME}}"         to fieldCamel,
            "{{FIELD_NAME_PASCAL}}"  to fieldPascal,
            "{{FIELD_NAME_UPPER}}"   to fieldUpper,
            "{{TEST_VALUE_NAME}}"    to settings.testValueName,
        )
    }

    // These helpers intentionally accept ANY input form — snake_case, kebab-case,
    // camelCase, acronyms, or "spaced words" — even though the wizard UI currently
    // restricts feature/field names to `^[a-z][a-z0-9_]*$`. Keeping the engine
    // independent of the UI's validation makes it reusable and is why CaseConverterTest
    // exercises the broader set of inputs (those cases are deliberate, not dead code).
    internal fun String.toIdentifierSegments(): List<String> =
        this.split(IDENTIFIER_SEGMENT_REGEX)
            .filter { it.isNotBlank() }

    internal fun String.toPascalCase(): String =
        toIdentifierSegments().joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

    internal fun String.toCamelCase(): String =
        toPascalCase().replaceFirstChar(Char::lowercase)

    internal fun String.toSnakeCase(): String =
        toIdentifierSegments().joinToString("_") { it.lowercase() }

    internal fun String.toUpperSnakeCase(): String =
        toSnakeCase().uppercase()

    // Single pass over the input (one scan, one allocation) instead of one full
    // String.replace per placeholder; also order-independent by construction.
    internal fun String.applySubstitutions(substitutions: Map<String, String>): String {
        if (substitutions.isEmpty()) return this
        val pattern = Regex(substitutions.keys.joinToString("|") { Regex.escape(it) })
        return pattern.replace(this) { substitutions.getValue(it.value) }
    }

    // ── Private implementation ────────────────────────────────────────────────

    private fun loadManifest(): List<String> {
        val stream = javaClass.getResourceAsStream("/templates/MANIFEST.txt")
            ?: error(
                "Template MANIFEST.txt not found in plugin resources. " +
                        "Run the 'generateTemplates' Gradle task and rebuild the plugin."
            )
        return stream.bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && it != "MANIFEST.txt" }
    }

    private fun renderSingleFile(
        templatePath: String,
        substitutions: Map<String, String>,
        targetDir: File,
    ) {
        // Apply substitutions to the path itself so directories/files named
        // after the package, app, or feature are renamed correctly.
        val substitutedPath = templatePath.applySubstitutions(substitutions)

        // Restore the real dotfile names (.gitignore, .gitattributes) in the destination
        val destPath = restoreFileName(substitutedPath)
        val destFile = targetDir.resolve(destPath)
        destFile.parentFile.mkdirs()

        val resourcePath = "/templates/$templatePath"
        val bytes = (javaClass.getResourceAsStream(resourcePath)
            ?: error("Template resource not found: $resourcePath"))
            .use { it.readBytes() }

        // Binary files are copied verbatim; text files are decoded as UTF-8,
        // substituted, and written back.
        if (isBinaryContent(templatePath, bytes)) {
            destFile.writeBytes(bytes)
        } else {
            val rendered = bytes.toString(Charsets.UTF_8).applySubstitutions(substitutions)
            destFile.writeText(rendered, Charsets.UTF_8)
        }

        // Restore the executable bit for the Gradle wrapper script
        if (destPath.endsWith("gradlew")) destFile.setExecutable(true)
    }

    /**
     * Maps a template path back to the path it is written out under, undoing the
     * dotfile renames the build applied. Internal so the round trip is unit-testable.
     */
    internal fun restoreFileName(path: String): String {
        val restored = RESTORED_FILE_NAMES[path.substringAfterLast('/')] ?: return path
        val dir = path.substringBeforeLast('/', "")
        return if (dir.isEmpty()) restored else "$dir/$restored"
    }

    /**
     * A file is binary if its extension is allowlisted OR its content contains a NUL
     * byte (catches binaries not covered by the allowlist). Internal so the decision
     * both branches of rendering depend on is directly unit-testable.
     */
    internal fun isBinaryContent(path: String, bytes: ByteArray): Boolean =
        path.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS || bytes.contains(0)
}
