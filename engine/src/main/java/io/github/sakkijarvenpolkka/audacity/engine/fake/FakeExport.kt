/*
 * Audacity Android port — export formats/options and import formats of the
 * fake engine, modelled on the 3.7.9 exporters (modules/import-export:
 * ExportPCM, ExportMP3, ExportOGG, ExportOpus, ExportFLAC, ExportWavPack,
 * ExportMP2) and the bridge's Android AAC exporter: option ids, titles,
 * value types/tags, defaults, dependent visibility and sample-rate lists.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import io.github.sakkijarvenpolkka.audacity.engine.EngineException
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOption
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportFormats
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportGroup
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal fun iv(n: Int) = ExportValue("i", JsonPrimitive(n))
internal fun sv(s: String) = ExportValue("s", JsonPrimitive(s))
internal fun bv(b: Boolean) = ExportValue("b", JsonPrimitive(b))

internal val ExportValue.int: Int get() = (v as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
internal val ExportValue.str: String get() = (v as? JsonPrimitive)?.content ?: ""
internal val ExportValue.bool: Boolean get() = (v as? JsonPrimitive)?.booleanOrNull ?: false

private fun sameValue(a: ExportValue, b: ExportValue) = a.t == b.t && (a.v as? JsonPrimitive)?.content == (b.v as? JsonPrimitive)?.content

internal class OptDef(
    val id: Int,
    val title: String,
    /** "enum" | "range" | "bool" | "int" | "double" | "string" */
    val type: String,
    val default: ExportValue,
    val values: List<ExportValue> = emptyList(),
    val names: List<String> = emptyList(),
    val hidden: Boolean = false,
    val readOnly: Boolean = false,
)

/** One options editor (one per format), like an ExportOptionsEditor whose
 *  values persist for the process (the native engine stores them in prefs). */
internal class ExportEditor(
    private val baseFormat: ExportFormat,
    val defs: List<OptDef>,
    private val hiddenFn: (OptDef, Map<Int, ExportValue>) -> Boolean = { d, _ -> d.hidden },
    private val readOnlyFn: (OptDef, Map<Int, ExportValue>) -> Boolean = { d, _ -> d.readOnly },
    private val ratesFn: (Map<Int, ExportValue>) -> List<Int> = { emptyList() },
    private val formatFn: (ExportFormat, Map<Int, ExportValue>) -> ExportFormat = { f, _ -> f },
) {
    val values: MutableMap<Int, ExportValue> = LinkedHashMap<Int, ExportValue>().apply { defs.forEach { put(it.id, it.default) } }

    val format: ExportFormat get() = formatFn(baseFormat, values)
    val sampleRates: List<Int> get() = ratesFn(values)

    fun snapshot() = ExportOptions(
        format = format,
        sampleRates = sampleRates,
        options = defs.map { d ->
            ExportOption(
                id = d.id, title = d.title, type = d.type, readOnly = readOnlyFn(d, values), hidden = hiddenFn(d, values),
                value = values[d.id], values = d.values, names = d.names,
            )
        },
    )

    fun set(id: Int, value: ExportValue) {
        val d = defs.firstOrNull { it.id == id } ?: throw EngineException(ErrorCodes.INVALID_ARGS, "no export option with id $id")
        if (value.t != d.default.t) throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}' expects a value tagged '${d.default.t}', got '${value.t}'")
        if (value.v !is JsonPrimitive) throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}': value must be a scalar")
        if (readOnlyFn(d, values)) throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}' is read-only")
        when (d.type) {
            "enum" -> if (d.values.none { sameValue(it, value) }) throw EngineException(ErrorCodes.INVALID_ARGS, "'${value.str}' is not a choice of '${d.title}'")
            "range" -> {
                val x = (value.v as JsonPrimitive).doubleOrNull ?: throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}' expects a number")
                val lo = (d.values[0].v as JsonPrimitive).doubleOrNull!!
                val hi = (d.values[1].v as JsonPrimitive).doubleOrNull!!
                if (x < lo || x > hi) throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}' must be in [$lo, $hi]")
            }
        }
        val normalized = when (value.t) {
            "i" -> iv((value.v as JsonPrimitive).doubleOrNull?.toInt() ?: throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}' expects an integer"))
            "b" -> bv((value.v as JsonPrimitive).booleanOrNull ?: throw EngineException(ErrorCodes.INVALID_ARGS, "option '${d.title}' expects a boolean"))
            else -> value
        }
        values[id] = normalized
    }

    /** WAV writer encoding for the staged file (fake: WAV data for every format). */
    fun wavEncoding(): Int {
        val key = baseFormat.key
        return when {
            key == WAV_KEY -> when (values[0x10000]?.int) {
                3 -> 24; 4 -> 32; 5 -> 8; 6 -> -32; 7 -> -64; else -> 16
            }
            key == FLAC_KEY && values[0]?.str == "24" -> 24
            key == WAVPACK_KEY && values[1]?.int == 24 -> 24
            key == WAVPACK_KEY && values[1]?.int == 32 -> -32
            else -> 16
        }
    }

    companion object {
        const val WAV_KEY = "WAV (Microsoft)"
        const val OTHER_KEY = "Other uncompressed files"
        const val MP3_KEY = "MP3 Files"
        const val OGG_KEY = "Ogg Vorbis Files"
        const val OPUS_KEY = "Opus Files"
        const val FLAC_KEY = "FLAC Files"
        const val WAVPACK_KEY = "WavPack Files"
        const val MP2_KEY = "MP2 Files"
        const val AAC_KEY = "M4A (AAC) Files"
    }
}

