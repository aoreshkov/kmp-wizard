package app.oreshkov.kmp.wizard.android

import app.oreshkov.kmp.wizard.KMPProjectSettings
import app.oreshkov.kmp.wizard.WizardInputValidation
import java.io.File
import java.net.URL

/**
 * The parts of the Android Studio wizard path that are plain filesystem/data logic.
 *
 * Deliberately imports nothing from `com.android.*`: Android Studio's template API is
 * only reachable when the optional `org.jetbrains.android` dependency is present, and
 * keeping this file free of it means the trickiest behaviour here — the dry-run
 * protocol and the default-file cleanup — is unit-testable as ordinary Kotlin.
 */

/**
 * Marker used to tell Android Studio's two recipe passes apart. See [isDryRunPass].
 *
 * Named with a leading dot and a .tmp suffix so that, in the (impossible unless the
 * IDE crashes between the passes) event it survives, it neither shows up prominently
 * nor looks like project content.
 */
internal const val DRY_RUN_MARKER_NAME = ".kmp-wizard-dry-run-marker.tmp"

/** Gallery thumbnail, rasterised from `META-INF/pluginIcon.svg`. */
internal const val THUMB_RESOURCE = "/icons/kmpWizardThumb.png"

/**
 * URL of the gallery thumbnail, or `null` if it is missing from the plugin jar.
 *
 * Anchored on a class of *this* plugin rather than on `javaClass`. That matters at the
 * one call site: inside Studio's `template { }` builder the innermost receiver is Studio's
 * own `TemplateBuilder`, so `javaClass` there resolves against the Android plugin's
 * classloader and silently comes back null.
 */
internal fun thumbnailUrl(): URL? = KMPProjectSettings::class.java.getResource(THUMB_RESOURCE)

/**
 * Paths Android Studio's New Project wizard writes into the project root before the
 * template recipe runs. The KMP templates bring their own copy of every one of these,
 * so Studio's versions have to go first or the generated project ends up with a stray
 * `app` module and a `settings.gradle.kts` that does not know about the real modules.
 *
 * The list mirrors `DEFAULT_WIZARD_PATHS` in JetBrains' own Kotlin Multiplatform plugin
 * (`com.intellij.kmm.wizard.android.KotlinMultiplatformWizardProjectRecipeKt`), which
 * solves the identical problem — including the Groovy-DSL spellings, since which pair
 * Studio writes depends on the build-configuration language chosen on its first page.
 */
internal val ANDROID_STUDIO_DEFAULT_PATHS: List<String> = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
    "gradle.properties",
    "local.properties",
    "gradlew",
    "gradlew.bat",
    ".gitignore",
    "gradle",
    "app",
)

/**
 * Reports whether this recipe invocation is Android Studio's **dry run**.
 *
 * Studio executes every template recipe twice: once against a recording executor, to
 * work out which files the template would touch, and once for real. Nothing in the
 * public `RecipeExecutor` API distinguishes the two, so — as JetBrains' own KMP plugin
 * does — the passes are told apart by their order: the first call creates a marker in
 * [rootDir] and answers `true`, the second finds the marker, removes it, and answers
 * `false`.
 *
 * Without this guard a recipe that writes straight to disk generates the entire project
 * twice.
 */
internal fun isDryRunPass(rootDir: File): Boolean {
    val marker = rootDir.resolve(DRY_RUN_MARKER_NAME)
    if (marker.exists()) {
        marker.delete()
        return false
    }
    rootDir.mkdirs()
    marker.createNewFile()
    return true
}

/**
 * Deletes [ANDROID_STUDIO_DEFAULT_PATHS] from [rootDir], returning `false` if anything
 * survived. Absent entries count as success — which of them Studio actually wrote
 * depends on the options picked on its first page.
 */
internal fun deleteAndroidStudioDefaults(rootDir: File): Boolean =
    ANDROID_STUDIO_DEFAULT_PATHS
        .map { rootDir.resolve(it) }
        .all { !it.exists() || it.deleteRecursively() }

/**
 * Builds the generation settings from the values Android Studio collected.
 *
 * [appName] and [packageName] come from Studio's own first page rather than from a
 * widget of ours — Studio owns project name, package and save location on this path.
 *
 * [pro] re-gates the two Pro flags for the same reason `KMPWizardStep.setupProject`
 * does: the widgets' enabled state is UI, and generation must never run Pro-on without
 * an entitlement regardless of what the UI allowed.
 *
 * Deselecting *every* platform falls back to Android. The IDEA path rejects that outright
 * with `validationOnApply`, but Studio's template DSL exposes no cross-parameter validator,
 * and generating with nothing selected would strip every platform module and leave an
 * unbuildable project. Android is the honest fallback here: the template is only reachable
 * from Studio's Android project gallery. The widget hint says one is required.
 */
internal fun studioSettings(
    appName: String,
    packageName: String,
    featureName: String,
    fieldName: String,
    testValueName: String,
    includeAndroid: Boolean,
    includeDesktop: Boolean,
    includeIos: Boolean,
    includeAgentConfig: Boolean,
    includeCi: Boolean,
    pro: Boolean,
): KMPProjectSettings {
    val anyPlatform =
        WizardInputValidation.isAtLeastOnePlatformSelected(includeAndroid, includeDesktop, includeIos)

    return KMPProjectSettings(
        appName = appName,
        packageName = packageName,
        featureName = featureName,
        fieldName = fieldName,
        testValueName = testValueName,
        includeAndroid = includeAndroid || !anyPlatform,
        includeDesktop = includeDesktop,
        includeIos = includeIos,
        includeAgentConfig = includeAgentConfig && pro,
        includeCi = includeCi && pro,
    )
}
