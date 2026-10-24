/*
 * Files — how full a volume is, for the drives on Home.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.os.StatFs
import java.nio.file.Path

/**
 * The size of the volume at [root] and the bytes free on it for apps, as `StatFs` reads them: (total,
 * free). Null when they cannot be read, or the volume reports no size.
 */
fun storageUsage(root: Path): Pair<Long, Long>? = try {
    val stat = StatFs(root.toString())
    stat.totalBytes.takeIf { it > 0 }?.let { it to stat.availableBytes }
} catch (e: IllegalArgumentException) {
    // StatFs's statvfs failed.
    null
}
