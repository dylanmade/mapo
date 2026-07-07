package com.mappo.ui.screen

import com.mappo.data.model.steam.ActionLayer
import com.mappo.data.model.steam.ActionLayerGraph
import com.mappo.data.model.steam.Activator
import com.mappo.data.model.steam.ActivatorGraph
import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.Binding
import com.mappo.data.model.steam.BindingGroup
import com.mappo.data.model.steam.BindingGroupGraph
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutputType
import com.mappo.data.model.steam.GroupInput
import com.mappo.data.model.steam.GroupInputGraph
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.PresetEntry
import com.mappo.ui.screen.remap.RemapPaneItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for `filterToOverrides` (Brick 5.5.c). Robolectric isn't needed
 * — the filter operates on plain data structures. See `RemapControlsScreenTest` for
 * the integration tests that verify the toggle and the in-screen rendering pipeline.
 *
 * These exhaustive shape tests are what `RemapControlsScreenTest` *can't* reliably
 * test, because Robolectric's LazyColumn doesn't materialize below-the-fold rows
 * (per `feedback_robolectric_compose_pitfalls`).
 */
class FilterToOverridesTest {

    @Test
    fun dropsRowsWithoutOverride_andTheirSubheader() {
        val items = listOf(
            RemapPaneItem.Subheader("face.header", "Face Buttons"),
            RemapPaneItem.BindingRow("face.a", "A", InputSource.BUTTON_DIAMOND, "button_a"),
            RemapPaneItem.BindingRow("face.b", "B", InputSource.BUTTON_DIAMOND, "button_b"),
        )
        val layer = layerGraphWith(overrides = emptyList())

        val filtered = filterToOverrides(items, layer)

        // Subheader and both rows dropped — layer has nothing.
        assertEquals(emptyList<RemapPaneItem>(), filtered)
    }

    @Test
    fun keepsOverriddenRow_andResurrectsItsSubheader() {
        val items = listOf(
            RemapPaneItem.Subheader("face.header", "Face Buttons"),
            RemapPaneItem.BindingRow("face.a", "A", InputSource.BUTTON_DIAMOND, "button_a"),
            RemapPaneItem.BindingRow("face.b", "B", InputSource.BUTTON_DIAMOND, "button_b"),
        )
        val layer = layerGraphWith(overrides = listOf(InputSource.BUTTON_DIAMOND to "button_a"))

        val filtered = filterToOverrides(items, layer)

        assertEquals(2, filtered.size)
        assertEquals("face.header", filtered[0].key)
        assertEquals("face.a", filtered[1].key)
    }

    @Test
    fun dropsSubheaderWithNoSurvivingRows_evenWhenLaterSubheaderHasSurvivors() {
        val items = listOf(
            RemapPaneItem.Subheader("face.header", "Face Buttons"),
            RemapPaneItem.BindingRow("face.a", "A", InputSource.BUTTON_DIAMOND, "button_a"),
            RemapPaneItem.Subheader("bumpers.header", "Bumpers"),
            RemapPaneItem.BindingRow("bumpers.l1", "L1", InputSource.LEFT_BUMPER, "click"),
        )
        // Override on L1 only — Face Buttons subheader should drop entirely.
        val layer = layerGraphWith(overrides = listOf(InputSource.LEFT_BUMPER to "click"))

        val filtered = filterToOverrides(items, layer)

        assertEquals(listOf("bumpers.header", "bumpers.l1"), filtered.map { it.key })
    }

    @Test
    fun keepsBothSubheadersWhenEachHasASurvivor() {
        val items = listOf(
            RemapPaneItem.Subheader("face.header", "Face Buttons"),
            RemapPaneItem.BindingRow("face.a", "A", InputSource.BUTTON_DIAMOND, "button_a"),
            RemapPaneItem.Subheader("bumpers.header", "Bumpers"),
            RemapPaneItem.BindingRow("bumpers.l1", "L1", InputSource.LEFT_BUMPER, "click"),
        )
        val layer = layerGraphWith(
            overrides = listOf(
                InputSource.BUTTON_DIAMOND to "button_a",
                InputSource.LEFT_BUMPER to "click",
            ),
        )

        val filtered = filterToOverrides(items, layer)

        assertEquals(
            listOf("face.header", "face.a", "bumpers.header", "bumpers.l1"),
            filtered.map { it.key },
        )
    }

