package app.oreshkov.kmp.wizard

import com.sun.nio.file.ExtendedOpenOption
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Makes [file] undeletable until the returned handle is closed — what an antivirus
 * scanner or indexer holding a file open does on Windows.
 *
 * Windows: an open handle without delete sharing blocks the delete. POSIX: a read-only
 * parent directory blocks unlinking its entries. Returns `null` where neither works
 * (e.g. running as root), so callers can skip the test via an assumption.
 */
internal fun holdUndeletable(file: File): AutoCloseable? {
    if (System.getProperty("os.name").startsWith("Windows")) {
        // Java opens files with delete sharing by default; a scanner's handle does not.
        val handle = Files.newByteChannel(file.toPath(), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)
        return AutoCloseable { handle.close() }
    }
    val parent = file.parentFile.toPath()
    val original = Files.getPosixFilePermissions(parent)
    Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"))
    val restore = AutoCloseable { Files.setPosixFilePermissions(parent, original) }
    if (Files.isWritable(parent)) { // root ignores the permission bits
        restore.close()
        return null
    }
    return restore
}
