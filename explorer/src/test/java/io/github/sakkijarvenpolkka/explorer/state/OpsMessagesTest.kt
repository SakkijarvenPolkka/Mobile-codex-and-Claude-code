// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ItemFailure
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/** What the shell tells of an operation that ended ([tellingOf]); the view model's side is in [ExplorerViewModelOpsTest]. */
class OpsMessagesTest {

    private val root: Path = Paths.get("/storage/emulated/0")

    @Test
    fun deleteToBinAccessDeniedIsListed() {
        val (a, b, c) = listOf("a.txt", "b.txt", "c.txt").map { root.resolve(it).toString() }
        val spec = OperationSpec("op", OpKind.DELETE_TO_BIN, listOf(a, b, c))
        val denied = ItemFailure(a, FsError.AccessDenied)
        val failed = listOf(denied, ItemFailure(b, FsError.BinUnavailable), ItemFailure(c, FsError.NotFound))

        val told = tellingOf(FinishedOp(spec, OpResult.Done(emptyList(), failed)))

        // What the bin could not take is asked about, what is gone needs no word, the rest is listed.
        assertEquals(ConfirmRequest.BinUnavailable(listOf(root.resolve("b.txt"))), told.question)
        assertEquals(FinishedOp(spec, OpResult.Done(emptyList(), listOf(denied))), told.listed)
        assertNull(told.message)
    }

    @Test
    fun refusedDeleteToBinAsksPermanent() {
        val paths = listOf(root.resolve("a.txt"), root.resolve("b.txt"))
        val spec = OperationSpec("op", OpKind.DELETE_TO_BIN, paths.map { it.toString() })

        val told = tellingOf(FinishedOp(spec, OpResult.Failed(FsError.BinUnavailable, 0, 0)))

        // No Recycle Bin at all: every item is asked about, and nothing else is told.
        assertEquals(Telling(question = ConfirmRequest.BinUnavailable(paths)), told)
    }
}
