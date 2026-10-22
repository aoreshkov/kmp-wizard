package app.oreshkov.kmp.wizard

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifetime rules of the Android Studio path's apiDump listeners: one pending listener
 * per project root, a timeout for a first sync that never comes, and nothing left behind
 * when the service (and with it the plugin) goes away. Listeners are registered with
 * these lifetimes as parent disposables, so a disposed lifetime is a removed listener.
 */
class KMPWizardAppServiceTest {

    private val scopeJob = SupervisorJob()
    private val service = KMPWizardAppService(CoroutineScope(scopeJob + Dispatchers.Unconfined))

    /** One gate per scheduled lifetime; completing it plays the timeout firing. */
    private val timeouts = mutableListOf<CompletableDeferred<Unit>>()

    @After fun tearDown() {
        Disposer.dispose(service)
        scopeJob.cancel()
    }

    private fun lifetime(rootPath: String): Disposable =
        service.apiDumpListenerLifetime(rootPath, awaitTimeout = {
            CompletableDeferred<Unit>().also { timeouts += it }.await()
        })

    private fun Disposable.disposedProbe(): () -> Boolean {
        var disposed = false
        Disposer.register(this) { disposed = true }
        return { disposed }
    }

    @Test fun `scheduling the same root again replaces the pending listener`() {
        val first = lifetime("/work/MyApp").disposedProbe()
        // Same root, spelled differently: keys are canonical paths.
        val second = lifetime("/work/other/../MyApp").disposedProbe()

        assertTrue("the earlier listener must be removed", first())
        assertFalse("exactly one listener stays live", second())
    }

    @Test fun `different roots keep their own listeners`() {
        val a = lifetime("/work/A").disposedProbe()
        val b = lifetime("/work/B").disposedProbe()

        assertFalse(a())
        assertFalse(b())
    }

    @Test fun `a listener whose first sync never comes is removed at the timeout`() {
        val pending = lifetime("/work/MyApp").disposedProbe()
        assertFalse(pending())

        timeouts.single().complete(Unit)

        assertTrue("the timeout must remove the listener", pending())
    }

    @Test fun `ending a lifetime early cancels its timeout`() {
        val lifetime = lifetime("/work/MyApp")

        Disposer.dispose(lifetime) // what the listener's one-shot claim does

        assertEquals("no timeout coroutine may outlive its listener", 0, scopeJob.children.count())
    }

    @Test fun `disposing the service removes every pending listener`() {
        val a = lifetime("/work/A").disposedProbe()
        val b = lifetime("/work/B").disposedProbe()

        Disposer.dispose(service)

        assertTrue(a())
        assertTrue(b())
    }
}
