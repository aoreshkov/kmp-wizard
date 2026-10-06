package app.oreshkov.kmp.wizard

import app.oreshkov.kmp.wizard.template.TemplateRenderer.toCamelCase
import app.oreshkov.kmp.wizard.template.TemplateRenderer.toSnakeCase

/**
 * The wizard's input rules, extracted from the UI step so the logic that decides
 * whether a generated project even compiles (a bad package name feeds straight into
 * the template substitutions) is unit-testable without Swing.
 */
internal object WizardInputValidation {

    private val SANITIZE_REGEX = Regex("[^a-z0-9]")
    private val PACKAGE_REGEX = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$")
    private val IDENTIFIER_REGEX = Regex("^[a-z][a-z0-9_]*$") // shared by feature + field

    /** Package prefix of the name the wizard proposes before the user types one. */
    const val DEFAULT_PACKAGE_PREFIX = "com.example."

    /** Used when a project name sanitizes to nothing (e.g. it is all punctuation). */
    private const val FALLBACK_PACKAGE_SEGMENT = "app"

    /**
     * Words that can never be a package segment or a generated identifier.
     *
     * Kotlin's *hard* keywords (soft and modifier keywords are legal identifiers) plus
     * Java's reserved words and literals. Java matters even though the generated sources
     * are Kotlin: the package also becomes the Android `namespace`/`applicationId`, and
     * AGP rejects any segment that is a Java keyword.
     */
    val RESERVED_WORDS: Set<String> = setOf(
        // Kotlin hard keywords — https://kotlinlang.org/docs/keyword-reference.html
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        // Java reserved words — JLS §3.9 (keywords) and §3.10.3/§3.10.8 (literals)
        "abstract", "assert", "boolean", "byte", "case", "catch", "char", "const", "default",
        "double", "enum", "extends", "final", "finally", "float", "goto", "implements",
        "import", "instanceof", "int", "long", "native", "new", "private", "protected",
        "public", "short", "static", "strictfp", "switch", "synchronized", "throws",
        "transient", "void", "volatile",
    )

    /** Derives a package segment from a project name: lowercased, non-[a-z0-9] stripped. */
    fun sanitize(name: String): String =
        name.lowercase().replace(SANITIZE_REGEX, "")

    /**
     * The package the wizard proposes for [projectName] — always one that passes
     * [isValidPackageName], since the user may accept it without typing anything.
     * A segment that would be empty, start with a digit, or be a reserved word
     * ("Object", "Class", "2FA") is repaired rather than proposed broken.
     */
    fun defaultPackageName(projectName: String): String {
        val segment = sanitize(projectName)
        val repaired = when {
            segment.isEmpty() -> FALLBACK_PACKAGE_SEGMENT
            segment.first().isDigit() -> FALLBACK_PACKAGE_SEGMENT + segment
            segment in RESERVED_WORDS -> segment + FALLBACK_PACKAGE_SEGMENT
            else -> segment
        }
        return DEFAULT_PACKAGE_PREFIX + repaired
    }

    /** At least two lowercase dot-separated segments, each starting with a letter. */
    fun isWellFormedPackageName(packageName: String): Boolean =
        packageName.matches(PACKAGE_REGEX)

    /** The reserved words used as segments of [packageName], in order of appearance. */
    fun reservedPackageSegments(packageName: String): List<String> =
        packageName.split('.').filter { it in RESERVED_WORDS }

    /** Well-formed and free of reserved segments — what the generated project needs. */
    fun isValidPackageName(packageName: String): Boolean =
        isWellFormedPackageName(packageName) && reservedPackageSegments(packageName).isEmpty()

    /** Feature/field names: lowercase letter first, then lowercase/digits/underscores. */
    fun isWellFormedIdentifier(identifier: String): Boolean =
        identifier.matches(IDENTIFIER_REGEX)

    /**
     * Whether [identifier] turns into a reserved word in generated code. The templates
     * use the camelCase and snake_case forms as Kotlin identifiers (`val note = …`), so
     * those are what must be checked: `is_` is well-formed but renders as `is`.
     */
    fun isReservedIdentifier(identifier: String): Boolean =
        identifier.toCamelCase() in RESERVED_WORDS || identifier.toSnakeCase() in RESERVED_WORDS

    /** Well-formed and not reserved in any generated form. */
    fun isValidIdentifier(identifier: String): Boolean =
        isWellFormedIdentifier(identifier) && !isReservedIdentifier(identifier)

    /**
     * Normalizes a free-form feature/field name to the canonical snake_case form the IDEA
     * form enforces, or returns `null` if nothing valid can be derived from it.
     *
     * For the Android Studio path, whose template DSL cannot validate input: there the
     * user may type `Note` or `My Feature`, which normalize cleanly, but also `2fa`,
     * `заметка` or `object`, which would render an empty, digit-leading or reserved
     * identifier and an unbuildable project.
     */
    fun normalizeIdentifier(input: String): String? =
        input.toSnakeCase().takeIf(::isValidIdentifier)

    /** The wizard cannot generate an empty project — one platform minimum. */
    fun isAtLeastOnePlatformSelected(android: Boolean, desktop: Boolean, ios: Boolean): Boolean =
        android || desktop || ios
}
