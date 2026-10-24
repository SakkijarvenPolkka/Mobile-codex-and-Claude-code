// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.NameProblem
import io.github.sakkijarvenpolkka.explorer.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {

    @Test
    fun eachMappedErrorHasItsOwnText() {
        assertEquals(UiText.res(R.string.error_access_denied), errorText(FsError.AccessDenied))
        assertEquals(UiText.res(R.string.error_not_found), errorText(FsError.NotFound))
        assertEquals(UiText.res(R.string.restricted_banner), errorText(FsError.Restricted))
    }

    @Test
    fun operationErrorsHaveTheirOwnText() {
        assertEquals(UiText.res(R.string.err_no_space), errorText(FsError.NoSpace))
        assertEquals(UiText.res(R.string.err_read_only), errorText(FsError.ReadOnly))
        assertEquals(UiText.res(R.string.err_io), errorText(FsError.IoError))
        assertEquals(UiText.res(R.string.err_volume_removed), errorText(FsError.VolumeRemoved))
        assertEquals(UiText.res(R.string.err_into_self), errorText(FsError.CrossesIntoSelf))
        assertEquals(UiText.res(R.string.err_too_large_fat), errorText(FsError.TooLargeForVolume))
        assertEquals(UiText.res(R.string.err_unsupported_item), errorText(FsError.Unsupported))
        assertEquals(UiText.res(R.string.err_busy), errorText(FsError.Busy))
        assertEquals(UiText.res(R.string.err_already_exists), errorText(FsError.AlreadyExists))
        // A name refused by the volume, or one its rules refuse before anything is written.
        assertEquals(UiText.res(R.string.err_name_volume), errorText(FsError.NameInvalid(NameProblem.NotAllowedByVolume)))
        assertEquals(UiText.res(R.string.err_name_volume), errorText(FsError.NameInvalid(NameProblem.FatChar(':'))))
    }

    @Test
    fun archiveErrorsHaveTheirOwnText() {
        assertEquals(UiText.res(R.string.zip_unsafe), errorText(FsError.UnsafeArchive))
        // Damaged or password protected: one text for both, as spec §6.2 words it.
        assertEquals(UiText.res(R.string.zip_corrupt), errorText(FsError.Corrupt))
        assertEquals(UiText.res(R.string.zip_corrupt), errorText(FsError.Encrypted))
        assertEquals(UiText.res(R.string.zip_encoding), errorText(FsError.UnsupportedEncoding))
    }

    @Test
    fun otherErrorsAreGenericNotAccessDeniedOrNotFound() {
        assertEquals(UiText.res(R.string.error_generic), errorText(FsError.BinUnavailable))
        assertEquals(UiText.res(R.string.error_generic), errorText(FsError.Unknown("EINVAL")))
        assertEquals(UiText.res(R.string.error_generic), errorText(FsError.Unknown(null)))
    }
}