internal object FakeFormats {
    /** DefaultRates of ExportFilePanel.cpp (used when a format allows any rate). */
    val DEFAULT_RATES = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

    val FORMATS: List<ExportFormat> = listOf(
        ExportFormat(ExportEditor.WAV_KEY, "WAV (Microsoft)", listOf("wav"), 255, true),
        ExportFormat(ExportEditor.OTHER_KEY, "Other uncompressed files", listOf("aiff"), 255, true),
        ExportFormat(ExportEditor.MP3_KEY, "MP3 Files", listOf("mp3"), 2, true),
        ExportFormat(ExportEditor.OGG_KEY, "Ogg Vorbis Files", listOf("ogg"), 255, true),
        ExportFormat(ExportEditor.OPUS_KEY, "Opus Files", listOf("opus"), 255, true),
        ExportFormat(ExportEditor.FLAC_KEY, "FLAC Files", listOf("flac"), 8, true),
        ExportFormat(ExportEditor.WAVPACK_KEY, "WavPack Files", listOf("wv"), 255, true),
        ExportFormat(ExportEditor.MP2_KEY, "MP2 Files", listOf("mp2"), 2, true),
        ExportFormat(ExportEditor.AAC_KEY, "M4A (AAC) Files", listOf("m4a"), 2, false),
    )

    private fun kbps(values: List<Int>, divisor: Int = 1) = values.map { "${it / divisor} kbps" }

    // libsndfile subtypes valid for WAV, in sf_encoding order (ExportPCM GetEncodings)
    private val WAV_ENCODINGS = listOf(
        0x0002 to "Signed 16 bit PCM", 0x0003 to "Signed 24 bit PCM", 0x0004 to "Signed 32 bit PCM",
        0x0005 to "Unsigned 8 bit PCM", 0x0006 to "32 bit float", 0x0007 to "64 bit float",
        0x0010 to "U-Law", 0x0011 to "A-Law", 0x0012 to "IMA ADPCM", 0x0013 to "Microsoft ADPCM",
        0x0020 to "GSM 6.10", 0x0030 to "32kbs G721 ADPCM",
    )

