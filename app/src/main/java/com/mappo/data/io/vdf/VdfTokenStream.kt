package com.mappo.data.io.vdf

/** A lexical token in a Valve KeyValues stream. */
internal sealed interface VdfToken {
    /** A quoted or unquoted string. */
    data class Str(val value: String) : VdfToken
    /** `{` — opens a block. */
    data object OpenBrace : VdfToken
    /** `}` — closes a block. */
    data object CloseBrace : VdfToken
    /** End of input. */
    data object End : VdfToken
}

/**
 * Streaming tokenizer for Valve KeyValues (VDF) text. Pull one [VdfToken] at a
 * time with [next]; the parser drives it.
 *
 * Handles the dialect Steam writes controller configs in:
 *  - **Quoted strings** (`"..."`) with C-style escape sequences (`\"`, `\\`,
 *    `\n`, `\t`). Steam enables escapes when writing `controller_mappings`, and
 *    binding strings preserve interior spaces/commas verbatim.
 *  - **Unquoted tokens** — bare words terminated by whitespace, a brace, a
 *    quote, or a conditional bracket. Rare in controller configs but valid KV1.
 *  - **`//` line comments** — skipped to end of line.
 *  - **`[$CONDITION]` platform tags** — skipped wholesale (Mappo targets a single
 *    platform; the tags would only ever evaluate one way for us).
 *
 * This is a faithful KV1 reader, not a general VDF/KV3 parser — binary KV and
 * KV3 (`<!-- kv3 -->`) are out of scope (Steam controller configs are KV1 text).
 */
internal class VdfTokenStream(private val text: String) {
    private var pos = 0

    /** Returns the next token, or [VdfToken.End] once input is exhausted. */
    fun next(): VdfToken {
        skipTrivia()
        if (pos >= text.length) return VdfToken.End
        return when (val c = text[pos]) {
            '{' -> { pos++; VdfToken.OpenBrace }
            '}' -> { pos++; VdfToken.CloseBrace }
            '"' -> VdfToken.Str(readQuoted())
            else -> VdfToken.Str(readUnquoted(c))
        }
    }

    /** Skips whitespace, `//` line comments, and `[...]` conditional tags. */
    private fun skipTrivia() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '/' && pos + 1 < text.length && text[pos + 1] == '/' -> {
                    pos += 2
                    while (pos < text.length && text[pos] != '\n') pos++
                }
                c == '[' -> {
                    // Platform conditional like [$WIN32]; consume through ']'.
                    while (pos < text.length && text[pos] != ']') pos++
                    if (pos < text.length) pos++ // eat ']'
                }
                c.isWhitespace() -> pos++
                else -> return
            }
        }
    }

    private fun readQuoted(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (pos < text.length) {
            val c = text[pos]
            when (c) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    if (pos >= text.length) break
                    sb.append(
                        when (val e = text[pos]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> e // unknown escape — keep the char literally
                        },
                    )
                    pos++
                }
                else -> { sb.append(c); pos++ }
            }
        }
        throw VdfParseException("Unterminated quoted string starting near offset ${pos}")
    }

    private fun readUnquoted(first: Char): String {
        val sb = StringBuilder()
        var c = first
        while (pos < text.length) {
            c = text[pos]
            if (c.isWhitespace() || c == '{' || c == '}' || c == '"' || c == '[') break
            sb.append(c)
            pos++
        }
        return sb.toString()
    }
}

/** Thrown when VDF text is malformed (unterminated string, dangling key, stray brace). */
class VdfParseException(message: String) : Exception(message)
