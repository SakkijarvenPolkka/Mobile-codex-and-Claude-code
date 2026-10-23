// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

class FsErrorTest {

    private val notAllowed = FsError.NameInvalid(NameProblem.NotAllowedByVolume)

    @Test
    fun mapsNioExceptions() {
        assertEquals(FsError.AccessDenied, AccessDeniedException("f").toFsError())
        assertEquals(FsError.NotFound, NoSuchFileException("f").toFsError())
        assertEquals(FsError.AlreadyExists, FileAlreadyExistsException("f").toFsError())
        assertEquals(FsError.Busy, FsException(FsError.Busy).toFsError())

        assertEquals(FsError.NoSpace, FileSystemException("f", null, "No space left on device").toFsError())
        // Without a reason, the message is read.
        assertEquals(FsError.NoSpace, FileSystemException("No space left on device").toFsError())
        assertEquals(FsError.NoSpace, IOException("write failed: ENOSPC (No space left on device)").toFsError())
        assertEquals(FsError.NoSpace, IOException("write failed: EDQUOT").toFsError())

        assertEquals(FsError.ReadOnly, FileSystemException("f", null, "Read-only file system").toFsError())
        assertEquals(FsError.ReadOnly, FileSystemException("Read-only file system").toFsError())
        assertEquals(FsError.ReadOnly, IOException("open failed: EROFS").toFsError())

        assertEquals(FsError.TooLargeForVolume, FileSystemException("f", null, "File too large").toFsError())
        assertEquals(FsError.TooLargeForVolume, IOException("write failed: EFBIG").toFsError())

        assertEquals(notAllowed, FileSystemException("f", null, "Invalid argument").toFsError())
        assertEquals(notAllowed, FileSystemException("f", null, "File name too long").toFsError())
        assertEquals(notAllowed, IOException("open failed: EINVAL").toFsError())
        assertEquals(notAllowed, IOException("open failed: ENAMETOOLONG").toFsError())

        assertEquals(FsError.IoError, FileSystemException("f", null, "Input/output error").toFsError())
        assertEquals(FsError.IoError, IOException("read failed: EIO (I/O error)").toFsError())

        // Android's java.io messages: "<path>: open failed: <errno> (<strerror>)".
        assertEquals(
            FsError.AccessDenied,
            FileNotFoundException("/x: open failed: EACCES (Permission denied)").toFsError(),
        )
        assertEquals(
            FsError.NotFound,
            FileNotFoundException("/EIO/x: open failed: ENOENT (No such file or directory)").toFsError(),
        )

        assertEquals(FsError.Unknown("boom"), IOException("boom").toFsError())
        assertEquals(FsError.Unknown(null), RuntimeException().toFsError())
        assertTrue(IOException("rename failed: EXDEV (Cross-device link)").toFsError() is FsError.Unknown)
        assertTrue(
            AtomicMoveNotSupportedException("a", "b", "Invalid cross-device link").toFsError() is FsError.Unknown,
        )
    }

    @Test
    fun onlyWholeOperationErrorsAreFatal() {
        val all = listOf(
            FsError.AccessDenied, FsError.NotFound, FsError.AlreadyExists, FsError.NoSpace,
            FsError.NameInvalid(NameProblem.Empty), FsError.TooLargeForVolume, FsError.CrossesIntoSelf,
            FsError.ReadOnly, FsError.IoError, FsError.VolumeRemoved, FsError.Restricted, FsError.Corrupt,
            FsError.Encrypted, FsError.Unsupported, FsError.UnsafeArchive, FsError.UnsupportedEncoding,
            FsError.BinUnavailable, FsError.Busy, FsError.Unknown(null),
        )
        assertEquals(
            setOf(FsError.NoSpace, FsError.IoError, FsError.ReadOnly, FsError.VolumeRemoved),
            all.filter { it.isFatal }.toSet(),
        )
    }
}
