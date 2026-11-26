package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The shared data files (trio-browser/shared), parsed once. The app reads them from assets. */
class SharedData(read: (String) -> String) {
    val providers: List<ProviderConfig> = parseProviders(Json.parseToJsonElement(read("providers.json")))
    val searchData: SearchData = SearchData.parse(Json.parseToJsonElement(read("search-engines.json")))
    val privacyRules: PrivacyRules = PrivacyRules.parse(Json.parseToJsonElement(read("privacy-rules.json")))
    val suffixes: SuffixData = SuffixData.parse(Json.parseToJsonElement(read("site-suffixes.json")))
    val prompts: JsonObject = Json.parseToJsonElement(read("prompts.json")).jsonObject
    val agentKeywords: Map<String, String> = agentKeywordMap(providers)

    fun provider(id: String): ProviderConfig = providers.first { it.id == id }
}

// Small JSON helpers -------------------------------------------------------------------------------

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.strOr(d: String): String = str() ?: d
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
internal fun JsonElement?.obj(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())
internal fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonElement?.isNull(): Boolean = this == null || this is JsonNull
internal fun JsonElement?.contentOrNullSafe(): String? = (this as? JsonPrimitive)?.contentOrNull

// Providers ------------------------------------------------------------------------------------------

data class CookieRule(val domain: String, val name: String?, val namePrefix: String?)

data class Prefill(val template: String, val maxEncodedChars: Int, val autoSubmits: Boolean)

data class ProviderConfig(
    val id: String,
    val name: String,
    val vendor: String,
    val monogram: String,
    val accent: String,
    val keywords: List<String>,
    val appUrl: String,
    val loginUrl: String,
    val logoutUrl: String?,
    val appHosts: List<String>,
    val authHosts: List<String>,
    val authPathPrefixes: List<String>,
    val anyOf: List<CookieRule>,
    val allOf: List<CookieRule>,
    val prefill: Prefill?,
    val composerSelectors: List<String>,
)

private fun cookieRules(e: JsonElement?): List<CookieRule> =
    e.arr().map { r -> r.obj().let { CookieRule(it["domain"].strOr(""), it["name"].str(), it["namePrefix"].str()) } }

fun parseProviders(json: JsonElement): List<ProviderConfig> = json.obj()["providers"].arr().map { e ->
    val o = e.obj()
    val cookies = o["sessionCookies"].obj()
    val prefill = o["prefill"].takeUnless { it.isNull() }?.obj()?.let {
        Prefill(it["template"].strOr(""), it["maxEncodedChars"].int() ?: 0, it["autoSubmits"].bool() ?: true)
    }
    ProviderConfig(
        id = o["id"].strOr(""),
        name = o["name"].strOr(""),
        vendor = o["vendor"].strOr(""),
        monogram = o["monogram"].strOr(""),
        accent = o["accent"].strOr("#5a49e8"),
        keywords = o["keywords"].strings(),
        appUrl = o["appUrl"].strOr(""),
        loginUrl = o["loginUrl"].strOr(""),
        logoutUrl = o["logoutUrl"].str(),
        appHosts = o["appHosts"].strings(),
        authHosts = o["authHosts"].strings(),
        authPathPrefixes = o["authPathPrefixes"].strings(),
        anyOf = cookieRules(cookies["anyOf"]),
        allOf = cookieRules(cookies["allOf"]),
        prefill = prefill,
        composerSelectors = o["composerSelectors"].strings(),
    )
}

fun agentKeywordMap(providers: List<ProviderConfig>): Map<String, String> =
    providers.flatMap { p -> p.keywords.map { it.lowercase() to p.id } }.toMap()
