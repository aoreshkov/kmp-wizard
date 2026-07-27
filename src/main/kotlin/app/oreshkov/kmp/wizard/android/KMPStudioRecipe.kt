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

    if (!deleteAndroidStudioDefaults(rootDir)) {
        LOG.warn("KMP Wizard: Could not remove Android Studio's default project files.")
        notify(
            null,
            KMPWizardBundle.message("notify.failure.title"),
            KMPWizardBundle.message("studio.notify.cleanup.failed"),
            NotificationType.ERROR,
        )
        return
    }

    try {
        // Staging isolation and commit-on-success, exactly as on the IDEA path: a failure
        // must not leave a half-rendered tree mixed in with Studio's own project files.
        runBlocking {
            generateStagedThenCommit(rootDir) { staging ->
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
