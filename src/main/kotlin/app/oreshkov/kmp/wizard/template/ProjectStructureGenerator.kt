package app.oreshkov.kmp.wizard.template

import app.oreshkov.kmp.wizard.KMPProjectSettings
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class ProjectStructureGenerator(private val settings: KMPProjectSettings) {

    fun generate(targetDir: File) {
        TemplateRenderer.render(settings, targetDir)
        postProcess(targetDir)
    }

    // ── Post-processing ───────────────────────────────────────────────────────

    /**
     * Removes platform directories and their Gradle include() lines when the
     * user has deselected a platform in the wizard, and the Pro scaffolding when
     * the user is not entitled to it.
     *
     * Every removal throws if anything is left behind (a file held open by an
     * antivirus scanner or indexer on Windows): a deselected module whose include()
     * is already gone, or Pro files reaching a free user, must fail generation rather
     * than ship. This runs against the staging directory, so the throw aborts before
     * the project root is touched.
     */
    internal fun postProcess(targetDir: File) {
        if (!settings.includeAndroid) {
            targetDir.remove("androidApp")
        }
        if (!settings.includeDesktop) {
            targetDir.remove("desktopApp")
        }
        if (!settings.includeIos) {
            targetDir.remove("iosApp")
            targetDir.remove("iosExport")
        }
        if (!settings.includeAndroid || !settings.includeDesktop || !settings.includeIos) {
            patchSettingsGradle(targetDir)
        }

        // Pro tier: strip agent / CI scaffolding when the user is not entitled to it.
        if (!settings.includeAgentConfig) {
            targetDir.remove("CLAUDE.md")
            targetDir.remove(".claude")
        }
        if (!settings.includeCi) {
            targetDir.remove(".github")
        }
    }

    /**
     * Deletes [relativePath] (file or directory tree; absent is fine). Unlike
     * [File.deleteRecursively], which only reports `false`, this deletes everything
     * it can and then throws, with each failed entry attached as a suppressed exception.
     */
    @OptIn(ExperimentalPathApi::class) // Path.deleteRecursively is still experimental in Kotlin 2.4
    private fun File.remove(relativePath: String) {
        toPath().resolve(relativePath).deleteRecursively()
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