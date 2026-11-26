package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.JsonObject

/** Mirrors desktop/src/core/prompts.js (output must match it exactly). */
object Prompts {
    data class Input(val question: String = "", val query: String = "", val title: String = "", val url: String = "", val content: String = "", val historyKind: String? = null)
    data class Result(val text: String, val truncated: Boolean)

    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

    private fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: "" }

    fun cleanContent(text: String): String = text
        .replace(Regex("\r\n?"), "\n")
        .replace(Regex("[ \t ]+$", RegexOption.MULTILINE), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .replace(Regex("<{3,}"), "<<")
        .replace(Regex(">{3,}"), ">>")
        .trim { it.isWhitespace() || it == ' ' || it == '﻿' }

    private fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = maxOf(0, max)
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }

    fun isMostlyKorean(text: String): Boolean {
        val hangul = text.count { it in 'ㄱ'..'ㆎ' || it in '가'..'힣' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return hangul > latin
    }

    fun build(prompts: JsonObject, actionId: String, input: Input, locale: String, charLimit: Int): Result {
        val isHistory = actionId.startsWith("history_")
        val key = if (isHistory) "history" else actionId
        val template = prompts["actions"].obj()[key].obj()[locale].str() ?: error("Unknown prompt action: $actionId")
        val tag = if (isHistory) "HISTORY" else if (actionId == "explain_selection" || actionId == "translate_selection") "TEXT" else "PAGE"
        val notice = fill(prompts["untrustedNotice"].obj()[locale].strOr(""), mapOf("tag" to tag))
        val question = input.question.trim()
        val values = HashMap<String, String>()
        values["question"] = question
        values["query"] = input.query.trim()
        values["title"] = input.title.trim()
        values["url"] = input.url
        values["untrustedNotice"] = notice
        values["pageBlock"] = ""
        values["targetLanguage"] = ""
        values["task"] = ""
        var content = cleanContent(input.content)
        if (actionId == "translate_selection") {
            values["targetLanguage"] = prompts["languages"].obj()[locale].obj()[if (isMostlyKorean(content)) "en" else "ko"].strOr("")
        }
        if (isHistory) {
            val kind = input.historyKind ?: actionId.removePrefix("history_")
            values["task"] = fill(prompts["historyTasks"].obj()[kind].obj()[locale].strOr(""), mapOf("question" to question))
        }
        fun render(c: String): String = if (actionId == "search_page") {
            fill(template, values + ("pageBlock" to if (c.isNotEmpty()) "\n\n$notice\n\n<<<PAGE\n$c\nPAGE>>>" else ""))
        } else fill(template, values + ("content" to c))

        var text = render(content)
        var truncated = false
        if (text.length > charLimit && content.isNotEmpty()) {
            val overhead = render("").length
            val marker = if (locale == "ko") "\n…(길이 제한으로 뒷부분 생략)" else "\n…(rest omitted to fit the length limit)"
            val room = maxOf(0, charLimit - overhead - marker.length)
            content = cut(content, room).trimEnd() + marker
            text = render(content)
            truncated = true
        }
        return Result(text, truncated)
    }
}