    // libsndfile major types offered by "Other uncompressed files" (WAV excluded)
    private class Header(val type: Int, val name: String, val ext: String, val maxChannels: Int, val encodings: List<Pair<Int, String>>)
    private val PCM = listOf(0x0002 to "Signed 16 bit PCM", 0x0003 to "Signed 24 bit PCM", 0x0004 to "Signed 32 bit PCM", 0x0006 to "32 bit float")
    private val HEADERS = listOf(
        Header(0x020000, "AIFF (Apple/SGI)", "aiff", 255, listOf(0x0001 to "Signed 8 bit PCM") + PCM + listOf(0x0007 to "64 bit float", 0x0010 to "U-Law", 0x0011 to "A-Law")),
        Header(0x030000, "AU (Sun/NeXT)", "au", 255, listOf(0x0001 to "Signed 8 bit PCM") + PCM + listOf(0x0010 to "U-Law", 0x0011 to "A-Law")),
        Header(0x040000, "RAW (header-less)", "raw", 255, listOf(0x0001 to "Signed 8 bit PCM") + PCM + listOf(0x0005 to "Unsigned 8 bit PCM")),
        Header(0x070000, "WAV (NIST Sphere)", "wav", 255, listOf(0x0001 to "Signed 8 bit PCM") + PCM.take(3) + listOf(0x0010 to "U-Law")),
        Header(0x080000, "VOC (Creative Labs)", "voc", 2, listOf(0x0005 to "Unsigned 8 bit PCM", 0x0002 to "Signed 16 bit PCM")),
        Header(0x0A0000, "SF (Berkeley/IRCAM/CARL)", "sf", 255, PCM.take(1) + listOf(0x0004 to "Signed 32 bit PCM", 0x0006 to "32 bit float")),
        Header(0x0B0000, "W64 (SoundFoundry WAVE 64)", "w64", 255, PCM + listOf(0x0005 to "Unsigned 8 bit PCM", 0x0007 to "64 bit float")),
        Header(0x0D0000, "MAT5 (GNU Octave 2.1 / Matlab 5.0)", "mat", 255, PCM.take(1) + listOf(0x0004 to "Signed 32 bit PCM", 0x0006 to "32 bit float")),
        Header(0x130000, "WAVEX (Microsoft)", "wav", 255, PCM + listOf(0x0005 to "Unsigned 8 bit PCM", 0x0007 to "64 bit float")),
        Header(0x180000, "CAF (Apple Core Audio File)", "caf", 255, listOf(0x0001 to "Signed 8 bit PCM") + PCM + listOf(0x0007 to "64 bit float")),
        Header(0x220000, "RF64 (RIFF 64)", "rf64", 255, PCM + listOf(0x0005 to "Unsigned 8 bit PCM", 0x0007 to "64 bit float")),
    )

    private val MP3_FIX_RATES = listOf(320, 256, 224, 192, 160, 144, 128, 112, 96, 80, 64, 56, 48, 40, 32, 24, 16, 8)
    private val MP3_RATES = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
    private const val OPUS_AUTO = -1000
    private const val OPUS_BITRATE_MAX = -1

