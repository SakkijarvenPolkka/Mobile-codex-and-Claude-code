/*
 * Audacity Android port — JNI surface of libaudacity-bridge (API.md §2).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

/**
 * Receives engine events (API.md §4). Called on the native engine thread:
 * implementations must not block and must not call back into
 * [NativeBridge.invoke]; copy and hand off. `type` is plain ASCII, `payload`
 * standard UTF-8 JSON.
 */
fun interface EngineListener {
    fun onEvent(type: String, payload: ByteArray)
}

/**
 * The JNI entry points of `libaudacity-bridge.so`, exactly as specified in
 * API.md §2. All strings cross JNI as standard UTF-8 byte arrays.
 *
 * The functions are instance methods of this object (JNI receives the
 * object instance as `jobject thiz`, symbol names
 * `Java_io_github_sakkijarvenpolkka_audacity_engine_NativeBridge_<name>`).
 *
 * Never call an `external` function unless [isLoaded] is true: without the
 * library every call throws [UnsatisfiedLinkError].
 */
object NativeBridge {
    const val LIBRARY_NAME = "audacity-bridge"

    /** Why loading failed (null when loaded or not attempted yet). */
    @Volatile
    var loadError: Throwable? = null
        private set

    /** Loads the library once per process; false when it is missing (e.g. a
     *  build with `-Paudacity.buildNative=false`) or failed to link. */
    val isLoaded: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e
            false
        } catch (e: SecurityException) {
            loadError = e
            false
        } catch (e: LinkageError) {
            loadError = e
            false
        }
    }

    /** Starts the engine thread (once per process); `config` = API.md §2.1.
     *  Completion is the `engine.ready` / `engine.failed` event. Any thread. */
    external fun start(config: ByteArray, listener: EngineListener): Boolean

    /** Runs a command on the engine thread and blocks until it finished;
     *  returns the response envelope (API.md §3.1). Background threads only. */
    external fun invoke(command: ByteArray, args: ByteArray): ByteArray

    /** Answers a blocking `dialog` event. Any thread. */
    external fun replyDialog(dialogId: Int, button: Int)

    /** Requests cancel (`stop=false`) or stop of a running progress. Any thread. */
    external fun cancelProgress(progressId: Int, stop: Boolean)

    /** Lock-free transport snapshot (API.md §6.4), `out.size >= 16`. Any thread. */
    external fun readTransport(out: DoubleArray): Boolean

    /** Lock-free meters (API.md §6.5), `out.size >= 14`; resets the peak
     *  accumulators (one consumer per process). Any thread. */
    external fun readMeters(out: FloatArray): Boolean

    /** API.md §7.2. Background threads only. */
    external fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long

    /** API.md §7.3. Background threads only. */
    external fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long

    /** API.md §7.4 (little-endian binary), null on error. Background threads only. */
    external fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): ByteArray?

    /** API.md §7.5. Background threads only. */
    external fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int, out: ByteArray): Long
}

/** Seam over [NativeBridge] so that [NativeAudacityEngine] can be tested on
 *  the JVM without the native library. */
internal interface BridgeApi {
    val isLoaded: Boolean
    fun start(config: ByteArray, listener: EngineListener): Boolean
    fun invoke(command: ByteArray, args: ByteArray): ByteArray
    fun replyDialog(dialogId: Int, button: Int)
    fun cancelProgress(progressId: Int, stop: Boolean)
    fun readTransport(out: DoubleArray): Boolean
    fun readMeters(out: FloatArray): Boolean
    fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long
    fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long
    fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): ByteArray?
    fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int, out: ByteArray): Long
}

/** [BridgeApi] backed by the real JNI functions. */
internal object NativeBridgeApi : BridgeApi {
    override val isLoaded: Boolean get() = NativeBridge.isLoaded
    override fun start(config: ByteArray, listener: EngineListener) = NativeBridge.start(config, listener)
    override fun invoke(command: ByteArray, args: ByteArray) = NativeBridge.invoke(command, args)
    override fun replyDialog(dialogId: Int, button: Int) = NativeBridge.replyDialog(dialogId, button)
    override fun cancelProgress(progressId: Int, stop: Boolean) = NativeBridge.cancelProgress(progressId, stop)
    override fun readTransport(out: DoubleArray) = NativeBridge.readTransport(out)
    override fun readMeters(out: FloatArray) = NativeBridge.readMeters(out)
    override fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray) =
        NativeBridge.waveColumns(trackId, channel, zoomLevel, firstColumn, count, out)
    override fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray) =
        NativeBridge.envelopeColumns(trackId, zoomLevel, firstColumn, count, out)
    override fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double) =
        NativeBridge.waveSamples(trackId, channel, t0, t1)
    override fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int, out: ByteArray) =
        NativeBridge.spectrogramColumns(trackId, channel, zoomLevel, firstColumn, count, rows, out)
}
