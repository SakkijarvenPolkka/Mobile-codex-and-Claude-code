// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {

    @Test
    fun eachMappedErrorHasItsOwnText() {
        assertEquals(UiText.res(R.string.error_access_denied), errorText(FsError.AccessDenied))
        assertEquals(UiText.res(R.string.error_not_found), errorText(FsError.NotFound))
        assertEquals(UiText.res(R.string.restricted_banner), errorText(FsError.Restricted))
        assertEquals(UiText.res(R.string.volume_unavailable), errorText(FsError.VolumeRemoved))
    }

    @Test
    fun otherErrorsAreGenericNotAccessDeniedOrNotFound() {
        assertEquals(UiText.res(R.string.error_generic), errorText(FsError.IoError))
        assertEquals(UiText.res(R.string.error_generic), errorText(FsError.Unknown("EINVAL")))
        assertEquals(UiText.res(R.string.error_generic), errorText(FsError.Unknown(null)))
    }
}
