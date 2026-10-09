/*
 * Audacity Android port — the quick effects sheet of the mobile edit bar:
 * one-tap versions of frequent Effect-menu commands (with fixed parameters,
 * like a macro step of Audacity 3.7.9) and shortcuts to the dialogs of the
 * effects people reach for on a phone.
 *
 * Effects are found by the internal symbol of their desktop PluginID
 * (`Effect_Audacity_Audacity_<Symbol>_Built-in Effect: <Symbol>`, API.md
 * §5.4), never by the translated menu name. Parameter keys are the 3.7.9
 * automation keys (lib-builtin-effects AmplifyBase.h, NormalizeBase.h).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.effects

import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.menu.MenuHost
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.pow

/** Internal symbols of the built-in effects the app refers to (3.7.9 `Symbol` definitions). */
object BuiltinEffects {
    const val FADE_IN = "Fade In"
    const val FADE_OUT = "Fade Out"
    const val NORMALIZE = "Normalize"
    const val AMPLIFY = "Amplify"
    const val REVERSE = "Reverse"
    const val INVERT = "Invert"
    const val NOISE_REDUCTION = "Noise Reduction"
    const val CHANGE_TEMPO = "Change Tempo"
    const val CHANGE_PITCH = "Change Pitch"
    /** ChangeSpeedBase::Symbol (menu name "Change Speed and Pitch"). */
    const val CHANGE_SPEED = "Change Speed and Pitch"
    /** EffectEqualizationCurve::Symbol (menu name "Filter Curve EQ"). */
    const val FILTER_CURVE = "Filter Curve"
    const val GRAPHIC_EQ = "Graphic EQ"
    const val COMPRESSOR = "Compressor"
    /** TimeScaleBase::Symbol. */
    const val SLIDING_STRETCH = "Sliding Stretch"
    const val LEGACY_COMPRESSOR = "Legacy Compressor"

    /** BUILTIN_EFFECT_PREFIX (lib-effects Effect.h): the path part of a built-in PluginID. */
    private const val BUILTIN_PATH = "_Built-in Effect: "

    /** The internal symbol of a built-in effect's id; null for other plug-ins (Nyquist, …). */
    fun symbolOf(id: String): String? =
        if (id.startsWith("Effect_")) id.substringAfter(BUILTIN_PATH, "").ifEmpty { null } else null

    /** The listed built-in effect with internal symbol [symbol]. */
    fun find(list: EffectList?, symbol: String): EffectInfo? = list?.effects?.firstOrNull { symbolOf(it.id) == symbol }

    /** Effects whose result is (selection length) / multiplier long: the dialog shows the new length. */
    fun changesLength(id: String): Boolean = symbolOf(id).let { it == CHANGE_TEMPO || it == CHANGE_SPEED }
}

/** What a quick-effect entry does. */
sealed interface QuickAction {
    /** `effects.apply` with these parameters (null: the effect has none). */
    data class Apply(val effectId: String, val params: Map<String, JsonElement>?) : QuickAction

    /** The effect's full dialog. */
    data class Dialog(val effectId: String) : QuickAction

    /** An edit command (`edit.silence`). */
    data class Edit(val command: String) : QuickAction
}

/** One tile of the sheet; [item] runs it through the menu machinery (disabled → why). */
class QuickEffect(
    val key: String,
    val label: UiText,
    val action: QuickAction,
    val required: Long,
    /** Noise Reduction explains its two steps when there is no selection (CommonCommandFlags.cpp). */
    val noiseReduction: Boolean = false,
) {
    /** Kept as a [MenuItem] so a tap on a disabled tile explains what is missing (Disallowed). */
    fun item(): MenuItem =
        MenuItem("quick:$key", label, required, noiseReductionReason = noiseReduction) { h -> run(h) }

    suspend fun run(h: MenuHost) {
        when (val a = action) {
            is QuickAction.Apply -> {
                val r = h.engine.applyEffect(a.effectId, a.params)
                // applied:false = the effect refused (e.g. Amplify that would clip)
                r.message?.takeIf { it.isNotBlank() }?.let { h.message(UiText.Raw(it)) }
            }
            is QuickAction.Dialog -> h.runEffect(a.effectId)
            is QuickAction.Edit -> h.engine.edit(a.command)
        }
    }
}

