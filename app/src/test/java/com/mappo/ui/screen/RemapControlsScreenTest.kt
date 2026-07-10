package com.mappo.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mappo.data.model.steam.ActionLayer
import com.mappo.data.model.steam.ActionLayerGraph
import com.mappo.data.model.steam.ActionSet
import com.mappo.data.model.steam.ActionSetGraph
import com.mappo.data.model.steam.Activator
import com.mappo.data.model.steam.ActivatorGraph
import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.Binding
import com.mappo.data.model.steam.BindingGroup
import com.mappo.data.model.steam.BindingGroupGraph
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.BindingOutputType
import com.mappo.data.model.steam.ControllerConfig
import com.mappo.data.model.steam.ControllerProfile
import com.mappo.data.model.steam.ControllerType
import com.mappo.data.model.steam.GroupInput
import com.mappo.data.model.steam.GroupInputGraph
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.PresetEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the brick 1.3 master-detail Remap Controls screen.
 * Robolectric per `project_compose_ui_test_blocker` — UI tests can't run on the AYN Thor.
 */
@RunWith(RobolectricTestRunner::class)
// Landscape-handheld viewport: the screen is a wide app bar + an expanded nav rail + the
// detail pane, which needs far more than Robolectric's default ~320dp width. This matches the
// real device (a wide landscape screen) so the rail, fly-out, and actions stay hit-testable.
@Config(sdk = [33], qualifiers = "w1280dp-h800dp")
class RemapControlsScreenTest {

    @get:Rule val composeRule = createComposeRule()


