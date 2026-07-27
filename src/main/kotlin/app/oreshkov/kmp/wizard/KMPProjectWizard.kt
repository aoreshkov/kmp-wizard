package app.oreshkov.kmp.wizard

import app.oreshkov.kmp.wizard.license.KMPLicense
import app.oreshkov.kmp.wizard.template.ProjectStructureGenerator
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import javax.swing.Icon

@Service(Service.Level.PROJECT)
internal class KMPWizardCoroutineService(val scope: CoroutineScope)

private val LOG = logger<KMPProjectWizard>()

/** Registered in plugin.xml; shared so tests can resolve the same group. */
internal const val NOTIFICATION_GROUP_ID = "app.oreshkov.kmp.wizard.notifications"

/**
 * Routes a wizard outcome to a balloon notification. Top-level + internal so the
 * success/failure routing can be exercised by a platform-harness test without
 * standing up the whole wizard chain.
 *
 * [project] is nullable because the Android Studio path runs inside a template recipe,
 * which is handed no Project — an application-level balloon is the correct fallback there.
 */
internal fun notify(project: Project?, title: String, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP_ID)
        .createNotification(title, content, type)
        .notify(project)
}

/**
 * Runs `apiDump` once the *first* Gradle resolve of [rootPath] finishes, then unsubscribes.
 *
 * [resolveProject] exists because the two wizard paths obtain the [Project] differently:
 * the IntelliJ IDEA generator already holds one, while the Android Studio recipe is handed
 * none and has to read it back off the sync task.
 */
private class ApiDumpAfterSyncListener(
    private val rootPath: String,
    private val resolveProject: (ExternalSystemTaskId) -> Project?,
) : ExternalSystemTaskNotificationListener {

    private fun isFirstSyncOfProject(projectPath: String, id: ExternalSystemTaskId): Boolean =
        id.type == ExternalSystemTaskType.RESOLVE_PROJECT &&
            id.projectSystemId == GradleConstants.SYSTEM_ID &&
            FileUtil.pathsEqual(projectPath, rootPath)

    private fun unsubscribe() {
        ExternalSystemProgressNotificationManager.getInstance().removeNotificationListener(this)
    }

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (!isFirstSyncOfProject(projectPath, id)) return
        unsubscribe()
        val project = resolveProject(id)
        if (project == null) {
            LOG.warn("KMP Wizard: Could not resolve the project for the finished sync — skipping apiDump.")
            return
        }
        runApiDump(project, rootPath)
    }

    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
        if (!isFirstSyncOfProject(projectPath, id)) return
        unsubscribe()
        // The sync surfaces its own errors; apiDump against a broken build
        // would only add noise on top of them.
        LOG.warn("KMP Wizard: Initial Gradle sync failed — skipping apiDump.")
    }

    override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
        if (!isFirstSyncOfProject(projectPath, id)) return
        unsubscribe()
        LOG.info("KMP Wizard: Initial Gradle sync cancelled — skipping apiDump.")
    }
}

/**
 * Arranges for `apiDump` to run once the generated project's *first* Gradle sync
 * finishes.
 *
 * Templates deliberately ship no BCV dumps: dumps are compiler-output snapshots
 * whose generated symbol names, declaration order, and Compose lambda keys all
 * derive from the ledger's names, so a substituted copy can never match what the
 * user's code produces — the first valid dump must come from `apiDump` on the
 * generated sources. Must be called *before* the sync is scheduled so the
 * resolve-finished event cannot be missed. The listener self-removes after the
 * first resolve of [rootPath]; [project] parents it so an abandoned project
 * cannot leak it.
 */
internal fun scheduleApiDumpAfterSync(project: Project, rootPath: String) {
    ExternalSystemProgressNotificationManager.getInstance()
        .addNotificationListener(ApiDumpAfterSyncListener(rootPath) { project }, project)
}

/**
 * [scheduleApiDumpAfterSync] for callers with no [Project] handle — the Android Studio
 * template recipe, which runs while Studio is still assembling the project.
 *
 * The listener is registered application-level (no parent disposable is available) and
 * resolves the project from the sync task itself; it unsubscribes on the first matching
 * resolve, whatever its outcome.
 */
internal fun scheduleApiDumpAfterFirstSync(rootPath: String) {
    ExternalSystemProgressNotificationManager.getInstance()
        .addNotificationListener(ApiDumpAfterSyncListener(rootPath, ExternalSystemTaskId::findProject))
}

