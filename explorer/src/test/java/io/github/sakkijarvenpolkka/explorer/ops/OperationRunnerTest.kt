// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ops

import io.github.sakkijarvenpolkka.explorer.fs.ConflictAction
import io.github.sakkijarvenpolkka.explorer.fs.ConflictKind
import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ItemFailure
import io.github.sakkijarvenpolkka.explorer.fs.KO_LABELS
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpOptions
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.Resolution
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.fs.allowedActions
import io.github.sakkijarvenpolkka.explorer.fs.partsUnder
import io.github.sakkijarvenpolkka.explorer.fs.tree
import io.github.sakkijarvenpolkka.explorer.fs.volumesOf
import io.github.sakkijarvenpolkka.explorer.fs.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class OperationRunnerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val internal: Path by lazy { folder.newFolder("internal").toPath().toRealPath() }
    private val sd: Path by lazy { folder.newFolder("sd").toPath().toRealPath() }

    private val volumes by lazy {
        volumesOf(
            VolumeRoot("primary", internal, VolumeTraits(), removable = false),
            VolumeRoot("1234-5678", sd, VolumeTraits(fatNames = true), removable = true),
        )
    }

    /** Where the journal keeps its files. */
    private val journalDir: File by lazy { folder.newFolder("ops") }

    /** The scopes of the runners a test made, cancelled after it. */
    private val scopes = ArrayList<Job>()

    @After
    fun cancelRunners() {
        scopes.forEach { it.cancel() }
    }

    /**
     * A runner on the test's scheduler, over a real [FileOps] on [fs], with its journal in [journalDir].
     * Its scope is its own, not the test's background scope, so that `advanceUntilIdle` runs its work.
     */
    private fun TestScope.runner(
        fs: FsBackend = NioFsBackend,
        host: RunnerHost? = null,
        journalFs: FsBackend = NioFsBackend,
    ): OperationRunner {
        val journal = OperationJournal(journalDir, journalFs)
        val ops = FileOps(fs, volumes, KO_LABELS, journal, ids = { "r1" })
        val scope = CoroutineScope(SupervisorJob().also(scopes::add))
        return OperationRunner(scope, ops, journal, StandardTestDispatcher(testScheduler), host)
    }

    private fun copy(id: String, sources: List<Path>, target: Path) =
        OperationSpec(id, OpKind.COPY, sources.map { it.toString() }, target.toString())

    private fun read(path: Path): String = String(Files.readAllBytes(path))

    private suspend fun OperationRunner.pending(): PendingConflict = state.first { it.pending != null }.pending!!

    private fun OperationRunner.results(): Map<String, OpResult> =
        state.value.finished.associate { it.spec.id to it.result }

    @Test
    fun submitRunsSerially() = runTest {
        val a = write(internal, "src/a.txt", "new a")
        val dst = write(internal, "dst/a.txt", "old a").parent
        val b = write(internal, "src/b.txt", "b")
        val other = Files.createDirectory(internal.resolve("other"))
        val runner = runner()
        val first = copy("op1", listOf(a), dst)
        val second = copy("op2", listOf(b), other)

        runner.submit(first)
        runner.submit(second)
        val pending = runner.pending()

        // The second waits until the first is done, even while the first waits for an answer.
        assertEquals(first, runner.state.value.running?.spec)
        assertEquals(listOf(second), runner.state.value.queue)
        assertFalse(Files.exists(other.resolve("b.txt")))
        pending.answer(Resolution(ConflictAction.SKIP))
        advanceUntilIdle()

        assertEquals(listOf("op1", "op2"), runner.state.value.finished.map { it.spec.id })
        assertEquals("b", read(other.resolve("b.txt")))
        assertEquals("old a", read(dst.resolve("a.txt")))
        assertNull(runner.state.value.running)
        assertEquals(emptyList<OperationSpec>(), runner.state.value.queue)
    }

    @Test
    fun conflictPublishedAndAnswered() = runTest {
        val a = write(internal, "src/a.txt", "new")
        val dst = write(internal, "dst/a.txt", "old").parent
        val runner = runner()

        runner.submit(copy("op1", listOf(a), dst))
        val pending = runner.pending()

        assertEquals("op1", pending.opId)
        assertEquals(ConflictKind.FILE_FILE, pending.conflict.kind)
        assertEquals(a, pending.conflict.source.path)
        assertEquals(allowedActions(ConflictKind.FILE_FILE), pending.allowed)
        pending.answer(Resolution(ConflictAction.REPLACE))
        val finished = runner.state.first { it.finished.isNotEmpty() }.finished.single()

        assertEquals(OpResult.Done(listOf(dst.resolve("a.txt")), emptyList()), finished.result)
        assertEquals("new", read(dst.resolve("a.txt")))
        assertNull(runner.state.value.pending)
    }

    @Test
    fun cancelWhilePromptingCleansUp() = runTest {
        val one = write(internal, "src/1.txt", "one")
        val two = write(internal, "src/2.txt", "two, new")
        val dst = write(internal, "dst/2.txt", "two, old").parent
        val runner = runner()

        runner.submit(copy("op1", listOf(one, two), dst))
        runner.pending()
        runner.cancel("op1")
        advanceUntilIdle()

        assertEquals(mapOf("op1" to OpResult.Cancelled), runner.results())
        assertNull(runner.state.value.pending)
        assertNull(runner.state.value.running)
        assertEquals(emptyList<Path>(), partsUnder(internal))
        // What was done stays done; the file asked about is as it was.
        assertEquals(mapOf("1.txt" to "one", "2.txt" to "two, old"), tree(dst))
        assertEquals(emptyList<String>(), journalDir.list().orEmpty().toList())
    }

    @Test
    fun applyToAllAnswersOnce() = runTest {
        val a = write(internal, "src/a.txt", "new a")
        val b = write(internal, "src/b.txt", "new b")
        val dst = write(internal, "dst/a.txt", "old a").parent
        write(internal, "dst/b.txt", "old b")
        val runner = runner()

        runner.submit(copy("op1", listOf(a, b), dst))
        runner.pending().answer(Resolution(ConflictAction.REPLACE, applyToAll = true))
        advanceUntilIdle()

        // The second conflict took that answer: nothing more was asked, and the operation ran to its end.
        assertNull(runner.state.value.pending)
        assertEquals(
            mapOf("op1" to OpResult.Done(listOf(dst.resolve("a.txt"), dst.resolve("b.txt")), emptyList())),
            runner.results(),
        )
        assertEquals(mapOf("a.txt" to "new a", "b.txt" to "new b"), tree(dst))
    }

    @Test
    fun hostNotifiedBusyIdle() = runTest {
        val a = write(internal, "src/a.txt", "a")
        val dst = Files.createDirectory(internal.resolve("dst"))
        val events = ArrayList<String>()
        val host = object : RunnerHost {
            override fun onBusy() {
                events += "busy"
            }

            override fun onIdle() {
                events += "idle"
            }

            override fun onFinished(op: FinishedOp) {
                events += "finished ${op.spec.id}"
            }
        }
        val runner = runner(host = host)

        runner.submit(copy("op1", listOf(a), dst))

        // At once, from the call that brought the work.
        assertEquals(listOf("busy"), events)
        advanceUntilIdle()
        assertEquals(listOf("busy", "finished op1", "idle"), events)
    }

    @Test
    fun cancelTouchingVolume() = runTest {
        val a = write(internal, "src/a.txt", "new")
        val b = write(internal, "src/b.txt", "b")
        val toSd = write(sd, "dst/a.txt", "old").parent
        val other = Files.createDirectory(internal.resolve("other"))
        val runner = runner()

        // One to the card waits for an answer, one to the card waits in the queue, one stays inside.
        runner.submit(copy("op1", listOf(a), toSd))
        runner.submit(copy("op2", listOf(b), toSd))
        runner.submit(copy("op3", listOf(b), other))
        runner.pending()
        runner.cancelTouching(sd)
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "op1" to OpResult.Failed(FsError.VolumeRemoved, 0, 1),
                "op2" to OpResult.Failed(FsError.VolumeRemoved, 0, 0),
                "op3" to OpResult.Done(listOf(other.resolve("b.txt")), emptyList()),
            ),
            runner.results(),
        )
        assertEquals(mapOf("a.txt" to "old"), tree(toSd))
        assertNull(runner.state.value.pending)
    }

    @Test
    fun busyPathsDuringRun() = runTest {
        val a = write(internal, "src/a.txt", "new")
        val d = write(internal, "src/d/x.txt", "x").parent
        val dst = write(internal, "dst/a.txt", "old").parent
        val runner = runner()
        assertEquals(emptySet<Path>(), runner.busyPaths())

        runner.submit(copy("op1", listOf(a, d), dst))
        val pending = runner.pending()

        assertEquals(setOf(a, d, dst), runner.busyPaths())
        pending.answer(Resolution(ConflictAction.SKIP))
        advanceUntilIdle()
        assertEquals(emptySet<Path>(), runner.busyPaths())
    }

    @Test
    fun retryFailedOnlyUncopied() = runTest {
        val src = internal.resolve("src")
        val dst = internal.resolve("dst")
        val spec = OperationSpec("op1", OpKind.MOVE, listOf("$src/d", "$src/x.txt", "$src/y.txt"), dst.toString())
        val failed = listOf(
            ItemFailure("$src/d/sub/p.txt", FsError.AccessDenied),
            ItemFailure("$src/x.txt", FsError.AccessDenied, copiedButSourceKept = true),
            ItemFailure("$src/d/q.txt", FsError.IoError),
            ItemFailure("$src/y.txt", FsError.AccessDenied),
            ItemFailure("$src/d/sub/r.txt", FsError.AccessDenied),
        )
        val runner = runner()

        runner.retryFailed(FinishedOp(spec, OpResult.Done(listOf(dst.resolve("d")), failed)))

        // Each item goes to the folder it was going to, one operation for each; the one already copied
        // (its source could only not be deleted) is left out.
        val queued = runner.state.value.queue
        assertEquals(
            listOf(
                Triple(OpKind.MOVE, listOf("$src/d/sub/p.txt", "$src/d/sub/r.txt"), "$dst/d/sub"),
                Triple(OpKind.MOVE, listOf("$src/d/q.txt"), "$dst/d"),
                Triple(OpKind.MOVE, listOf("$src/y.txt"), dst.toString()),
            ),
            queued.map { Triple(it.kind, it.sources, it.target) },
        )
        assertEquals(3, queued.map { it.id }.toSet().size)
        assertTrue(queued.none { it.id == "op1" })
    }

    @Test
    fun retryFailedOfOtherKindsRunsTheirSourcesAgain() = runTest {
        val bin = internal.resolve(".Recycle.Bin")
        val restore = OperationSpec(
            "op1",
            OpKind.RESTORE,
            listOf("$bin/b1/d", "$bin/b2/x.txt", "$bin/b3/e"),
            options = OpOptions(binItemIds = listOf("b1", "b2", "b3")),
        )
        val delete = OperationSpec("op2", OpKind.DELETE_PERMANENT, listOf("$internal/d", "$internal/e"))
        val runner = runner()

        val restoreFailed = ItemFailure("$bin/b3/e/f.txt", FsError.IoError)
        val deleteFailed = ItemFailure("$internal/d/locked", FsError.AccessDenied)
        runner.retryFailed(FinishedOp(restore, OpResult.Done(emptyList(), listOf(restoreFailed))))
        runner.retryFailed(FinishedOp(delete, OpResult.Done(emptyList(), listOf(deleteFailed))))

        // The top-level source a failure is in, with its bin item.
        val (again, deleteAgain) = runner.state.value.queue
        val restoreAgain = restore.copy(sources = listOf("$bin/b3/e"), options = OpOptions(binItemIds = listOf("b3")))
        assertEquals(restoreAgain.copy(id = again.id), again)
        assertEquals(delete.copy(id = deleteAgain.id, sources = listOf("$internal/d")), deleteAgain)
        assertTrue(again.id != restore.id && deleteAgain.id != delete.id)
    }

    @Test
    fun interruptedOpsRetriedOrDismissed() = runTest {
        val src = internal.resolve("src")
        val dst = Files.createDirectory(internal.resolve("dst"))
        // A move of a, b and c, interrupted when a was done, and b too, but not yet noted.
        write(dst, "a.txt", "a")
        write(dst, "b.txt", "b")
        write(src, "c.txt", "c")
        val move = OperationSpec("old1", OpKind.MOVE, listOf("$src/a.txt", "$src/b.txt", "$src/c.txt"), dst.toString())
        val copying = OperationSpec("old2", OpKind.COPY, listOf("$src/c.txt"), dst.toString())
        // Before the process died.
        OperationJournal(journalDir, NioFsBackend).apply {
            begin(move)
            committed(move.id, 1)
            begin(copying)
        }
        val runner = runner()

        runner.loadInterrupted()
        advanceUntilIdle()

        val (moved, copied) = runner.state.value.interrupted.sortedBy { it.spec.id }
        assertEquals(InterruptedOp(move, 1), moved)
        assertEquals(InterruptedOp(copying, 0), copied)
        runner.dismissInterrupted(copying.id)
        runner.retry(moved)
        assertEquals(emptyList<InterruptedOp>(), runner.state.value.interrupted)
        advanceUntilIdle()

        // Only what was neither noted as done nor gone, under a new id.
        val retried = runner.state.value.finished.single()
        assertNotEquals(move.id, retried.spec.id)
        assertEquals(move.copy(id = retried.spec.id, sources = listOf("$src/c.txt")), retried.spec)
        assertEquals(OpResult.Done(listOf(dst.resolve("c.txt")), emptyList()), retried.result)
        assertEquals(mapOf("a.txt" to "a", "b.txt" to "b", "c.txt" to "c"), tree(dst))
        // Both journals are gone.
        assertEquals(emptyList<String>(), journalDir.list().orEmpty().toList())
    }

    @Test
    fun unexpectedExceptionBecomesFailedNotCrash() = runTest {
        val a = write(internal, "src/a.txt", "a")
        val broken = Files.createDirectory(internal.resolve("broken"))
        val other = Files.createDirectory(internal.resolve("other"))
        // A bug below FileOps, not a failure the file system reports.
        val fs = object : FsBackend by NioFsBackend {
            override fun availableBytes(path: Path): Long =
                if (path.startsWith(broken)) throw IllegalStateException("boom") else NioFsBackend.availableBytes(path)
        }
        val runner = runner(fs)

        runner.submit(copy("op1", listOf(a), broken))
        runner.submit(copy("op2", listOf(a), other))
        advanceUntilIdle()

        // The runner goes on with the next.
        assertEquals(
            mapOf(
                "op1" to OpResult.Failed(FsError.Unknown("boom"), 0, 0),
                "op2" to OpResult.Done(listOf(other.resolve("a.txt")), emptyList()),
            ),
            runner.results(),
        )
    }

    @Test
    fun journalFailuresDoNotStopTheOperation() = runTest {
        val a = write(internal, "src/a.txt", "a")
        val dst = Files.createDirectory(internal.resolve("dst"))
        // Nothing can be written where the journal is.
        val readOnly = FaultyFs().apply { refuse(FaultyFs.Op.CREATE_NEW, "", "Read-only file system") }
        val runner = runner(journalFs = readOnly)

        runner.submit(copy("op1", listOf(a), dst))
        advanceUntilIdle()

        assertEquals(mapOf("op1" to OpResult.Done(listOf(dst.resolve("a.txt")), emptyList())), runner.results())
        assertEquals("a", read(dst.resolve("a.txt")))
        assertTrue(readOnly.calls.isNotEmpty())
        assertEquals(emptyList<String>(), journalDir.list().orEmpty().toList())
    }

    @Test
    fun finishedCappedAtTwenty() = runTest {
        val dst = Files.createDirectory(internal.resolve("dst"))
        val runner = runner()

        for (n in 1..21) runner.submit(copy("op$n", emptyList(), dst))
        advanceUntilIdle()

        // The latest, the oldest dropped.
        assertEquals((2..21).map { "op$it" }, runner.state.value.finished.map { it.spec.id })
        runner.dismiss("op21")
        assertEquals((2..20).map { "op$it" }, runner.state.value.finished.map { it.spec.id })
    }
}
