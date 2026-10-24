// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.platform.VolumeSource
import java.nio.file.Path

/**
 * Saves a session of one tab at [folder] (on one of [volumes]) unless the store has one already: a view
 * model on it starts in that folder instead of at Home, with nothing to go back to.
 */
fun SettingsStore.startAt(folder: Path, volumes: VolumeSource) {
    val codec = VolumeLocationCodec { volumes.volumes.value }
    if (loadSession(codec) == null) saveSession(TabsReducer.initial(Location.Folder(folder)), codec)
}
