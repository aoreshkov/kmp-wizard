package app.oreshkov.kmp.wizard

import app.oreshkov.kmp.wizard.license.KMPLicense
import app.oreshkov.kmp.wizard.template.ProjectStructureGenerator
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
