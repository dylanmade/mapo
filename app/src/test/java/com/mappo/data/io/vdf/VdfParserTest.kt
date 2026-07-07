package com.mappo.data.io.vdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tokenizer + parser contract tests. These guard the load-bearing VDF
 * properties the importer depends on — above all that **duplicate keys are
 * preserved as a list**, because a controller config repeats `"group"`,
 * `"preset"`, and `"binding"` keys and a map-backed parse would silently drop
 * all but the last.
 */
class VdfParserTest {

    @Test
    fun parsesNestedBlocksAndLeaves() {
        val root = VdfParser.parse(
            """
            "controller_mappings"
            {
                "version" "3"
                "actions" { "Default" { "title" "Default" "legacy_set" "1" } }
            }
            """.trimIndent(),
        )
        val cm = root.obj("controller_mappings")!!
        assertEquals("3", cm.string("version"))
        assertEquals("Default", cm.obj("actions")!!.obj("Default")!!.string("title"))
    }

    @Test
    fun preservesDuplicateKeysAsList() {
        // Three groups at the same level — the whole point of the list-backed model.
        val root = VdfParser.parse(
            """
            "root"
            {
                "group" { "id" "0" }
                "group" { "id" "1" }
                "group" { "id" "2" }
            }
            """.trimIndent(),
        )
        val groups = root.obj("root")!!.objects("group")
        assertEquals(3, groups.size)
        assertEquals(listOf("0", "1", "2"), groups.map { it.string("id") })
    }

    @Test
    fun duplicateBindingRowsAllSurvive() {
        val root = VdfParser.parse(
            """
            "bindings"
            {
                "binding" "key_press A, , "
                "binding" "key_press B, , "
            }
            """.trimIndent(),
        )
        val bindings = root.obj("bindings")!!.all("binding").mapNotNull { (it as? VdfValue.Str)?.value }
        assertEquals(listOf("key_press A, , ", "key_press B, , "), bindings)
    }

    @Test
    fun firstWinsForUniqueKeyLookups() {
        val root = VdfParser.parse(""""x" { "k" "first" "k" "second" }""")
        assertEquals("first", root.obj("x")!!.string("k"))
        assertEquals(listOf("first", "second"), root.obj("x")!!.all("k").mapNotNull { (it as? VdfValue.Str)?.value })
    }

    @Test
    fun keyLookupsAreCaseInsensitive() {
        val root = VdfParser.parse(""""Root" { "Version" "3" }""")
        assertEquals("3", root.obj("root")!!.string("version"))
    }

    @Test
    fun preservesInteriorWhitespaceAndCommasInQuotedStrings() {
        // Binding strings rely on this — "key_press SPACE, , " must arrive verbatim.
        val root = VdfParser.parse(""""b" { "binding" "controller_action add_layer 3 1 1, , " }""")
        assertEquals("controller_action add_layer 3 1 1, , ", root.obj("b")!!.string("binding"))
    }

    @Test
    fun handlesEscapeSequences() {
        val root = VdfParser.parse(""""x" { "desc" "line1\nline2\t\"quoted\"\\end" }""")
        assertEquals("line1\nline2\t\"quoted\"\\end", root.obj("x")!!.string("desc"))
    }

    @Test
    fun skipsLineCommentsAndConditionalTags() {
        val root = VdfParser.parse(
            """
            // leading comment
            "x"
            {
                "a" "1" // trailing comment
                "b" "2" [${'$'}WIN32]
            }
            """.trimIndent(),
        )
        assertEquals("1", root.obj("x")!!.string("a"))
        assertEquals("2", root.obj("x")!!.string("b"))
    }

    @Test
    fun handlesUnquotedTokens() {
        val root = VdfParser.parse("key { mode joystick_move }")
        assertEquals("joystick_move", root.obj("key")!!.string("mode"))
    }

    @Test
    fun emptyBlockYieldsEmptyObj() {
        val root = VdfParser.parse(""""x" { "disabled_activators" { } }""")
        assertTrue(root.obj("x")!!.obj("disabled_activators")!!.entries.isEmpty())
    }

    @Test
    fun missingKeyReturnsNull() {
        val root = VdfParser.parse(""""x" { "a" "1" }""")
        assertNull(root.obj("x")!!.string("nope"))
        assertNull(root.obj("x")!!.obj("nope"))
    }

    @Test(expected = VdfParseException::class)
    fun unterminatedStringThrows() {
        VdfParser.parse(""""x" { "a" "unterminated }""")
    }

    @Test(expected = VdfParseException::class)
    fun strayCloseBraceThrows() {
        VdfParser.parse(""""x" "1" }""")
    }
}