    fun newEditor(key: String): ExportEditor? {
        val format = FORMATS.firstOrNull { it.key == key } ?: return null
        return when (key) {
            ExportEditor.WAV_KEY -> ExportEditor(format, listOf(
                OptDef(0x10000, "Encoding", "enum", iv(2), WAV_ENCODINGS.map { iv(it.first) }, WAV_ENCODINGS.map { it.second }),
            ))
            ExportEditor.OTHER_KEY -> ExportEditor(
                format,
                listOf(OptDef(0, "Header", "enum", iv(HEADERS[0].type), HEADERS.map { iv(it.type) }, HEADERS.map { it.name })) +
                    HEADERS.map { h -> OptDef(h.type, "Encoding", "enum", iv(h.encodings[if (h.encodings.size > 1) 1 else 0].first),
                        h.encodings.map { iv(it.first) }, h.encodings.map { it.second }) },
                hiddenFn = { d, v -> d.id != 0 && d.id != v[0]?.int },
                formatFn = { f, v ->
                    val h = HEADERS.firstOrNull { it.type == v[0]?.int } ?: HEADERS[0]
                    f.copy(extensions = listOf(h.ext), maxChannels = h.maxChannels)
                },
            )
            ExportEditor.MP3_KEY -> ExportEditor(
                format,
                listOf(
                    OptDef(0, "Bit Rate Mode", "enum", sv("SET"), listOf("SET", "VBR", "ABR", "CBR").map { sv(it) }, listOf("Preset", "Variable", "Average", "Constant")),
                    OptDef(1, "Quality", "enum", iv(2), (0..3).map { iv(it) },
                        listOf("Excessive, 320 kbps", "Extreme, 220-260 kbps", "Standard, 170-210 kbps", "Medium, 145-185 kbps")),
                    OptDef(2, "Quality", "enum", iv(2), (0..9).map { iv(it) },
                        listOf("220-260 kbps (Best Quality)", "200-250 kbps", "170-210 kbps", "155-195 kbps", "145-185 kbps", "110-150 kbps",
                            "95-135 kbps", "80-120 kbps", "65-105 kbps", "45-85 kbps (Smaller files)"), hidden = true),
                    OptDef(3, "Quality", "enum", iv(192), MP3_FIX_RATES.map { iv(it) }, kbps(MP3_FIX_RATES), hidden = true),
                    OptDef(4, "Quality", "enum", iv(192), MP3_FIX_RATES.map { iv(it) }, kbps(MP3_FIX_RATES), hidden = true),
                ),
                hiddenFn = { d, v -> d.id != 0 && d.id != when (v[0]?.str) { "VBR" -> 2; "ABR" -> 3; "CBR" -> 4; else -> 1 } },
                ratesFn = { v ->
                    val mode = v[0]?.str
                    val bitrate = when (mode) { "ABR" -> v[3]?.int; "CBR" -> v[4]?.int; else -> null }
                    var low = 8000
                    var high = 48000
                    if (bitrate != null) {
                        if (bitrate > 160) low = 32000 else if (bitrate < 32 || bitrate == 144) high = 24000
                    }
                    MP3_RATES.filter { it in low..high }
                },
            )
            ExportEditor.OGG_KEY -> ExportEditor(format, listOf(OptDef(0, "Quality", "range", iv(5), listOf(iv(0), iv(10)))))
            ExportEditor.OPUS_KEY -> {
                val bitrates = listOf(6000, 8000, 16000, 24000, 32000, 40000, 48000, 64000, 80000, 96000, 128000, 160000, 192000, 256000)
                ExportEditor(
                    format,
                    listOf(
                        OptDef(0, "Bit Rate", "enum", iv(OPUS_AUTO), (bitrates + listOf(OPUS_AUTO, OPUS_BITRATE_MAX)).map { iv(it) },
                            kbps(bitrates, 1000) + listOf("Auto", "Maximum")),
                        OptDef(1, "Quality", "range", iv(10), listOf(iv(0), iv(10))),
                        OptDef(2, "Frame Duration", "enum", iv(200), listOf(25, 50, 100, 200, 400, 600).map { iv(it) },
                            listOf("2.5 ms", "5 ms", "10 ms", "20 ms", "40 ms", "60 ms")),
                        OptDef(3, "VBR Mode", "enum", iv(1), listOf(iv(0), iv(1), iv(2)), listOf("Off", "On", "Constrained")),
                        OptDef(4, "Optimize for", "enum", iv(2049), listOf(iv(2048), iv(2049), iv(2051)), listOf("Speech", "Audio", "Low Delay")),
                        OptDef(5, "Cutoff", "enum", iv(OPUS_AUTO), listOf(OPUS_AUTO, 1101, 1102, 1103, 1104, 1105).map { iv(it) },
                            listOf("Auto", "Narrowband", "Mediumband", "Wideband", "Super Wideband", "Fullband")),
                    ),
                    ratesFn = { listOf(8000, 12000, 16000, 24000, 48000) },
                )
            }
            ExportEditor.FLAC_KEY -> ExportEditor(format, listOf(
                OptDef(0, "Bit Depth", "enum", sv("16"), listOf(sv("16"), sv("24")), listOf("16 bit", "24 bit")),
                OptDef(1, "Level", "enum", sv("5"), (0..8).map { sv(it.toString()) },
                    listOf("0 (fastest)", "1", "2", "3", "4", "5", "6", "7", "8 (best)")),
            ))
            ExportEditor.WAVPACK_KEY -> ExportEditor(
                format,
                listOf(
                    OptDef(0, "Quality", "enum", iv(1), (0..3).map { iv(it) },
                        listOf("Low Quality (Fast)", "Normal Quality", "High Quality (Slow)", "Very High Quality (Slowest)")),
                    OptDef(1, "Bit Depth", "enum", iv(16), listOf(iv(16), iv(24), iv(32)), listOf("16 bit", "24 bit", "32 bit float")),
                    OptDef(2, "Hybrid Mode", "bool", bv(false)),
                    // Android: a second .wvc file cannot be delivered through one SAF document (import notes §2.3)
                    OptDef(3, "Create Correction(.wvc) File", "bool", bv(false), readOnly = true),
                    OptDef(4, "Bit Rate", "enum", iv(40), listOf(22, 25, 30, 35, 40, 45, 50, 60, 70, 80).map { iv(it) },
                        listOf(22, 25, 30, 35, 40, 45, 50, 60, 70, 80).map { "%.1f bps".format(java.util.Locale.ROOT, it / 10.0) }),
                ),
                readOnlyFn = { d, v -> d.id == 3 || (d.id == 4 && v[2]?.bool != true) },
            )
            ExportEditor.MP2_KEY -> ExportEditor(
                format,
                listOf(
                    OptDef(0, "Version", "enum", iv(1), listOf(iv(0), iv(1)), listOf("MPEG2", "MPEG1")),
                    OptDef(1, "Bit Rate", "enum", iv(192), listOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384).map { iv(it) },
                        kbps(listOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384))),
                    OptDef(2, "Bit Rate", "enum", iv(96), listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160).map { iv(it) },
                        kbps(listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)), hidden = true),
                ),
                hiddenFn = { d, v -> if (v[0]?.int == 0) d.id == 1 else d.id == 2 },
                ratesFn = { v -> if (v[0]?.int == 0) listOf(16000, 22050, 24000) else listOf(32000, 44100, 48000) },
            )
            ExportEditor.AAC_KEY -> {
                val lc = listOf(32000, 48000, 64000, 96000, 128000, 160000, 192000, 256000, 320000)
                val he = listOf(24000, 32000, 48000, 64000)
                val hev2 = listOf(16000, 24000, 32000)
                ExportEditor(
                    format,
                    listOf(
                        OptDef(1, "Profile", "enum", iv(2), listOf(iv(2), iv(5), iv(29)), listOf("AAC-LC", "HE-AAC", "HE-AAC v2")),
                        OptDef(0, "Bit Rate", "enum", iv(160000), lc.map { iv(it) }, kbps(lc, 1000)),
                        OptDef(2, "Bit Rate", "enum", iv(48000), he.map { iv(it) }, kbps(he, 1000), hidden = true),
                        OptDef(3, "Bit Rate", "enum", iv(32000), hev2.map { iv(it) }, kbps(hev2, 1000), hidden = true),
                    ),
                    hiddenFn = { d, v -> d.id != 1 && d.id != when (v[1]?.int) { 5 -> 2; 29 -> 3; else -> 0 } },
                    ratesFn = { v ->
                        if (v[1]?.int == 2) listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
                        else listOf(16000, 22050, 24000, 32000, 44100, 48000)
                    },
                )
            }
            else -> null
        }
    }

    val IMPORT: ImportFormats = run {
        val groups = listOf(
            ImportGroup("WAV, AIFF, and other uncompressed types",
                listOf("wav", "wave", "aif", "aiff", "aifc", "au", "snd", "caf", "w64", "rf64", "voc", "sf", "ircam", "nist", "sph", "mat", "pvf", "xi", "htk", "sds", "avr", "sd2")),
            ImportGroup("Ogg Vorbis files", listOf("ogg", "oga")),
            ImportGroup("FLAC files", listOf("flac", "flc")),
            ImportGroup("MP3 files", listOf("mp3", "mp2", "mp1", "mpg", "mpeg", "mpa")),
            ImportGroup("Opus files", listOf("opus")),
            ImportGroup("WavPack files", listOf("wv")),
            ImportGroup("AAC, M4A, AMR and other formats (Android)",
                listOf("m4a", "aac", "mp4", "3gp", "3gpp", "amr", "awb", "mka", "mkv", "webm", "ts", "adts", "m4b")),
        )
        ImportFormats(groups, groups.flatMap { it.extensions }.distinct())
    }
}
