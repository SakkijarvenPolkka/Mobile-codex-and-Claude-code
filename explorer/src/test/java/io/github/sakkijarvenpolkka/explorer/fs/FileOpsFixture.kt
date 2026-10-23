// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/** The Korean labels, as the app's resources give them. */
val KO_LABELS = NameLabels(
    newFolder = "새 폴더",
    newTextDocument = "새 텍스트 문서",
    copySuffix = " - 복사본",
)

/** The id of the operations the tests run. */
const val OP_ID = "op1"

/** When the tests delete things to the Recycle Bin. */
const val DELETED_AT = 1_700_000_000_000L

/** A journal in memory: the temporary files still recorded. */
class MemoryJournal : TempJournal {
    val recorded = LinkedHashSet<Path>()

    override fun add(temp: Path) {
        recorded.add(temp)
    }

    override fun remove(temp: Path) {
        recorded.remove(temp)
    }
}

/** A [VolumeLookup] over [roots]: the one with the longest root a path starts with. */
fun volumesOf(vararg roots: VolumeRoot): VolumeLookup = VolumeLookup { path ->
    roots.filter { path.startsWith(it.root) }.maxByOrNull { it.root.nameCount }
}

/** A resolver for operations that must not ask: it fails the test if it is. */
val NeverAsked = ConflictResolver { throw AssertionError("asked about $it") }

/** A resolver that answers [answer] for each conflict and keeps what it was asked in [asked]. */
class Answering(private val answer: (Conflict) -> Resolution) : ConflictResolver {
    val asked = ArrayList<Conflict>()

    override suspend fun resolve(c: Conflict): Resolution {
        asked += c
        return answer(c)
    }
}

fun spec(kind: OpKind, sources: List<Path>, target: Path) =
    OperationSpec(OP_ID, kind, sources.map { it.toString() }, target.toString())

/** Restoring [items] from the Recycle Bin. */
fun restore(vararg items: BinItem) = OperationSpec(
    OP_ID,
    OpKind.RESTORE,
    items.map { it.payload.toString() },
    options = OpOptions(binItemIds = items.map { it.id }),
)

/** The Recycle Bin folder of the volume at [root]. */
fun binOf(root: Path): Path = root.resolve(HiddenNames.RECYCLE_BIN)

/** What [tree] finds under [root], the Recycle Bin left out. */
fun outsideBin(root: Path): Map<String, String> =
    tree(root).filterKeys { !it.startsWith(HiddenNames.RECYCLE_BIN + "/") }

/** Runs [spec] to its end. */
fun FileOps.runToEnd(
    spec: OperationSpec,
    resolver: ConflictResolver = NeverAsked,
    onProgress: (OpProgress) -> Unit = {},
): OpResult = runBlocking { run(spec, resolver, onProgress) }

/**
 * Everything under [root], links not followed: a file's relative path to its content, a folder's
 * (`name/`) to "", a link's to `-> target`, anything else's to `(special)`.
 */
fun tree(root: Path): Map<String, String> {
    val found = TreeMap<String, String>()
    fun visit(dir: Path, prefix: String) {
        for (child in NioFsBackend.list(dir)) {
            val name = prefix + child.fileName
            val attributes = NioFsBackend.attributes(child)
            when {
                attributes.isSymbolicLink -> found[name] = "-> " + Files.readSymbolicLink(child)
                attributes.isDirectory -> {
                    found["$name/"] = ""
                    visit(child, "$name/")
                }
                attributes.isRegularFile -> found[name] = String(Files.readAllBytes(child))
                else -> found[name] = "(special)"
            }
        }
    }
    visit(root, "")
    return found
}

/** Writes [content] to [relative] under [root], creating the folders on the way. */
fun write(root: Path, relative: String, content: String = relative): Path {
    val path = root.resolve(relative)
    Files.createDirectories(path.parent)
    return Files.write(path, content.toByteArray())
}

/** The temporary files (`*.xpart`) anywhere under [root]. */
fun partsUnder(root: Path): List<Path> = tree(root).keys
    .filter { it.endsWith(HiddenNames.PART_SUFFIX) }
    .map { root.resolve(it) }
