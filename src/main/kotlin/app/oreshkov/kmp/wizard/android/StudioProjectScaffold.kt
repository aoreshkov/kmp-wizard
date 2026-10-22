package app.oreshkov.kmp.wizard.android

import app.oreshkov.kmp.wizard.KMPProjectSettings
import app.oreshkov.kmp.wizard.WizardInputValidation
import java.io.File
import java.net.URL
import java.nio.file.Files

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
 * so Studio's versions have to go or the generated project ends up with a stray `app`
 * module and a `settings.gradle.kts` that does not know about the real modules.
 *
 * They are handed to [app.oreshkov.kmp.wizard.generateStagedThenCommit] as replaced
 * paths, so they are only moved aside once rendering has succeeded and are put back
 * if the commit fails — a failure never leaves a root with no build files at all.
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
 * How old a dry-run marker may be and still belong to the current wizard run. Studio runs
 * the two recipe passes back-to-back, so anything older was left by an aborted run.
 */
internal const val DRY_RUN_MARKER_MAX_AGE_MILLIS: Long = 10 * 60 * 1000

/**
 * Reports whether this recipe invocation is Android Studio's **dry run**.
 *
 * Studio executes every template recipe twice: once against a recording executor, to
 * work out which files the template would touch, and once for real. Nothing in the
 * public `RecipeExecutor` API distinguishes the two, so — as JetBrains' own KMP plugin
 * does — the passes are told apart by their order: the first call writes a timestamped
 * marker in [rootDir] and answers `true`, the second finds the marker, removes it, and
 * answers `false`.
 *
 * A marker that is older than [DRY_RUN_MARKER_MAX_AGE_MILLIS] or unreadable is *stale* —
 * left by a run that never reached its second pass — and is treated as absent: trusting
 * it would flip the passes, writing the project during the dry run and leaving the new
 * marker inside the finished project. Marker I/O throws rather than failing silently, so
 * a marker that cannot be written or removed aborts the recipe visibly.
 *
 * Without this guard a recipe that writes straight to disk generates the entire project
 * twice. [now] is injectable for tests.
 */
internal fun isDryRunPass(rootDir: File, now: () -> Long = System::currentTimeMillis): Boolean {
    val marker = rootDir.toPath().resolve(DRY_RUN_MARKER_NAME)
    if (Files.exists(marker)) {
        val writtenAt = runCatching { Files.readString(marker).trim().toLong() }.getOrNull()
        Files.delete(marker)
        val age = writtenAt?.let { now() - it }
        if (age != null && age in 0..DRY_RUN_MARKER_MAX_AGE_MILLIS) return false
        // Stale: fall through and treat this pass as the dry run of a new wizard run.
    }
    Files.createDirectories(rootDir.toPath())
    Files.writeString(marker, now().toString())
    return true
}

/** The outcome of mapping Android Studio's form values onto generation settings. */
internal sealed interface StudioInput {
    data class Valid(val settings: KMPProjectSettings) : StudioInput

    /**
     * A name the generated project cannot use: a package that is not a valid Kotlin
     * package, or a feature/field name that cannot become a Kotlin identifier.
     * [parameter] says which input it came from so the recipe can name it in the
     * user-facing message; [clashesWith] is set when a field name is a valid identifier
     * but collides with that fixed member of the generated model.
     */
    data class InvalidName(
        val parameter: NameParameter,
        val value: String,
        val clashesWith: String? = null,
    ) : StudioInput
}

internal enum class NameParameter { PACKAGE, FEATURE, FIELD }

/**
 * Builds the generation settings from the values Android Studio collected.
 *
 * Studio's template DSL can only check a string parameter against its fixed
 * `Constraint` enum, so feature and field names arrive unvalidated. They are normalized
 * to the snake_case form the IDEA form enforces (`Note` and `My Feature` are fine) and
 * rejected as [StudioInput.InvalidName] when nothing valid can be derived — `2fa`,
 * `заметка` or `object` would otherwise render an unbuildable project.
 *
 * [appName] and [packageName] come from Studio's own first page rather than from a
 * widget of ours — Studio owns project name, package and save location on this path.
 * Studio validates the package with Java rules only, so a Kotlin-only hard keyword
 * segment (`com.example.fun`) or an underscore segment (which breaks the iOS and desktop
 * bundle identifiers) gets through; the package is therefore re-checked with the IDEA
 * form's rules, first, so nothing is generated for it. The app name needs no check: it
 * is repaired into a valid identifier at render time (`WizardInputValidation.appIdentifier`).
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
): StudioInput {
    val pkg = packageName.trim()
    if (!WizardInputValidation.isValidPackageName(pkg)) {
        return StudioInput.InvalidName(NameParameter.PACKAGE, packageName)
    }
    val feature = WizardInputValidation.normalizeIdentifier(featureName)
        ?: return StudioInput.InvalidName(NameParameter.FEATURE, featureName)
    val field = WizardInputValidation.normalizeIdentifier(fieldName)
        ?: return StudioInput.InvalidName(NameParameter.FIELD, fieldName)
    WizardInputValidation.templateMemberCollision(field)?.let { member ->
        return StudioInput.InvalidName(NameParameter.FIELD, fieldName, clashesWith = member)
    }

    val anyPlatform =
        WizardInputValidation.isAtLeastOnePlatformSelected(includeAndroid, includeDesktop, includeIos)

    return StudioInput.Valid(
        KMPProjectSettings(
            appName = appName,
            packageName = pkg,
            featureName = feature,
            fieldName = field,
            testValueName = testValueName.trim(),
            includeAndroid = includeAndroid || !anyPlatform,
            includeDesktop = includeDesktop,
            includeIos = includeIos,
            includeAgentConfig = includeAgentConfig,
            includeCi = includeCi,
        ).withEntitlement(pro),
    )
}
