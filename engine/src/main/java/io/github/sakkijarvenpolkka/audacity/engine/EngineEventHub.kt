/*
 * Audacity Android port — engine events (API.md §4) → Kotlin flows.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.DialogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineFailed
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineReady
import io.github.sakkijarvenpolkka.audacity.engine.model.LogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.ProgressEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Event type names of API.md §4. */
object EngineEvents {
    const val READY = "engine.ready"
    const val FAILED = "engine.failed"
    const val SNAPSHOT = "snapshot"
    const val PROGRESS = "progress"
    const val DIALOG = "dialog"
    const val TRANSPORT = "transport"
    const val LOG = "log"
}

/**
 * Holds the observable engine state and turns raw events into it. Used by
 * [NativeAudacityEngine] (from its single event thread, so events are applied
 * in arrival order) and by [FakeAudacityEngine] (through the typed helpers).
 * Every method is non-blocking and thread-safe.
 */
class EngineEventHub(initialStatus: EngineStatus = EngineStatus.Starting) {
    private val _status = MutableStateFlow(initialStatus)
    private val _snapshot = MutableStateFlow(Snapshot.EMPTY)
    private val _progress = MutableStateFlow<Map<Int, ProgressEvent>>(emptyMap())
    private val _pendingDialogs = MutableStateFlow<List<DialogEvent>>(emptyList())
    private val _transportState = MutableStateFlow(TransportEvent("stopped"))
    private val _ready = MutableStateFlow<EngineReady?>(null)
    private val _dialogs = MutableSharedFlow<DialogEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _transportEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val status: StateFlow<EngineStatus> = _status.asStateFlow()
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    val progress: StateFlow<Map<Int, ProgressEvent>> = _progress.asStateFlow()
    val pendingDialogs: StateFlow<List<DialogEvent>> = _pendingDialogs.asStateFlow()
    val transportState: StateFlow<TransportEvent> = _transportState.asStateFlow()
    /** Payload of `engine.ready` (self-checks, recoverable count), null before. */
    val readyInfo: StateFlow<EngineReady?> = _ready.asStateFlow()
    val dialogs: SharedFlow<DialogEvent> = _dialogs.asSharedFlow()
    val transportEvents: SharedFlow<TransportEvent> = _transportEvents.asSharedFlow()
    val logs: SharedFlow<LogEvent> = _logs.asSharedFlow()

    /** Decodes and applies one raw event (`payload` = UTF-8 JSON). Malformed
     *  payloads are reported on [logs] and otherwise ignored. */
    fun dispatch(type: String, payload: ByteArray) {
        val text = payload.decodeToString()
        try {
            when (type) {
                EngineEvents.SNAPSHOT -> setSnapshot(EngineJson.json.decodeFromString(Snapshot.serializer(), text))
                EngineEvents.PROGRESS -> applyProgress(EngineJson.json.parseToJsonElement(text).jsonObject)
                EngineEvents.DIALOG -> showDialog(EngineJson.json.decodeFromString(DialogEvent.serializer(), text))
                EngineEvents.TRANSPORT -> setTransport(EngineJson.json.decodeFromString(TransportEvent.serializer(), text))
                EngineEvents.LOG -> log(EngineJson.json.decodeFromString(LogEvent.serializer(), text))
                EngineEvents.READY -> setReady(EngineJson.json.decodeFromString(EngineReady.serializer(), text))
                EngineEvents.FAILED -> setStatus(EngineStatus.Failed(EngineJson.json.decodeFromString(EngineFailed.serializer(), text).message))
                else -> log(LogEvent("debug", "unknown engine event '$type'"))
            }
        } catch (e: Exception) {
            log(LogEvent("error", "malformed '$type' event: ${e.message}"))
        }
    }

    fun setStatus(status: EngineStatus) {
        _status.value = status
    }

    fun setReady(ready: EngineReady) {
        _ready.value = ready
        for (check in ready.selfChecks) if (!check.ok) {
            log(LogEvent("warning", "self-check ${check.name} failed${if (check.message.isNotEmpty()) ": ${check.message}" else ""}"))
        }
        _status.value = EngineStatus.Ready(ready.recoverable)
    }

    fun setSnapshot(snapshot: Snapshot) {
        _snapshot.value = snapshot
    }

    /** Applies a `progress` event; `update` events only carry the changed
     *  fields, so they are merged into the `begin` state. */
    fun applyProgress(obj: JsonObject) {
        val id = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
        when ((obj["phase"] as? JsonPrimitive)?.content) {
            "end" -> endProgress(id)
            "begin" -> beginProgress(EngineJson.json.decodeFromJsonElement(ProgressEvent.serializer(), obj))
            else -> _progress.update { map ->
                val old = map[id] ?: ProgressEvent(id = id, phase = "update")
                val merged = old.copy(
                    phase = "update",
                    title = obj.string("title") ?: old.title,
                    message = obj.string("message") ?: old.message,
                    fraction = (obj["fraction"] as? JsonPrimitive)?.doubleOrNull ?: old.fraction,
                    cancellable = (obj["cancellable"] as? JsonPrimitive)?.booleanOrNull ?: old.cancellable,
                    stoppable = (obj["stoppable"] as? JsonPrimitive)?.booleanOrNull ?: old.stoppable,
                )
                map + (id to merged)
            }
        }
    }

    fun beginProgress(event: ProgressEvent) {
        _progress.update { it + (event.id to event.copy(phase = "begin")) }
    }

    fun updateProgress(id: Int, fraction: Double, message: String? = null) {
        _progress.update { map ->
            val old = map[id] ?: return@update map
            map + (id to old.copy(phase = "update", fraction = fraction, message = message ?: old.message))
        }
    }

    fun endProgress(id: Int) {
        _progress.update { it - id }
    }

    fun showDialog(dialog: DialogEvent) {
        _pendingDialogs.update { list -> list.filter { it.id != dialog.id } + dialog }
        _dialogs.tryEmit(dialog)
    }

    /** Removes a dialog from [pendingDialogs]; returns it (null if unknown). */
    fun resolveDialog(id: Int): DialogEvent? {
        var found: DialogEvent? = null
        _pendingDialogs.update { list ->
            found = list.firstOrNull { it.id == id }
            if (found == null) list else list.filter { it.id != id }
        }
        return found
    }

    fun setTransport(event: TransportEvent) {
        _transportState.value = event
        _transportEvents.tryEmit(event)
    }

    fun log(event: LogEvent) {
        _logs.tryEmit(event)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
