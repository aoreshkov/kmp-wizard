package app.oreshkov.kmp.wizard

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Drives [ApiDumpAfterSyncListener] with synthetic task events: only the first Gradle
 * resolve of the generated root may trigger apiDump, the listener must unsubscribe
 * exactly once whatever that resolve's outcome, and a project it cannot resolve must
 * skip the dump rather than fail.
 */
class ApiDumpAfterSyncListenerTest {

    private val rootPath = "/work/MyApp"
    private val project: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "FakeProject"
            else -> null
        }
    } as Project

    private val dumps = mutableListOf<Pair<Project, String>>()
    private val unsubscribed = mutableListOf<ExternalSystemTaskNotificationListener>()

    private fun listener(resolve: (ExternalSystemTaskId) -> Project? = { project }) =
        ApiDumpAfterSyncListener(
            rootPath = rootPath,
            resolveProject = resolve,
            runDump = { p, path -> dumps += p to path },
            unsubscribe = { unsubscribed += it },
        )

    private fun taskId(
        type: ExternalSystemTaskType = ExternalSystemTaskType.RESOLVE_PROJECT,
        system: ProjectSystemId = GradleConstants.SYSTEM_ID,
    ) = ExternalSystemTaskId.create(system, type, "test-project-id")

    @Test fun `the first matching resolve runs apiDump once and unsubscribes`() {
        val l = listener()
        l.onSuccess(rootPath, taskId())

        assertEquals(listOf(project to rootPath), dumps)
        assertEquals(1, unsubscribed.size)
        assertSame(l, unsubscribed.single())
    }

    @Test fun `later matching resolves are ignored, even if delivered after unsubscribing`() {
        val l = listener()
        l.onSuccess(rootPath, taskId())
        l.onSuccess(rootPath, taskId())
        l.onFailure(rootPath, taskId(), RuntimeException("second sync"))

        assertEquals("apiDump must run exactly once", 1, dumps.size)
        assertEquals("unsubscribe must happen exactly once", 1, unsubscribed.size)
    }

    @Test fun `events for other task types, systems or paths are ignored`() {
        val l = listener()
        l.onSuccess(rootPath, taskId(type = ExternalSystemTaskType.EXECUTE_TASK))
        l.onSuccess(rootPath, taskId(system = ProjectSystemId("MAVEN")))
        l.onSuccess("/work/OtherApp", taskId())
        l.onSuccess("/work/MyApp/sub", taskId())
        l.onFailure("/work/OtherApp", taskId(), RuntimeException("unrelated"))
        l.onCancel("/work/OtherApp", taskId())

        assertEquals(emptyList<Pair<Project, String>>(), dumps)
        assertEquals("must stay subscribed for the real event", 0, unsubscribed.size)

        l.onSuccess(rootPath, taskId())
        assertEquals(1, dumps.size)
    }

    @Test fun `a failed first sync unsubscribes without running apiDump`() {
        val l = listener()
        l.onFailure(rootPath, taskId(), RuntimeException("sync failed"))
        l.onSuccess(rootPath, taskId())

        assertEquals(emptyList<Pair<Project, String>>(), dumps)
        assertEquals(1, unsubscribed.size)
    }

    @Test fun `a cancelled first sync unsubscribes without running apiDump`() {
        val l = listener()
        l.onCancel(rootPath, taskId())
        l.onSuccess(rootPath, taskId())

        assertEquals(emptyList<Pair<Project, String>>(), dumps)
        assertEquals(1, unsubscribed.size)
    }

    @Test fun `an unresolvable project skips apiDump but still unsubscribes`() {
        val l = listener(resolve = { null })
        l.onSuccess(rootPath, taskId())

        assertEquals(emptyList<Pair<Project, String>>(), dumps)
        assertEquals(1, unsubscribed.size)
    }
}
