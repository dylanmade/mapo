package com.mapo.service.input

import com.mapo.data.model.steam.ActionSet
import com.mapo.data.model.steam.ActionSetGraph
import com.mapo.data.model.steam.Activator
import com.mapo.data.model.steam.ActivatorGraph
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.Binding
import com.mapo.data.model.steam.BindingGroup
import com.mapo.data.model.steam.BindingGroupGraph
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.ControllerProfile
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.GroupInput
import com.mapo.data.model.steam.GroupInputGraph
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.PresetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric so org.json.JSONObject is a real implementation at test time
// (the Android-stub JSONObject `has()` always returns false, which would defeat
// every settings parse test). Per project_compose_ui_test_blocker.md.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompiledConfigTest {

    @Test
    fun emptyConfig_compilesToEmpty() {
        val cfg = ControllerConfig(
            controllerProfile = sampleControllerProfile(),
            actionSets = emptyList(),
        )

        val compiled = cfg.toCompiled()

        assertSame(CompiledConfig.EMPTY, compiled)
        assertEquals(0L, compiled.activeActionSetId)
        assertTrue(compiled.inputs.isEmpty())
    }

    @Test
    fun singleBindingRoundTrip_lookupFindsKeyPress() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND,
                    state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                inputKey = "button_a",
                                activators = listOf(
                                    activatorWith(
                                        type = ActivatorType.FULL_PRESS,
                                        bindings = listOf(
                                            binding(BindingOutputType.KEY_PRESS, "ENTER"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val hit = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")

        assertNotNull(hit)
        assertEquals(1, hit!!.activators.size)
        assertEquals(ActivatorType.FULL_PRESS, hit.activators[0].type)
        assertEquals(BindingOutput.KeyPress("ENTER"), hit.activators[0].bindings.single())
    }

    @Test
    fun lookup_missingAddress_returnsNull() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(unboundBinding())))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        assertEquals(null, compiled.lookup(InputSource.BUTTON_DIAMOND, "button_b"))
        assertEquals(null, compiled.lookup(InputSource.DPAD, "dpad_north"))
    }

    @Test
    fun multipleSources_eachPresetEntryGetsItsOwnAddress() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER"))))),
                            inputWith("button_b", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ESCAPE"))))),
                        ),
                    ),
                ),
                presetEntry(
                    inputSource = InputSource.DPAD, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("dpad_north", listOf(activatorWith(bindings = listOf(unboundBinding())))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        assertEquals(3, compiled.inputs.size)
        assertEquals(
            BindingOutput.KeyPress("ENTER"),
            compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0].bindings.single(),
        )
        assertEquals(
            BindingOutput.KeyPress("ESCAPE"),
            compiled.lookup(InputSource.BUTTON_DIAMOND, "button_b")!!.activators[0].bindings.single(),
        )
        assertEquals(
            BindingOutput.Unbound,
            compiled.lookup(InputSource.DPAD, "dpad_north")!!.activators[0].bindings.single(),
        )
    }

    @Test
    fun nonActiveStatePresetEntries_areExcluded() {
        // The same physical source can have multiple preset entries qualified by state
        // (active / inactive / modeshift). Phase 2 honors only the "active" entry.
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER"))))),
                        ),
                    ),
                ),
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "modeshift",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "SPACE"))))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        // The "active" entry wins; the modeshift entry is dropped — Phase 5 territory.
        assertEquals(
            BindingOutput.KeyPress("ENTER"),
            compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0].bindings.single(),
        )
    }

    @Test
    fun multipleActivators_areAllPreserved() {
        // Phase 3 lands real multi-activator semantics (FULL + LONG + DOUBLE etc.);
        // for now the compiler just needs to carry every activator forward so the
        // state machine can decide what to do with them.
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                inputKey = "button_a",
                                activators = listOf(
                                    activatorWith(
                                        type = ActivatorType.FULL_PRESS,
                                        bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER")),
                                    ),
                                    activatorWith(
                                        type = ActivatorType.LONG_PRESS,
                                        bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ESCAPE")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val activators = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators

        assertEquals(2, activators.size)
        assertEquals(ActivatorType.FULL_PRESS, activators[0].type)
        assertEquals(ActivatorType.LONG_PRESS, activators[1].type)
        assertEquals(BindingOutput.KeyPress("ENTER"), activators[0].bindings.single())
        assertEquals(BindingOutput.KeyPress("ESCAPE"), activators[1].bindings.single())
    }

    @Test
    fun activeActionSetId_propagatesFromGraph() {
        val cfg = configWith(
            actionSet = ActionSet(id = 42L, controllerProfileId = 1L, name = "default", title = "Default"),
            preset = emptyList(),
        )

        val compiled = cfg.toCompiled()

        assertEquals(42L, compiled.activeActionSetId)
        assertTrue(compiled.inputs.isEmpty())
    }

    // ── Settings parsing (Brick 3.1) ──────────────────────────────────────────

    @Test
    fun settingsJson_empty_yieldsDefaults() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    type = ActivatorType.LONG_PRESS,
                                    settingsJson = "{}",
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ESCAPE")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val activator = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0]

        assertEquals(CompiledActivatorSettings.DEFAULT_LONG_PRESS_TIME_MS, activator.settings.longPressTimeMs)
    }

    @Test
    fun settingsJson_longPressTime_parsedAndPropagated() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    type = ActivatorType.LONG_PRESS,
                                    settingsJson = """{"long_press_time_ms": 1234}""",
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ESCAPE")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val activator = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0]

        assertEquals(1234L, activator.settings.longPressTimeMs)
    }

    @Test
    fun settingsJson_malformed_fallsBackToDefaults() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    type = ActivatorType.LONG_PRESS,
                                    settingsJson = "not valid json",
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ESCAPE")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val activator = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0]

        assertEquals(CompiledActivatorSettings.DEFAULT_LONG_PRESS_TIME_MS, activator.settings.longPressTimeMs)
    }

    @Test
    fun multipleBindingsOnOneActivator_arePreservedInOrder() {
        // Cycle_binding (multi-binding activators) lands in Phase 3 — the runtime cycles
        // through these one at a time. The compiler keeps the binding order verbatim.
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                inputKey = "button_a",
                                activators = listOf(
                                    activatorWith(
                                        type = ActivatorType.FULL_PRESS,
                                        bindings = listOf(
                                            binding(BindingOutputType.KEY_PRESS, "ENTER"),
                                            binding(BindingOutputType.KEY_PRESS, "ESCAPE"),
                                            binding(BindingOutputType.KEY_PRESS, "SPACE"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val bindings = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0].bindings

        assertEquals(3, bindings.size)
        assertEquals(BindingOutput.KeyPress("ENTER"), bindings[0])
        assertEquals(BindingOutput.KeyPress("ESCAPE"), bindings[1])
        assertEquals(BindingOutput.KeyPress("SPACE"), bindings[2])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sampleControllerProfile() = ControllerProfile(
        id = 1L, profileId = 1L,
        controllerType = ControllerType.GENERIC_ANDROID,
        name = "Default",
    )

    private fun configWith(
        actionSet: ActionSet = ActionSet(id = 1L, controllerProfileId = 1L, name = "default", title = "Default"),
        preset: List<PresetEntry>,
    ) = ControllerConfig(
        controllerProfile = sampleControllerProfile(),
        actionSets = listOf(ActionSetGraph(actionSet, layers = emptyList(), preset = preset)),
    )

    private fun presetEntry(
        inputSource: InputSource,
        state: String,
        group: BindingGroupGraph,
    ) = PresetEntry(inputSource, state, group)

    private fun groupWith(inputs: List<GroupInputGraph>) = BindingGroupGraph(
        group = BindingGroup(id = 1L, actionSetId = 1L, name = "g", mode = BindingMode.BUTTON_PAD),
        inputs = inputs,
    )

    private fun inputWith(
        inputKey: String,
        activators: List<ActivatorGraph>,
    ): GroupInputGraph {
        val id = nextId++
        return GroupInputGraph(
            input = GroupInput(id = id, bindingGroupId = 1L, inputKey = inputKey, orderIndex = 0),
            activators = activators,
        )
    }

    private fun activatorWith(
        type: ActivatorType = ActivatorType.FULL_PRESS,
        bindings: List<Binding>,
        settingsJson: String = "{}",
    ): ActivatorGraph {
        val activatorId = nextId++
        // Bindings stamped with a fresh activatorId so the test data round-trips through
        // BindingOutput.fromEntity correctly.
        val stamped = bindings.map { it.copy(id = nextId++, activatorId = activatorId) }
        return ActivatorGraph(
            activator = Activator(
                id = activatorId,
                groupInputId = 1L,
                type = type,
                settingsJson = settingsJson,
                orderIndex = 0,
            ),
            bindings = stamped,
        )
    }

    private fun binding(type: BindingOutputType, args: String) = Binding(
        id = 0L, activatorId = 0L, outputType = type, args = args, orderIndex = 0,
    )

    private fun unboundBinding() = binding(BindingOutputType.UNBOUND, "")

    private var nextId = 100L
}
