/*
 * Audacity Android port — wire-format helpers (API.md §3.1, §7.4).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.Envelope
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small result objects of commands that have no model class. */
@Serializable
internal data class IdResult(val id: Long)

@Serializable
internal data class LabelRef(val trackId: Long, val index: Int)

@Serializable
internal data class LabelIndex(val index: Int)

@Serializable
internal data class TrackIdResult(val trackId: Long)

/** Encoding/decoding of the command protocol, shared by the native engine and tests. */
object EngineProtocol {
    val EMPTY_ARGS: JsonObject = JsonObject(emptyMap())

    /**
     * Builds an argument object. Values may be null (the key is omitted),
     * String, Number, Boolean, JsonElement, a List/Array of those or a
     * Map<String, *>. Non-finite numbers are rejected (they are not JSON).
     */
    fun args(vararg pairs: Pair<String, Any?>): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        for ((k, v) in pairs) if (v != null) map[k] = toJson(v)
        return JsonObject(map)
    }

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> {
            require(value.isFinite()) { "non-finite number in arguments: $value" }
            JsonPrimitive(value)
        }
        is Float -> {
            require(value.isFinite()) { "non-finite number in arguments: $value" }
            JsonPrimitive(value.toDouble())
        }
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        else -> throw IllegalArgumentException("unsupported argument type ${value::class.java.name}")
    }

    fun encodeArgs(args: JsonObject): ByteArray = args.toString().encodeToByteArray()

    /** Parses a response envelope; malformed input becomes an `INTERNAL` error envelope. */
    fun decodeEnvelope(bytes: ByteArray): Envelope {
        val text = bytes.decodeToString()
        return try {
            EngineJson.json.decodeFromString(Envelope.serializer(), text)
        } catch (e: SerializationException) {
            malformed("malformed response envelope: ${e.message}")
        } catch (e: IllegalArgumentException) {
            malformed("malformed response envelope: ${e.message}")
        }
    }

    private fun malformed(message: String) = Envelope(
        ok = false,
        error = io.github.sakkijarvenpolkka.audacity.engine.model.EngineErrorInfo(ErrorCodes.INTERNAL, message),
    )

    /** The `result` of a successful envelope (`{}` when absent).
     *  @throws EngineException for `ok:false`. */
    fun unwrap(envelope: Envelope): JsonElement {
        if (!envelope.ok) {
            val error = envelope.error
            throw EngineException(error?.code ?: ErrorCodes.INTERNAL, error?.message ?: "engine error without details")
        }
        return envelope.result ?: EMPTY_ARGS
    }

    /** Decodes a command result into [serializer]'s type; schema mismatches
     *  become `INTERNAL` engine exceptions. */
    fun <T> decodeResult(command: String, result: JsonElement, serializer: KSerializer<T>): T = try {
        EngineJson.json.decodeFromJsonElement(serializer, result)
    } catch (e: SerializationException) {
        throw EngineException(ErrorCodes.INTERNAL, "unexpected result of $command: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw EngineException(ErrorCodes.INTERNAL, "unexpected result of $command: ${e.message}")
    }

    inline fun <reified T> decodeResult(command: String, result: JsonElement): T =
        decodeResult(command, result, serializer<T>())

    /**
     * Decodes the binary layout of API.md §7.4:
     * `int32 runCount; runCount × { int32 clipIndex; float64 firstSampleTime;
     * float64 samplePeriod; int32 n; float32 values[n]; float32 envelope[n] }`,
     * little-endian. Returns null for malformed input.
     */
    fun decodeWaveSamples(bytes: ByteArray): List<SampleRun>? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val runCount = buf.getInt()
            if (runCount < 0) return null
            val runs = ArrayList<SampleRun>(runCount.coerceAtMost(1024))
            repeat(runCount) {
                val clipIndex = buf.getInt()
                val first = buf.getDouble()
                val period = buf.getDouble()
                val n = buf.getInt()
                if (n < 0 || n.toLong() * 8 > buf.remaining()) return null
                val values = FloatArray(n)
                buf.asFloatBuffer().get(values)
                buf.position(buf.position() + 4 * n)
                val envelope = FloatArray(n)
                buf.asFloatBuffer().get(envelope)
                buf.position(buf.position() + 4 * n)
                runs += SampleRun(clipIndex, first, period, values, envelope)
            }
            runs
        } catch (e: BufferUnderflowException) {
            null
        }
    }

    /** Inverse of [decodeWaveSamples] (used by tests and the fake engine). */
    fun encodeWaveSamples(runs: List<SampleRun>): ByteArray {
        val size = 4 + runs.sumOf { 4 + 8 + 8 + 4 + 8 * it.values.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(runs.size)
        for (r in runs) {
            buf.putInt(r.clipIndex)
            buf.putDouble(r.firstSampleTime)
            buf.putDouble(r.samplePeriod)
            buf.putInt(r.values.size)
            for (v in r.values) buf.putFloat(v)
            for (i in r.values.indices) buf.putFloat(r.envelope.getOrElse(i) { 1f })
        }
        return buf.array()
    }
}
