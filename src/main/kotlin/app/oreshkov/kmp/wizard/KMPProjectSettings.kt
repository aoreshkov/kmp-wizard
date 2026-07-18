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
)
