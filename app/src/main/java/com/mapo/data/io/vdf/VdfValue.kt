package com.mapo.data.io.vdf

/**
 * A parsed Valve KeyValues (VDF) value — the in-memory tree a [VdfParser]
 * produces. Either a [Str] leaf or an [Obj] block.
 *
 * **Duplicate keys are first-class.** A Steam controller config legitimately
 * repeats keys at the same level — many `"group"` blocks, several `"preset"`
 * blocks, repeated `"binding"` rows inside one `"bindings"` block. A naive
 * map-backed model silently drops all but the last; [Obj] is therefore an
 * *ordered list* of entries and every lookup that could legitimately match
 * more than once ([all], [objects]) returns a list. Single-value convenience
 * accessors ([string], [obj], [first]) return the *first* match, matching
 * Valve's own "first wins" read semantics for genuinely-unique keys.
 *
 * Keys compare case-insensitively (Valve KeyValues keys are case-insensitive);
 * the original casing is preserved on each [VdfEntry] for faithful re-export.
 */
sealed interface VdfValue {

    /** A string leaf. VDF has no typed scalars — numbers, booleans, and binding
     *  strings all arrive as text and are interpreted by the consumer. */
    data class Str(val value: String) : VdfValue

    /** A `{ ... }` block: an ordered, duplicate-tolerant list of child entries. */
    data class Obj(val entries: List<VdfEntry>) : VdfValue {

        /** Every value stored under [key] (case-insensitive), in document order. */
        fun all(key: String): List<VdfValue> =
            entries.filter { it.key.equals(key, ignoreCase = true) }.map { it.value }

        /** The first value stored under [key], or null if absent. */
        fun first(key: String): VdfValue? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

        /** The first [Str] value under [key], or null if absent / not a leaf. */
        fun string(key: String): String? = (first(key) as? Str)?.value

        /** The first [Obj] value under [key], or null if absent / not a block. */
        fun obj(key: String): Obj? = first(key) as? Obj

        /** Every [Obj] block stored under [key] (e.g. all `"group"` blocks). */
        fun objects(key: String): List<Obj> = all(key).filterIsInstance<Obj>()

        /** Flattens this block's direct [Str] children into a `key → value` map.
         *  Used for leaf-only blocks like `"settings"`. Later duplicates win
         *  (settings blocks don't repeat keys, so this is effectively lossless). */
        fun toStringMap(): Map<String, String> =
            entries.mapNotNull { e -> (e.value as? Str)?.let { e.key to it.value } }.toMap()

        /** Keys in document order (duplicates retained). */
        val keys: List<String> get() = entries.map { it.key }
    }
}

/** One `key → value` pair inside a [VdfValue.Obj], preserving the key's original casing. */
data class VdfEntry(val key: String, val value: VdfValue)
