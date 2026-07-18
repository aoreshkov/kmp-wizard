package app.oreshkov.kmp.wizard

/**
 * The wizard's input rules, extracted from the UI step so the logic that decides
 * whether a generated project even compiles (a bad package name feeds straight into
 * the template substitutions) is unit-testable without Swing.
 */
internal object WizardInputValidation {

    private val SANITIZE_REGEX = Regex("[^a-z0-9]")
    private val PACKAGE_REGEX = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$")
    private val IDENTIFIER_REGEX = Regex("^[a-z][a-z0-9_]*$") // shared by feature + field

    /** Derives a package segment from a project name: lowercased, non-[a-z0-9] stripped. */
    fun sanitize(name: String): String =
        name.lowercase().replace(SANITIZE_REGEX, "")

    /** At least two lowercase dot-separated segments, each starting with a letter. */
    fun isValidPackageName(packageName: String): Boolean =
        packageName.matches(PACKAGE_REGEX)

    /** Feature/field names: lowercase letter first, then lowercase/digits/underscores. */
    fun isValidIdentifier(identifier: String): Boolean =
        identifier.matches(IDENTIFIER_REGEX)

    /** The wizard cannot generate an empty project — one platform minimum. */
    fun isAtLeastOnePlatformSelected(android: Boolean, desktop: Boolean, ios: Boolean): Boolean =
        android || desktop || ios
}
