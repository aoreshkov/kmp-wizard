package app.oreshkov.kmp.wizard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Name prefix of the backup directory a commit creates inside the project root. */
internal const val COMMIT_BACKUP_DIR_PREFIX = ".kmp-wizard-backup-"

/**
 * Runs [generate] against an isolated staging directory and commits the result to
 * [rootDir] only once generation has fully succeeded, so a cancel or a failure never
 * leaves a half-built project among the IDE's own files.
 *
 * Guarantees, whatever [generate] or the commit does:
 * - the staging directory is always deleted (cleanup runs [NonCancellable]);
 * - [rootDir] is touched only after [generate] returned normally and the coroutine
 *   is still active ([ensureActive] is the last bail-out point);
 * - the commit is all-or-nothing: if it fails part-way (disk full, a file held open by
 *   an antivirus scanner on Windows) every file it wrote is removed, every file it
 *   displaced is put back, and directories it created — [rootDir] included — are
 *   deleted again, so the root is left exactly as it was found;
 * - exceptions (including [kotlinx.coroutines.CancellationException]) propagate to
 *   the caller unchanged.
 *
 * [replacedPaths] are root-relative entries the generated project supersedes (Android
 * Studio's default scaffold). They are moved aside as part of the commit rather than
 * deleted up front, so a failed commit restores them along with everything else.
 *
 * All filesystem work runs on [Dispatchers.IO]. Top-level + internal so the
 * commit/cancel/cleanup contract is unit-testable without the wizard UI; [copyFile]
 * exists only so tests can inject an I/O failure mid-commit.
 */
internal suspend fun generateStagedThenCommit(
    rootDir: File,
    replacedPaths: List<String> = emptyList(),
    copyFile: (source: Path, target: Path) -> Unit = ::copyPreservingAttributes,
    generate: suspend (staging: File) -> Unit,
) {
    val staging = withContext(Dispatchers.IO) {
        Files.createTempDirectory("kmp-wizard-").toFile()
    }
    try {
        withContext(Dispatchers.IO) {
            generate(staging)
            ensureActive() // last chance to bail out before touching the project root
            RootCommit(rootDir, copyFile).run(staging, replacedPaths)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            staging.deleteRecursively()
        }
    }
}

private fun copyPreservingAttributes(source: Path, target: Path) {
    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
}

/**
 * One commit of a staged tree into [rootDir], recording every change it makes so a
 * failure can be rolled back.
 *
 * Existing entries the commit would overwrite or that are listed as replaced are moved
 * (a same-volume rename, never a copy) into a backup directory inside [rootDir], which
 * is only created when something needs displacing — the IDEA path, whose root holds no
 * overlapping files, never creates one.
 */
private class RootCommit(
    private val rootDir: File,
    private val copyFile: (Path, Path) -> Unit,
) {
    private val createdDirs = mutableListOf<Path>()
    private val createdFiles = mutableListOf<Path>()
    private val displaced = mutableListOf<Pair<Path, Path>>() // original -> backup
    private var backupDir: Path? = null

    fun run(staging: File, replacedPaths: List<String>) {
        try {
            ensureDirectory(rootDir.toPath())
            replacedPaths.forEach { moveAside(rootDir.toPath().resolve(it)) }
            copyTree(staging.toPath())
            // COPY_ATTRIBUTES carries POSIX modes only where the filesystem has them;
            // the wrapper must be runnable regardless.
            rootDir.resolve("gradlew").takeIf { it.isFile }?.setExecutable(true)
        } catch (e: Throwable) {
            rollBack(e)
            throw e
        }
        // Committed: the displaced originals are no longer needed.
        backupDir?.toFile()?.deleteRecursively()
    }

    private fun copyTree(staging: Path) {
        staging.toFile().walkTopDown().forEach { source ->
            if (source.toPath() == staging) return@forEach
            val target = rootDir.toPath().resolve(source.toPath().relativeTo(staging))
            if (source.isDirectory) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    moveAside(target)
                }
                ensureDirectory(target)
            } else {
                moveAside(target)
                copyFile(source.toPath(), target)
                createdFiles.add(target) // add(), not +=: a Path is itself an Iterable<Path>
            }
        }
    }

    /** Creates [dir] and any missing parents, remembering which ones this commit made. */
    private fun ensureDirectory(dir: Path) {
        if (Files.isDirectory(dir)) return
        dir.parent?.let(::ensureDirectory)
        Files.createDirectory(dir)
        createdDirs.add(dir)
    }

    /** Moves an existing entry out of the way so the commit can be undone. No-op if absent. */
    private fun moveAside(target: Path) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        val backup = backupRoot().resolve(rootDir.toPath().relativize(target))
        Files.createDirectories(backup.parent)
        Files.move(target, backup)
        displaced += target to backup
    }

    private fun backupRoot(): Path =
        backupDir ?: Files.createTempDirectory(rootDir.toPath(), COMMIT_BACKUP_DIR_PREFIX).also { backupDir = it }

    /**
     * Best-effort undo in reverse order. Every step runs even if an earlier one fails;
     * failures are attached to [cause] as suppressed exceptions instead of masking it.
     */
    private fun rollBack(cause: Throwable) {
        fun attempt(step: () -> Unit) {
            try {
                step()
            } catch (e: Exception) {
                cause.addSuppressed(e)
            }
        }
        createdFiles.asReversed().forEach { attempt { Files.deleteIfExists(it) } }
        // Only directories this commit created, deepest first, and only if now empty —
        // before the restore below, which may need a displaced entry's path back.
        createdDirs.asReversed().forEach { dir -> attempt { if (Files.isDirectory(dir) && dir.isEmptyDirectory()) Files.delete(dir) } }
        displaced.asReversed().forEach { (original, backup) ->
            attempt {
                Files.createDirectories(original.parent)
                Files.move(backup, original)
            }
        }
        backupDir?.let { dir -> attempt { if (!dir.toFile().deleteRecursively()) throw IOException("Could not delete $dir") } }
    }

    private fun Path.relativeTo(base: Path): Path = base.relativize(this)

    private fun Path.isEmptyDirectory(): Boolean = Files.list(this).use { !it.findAny().isPresent }
}