    @Test
    fun advancedDialog_rendersAllSections() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = sampleConfig(),
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // The rail lives in the advanced editor dialog now — open it from a group box.
        composeRule.onNodeWithTag("simple-group:DPAD").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("section-rail-item:buttons").assertExists()
        composeRule.onNodeWithTag("section-rail-item:dpad").assertExists()
        composeRule.onNodeWithTag("section-rail-item:triggers").assertExists()
        composeRule.onNodeWithTag("section-rail-item:joysticks").assertExists()
        composeRule.onNodeWithTag("section-rail-item:gyro").assertExists()
    }

    @Test
    fun simpleView_rendersFaceGlyphs_andDefaultLabels() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = sampleConfig(),
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // The face box renders its A/B/X/Y badges; unconfigured groups read "Default".
        composeRule.onAllNodesWithText("A", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("B", useUnmergedTree = true).assertCountEquals(1)
        assert(
            composeRule.onAllNodesWithText("Default", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        ) { "Expected unconfigured groups to summarize as 'Default'" }
    }

    @Test
    fun tappingDpadBox_opensAdvancedEditorOnDpadSection() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = sampleConfig(),
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("simple-group:DPAD").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Advanced controls").assertIsDisplayed()
        composeRule.onNodeWithTag("section-rail-item:dpad").assertIsSelected()
    }



    @Test
    fun displaysBoundLabel_forConfiguredBinding() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = sampleConfig(boundButtonA = BindingOutput.KeyPress("ENTER")),
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("KB: ENTER", useUnmergedTree = true).assertExists()
    }

    // ── Scope fly-out: action sets ──────────────

    @Test
    fun tabs_listEverySet_andSelectingInvokesCallback() {
        var selectedSetId: Long? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = twoSetConfig(
                            setAButtonA = BindingOutput.KeyPress("ENTER"),
                            setBButtonA = BindingOutput.KeyPress("SPACE"),
                        ),
                        viewingActionSetId = 1L,
                        onSelectActionSet = { selectedSetId = it },
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Every set renders as a top-bar tab; tapping one selects it.
        composeRule.onNodeWithText("Menu", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Menu", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assert(selectedSetId == 2L) {
            "Expected onSelectActionSet(2L), got $selectedSetId"
        }
    }

    @Test
    fun bindingRowReflectsViewingSet_notDefaultSet() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = twoSetConfig(
                            setAButtonA = BindingOutput.KeyPress("ENTER"),
                            setBButtonA = BindingOutput.KeyPress("SPACE"),
                        ),
                        // Default per twoSetConfig is set 1 (ENTER); viewing 2 (SPACE).
                        viewingActionSetId = 2L,
                        onSelectActionSet = {},
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("KB: SPACE", useUnmergedTree = true).assertExists()
        // The default set's binding is NOT shown — the editor follows the viewing pointer.
        composeRule.onAllNodesWithText("KB: ENTER", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun nullViewingActionSetId_fallsBackToDefaultSet() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = twoSetConfig(
                            setAButtonA = BindingOutput.KeyPress("ENTER"),
                            setBButtonA = BindingOutput.KeyPress("SPACE"),
                        ),
                        viewingActionSetId = null,
                        onSelectActionSet = {},
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Default set (set 1) wins → ENTER, not SPACE.
        composeRule.onNodeWithText("KB: ENTER", useUnmergedTree = true).assertExists()
    }

    // ── Top-bar tabs: set management ─────────────────────────────────────

    @Test
    fun addSetButton_opensAddSetDialog() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = twoSetConfig(
                            setAButtonA = BindingOutput.Unbound,
                            setBButtonA = BindingOutput.Unbound,
                        ),
                        viewingActionSetId = 1L,
                        onSelectActionSet = {},
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Add action set").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add Action Set", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun bindingRow_showsResolvedSetTitle_forChangePresetBinding() {
        // Brick 4.5: a button bound to CHANGE_PRESET should display "Switch to: <set title>"
        // — the row-preview side of the context-aware displayLabel(config).
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = twoSetConfig(
                            setAButtonA = BindingOutput.ControllerAction("CHANGE_PRESET", listOf("2")),
                            setBButtonA = BindingOutput.Unbound,
                        ),
                        viewingActionSetId = 1L,
                        onSelectActionSet = {},
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Switch to: Menu").assertIsDisplayed()
    }

    // ── Top-bar tabs: layers ─────────────────────────────────────────────

    @Test
    fun tabs_listLayers_asSubordinateTabs() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = singleSetConfigWithLayers(
                            layers = listOf(10L to "Scope", 11L to "Vehicle"),
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = null,  // base view → the set tab is selected, not a layer
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Scope", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Vehicle", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tabs_selectingLayer_invokesOnSelectLayer_withId() {
        var selectedLayerId: Long? = -1L  // sentinel; null is a meaningful value
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = singleSetConfigWithLayers(
                            layers = listOf(10L to "Scope", 11L to "Vehicle"),
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = null,
                        onSelectLayer = { selectedLayerId = it },
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Vehicle", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assert(selectedLayerId == 11L) {
            "Expected onSelectLayer(11L), got $selectedLayerId"
        }
    }

    @Test
    fun tabs_selectingSetTab_dropsToBase_withNull() {
        var lastSelected: Long? = -1L
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = singleSetConfigWithLayers(
                            layers = listOf(10L to "Scope"),
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = 10L,
                        onSelectLayer = { lastSelected = it },
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Tapping the set tab itself selects the base set (no layer overlay). (By tag — the
        // set's "Default" title collides with the simple view's "Default" summary labels.)
        composeRule.onNodeWithTag("tab:set:1").performClick()
        composeRule.waitForIdle()

        assert(lastSelected == null) {
            "Expected selecting the set row to fire onSelectLayer(null), got $lastSelected"
        }
    }

    @Test
    fun tabs_listAllSetsLayers_notJustViewing() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = twoSetConfigWithLayers(
                            setALayers = listOf(10L to "ScopeA"),
                            setBLayers = listOf(20L to "ScopeB"),
                        ),
                        viewingActionSetId = 2L,  // viewing set B
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // The tab bar is a global switcher: every set's layers are listed, not just the
        // viewing set's (unlike the old per-set pill row).
        composeRule.onNodeWithText("ScopeA", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("ScopeB", useUnmergedTree = true).assertExists()
    }

    // ── Overlay editing mode (Brick 5.5.c) ────────────────────────────────────

    @Test
    fun overlayMode_ghostRow_showsBaseBindingWhenLayerHasNoOverride() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = configWithLayerOverride(
                            baseButtonA = BindingOutput.KeyPress("ENTER"),
                            layerId = 10L,
                            layerTitle = "Scope",
                            layerOverrideButtonA = null,  // ghost row
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = 10L,
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // The base set's binding text shows through as ghost (Robolectric can't probe
        // alpha cleanly; we verify the text is *present* and there's no override icon).
        composeRule.onNodeWithText("KB: ENTER", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("Override actions").assertCountEquals(0)
    }

    @Test
    fun overlayMode_overriddenRow_showsLayerBindingAndOverflowMenu() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = configWithLayerOverride(
                            baseButtonA = BindingOutput.KeyPress("ENTER"),
                            layerId = 10L,
                            layerTitle = "Scope",
                            layerOverrideButtonA = BindingOutput.MouseButton("MOUSE_LEFT"),
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = 10L,
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Override visible in the simple view; base hidden for that row.
        composeRule.onNodeWithText("MS: MOUSE_LEFT", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("KB: ENTER", useUnmergedTree = true).assertCountEquals(0)
        // Trailing menu lives in the advanced editor dialog.
        composeRule.onNodeWithTag("simple-group:FACE").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Override actions").assertIsDisplayed()
    }

    @Test
    fun overlayMode_clearOverride_invokesCallback_withLayerSourceAndKey() {
        var args: Triple<Long, com.mappo.data.model.steam.InputSource, String>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = configWithLayerOverride(
                            baseButtonA = BindingOutput.KeyPress("ENTER"),
                            layerId = 42L,
                            layerTitle = "Scope",
                            layerOverrideButtonA = BindingOutput.MouseButton("MOUSE_LEFT"),
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = 42L,
                        onClearLayerOverride = { layerId, src, key -> args = Triple(layerId, src, key) },
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("simple-group:FACE").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Override actions").performClick()
        composeRule.onNodeWithText("Clear override").performClick()

        assert(args == Triple(42L, com.mappo.data.model.steam.InputSource.BUTTON_DIAMOND, "button_a")) {
            "Expected callback (42, BUTTON_DIAMOND, button_a); got $args"
        }
    }

    @Test
    fun overridesFilterToggle_hiddenInBaseMode() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = sampleConfig(),
                        viewingActionSetId = 1L,
                        viewingLayerId = null,
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("simple-group:FACE").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Only overrides").assertCountEquals(0)
        composeRule.onAllNodesWithText("Show all").assertCountEquals(0)
    }

    @Test
    fun overridesFilterToggle_visibleInOverlayMode_andOverriddenRowSurvivesFilter() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1200.dp, 1600.dp)) {
                    RemapControlsScreen(
                        config = configWithLayerOverride(
                            baseButtonA = BindingOutput.KeyPress("ENTER"),
                            layerId = 10L,
                            layerTitle = "Scope",
                            layerOverrideButtonA = BindingOutput.MouseButton("MOUSE_LEFT"),
                        ),
                        viewingActionSetId = 1L,
                        viewingLayerId = 10L,
                        onOpenInputEditor = { _, _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("simple-group:FACE").performClick()
        composeRule.waitForIdle()

        // Toggle visible.
        composeRule.onNodeWithText("Show all").assertIsDisplayed()
        composeRule.onNodeWithText("Only overrides").assertIsDisplayed()

        composeRule.onNodeWithText("Only overrides").performClick()
        composeRule.waitForIdle()

        // The overridden row is still in the tree — the filter let it through.
        // We don't assert that the non-overridden rows are *gone* here because
        // Robolectric's LazyColumn doesn't always materialize below-the-fold rows
        // anyway (per feedback_robolectric_compose_pitfalls). The filter's
        // correctness is exhaustively tested at the pure-function level via
        // FilterToOverridesTest.
        // (onAllNodes + onFirst: the label also renders in the simple view beneath the dialog.)
        composeRule.onAllNodesWithText("MS: MOUSE_LEFT", useUnmergedTree = true).onFirst().assertExists()
    }

    /**
     * Helper for overlay-mode tests. Builds a single-set config with one layer that
     * optionally carries a `button_a` override (when [layerOverrideButtonA] is non-null).
     * Mirrors `sampleConfig`'s base shape (four face buttons on BUTTON_DIAMOND).
     */
    private fun configWithLayerOverride(
        baseButtonA: BindingOutput,
        layerId: Long,
        layerTitle: String,
        layerOverrideButtonA: BindingOutput?,
    ): ControllerConfig {
        val base = sampleConfig(boundButtonA = baseButtonA)
        val baseSet = base.actionSets.first()

        val layerPresetEntries = if (layerOverrideButtonA != null) {
            val overlayActivator = Activator(
                id = 5000L, groupInputId = 6000L, type = ActivatorType.FULL_PRESS, orderIndex = 0,
            )
            val overlayBinding = layerOverrideButtonA.toEntity().let { (t, args) ->
                Binding(id = 7000L, activatorId = overlayActivator.id, outputType = t, args = args, orderIndex = 0)
            }
            val overlayInput = GroupInputGraph(
                input = GroupInput(id = 6000L, bindingGroupId = 4000L, inputKey = "button_a", orderIndex = 0),
                activators = listOf(ActivatorGraph(overlayActivator, listOf(overlayBinding))),
            )
            val overlayGroup = BindingGroupGraph(
                group = BindingGroup(
                    id = 4000L, actionSetId = null, actionLayerId = layerId,
                    name = "face_overlay", mode = BindingMode.BUTTON_PAD,
                ),
                inputs = listOf(overlayInput),
            )
            listOf(PresetEntry(InputSource.BUTTON_DIAMOND, "active", overlayGroup))
        } else emptyList()

        val layer = ActionLayerGraph(
            layer = ActionLayer(
                id = layerId,
                parentActionSetId = baseSet.actionSet.id,
                name = layerTitle.lowercase(),
                title = layerTitle,
            ),
            bindingGroups = emptyList(),
            preset = layerPresetEntries,
        )

        return base.copy(actionSets = listOf(baseSet.copy(layers = listOf(layer))))
    }

    /** Single-set config with [layers] attached (id+title pairs, in order). */
    private fun singleSetConfigWithLayers(
        layers: List<Pair<Long, String>>,
    ): ControllerConfig {
        val base = sampleConfig()
        val setWithLayers = base.actionSets.first().copy(
            layers = layers.mapIndexed { idx, (id, title) ->
                ActionLayerGraph(
                    layer = ActionLayer(
                        id = id,
                        parentActionSetId = base.actionSets.first().actionSet.id,
                        name = title.lowercase(),
                        title = title,
                        orderIndex = idx,
                    ),
                    bindingGroups = emptyList(),
                )
            },
        )
        return base.copy(actionSets = listOf(setWithLayers))
    }

    /** Two-set config where each set carries its own layer list. */
    private fun twoSetConfigWithLayers(
        setALayers: List<Pair<Long, String>>,
        setBLayers: List<Pair<Long, String>>,
    ): ControllerConfig {
        val base = twoSetConfig(
            setAButtonA = BindingOutput.Unbound,
            setBButtonA = BindingOutput.Unbound,
        )
        fun attach(graph: ActionSetGraph, layers: List<Pair<Long, String>>) = graph.copy(
            layers = layers.mapIndexed { idx, (id, title) ->
                ActionLayerGraph(
                    layer = ActionLayer(
                        id = id,
                        parentActionSetId = graph.actionSet.id,
                        name = title.lowercase(),
                        title = title,
                        orderIndex = idx,
                    ),
                    bindingGroups = emptyList(),
                )
            },
        )
        return base.copy(
            actionSets = listOf(
                attach(base.actionSets[0], setALayers),
                attach(base.actionSets[1], setBLayers),
            ),
        )
    }

    /**
     * Builds a two-action-set config. Set 1 is the starting set (first in order)
     * ("Gameplay", button_a → [setAButtonA]); Set 2 is "Menu" with button_a → [setBButtonA].
     */
    private fun twoSetConfig(
        setAButtonA: BindingOutput,
        setBButtonA: BindingOutput,
    ): ControllerConfig {
        fun buildFaceGroup(actionSetId: Long, bindingGroupId: Long, baseId: Long, output: BindingOutput): BindingGroupGraph {
            val activator = Activator(
                id = baseId, groupInputId = baseId + 1, type = ActivatorType.FULL_PRESS, orderIndex = 0,
            )
            val binding = output.toEntity().let { (t, args) ->
                Binding(id = baseId + 100, activatorId = activator.id, outputType = t, args = args, orderIndex = 0)
            }
            val buttonAInput = GroupInputGraph(
                input = GroupInput(id = baseId + 1, bindingGroupId = bindingGroupId, inputKey = "button_a", orderIndex = 0),
                activators = listOf(ActivatorGraph(activator, listOf(binding))),
            )
            return BindingGroupGraph(
                group = BindingGroup(id = bindingGroupId, actionSetId = actionSetId, name = "face_buttons", mode = BindingMode.BUTTON_PAD),
                inputs = listOf(buttonAInput),
            )
        }
        val setA = ActionSetGraph(
            actionSet = ActionSet(id = 1L, controllerProfileId = 1L, name = "gameplay", title = "Gameplay"),
            layers = emptyList(),
            preset = listOf(PresetEntry(InputSource.BUTTON_DIAMOND, "active", buildFaceGroup(1L, 1L, 100L, setAButtonA))),
        )
        val setB = ActionSetGraph(
            actionSet = ActionSet(id = 2L, controllerProfileId = 1L, name = "menu", title = "Menu"),
            layers = emptyList(),
            preset = listOf(PresetEntry(InputSource.BUTTON_DIAMOND, "active", buildFaceGroup(2L, 2L, 200L, setBButtonA))),
        )
        return ControllerConfig(
            controllerProfile = ControllerProfile(
                id = 1L, profileId = 1L,
                controllerType = ControllerType.GENERIC_ANDROID, name = "Default",
            ),
            actionSets = listOf(setA, setB),
        )
    }

    /** Builds a minimal ControllerConfig matching the seed shape, with optional override for BUTTON_A. */
    private fun sampleConfig(
        boundButtonA: BindingOutput = BindingOutput.Unbound,
    ): ControllerConfig {
        val activator = Activator(id = 100L, groupInputId = 10L, type = ActivatorType.FULL_PRESS, orderIndex = 0)
        val binding = boundButtonA.toEntity().let { (type, args) ->
            Binding(id = 1000L, activatorId = activator.id, outputType = type, args = args, orderIndex = 0)
        }
        val buttonAInput = GroupInputGraph(
            input = GroupInput(id = 10L, bindingGroupId = 1L, inputKey = "button_a", orderIndex = 0),
            activators = listOf(ActivatorGraph(activator, listOf(binding))),
        )
        fun unboundInput(id: Long, key: String, order: Int): GroupInputGraph {
            val act = Activator(id = id + 100L, groupInputId = id, type = ActivatorType.FULL_PRESS)
            val b = Binding(id = id + 1000L, activatorId = act.id, outputType = BindingOutputType.UNBOUND, args = "")
            return GroupInputGraph(
                input = GroupInput(id = id, bindingGroupId = 1L, inputKey = key, orderIndex = order),
                activators = listOf(ActivatorGraph(act, listOf(b))),
            )
        }
        val faceGroup = BindingGroupGraph(
            group = BindingGroup(id = 1L, actionSetId = 1L, name = "face_buttons", mode = BindingMode.BUTTON_PAD),
            inputs = listOf(
                buttonAInput,
                unboundInput(11L, "button_b", 1),
                unboundInput(12L, "button_x", 2),
                unboundInput(13L, "button_y", 3),
            ),
        )
        val faceEntry = PresetEntry(InputSource.BUTTON_DIAMOND, "active", faceGroup)
        // Minimal: only BUTTON_DIAMOND populated. Other sections will show with Unbound rows.
        val actionSet = ActionSetGraph(
            actionSet = ActionSet(id = 1L, controllerProfileId = 1L, name = "default", title = "Default"),
            layers = emptyList(),
            preset = listOf(faceEntry),
        )
        return ControllerConfig(
            controllerProfile = ControllerProfile(
                // Distinct from the "Default" set title so app-bar subtitle text doesn't
                // collide with the set row in onNodeWithText lookups.
                id = 1L, profileId = 1L,
                controllerType = ControllerType.GENERIC_ANDROID, name = "Test profile",
            ),
            actionSets = listOf(actionSet),
        )
    }
}
