package app.oreshkov.kmp.wizard

data class KMPProjectSettings(
    val appName: String = "MyApp",
    val packageName: String = "com.example.myapp",
    val featureName: String = "note",
    val fieldName: String = "content",
    val includeAndroid: Boolean = true,
    val includeDesktop: Boolean = true,
    val includeIos: Boolean = true,
    val testValueName: String = "Buy groceries",
    // Pro tier: agent + CI scaffolding written into the generated project.
    // includeAgentConfig -> CLAUDE.md and .claude/; includeCi -> .github/.
    val includeAgentConfig: Boolean = true,
    val includeCi: Boolean = true,
) {
    /**
     * These settings with the Pro features forced off unless [licensed]. Both wizard paths
     * apply it with an entitlement read at generation time, so generation can never run
     * Pro-on without a license, whatever the form's checkboxes or enabled state said.
     */
    fun withEntitlement(licensed: Boolean): KMPProjectSettings =
        copy(includeAgentConfig = includeAgentConfig && licensed, includeCi = includeCi && licensed)
}
