package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import java.io.File

class ProjectStructureGenerator(private val settings: KMPProjectSettings) {

    fun generate(targetDir: File) {
        TemplateRenderer.render(settings, targetDir)
        postProcess(targetDir)
    }

    // ── Post-processing ───────────────────────────────────────────────────────

    /**
     * Removes platform directories and their Gradle include() lines when the
     * user has deselected a platform in the wizard.
     */
    internal fun postProcess(targetDir: File) {
        if (!settings.includeAndroid) {
            targetDir.resolve("androidApp").deleteRecursively()
        }
        if (!settings.includeDesktop) {
            targetDir.resolve("desktopApp").deleteRecursively()
        }
        if (!settings.includeIos) {
            targetDir.resolve("iosApp").deleteRecursively()
            targetDir.resolve("iosExport").deleteRecursively()
        }
        if (!settings.includeAndroid || !settings.includeDesktop || !settings.includeIos) {
            patchSettingsGradle(targetDir)
        }

        // Pro tier: strip agent / CI scaffolding when the user is not entitled to it.
        if (!settings.includeAgentConfig) {
            targetDir.resolve("CLAUDE.md").delete()
            targetDir.resolve(".claude").deleteRecursively()
        }
        if (!settings.includeCi) {
            targetDir.resolve(".github").deleteRecursively()
        }
    }

    private fun patchSettingsGradle(targetDir: File) {
        val settingsFile = targetDir.resolve("settings.gradle.kts")
        if (!settingsFile.exists()) return

        // Exact-token match on the quoted module path so a prefix-colliding module
        // (e.g. ":androidAppSample") is never stripped along with ":androidApp".
        // Matching stays line-based: the templates emit one include() per line, so a
        // matching line is dropped whole (a multi-module include line would lose its
        // other modules too — a known limitation encoded in the tests).
        fun moduleRegex(module: String) = Regex("""["']:$module["']""")
        val excludedModules = buildList {
            if (!settings.includeAndroid) add(moduleRegex("androidApp"))
            if (!settings.includeDesktop) add(moduleRegex("desktopApp"))
            if (!settings.includeIos) add(moduleRegex("iosExport"))
        }

        val patched = settingsFile
            .readLines()
            .filter { line -> excludedModules.none { it.containsMatchIn(line) } }
            .joinToString("\n", postfix = "\n")

        settingsFile.writeText(patched)
    }
}