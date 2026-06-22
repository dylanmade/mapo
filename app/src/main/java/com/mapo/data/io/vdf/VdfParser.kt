package com.mapo.data.io.vdf

/**
 * Recursive-descent parser for Valve KeyValues (VDF) text → a [VdfValue.Obj]
 * tree. The companion [parse] is the entry point; instances are internal state.
 *
 * Grammar (KV1):
 * ```
 *   document := entry*
 *   entry    := STRING value
 *   value    := STRING | '{' entry* '}'
 * ```
 * A `controller_mappings` file is one top-level entry whose value is the whole
 * config block, so callers read `parse(text).obj("controller_mappings")`.
 *
 * Duplicate keys are preserved (see [VdfValue.Obj]); this is the property that
 * lets the importer see every `"group"` / `"preset"` / `"binding"`.
 */
class VdfParser private constructor(private val stream: VdfTokenStream) {

    /** One-token lookahead buffer. */
    private var lookahead: VdfToken? = null

    private fun peek(): VdfToken = lookahead ?: stream.next().also { lookahead = it }

    private fun advance(): VdfToken = peek().also { lookahead = null }

    /** Parses entries until a [VdfToken.CloseBrace] (nested) or [VdfToken.End]
     *  (top level). [closing] selects which terminator is expected. */
    private fun parseEntries(closing: Boolean): List<VdfEntry> {
        val entries = ArrayList<VdfEntry>()
        while (true) {
            when (val t = advance()) {
                is VdfToken.End -> {
                    if (closing) throw VdfParseException("Unexpected end of input inside a block")
                    return entries
                }
                is VdfToken.CloseBrace -> {
                    if (!closing) throw VdfParseException("Unexpected '}' at top level")
                    return entries
                }
                is VdfToken.OpenBrace ->
                    throw VdfParseException("Expected a key but found '{'")
                is VdfToken.Str -> entries.add(VdfEntry(t.value, parseValue()))
            }
        }
    }

    /** Parses the value following a key: a string leaf or a nested block. */
    private fun parseValue(): VdfValue =
        when (val t = advance()) {
            is VdfToken.Str -> VdfValue.Str(t.value)
            is VdfToken.OpenBrace -> VdfValue.Obj(parseEntries(closing = true))
            is VdfToken.CloseBrace -> throw VdfParseException("Expected a value but found '}'")
            is VdfToken.End -> throw VdfParseException("Expected a value but reached end of input")
        }

    companion object {
        /** Parses [text] into a [VdfValue.Obj] of its top-level entries. */
        fun parse(text: String): VdfValue.Obj =
            VdfValue.Obj(VdfParser(VdfTokenStream(text)).parseEntries(closing = false))
    }
}
