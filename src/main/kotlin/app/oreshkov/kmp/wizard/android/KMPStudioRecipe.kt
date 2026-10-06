package app.oreshkov.kmp.wizard.android

import app.oreshkov.kmp.wizard.KMPProjectSettings
import app.oreshkov.kmp.wizard.KMPWizardBundle
import app.oreshkov.kmp.wizard.generateStagedThenCommit
import app.oreshkov.kmp.wizard.notify
import app.oreshkov.kmp.wizard.scheduleApiDumpAfterFirstSync
import app.oreshkov.kmp.wizard.template.ProjectStructureGenerator
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.runBlocking
import java.io.File

private val LOG = logger<KMPWizardTemplateProvider>()

/**
 * Generates the KMP project into a root directory Android Studio has already created.
 *
 * This is the body of the template recipe, kept out of [KMPWizardTemplateProvider] so the
 * declarative template stays readable. The generation core — [ProjectStructureGenerator],
 * [app.oreshkov.kmp.wizard.template.TemplateRenderer], [generateStagedThenCommit] — is
 * shared verbatim with the IntelliJ IDEA path; only the surrounding choreography differs.
 *
 * Runs on the EDT, which is where Android Studio invokes template recipes. That is what
 * makes the *synchronous* VFS refresh below both necessary and safe: Studio kicks off the
 * project's first Gradle sync as soon as the render returns, so the tree has to be visible
 * to the VFS by then. Blocking the EDT for the duration is deliberate and matches how
 * JetBrains' own Kotlin Multiplatform template behaves; Studio is showing its own progress
 * UI across the whole render.
 */
internal fun generateKmpProject(rootDir: File, settings: KMPProjectSettings) {
    LOG.info("KMP Wizard: Starting Android Studio generation at ${rootDir.absolutePath}")

    try {
        // Staging isolation and commit-on-success, exactly as on the IDEA path: a failure
        // must not leave a half-rendered tree mixed in with Studio's own project files.
        // Studio's default scaffold is superseded inside the same commit — moved aside
        // only after rendering succeeded, and restored if the commit itself fails.
        runBlocking {
            generateStagedThenCommit(rootDir, replacedPaths = ANDROID_STUDIO_DEFAULT_PATHS) { staging ->
                ProjectStructureGenerator(settings).generate(staging)
            }
        }

        val rootPath = rootDir.toPath()
        val rootVfsDir = VirtualFileManager.getInstance().findFileByNioPath(rootPath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath)
        if (rootVfsDir != null) {
            // Synchronous, recursive, reloading children — the sync that Studio starts
            // right after this must not race an asynchronous refresh.
            VfsUtil.markDirtyAndRefresh(false, true, true, rootVfsDir)
        }

        // Templates carry no BCV dumps (they cannot survive renaming), so the first sync's
        // completion triggers apiDump to create them from the generated sources.
        scheduleApiDumpAfterFirstSync(rootDir.absolutePath)

        LOG.info("KMP Wizard: Android Studio generation complete.")
        notify(
            null,
            KMPWizardBundle.message("notify.success.title"),
            KMPWizardBundle.message("notify.success.content", settings.appName),
            NotificationType.INFORMATION,
        )
    } catch (e: Exception) {
        // warn, not error: Logger.error would raise the IDE's "fatal errors" dialog for a
        // failure that is fully handled here (staging cleanup + user notification).
        LOG.warn("KMP Wizard: Android Studio generation failed", e)
        notify(
            null,
            KMPWizardBundle.message("notify.failure.title"),
            KMPWizardBundle.message("notify.failure.content", e.message ?: e.toString()),
            NotificationType.ERROR,
        )
    }
}

/**
 * Tells the user why nothing was generated. Studio's form cannot reject the name up
 * front (see [studioSettings]), so this is the earliest point the problem can surface;
 * Studio's own default project is left untouched.
 */
internal fun notifyInvalidStudioName(input: StudioInput.InvalidName) {
    LOG.warn("KMP Wizard: Rejected ${input.parameter} name \"${input.value}\" — nothing generated.")
    val label = when (input.parameter) {
        NameParameter.FEATURE -> KMPWizardBundle.message("studio.param.feature")
        NameParameter.FIELD -> KMPWizardBundle.message("studio.param.field")
    }
    notify(
        null,
        KMPWizardBundle.message("notify.failure.title"),
        KMPWizardBundle.message("studio.notify.invalidName", label, input.value),
        NotificationType.ERROR,
    )
}
