package app.oreshkov.kmp.wizard

import app.oreshkov.kmp.wizard.license.KMPLicense
import app.oreshkov.kmp.wizard.template.ProjectStructureGenerator
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Platform-harness coverage for the pieces of the wizard that need a running IDE:
 * the notification group registration (from plugin.xml) and the success/failure
 * routing of [notify], plus a VFS smoke check on a freshly generated project.
 *
 * The full `setupProject` flow can't be driven here because it calls
 * `linkAndSyncGradleProject` (a real Gradle sync), so these tests target the
 * genuinely platform-dependent seams instead. This is the consumer that justifies
 * the `TestFrameworkType.Platform` test dependency.
 */
class KMPProjectWizardPlatformTest : BasePlatformTestCase() {

    fun testNotificationGroupIsRegistered() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
        assertNotNull("Notification group '$NOTIFICATION_GROUP_ID' must be registered in plugin.xml", group)
    }

    fun testSuccessAndFailureNotificationsRouteCorrectly() {
        val received = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    received.add(notification)
                }
            },
        )

        val successTitle = KMPWizardBundle.message("notify.success.title")
        val successContent = KMPWizardBundle.message("notify.success.content", "Ledger")
        val failureTitle = KMPWizardBundle.message("notify.failure.title")
        val failureContent = KMPWizardBundle.message("notify.failure.content", "boom")

        notify(project, successTitle, successContent, NotificationType.INFORMATION)
        notify(project, failureTitle, failureContent, NotificationType.ERROR)

        assertEquals("both notifications should be delivered", 2, received.size)

        val success = received[0]
        assertEquals(NotificationType.INFORMATION, success.type)
        assertEquals(successTitle, success.title)
        assertEquals(successContent, success.content)

        val failure = received[1]
        assertEquals(NotificationType.ERROR, failure.type)
        assertEquals(failureTitle, failure.title)
        assertEquals(failureContent, failure.content)
    }

    // ── reportGenerationOutcome: what the user is told about each ending ─────────

    fun testCancelAfterCommitWarnsThatTheProjectIsNotLinked() {
        val received = collectNotifications()

        val thrown = runCatching {
            runBlocking {
                reportGenerationOutcome(project, "Ledger") { onCommitted ->
                    onCommitted()
                    throw CancellationException("Cancel pressed during the Gradle link")
                }
            }
        }.exceptionOrNull()

        assertTrue("cancellation must be rethrown, got $thrown", thrown is CancellationException)
        assertEquals(1, received.size)
        assertEquals(NotificationType.WARNING, received[0].type)
        assertEquals(KMPWizardBundle.message("notify.cancelledAfterCommit.title"), received[0].title)
        assertEquals(KMPWizardBundle.message("notify.cancelledAfterCommit.content", "Ledger"), received[0].content)
    }

    fun testCancelBeforeCommitIsSilent() {
        val received = collectNotifications()

        val thrown = runCatching {
            runBlocking {
                reportGenerationOutcome(project, "Ledger") { throw CancellationException("Cancel pressed while rendering") }
            }
        }.exceptionOrNull()

        assertTrue("cancellation must be rethrown, got $thrown", thrown is CancellationException)
        assertEquals("nothing was written, so nothing to report", 0, received.size)
    }

    fun testLeftoverBackupTurnsSuccessIntoAWarningNamingIt() {
        val received = collectNotifications()
        val leftover = Path.of("/work/Ledger/.kmp-wizard-backup-1")

        runBlocking { reportGenerationOutcome(project, "Ledger") { onCommitted -> onCommitted(); leftover } }

        assertEquals(1, received.size)
        assertEquals(NotificationType.WARNING, received[0].type)
        assertEquals(KMPWizardBundle.message("notify.success.leftoverBackup", "Ledger", leftover.toString()), received[0].content)
    }

    fun testFailureIsReportedAndNotRethrown() {
        val received = collectNotifications()

        runBlocking { reportGenerationOutcome(project, "Ledger") { throw IOException("disk full") } }

        assertEquals(1, received.size)
        assertEquals(NotificationType.ERROR, received[0].type)
        assertEquals(KMPWizardBundle.message("notify.failure.content", "disk full"), received[0].content)
    }

    // ── Pro entitlement gate ─────────────────────────────────────────────────

    fun testALapsedLicenseStripsProOutputRequestedInTheForm() {
        // The form's checkboxes were set while licensed; generation re-reads the license
        // and must drop both Pro features if it no longer holds.
        val requested = KMPProjectSettings(includeAgentConfig = true, includeCi = true)

        val unlicensed = requested.withEntitlement(licensed = false)
        assertFalse(unlicensed.includeAgentConfig)
        assertFalse(unlicensed.includeCi)

        val dir = createTempDirectory("kmp_platform_").toFile()
        try {
            ProjectStructureGenerator(unlicensed).generate(dir)
            assertFalse("CLAUDE.md must be stripped", dir.resolve("CLAUDE.md").exists())
            assertFalse(".claude must be stripped", dir.resolve(".claude").exists())
            assertFalse(".github must be stripped", dir.resolve(".github").exists())
        } finally {
            dir.deleteRecursively()
        }

        val licensed = requested.withEntitlement(licensed = true)
        assertTrue("an entitlement keeps what was requested", licensed.includeAgentConfig && licensed.includeCi)
        assertFalse(
            "an entitlement never turns a feature on",
            requested.copy(includeCi = false).withEntitlement(licensed = true).includeCi,
        )
    }

    private fun collectNotifications(): MutableList<Notification> {
        val received = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    received.add(notification)
                }
            },
        )
        return received
    }

    fun testIsProIsFalseWithoutALicense() {
        // The Pro gate that drives the wizard's checkbox defaults and the final
        // entitlement re-check in setupProject. In the test IDE there is no
        // confirmation stamp (and LicensingFacade may not even be initialized), so
        // the tri-state isLicensed() is null/false — isPro() must map both to false.
        assertFalse("isPro() must fail closed in an unlicensed IDE", KMPLicense.isPro())
    }

    fun testGeneratedProjectIsVisibleToVfs() {
        val dir = createTempDirectory("kmp_platform_").toFile()
        try {
            ProjectStructureGenerator(KMPProjectSettings()).generate(dir)

            val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.toPath())
            assertNotNull("VFS should see the generated project root", rootVf)

            val settingsVf = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(dir.toPath().resolve("settings.gradle.kts"))
            assertNotNull("settings.gradle.kts should be visible via VFS", settingsVf)
        } finally {
            dir.deleteRecursively()
        }
    }
}
