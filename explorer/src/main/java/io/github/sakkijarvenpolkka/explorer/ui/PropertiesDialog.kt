/*
 * Files — the properties dialog of the selection or the current folder, and the count of what
 * its folders contain.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.Formats
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.nio.file.Path
import java.time.ZoneId

/** What a selection holds as counted so far: files, folders and the files' size in bytes. */
data class ContentCount(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0)

/**
 * Counts [entries] and everything under their folders, emitting the total so far at once and
 * again after each folder is read. A selected file counts itself; a selected folder counts
 * itself only among several, since the properties of one folder show what it contains. Links
 * are neither followed nor counted, the app's internal names are left out, and so is a folder
 * that cannot be read. The walk stops when its collector is cancelled.
 */
fun countContents(fs: FsBackend, entries: List<FileEntry>): Flow<ContentCount> = flow {
    var files = 0
    var folders = 0
    var bytes = 0L
    val pending = ArrayDeque<Path>()
    for (entry in entries) {
        when (entry.kind) {
            EntryKind.FILE -> {
                files++
                bytes += entry.size
            }
            EntryKind.DIR -> {
                if (entries.size > 1) folders++
                pending.addLast(entry.path)
            }
            EntryKind.SYMLINK, EntryKind.OTHER -> Unit
        }
    }
    emit(ContentCount(files, folders, bytes))
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val children = try {
            fs.list(pending.removeLast())
        } catch (e: IOException) {
            continue
        }
        for (child in children) {
            if (HiddenNames.isInternal(child.fileName.toString())) continue
            val attributes = try {
                fs.attributes(child)
            } catch (e: IOException) {
                continue
            }
            when {
                attributes.isSymbolicLink -> Unit
                attributes.isDirectory -> {
                    folders++
                    pending.addLast(child)
                }
                attributes.isRegularFile -> {
                    files++
                    bytes += attributes.size()
                }
            }
        }
        emit(ContentCount(files, folders, bytes))
    }
}

/**
 * The properties of [entries]. One item shows its icon and name, type, location, size, what a
 * folder contains, date modified and whether it is hidden; several show the type and location
 * they share, their total size and contents and whether all are hidden. Sizes and contents
 * follow [contents] (see [countContents]) while it counts.
 */
@Composable
fun PropertiesDialog(entries: List<FileEntry>, contents: Flow<ContentCount>, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val korean = locale.language == "ko"
    val counted by contents.collectAsState(initial = ContentCount())
    val single = entries.singleOrNull()
    val names = entries.take(2).joinToString(", ") { it.name } + if (entries.size > 2) ", …" else ""
    ContentDialog(
        title = res.getString(R.string.prop_title, DisplayName.inSentence(names)),
        onDismiss = onDismiss,
        primary = null,
        close = stringResource(R.string.close) to onDismiss,
    ) {
        Column(Modifier.testTag(Tags.PROPERTIES), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (single != null) {
                val gap = Arrangement.spacedBy(12.dp)
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                    EntryIcon(single, Modifier.size(32.dp))
                    OneLine(DisplayName.of(single.name), Modifier.weight(1f))
                }
                Separator()
            }
            entries.map { typeLabel(it, res) }.distinct().singleOrNull()?.let { Property(R.string.prop_type, it) }
            entries.map { it.path.parent }.distinct().singleOrNull()?.let {
                Property(R.string.prop_location, DisplayName.of(it.toString()))
            }
            val bytes = if (single?.kind == EntryKind.FILE) single.size else counted.bytes
            Property(R.string.prop_size, Formats.size(bytes, korean))
            if (single == null || single.kind == EntryKind.DIR) {
                val contains = res.getString(R.string.prop_contains, counted.files, counted.folders)
                Property(R.string.prop_contains_label, contains)
            }
            Separator()
            if (single != null) {
                Property(R.string.prop_modified, Formats.date(single.modified, locale, ZoneId.systemDefault()))
            }
            HiddenAttribute(entries.all { it.hidden })
        }
    }
}

/** A label and its value on one line; a long value is cut in the middle. */
@Composable
private fun Property(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            stringResource(label),
            modifier = Modifier.width(LABEL_WIDTH),
            style = FluentTheme.type.body.copy(color = FluentTheme.colors.textSecondary),
        )
        OneLine(value, Modifier.weight(1f))
    }
}

@Composable
private fun OneLine(text: String, modifier: Modifier) {
    BasicText(
        text,
        modifier = modifier,
        style = FluentTheme.type.body.copy(color = FluentTheme.colors.text),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

@Composable
private fun Separator() {
    Spacer(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(FluentTheme.colors.stroke))
}

/** The hidden attribute as a check box that shows it and cannot change it. */
@Composable
private fun HiddenAttribute(hidden: Boolean) {
    Row(
        Modifier
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = hidden,
                interactionSource = null,
                indication = null,
                enabled = false,
                role = Role.Checkbox,
                onValueChange = {},
            )
            .testTag(Tags.PROP_HIDDEN),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(hidden)
        val style = FluentTheme.type.body.copy(color = FluentTheme.colors.text)
        BasicText(stringResource(R.string.prop_hidden), style = style)
    }
}

private val LABEL_WIDTH = 104.dp
