package app.oreshkov.kmp.wizard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Runs [generate] against an isolated staging directory and commits the result to
 * [rootDir] only once generation has fully succeeded, so a cancel or a failure never
 * leaves a half-built project among the IDE's own files.
 *
 * Guarantees, whatever [generate] does:
 * - the staging directory is always deleted (cleanup runs [NonCancellable]);
 * - [rootDir] is touched only after [generate] returned normally and the coroutine
 *   is still active ([ensureActive] is the last bail-out point);
 * - exceptions (including [kotlinx.coroutines.CancellationException]) propagate to
 *   the caller unchanged.
 *
 * All filesystem work runs on [Dispatchers.IO]. Top-level + internal so the
 * commit/cancel/cleanup contract is unit-testable without the wizard UI.
 */
internal suspend fun generateStagedThenCommit(rootDir: File, generate: suspend (staging: File) -> Unit) {
    val staging = withContext(Dispatchers.IO) {
        Files.createTempDirectory("kmp-wizard-").toFile()
    }
    try {
        withContext(Dispatchers.IO) {
            generate(staging)
            ensureActive() // last chance to bail out before touching the project root
            staging.copyRecursively(rootDir, overwrite = true)
            // copyRecursively does not preserve the executable bit.
            rootDir.resolve("gradlew").setExecutable(true)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            staging.deleteRecursively()
        }
    }
}