private fun runApiDump(project: Project, rootPath: String) {
    LOG.info("KMP Wizard: Running apiDump to create binary-compatibility dumps...")
    val taskSettings = ExternalSystemTaskExecutionSettings().apply {
        externalProjectPath = rootPath
        taskNames = listOf("apiDump")
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
    }
    // The sync listener fires on a background thread; runTask goes through the
    // execution infrastructure, which expects the EDT.
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        ExternalSystemUtil.runTask(
            taskSettings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            object : TaskCallback {
                override fun onSuccess() {
                    LOG.info("KMP Wizard: apiDump finished — BCV dumps created.")
                }

                override fun onFailure() {
                    LOG.warn("KMP Wizard: apiDump failed — BCV dumps not created.")
                    notify(
                        project,
                        KMPWizardBundle.message("notify.apiDump.failure.title"),
                        KMPWizardBundle.message("notify.apiDump.failure.content"),
                        NotificationType.WARNING,
                    )
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
        )
    }
}

class KMPProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "app.oreshkov.kmp.wizard"
    override val name: String get() = KMPWizardBundle.message("wizard.name")
    override val icon: Icon = IconLoader.getIcon("/icons/kmpWizard.svg", javaClass)
    override val ordinal: Int = 100

    override fun createStep(context: WizardContext): NewProjectWizardStep {
        return RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::KMPWizardStep)
    }

    private class KMPWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

        private val base = requireNotNull(baseData) {
            "KMPWizardStep must be preceded by a NewProjectWizardBaseStep — the step chain in createStep() is misconfigured."
        }
        private val packageNameProperty = propertyGraph.property("com.example.${WizardInputValidation.sanitize(base.name)}")
        private val featureNameProperty = propertyGraph.property("note")
        private val fieldNameProperty = propertyGraph.property("content")
        private val testValueNameProperty = propertyGraph.property("Buy groceries")
        private val includeAndroidProperty = propertyGraph.property(true)
        private val includeDesktopProperty = propertyGraph.property(true)
        private val includeIosProperty = propertyGraph.property(true)
        // Pro features default to the user's entitlement: pre-checked when licensed, off and
        // locked otherwise. Held as an observable so the UI can react if the user activates a
        // license from within the wizard (see the "Get a license" link).
        private val proProperty = propertyGraph.property(KMPLicense.isPro())
        private var pro by proProperty
        private val includeAgentConfigProperty = propertyGraph.property(pro)
        private val includeCiProperty = propertyGraph.property(pro)

        var packageName by packageNameProperty
        var featureName by featureNameProperty
        var fieldName by fieldNameProperty
        var testValueName by testValueNameProperty
        var includeAndroid by includeAndroidProperty
        var includeDesktop by includeDesktopProperty
        var includeIos by includeIosProperty
        var includeAgentConfig by includeAgentConfigProperty
        var includeCi by includeCiProperty

        init {
            base.nameProperty.afterChange {
                if (packageName.startsWith("com.example.")) {
                    packageName = "com.example.${WizardInputValidation.sanitize(it)}"
                }
            }
        }

        override fun setupUI(builder: Panel) {
            with(builder) {
                row(KMPWizardBundle.message("settings.package.label")) {
                    textField()
                        .bindText(packageNameProperty)
                        .comment(KMPWizardBundle.message("settings.package.comment"))
                        .validationOnInput {
                            val pkg = it.text.trim()
                            when {
                                pkg.isBlank() -> error(KMPWizardBundle.message("settings.package.error.empty"))
                                !WizardInputValidation.isValidPackageName(pkg) ->
                                    error(KMPWizardBundle.message("settings.package.error.invalid"))
                                else -> null
                            }
                        }
                }

                row(KMPWizardBundle.message("settings.feature.label")) {
                    textField()
                        .bindText(featureNameProperty)
                        .comment(KMPWizardBundle.message("settings.feature.comment"))
                        .validationOnInput {
                            val name = it.text.trim()
                            when {
                                name.isBlank() -> error(KMPWizardBundle.message("settings.feature.error.empty"))
                                !WizardInputValidation.isValidIdentifier(name) ->
                                    error(KMPWizardBundle.message("settings.identifier.error.charset"))
                                else -> null
                            }
                        }
                }

                row(KMPWizardBundle.message("settings.field.label")) {
                    textField()
                        .bindText(fieldNameProperty)
                        .comment(KMPWizardBundle.message("settings.field.comment"))
                        .validationOnInput {
                            val name = it.text.trim()
                            when {
                                name.isBlank() -> error(KMPWizardBundle.message("settings.field.error.empty"))
                                !WizardInputValidation.isValidIdentifier(name) ->
                                    error(KMPWizardBundle.message("settings.identifier.error.charset"))
                                else -> null
                            }
                        }
                }

                row(KMPWizardBundle.message("settings.testValue.label")) {
                    textField()
                        .bindText(testValueNameProperty)
                        .comment(KMPWizardBundle.message("settings.testValue.comment"))
                        .validationOnInput {
                            if (it.text.trim().isBlank()) {
                                error(KMPWizardBundle.message("settings.testValue.error.empty"))
                            } else null
                        }
                }

                group(KMPWizardBundle.message("platforms.group")) {
                    row {
                        checkBox(KMPWizardBundle.message("platforms.android"))
                            .bindSelected(includeAndroidProperty)
                    }
                    row {
                        checkBox(KMPWizardBundle.message("platforms.desktop"))
                            .bindSelected(includeDesktopProperty)
                    }
                    row {
                        checkBox(KMPWizardBundle.message("platforms.ios"))
                            .bindSelected(includeIosProperty)
                            .validationOnApply {
                                if (!WizardInputValidation.isAtLeastOnePlatformSelected(includeAndroid, includeDesktop, includeIos)) {
                                    error(KMPWizardBundle.message("platforms.error.none"))
                                } else null
                            }
                    }
                }

                group(KMPWizardBundle.message("pro.group")) {
                    row {
                        label(KMPWizardBundle.message("pro.locked.hint"))
                        link(KMPWizardBundle.message("pro.locked.cta")) {
                            KMPLicense.requestLicense()
                            // The registration dialog is modal, so control returns here once it
                            // closes — re-check entitlement and unlock the features in place.
                            if (KMPLicense.isPro()) {
                                pro = true
                                includeAgentConfig = true
                                includeCi = true
                            }
                        }
                    }.visibleIf(proProperty.not())
                    row {
                        checkBox(KMPWizardBundle.message("pro.agentConfig"))
                            .bindSelected(includeAgentConfigProperty)
                            .enabledIf(proProperty)
                    }
                    row {
                        checkBox(KMPWizardBundle.message("pro.ci"))
                            .bindSelected(includeCiProperty)
                            .enabledIf(proProperty)
                    }
                }
            }
        }

        override fun setupProject(project: Project) {
            super.setupProject(project)

            val settings = KMPProjectSettings(
                appName = base.name,
                packageName = packageName,
                featureName = featureName,
                fieldName = fieldName,
                testValueName = testValueName,
                includeAndroid = includeAndroid,
                includeDesktop = includeDesktop,
                includeIos = includeIos,
                // Re-check entitlement here so generation can never be driven Pro-on
                // without a license, independent of the UI's enabled state.
                includeAgentConfig = includeAgentConfig && pro,
                includeCi = includeCi && pro,
            )

            val rootDir = File(base.path).resolve(base.name)
            val projectName = base.name
            LOG.info("KMP Wizard: Starting generation at ${rootDir.absolutePath}")

            project.service<KMPWizardCoroutineService>().scope.launch {
                try {
                    withBackgroundProgress(project, KMPWizardBundle.message("progress.generating", projectName), cancellable = true) {
                        LOG.info("KMP Wizard: Rendering templates...")
                        // Staging isolation, commit-on-success, and cleanup live in
                        // generateStagedThenCommit (unit-tested there).
                        generateStagedThenCommit(rootDir) { staging ->
                            ProjectStructureGenerator(settings).generate(staging)
                        }

                        LOG.info("KMP Wizard: Refreshing VFS...")
                        // Two-step refresh on purpose: the synchronous call is the only way to
                        // obtain a VirtualFile for a directory the VFS has never seen (safe here —
                        // we are off the EDT and outside any read action), and the async recursive
                        // markDirtyAndRefresh then loads the whole subtree without blocking.
                        val rootVfsDir = LocalFileSystem.getInstance()
                            .refreshAndFindFileByNioFile(rootDir.toPath())
                        if (rootVfsDir != null) {
                            // async + recursive + reload children: one pass, off the EDT.
                            VfsUtil.markDirtyAndRefresh(true, true, true, rootVfsDir)
                        }

                        LOG.info("KMP Wizard: Linking Gradle project...")
                        // Templates carry no BCV dumps (they cannot survive renaming) —
                        // the first sync's completion triggers apiDump to create them
                        // from the generated sources. Registered before linking so the
                        // resolve-finished event cannot be missed.
                        scheduleApiDumpAfterSync(project, rootDir.absolutePath)
                        withContext(Dispatchers.EDT) {
                            linkAndSyncGradleProject(project, rootDir.absolutePath)
                        }
                        LOG.info("KMP Wizard: Generation complete.")
                    }

                    notify(
                        project,
                        KMPWizardBundle.message("notify.success.title"),
                        KMPWizardBundle.message("notify.success.content", projectName),
                        NotificationType.INFORMATION,
                    )
                } catch (e: CancellationException) {
                    LOG.info("KMP Wizard: Generation cancelled.")
                    throw e
                } catch (e: Exception) {
                    // warn, not error: Logger.error would raise the IDE's "fatal errors" dialog
                    // for a failure that is fully handled here (cleanup + user notification).
                    LOG.warn("KMP Wizard: Generation failed", e)
                    notify(
                        project,
                        KMPWizardBundle.message("notify.failure.title"),
                        KMPWizardBundle.message("notify.failure.content", e.message ?: e.toString()),
                        NotificationType.ERROR,
                    )
                }
            }
        }

    }
}