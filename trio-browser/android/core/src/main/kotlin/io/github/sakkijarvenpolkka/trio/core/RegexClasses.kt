package io.github.sakkijarvenpolkka.trio.core

/**
 * Character classes for regular expressions in the core.
 *
 * The core runs on two regex engines: the JVM's (unit tests) and ICU (Android). They disagree on
 * the shorthand classes (\s, \d, \w are ASCII on the JVM and Unicode on ICU), and ICU rejects
 * JVM-only syntax such as the (?U) flag with a PatternSyntaxException. Core patterns therefore
 * spell their classes out, matching the JavaScript reference implementation exactly:
 * [SPACE] is JavaScript's \s, digits are [0-9], word characters are [A-Za-z0-9_].
 * RegexPortabilityTest keeps shorthand classes and inline flags out of the core.
 */
internal const val SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
