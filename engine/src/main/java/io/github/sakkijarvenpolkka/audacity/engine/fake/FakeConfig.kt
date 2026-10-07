/*
 * Audacity Android port — configuration of the in-memory fake engine.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry

/**
 * @param demoProject start with a demo project (two wave tracks with
 *   procedurally generated, deterministic audio and a label track) and two
 *   saved demo projects in the project list; false = empty "Untitled" project.
 * @param longOperationMillis simulated duration of long-running commands
 *   (import, export, effects, mix/resample, save): they emit `progress`
 *   events and can be cancelled through `cancelProgress`. 0 = instant.
 * @param clock monotonic nanoseconds (like `System.nanoTime()`); drives the
 *   transport simulation. Tests pass a manual clock.
 * @param autoTick run a background ticker (50 ms) while the transport is
 *   active, so playback ends and recording grows without polling. With false
 *   the simulation advances only when the engine is called (readTransport,
 *   readMeters, commands).
 * @param recordPermission initial microphone permission.
 * @param filesDir / [cacheDir] used to build project and staging paths.
 * @param recoverable projects offered for recovery at start; when not empty
 *   no project is open initially (like the native engine, API.md §4.1).
 */
data class FakeConfig(
    val demoProject: Boolean = true,
    val longOperationMillis: Long = 0,
    val clock: () -> Long = System::nanoTime,
    val autoTick: Boolean = true,
    val recordPermission: Boolean = true,
    val filesDir: String = "/data/user/0/io.github.sakkijarvenpolkka.audacity/files",
    val cacheDir: String = "/data/user/0/io.github.sakkijarvenpolkka.audacity/cache",
    val recoverable: List<ProjectFileEntry> = emptyList(),
) {
    val projectsDir: String get() = "$filesDir/Projects"
    val pluginsDir: String get() = "$filesDir/audacity/plug-ins"
}