    @Test
    fun disabledRows_alwaysHidden() {
        val items = listOf(
            RemapPaneItem.Subheader("triggers.header", "Triggers"),
            RemapPaneItem.DisabledRow("triggers.l2soft", "L2 Soft Pull"),
            RemapPaneItem.BindingRow("triggers.l2full", "L2 Full Pull", InputSource.LEFT_TRIGGER, "click"),
        )
        val layer = layerGraphWith(overrides = listOf(InputSource.LEFT_TRIGGER to "click"))

        val filtered = filterToOverrides(items, layer)

        // Subheader + overridden binding survive; disabled row gone.
        assertEquals(listOf("triggers.header", "triggers.l2full"), filtered.map { it.key })
    }

    @Test
    fun overrideOnMidSectionRow_doesNotResurrectPreviousSection() {
        // Verifies the "pendingSubheader" reset semantics: when face.a doesn't pass,
        // the queued Face Buttons subheader must NOT carry over into bumpers.
        val items = listOf(
            RemapPaneItem.Subheader("face.header", "Face Buttons"),
            RemapPaneItem.BindingRow("face.a", "A", InputSource.BUTTON_DIAMOND, "button_a"),
            RemapPaneItem.Subheader("bumpers.header", "Bumpers"),
            RemapPaneItem.BindingRow("bumpers.r1", "R1", InputSource.RIGHT_BUMPER, "click"),
        )
        val layer = layerGraphWith(overrides = listOf(InputSource.RIGHT_BUMPER to "click"))

        val filtered = filterToOverrides(items, layer)

        // Face Buttons subheader is dropped because no rows under it survive.
        assertEquals(listOf("bumpers.header", "bumpers.r1"), filtered.map { it.key })
    }

    /**
     * Build a layer graph whose preset carries entries for each requested
     * `(inputSource, inputKey)`. Each entry has a single FULL_PRESS UNBOUND activator
     * — enough to make `layer.presetFor(...).group.inputByKey(...)` non-null, which
     * is the only thing the filter checks.
     */
    private fun layerGraphWith(
        overrides: List<Pair<InputSource, String>>,
    ): ActionLayerGraph {
        val grouped = overrides.groupBy { it.first }
        val presetEntries = grouped.entries.mapIndexed { idx, (inputSource, keys) ->
            val baseId = 1000L + idx * 100
            val groupId = baseId
            val inputs = keys.mapIndexed { kIdx, (_, key) ->
                val inputId = baseId + 10 + kIdx
                val activatorId = baseId + 30 + kIdx
                val bindingId = baseId + 50 + kIdx
                GroupInputGraph(
                    input = GroupInput(id = inputId, bindingGroupId = groupId, inputKey = key, orderIndex = kIdx),
                    activators = listOf(
                        ActivatorGraph(
                            activator = Activator(
                                id = activatorId, groupInputId = inputId,
                                type = ActivatorType.FULL_PRESS, orderIndex = 0,
                            ),
                            bindings = listOf(
                                Binding(
                                    id = bindingId, activatorId = activatorId,
                                    outputType = BindingOutputType.UNBOUND, args = "", orderIndex = 0,
                                ),
                            ),
                        ),
                    ),
                )
            }
            val group = BindingGroupGraph(
                group = BindingGroup(
                    id = groupId, actionSetId = null, actionLayerId = 1L,
                    name = inputSource.name.lowercase(), mode = BindingMode.BUTTON_PAD,
                ),
                inputs = inputs,
            )
            PresetEntry(inputSource, "active", group)
        }
        return ActionLayerGraph(
            layer = ActionLayer(id = 1L, parentActionSetId = 1L, name = "scope", title = "Scope"),
            bindingGroups = emptyList(),
            preset = presetEntries,
        )
    }
}