object QuickEffects {
    /** Normalize to −1 dB peak (the 3.7.9 defaults: remove DC, both channels together). */
    val NORMALIZE_PARAMS: Map<String, JsonElement> = mapOf(
        "PeakLevel" to JsonPrimitive(-1.0),
        "ApplyVolume" to JsonPrimitive(true),
        "RemoveDcOffset" to JsonPrimitive(true),
        "StereoIndependent" to JsonPrimitive(false),
    )

    /** Amplify by [db] decibels; refuses to clip (Amplify's "Allow clipping" off). */
    fun amplifyParams(db: Double): Map<String, JsonElement> = mapOf(
        "Ratio" to JsonPrimitive(10.0.pow(db / 20.0)),
        "AllowClipping" to JsonPrimitive(false),
    )

    private const val NEEDS_AUDIO = CommandFlags.NB or CommandFlags.TS or CommandFlags.WS

    /**
     * One-tap entries, in the sheet's order: Fade In, Fade Out, Normalize
     * −1 dB, Amplify +3 dB / −3 dB, Reverse, Invert, Silence. An effect the
     * engine does not list is left out.
     */
    fun oneTap(list: EffectList?): List<QuickEffect> {
        val out = ArrayList<QuickEffect>()
        fun apply(key: String, symbol: String, params: Map<String, JsonElement>?, label: (EffectInfo) -> UiText = { UiText.Raw(it.name) }) {
            val info = BuiltinEffects.find(list, symbol) ?: return
            out += QuickEffect(key, label(info), QuickAction.Apply(info.id, params), NEEDS_AUDIO)
        }
        apply("fadeIn", BuiltinEffects.FADE_IN, null)
        apply("fadeOut", BuiltinEffects.FADE_OUT, null)
        apply("normalize", BuiltinEffects.NORMALIZE, NORMALIZE_PARAMS) { UiText.Res(R.string.qfx_normalize, listOf(it.name)) }
        apply("amplifyUp", BuiltinEffects.AMPLIFY, amplifyParams(3.0)) { UiText.Res(R.string.qfx_amplify_up, listOf(it.name)) }
        apply("amplifyDown", BuiltinEffects.AMPLIFY, amplifyParams(-3.0)) { UiText.Res(R.string.qfx_amplify_down, listOf(it.name)) }
        apply("reverse", BuiltinEffects.REVERSE, null)
        apply("invert", BuiltinEffects.INVERT, null)
        // Edit ▸ Remove Special ▸ Silence Audio (Ctrl+L)
        out += QuickEffect("silence", UiText.Res(R.string.m_silence), QuickAction.Edit("edit.silence"), NEEDS_AUDIO)
        return out
    }

    /**
     * Entries that open the full dialog: Noise Reduction, Change Tempo,
     * Change Pitch, Change Speed and Pitch (multiplier UI), Equalization
     * (Filter Curve EQ, else Graphic EQ), Compressor (else Legacy Compressor).
     */
    fun dialogs(list: EffectList?): List<QuickEffect> {
        val out = ArrayList<QuickEffect>()
        fun dialog(key: String, vararg symbols: String) {
            val info = symbols.firstNotNullOfOrNull { BuiltinEffects.find(list, it) } ?: return
            out += QuickEffect(
                key, UiText.Raw(MenuSpec.effectLabel(info)), QuickAction.Dialog(info.id), MenuSpec.effectFlags(info),
                noiseReduction = info.special == "noiseReduction",
            )
        }
        dialog("noiseReduction", BuiltinEffects.NOISE_REDUCTION)
        dialog("changeTempo", BuiltinEffects.CHANGE_TEMPO)
        dialog("changePitch", BuiltinEffects.CHANGE_PITCH)
        dialog("changeSpeed", BuiltinEffects.CHANGE_SPEED)
        dialog("equalization", BuiltinEffects.FILTER_CURVE, BuiltinEffects.GRAPHIC_EQ)
        dialog("compressor", BuiltinEffects.COMPRESSOR, BuiltinEffects.LEGACY_COMPRESSOR)
        return out
    }
}
