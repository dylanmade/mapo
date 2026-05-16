package com.mapo.service.input

import com.mapo.data.model.steam.ActionLayer
import com.mapo.data.model.steam.ActionLayerGraph
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
        assertEquals(0L, compiled.startingActionSetId)
        assertTrue(compiled.sets.isEmpty())
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
                    // mode=DPAD falls through to StubMode (unimplemented) so dpad_north
                    // isn't dropped by Brick 6.1's mode validation.
                    group = groupWith(
                        mode = BindingMode.DPAD,
                        inputs = listOf(
                            inputWith("dpad_north", listOf(activatorWith(bindings = listOf(unboundBinding())))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        assertEquals(3, compiled.totalInputCount)
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
    fun multipleActionSets_eachCompilesToItsOwnInputs() {
        // Brick 4.2: every set in the graph gets compiled, keyed by set id.
        val setA = ActionSetGraph(
            actionSet = ActionSet(id = 10L, controllerProfileId = 1L, name = "gameplay", title = "Gameplay"),
            layers = emptyList(),
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER"))))),
                        ),
                    ),
                ),
            ),
        )
        val setB = ActionSetGraph(
            actionSet = ActionSet(id = 20L, controllerProfileId = 1L, name = "menu", title = "Menu"),
            layers = emptyList(),
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "SPACE"))))),
                        ),
                    ),
                ),
            ),
        )
        val cfg = ControllerConfig(
            controllerProfile = sampleControllerProfile(),
            actionSets = listOf(setA, setB),
        )

        val compiled = cfg.toCompiled()

        assertEquals(setOf(10L, 20L), compiled.sets.keys)
        assertEquals(
            "starting set is the first action set in the graph",
            10L, compiled.startingActionSetId,
        )
        val setALookup = compiled.lookup(setId = 10L, source = InputSource.BUTTON_DIAMOND, inputKey = "button_a")!!
        val setBLookup = compiled.lookup(setId = 20L, source = InputSource.BUTTON_DIAMOND, inputKey = "button_a")!!
        assertEquals(BindingOutput.KeyPress("ENTER"), setALookup.activators[0].bindings.single())
        assertEquals(BindingOutput.KeyPress("SPACE"), setBLookup.activators[0].bindings.single())
    }

    @Test
    fun actionLayers_areMaterializedIntoCompiledActionSetLayersMap() {
        // Brick 5.1: layer rows reach the compiled snapshot so the evaluator can stack
        // them at runtime. This baseline case has no layer presets — so layer inputs
        // start empty (5.5.a only populates them when preset entries exist).
        val setWithLayers = ActionSetGraph(
            actionSet = ActionSet(id = 7L, controllerProfileId = 1L, name = "default", title = "Default"),
            layers = listOf(
                ActionLayerGraph(
                    layer = ActionLayer(id = 100L, parentActionSetId = 7L, name = "scope", title = "Scope"),
                    bindingGroups = emptyList(),
                ),
                ActionLayerGraph(
                    layer = ActionLayer(id = 101L, parentActionSetId = 7L, name = "vehicle", title = "Vehicle"),
                    bindingGroups = emptyList(),
                ),
            ),
            preset = emptyList(),
        )
        val cfg = ControllerConfig(
            controllerProfile = sampleControllerProfile(),
            actionSets = listOf(setWithLayers),
        )

        val compiled = cfg.toCompiled()
        val compiledSet = compiled.sets.getValue(7L)
        assertEquals(setOf(100L, 101L), compiledSet.layers.keys)
        assertTrue(
            "Layer overlays are empty when the layer has no preset entries",
            compiledSet.layers.values.all { it.inputs.isEmpty() },
        )
    }

    @Test
    fun actionLayer_presetEntries_areFoldedIntoCompiledLayerInputs() {
        // Brick 5.5.a: each layer's preset entries are compiled into CompiledLayer.inputs
        // exactly like a set's preset folds into CompiledActionSet.inputs. This is what
        // makes the runtime layer-stack walk (Brick 5.1) actually produce overrides.
        val setWithOverlay = ActionSetGraph(
            actionSet = ActionSet(id = 9L, controllerProfileId = 1L, name = "default", title = "Default"),
            layers = listOf(
                ActionLayerGraph(
                    layer = ActionLayer(id = 200L, parentActionSetId = 9L, name = "scope", title = "Scope"),
                    bindingGroups = emptyList(),
                    preset = listOf(
                        presetEntry(
                            inputSource = InputSource.BUTTON_DIAMOND,
                            state = "active",
                            group = groupWith(
                                inputs = listOf(
                                    inputWith(
                                        "button_a",
                                        listOf(activatorWith(
                                            bindings = listOf(binding(BindingOutputType.MOUSE_BUTTON, "MOUSE_LEFT")),
                                        )),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND,
                    state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val cfg = ControllerConfig(
            controllerProfile = sampleControllerProfile(),
            actionSets = listOf(setWithOverlay),
        )

        val compiled = cfg.toCompiled()
        val compiledSet = compiled.sets.getValue(9L)
        val baseAddress = InputAddress(InputSource.BUTTON_DIAMOND, "button_a")

        // Base set still binds ENTER for button_a.
        assertEquals(
            BindingOutput.KeyPress("ENTER"),
            compiledSet.inputs.getValue(baseAddress).activators[0].bindings.single(),
        )
        // The Scope layer overrides button_a → MOUSE_LEFT.
        val layerInput = compiledSet.layers.getValue(200L).inputs.getValue(baseAddress)
        assertEquals(
            BindingOutput.MouseButton("MOUSE_LEFT"),
            layerInput.activators[0].bindings.single(),
        )
    }

    @Test
    fun actionLayer_inactiveStatePresetEntries_areSkipped() {
        // Mirror of the base-set behavior: only state=="active" preset entries compile.
        val setWithLayer = ActionSetGraph(
            actionSet = ActionSet(id = 12L, controllerProfileId = 1L, name = "default", title = "Default"),
            layers = listOf(
                ActionLayerGraph(
                    layer = ActionLayer(id = 300L, parentActionSetId = 12L, name = "scope", title = "Scope"),
                    bindingGroups = emptyList(),
                    preset = listOf(
                        presetEntry(
                            inputSource = InputSource.BUTTON_DIAMOND,
                            state = "inactive",
                            group = groupWith(
                                inputs = listOf(
                                    inputWith(
                                        "button_a",
                                        listOf(activatorWith(
                                            bindings = listOf(binding(BindingOutputType.KEY_PRESS, "X")),
                                        )),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            preset = emptyList(),
        )
        val cfg = ControllerConfig(
            controllerProfile = sampleControllerProfile(),
            actionSets = listOf(setWithLayer),
        )

        val compiled = cfg.toCompiled()
        assertTrue(
            "Inactive layer preset entries don't compile into the active overlay map",
            compiled.sets.getValue(12L).layers.getValue(300L).inputs.isEmpty(),
        )
    }

    @Test
    fun startingActionSetId_isFirstActionSetInGraph() {
        val cfg = configWith(
            actionSet = ActionSet(id = 42L, controllerProfileId = 1L, name = "default", title = "Default"),
            preset = emptyList(),
        )

        val compiled = cfg.toCompiled()

        assertEquals(42L, compiled.startingActionSetId)
        assertEquals(1, compiled.sets.size)
        assertTrue(compiled.sets.getValue(42L).inputs.isEmpty())
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
    fun settingsJson_doubleTapTime_parsedAndPropagated() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    type = ActivatorType.DOUBLE_PRESS,
                                    settingsJson = """{"double_tap_time_ms": 180}""",
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "SPACE")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val activator = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0]

        assertEquals(180L, activator.settings.doubleTapTimeMs)
        assertEquals(CompiledActivatorSettings.DEFAULT_LONG_PRESS_TIME_MS, activator.settings.longPressTimeMs)
    }

    @Test
    fun settingsJson_universalSettings_parsedAndPropagated() {
        // Brick 3.3 universal settings round-trip through the compiler.
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    type = ActivatorType.FULL_PRESS,
                                    settingsJson = """{
                                        "toggle": true,
                                        "hold_to_repeat": true,
                                        "repeat_rate_ms": 80,
                                        "fire_start_delay_ms": 50,
                                        "fire_end_delay_ms": 100,
                                        "cycle_bindings": true,
                                        "interruptable": false
                                    }""".trimIndent(),
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val settings = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0].settings

        assertEquals(true, settings.toggle)
        assertEquals(true, settings.holdToRepeat)
        assertEquals(80L, settings.repeatRateMs)
        assertEquals(50L, settings.fireStartDelayMs)
        assertEquals(100L, settings.fireEndDelayMs)
        assertEquals(true, settings.cycleBindings)
        assertEquals(false, settings.interruptable)
    }

    @Test
    fun settingsJson_chordPartner_parsedAndPropagated() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        inputs = listOf(
                            inputWith(
                                "button_a",
                                listOf(activatorWith(
                                    type = ActivatorType.CHORDED_PRESS,
                                    settingsJson = """{
                                        "chord_partner_source": "BUTTON_DIAMOND",
                                        "chord_partner_key": "button_b"
                                    }""".trimIndent(),
                                    bindings = listOf(binding(BindingOutputType.KEY_PRESS, "SPACE")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()
        val settings = compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0].settings

        assertEquals(InputSource.BUTTON_DIAMOND, settings.chordPartnerSource)
        assertEquals("button_b", settings.chordPartnerKey)
        assertEquals(
            InputAddress(InputSource.BUTTON_DIAMOND, "button_b"),
            settings.chordPartner,
        )
    }

    @Test
    fun settings_toJson_roundTrips() {
        val original = CompiledActivatorSettings(
            longPressTimeMs = 750L,
            doubleTapTimeMs = 220L,
            toggle = true,
            holdToRepeat = true,
            repeatRateMs = 90L,
            fireStartDelayMs = 40L,
            fireEndDelayMs = 60L,
            cycleBindings = true,
            interruptable = false,
            chordPartnerSource = InputSource.DPAD,
            chordPartnerKey = "dpad_north",
        )
        val roundTripped = CompiledActivatorSettings.parse(original.toJson())
        assertEquals(original, roundTripped)
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

    // ── Mode validation (Brick 6.1) ──────────────────────────────────────────

    @Test
    fun buttonPadMode_dropsSubInputsThatArentFaceButtons() {
        // BUTTON_PAD has a strict sub-input vocabulary: button_a/b/x/y only. A misseeded
        // dpad_north under a BUTTON_PAD group should be dropped at compile time so the
        // runtime never sees a phantom address.
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.BUTTON_DIAMOND, state = "active",
                    group = groupWith(
                        mode = BindingMode.BUTTON_PAD,
                        inputs = listOf(
                            inputWith("button_a", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER"))))),
                            inputWith("dpad_north", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "X"))))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        // button_a survives; dpad_north is dropped.
        assertEquals(1, compiled.totalInputCount)
        assertEquals(
            BindingOutput.KeyPress("ENTER"),
            compiled.lookup(InputSource.BUTTON_DIAMOND, "button_a")!!.activators[0].bindings.single(),
        )
        assertEquals(null, compiled.lookup(InputSource.BUTTON_DIAMOND, "dpad_north"))
    }

    @Test
    fun singleButtonMode_acceptsOnlyClick() {
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.LEFT_BUMPER, state = "active",
                    group = groupWith(
                        mode = BindingMode.SINGLE_BUTTON,
                        inputs = listOf(
                            inputWith("click", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "ENTER"))))),
                            inputWith("edge", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "X"))))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        assertEquals(1, compiled.totalInputCount)
        assertNotNull(compiled.lookup(InputSource.LEFT_BUMPER, "click"))
        assertEquals(null, compiled.lookup(InputSource.LEFT_BUMPER, "edge"))
    }

    @Test
    fun unimplementedMode_isPermissiveViaStubMode() {
        // Until 6.2+ implements DPAD/TRIGGER/etc. runtimes, those modes fall through to
        // StubMode and shouldn't drop ANY sub-input. This is what keeps existing seeded
        // data working through the 6.1 cutover.
        val cfg = configWith(
            preset = listOf(
                presetEntry(
                    inputSource = InputSource.DPAD, state = "active",
                    group = groupWith(
                        mode = BindingMode.DPAD,
                        inputs = listOf(
                            inputWith("dpad_north", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "UP"))))),
                            // Even a typo'd key passes through under a stub mode.
                            inputWith("totally_made_up", listOf(activatorWith(bindings = listOf(binding(BindingOutputType.KEY_PRESS, "Z"))))),
                        ),
                    ),
                ),
            ),
        )

        val compiled = cfg.toCompiled()

        assertEquals(2, compiled.totalInputCount)
        assertNotNull(compiled.lookup(InputSource.DPAD, "dpad_north"))
        assertNotNull(compiled.lookup(InputSource.DPAD, "totally_made_up"))
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

    /**
     * Default mode is [BindingMode.BUTTON_PAD] because the bulk of tests in this file
     * bind face buttons; tests that bind other sub-input vocabularies (dpad_north, etc.)
     * pass a matching [mode] so Brick 6.1's compile-path mode validation doesn't drop
     * them. Passing an unimplemented mode (e.g. [BindingMode.DPAD]) is also fine — those
     * fall through to StubMode and accept any sub-input key.
     */
    private fun groupWith(
        inputs: List<GroupInputGraph>,
        mode: BindingMode = BindingMode.BUTTON_PAD,
    ) = BindingGroupGraph(
        group = BindingGroup(id = 1L, actionSetId = 1L, name = "g", mode = mode),
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

    /**
     * Convenience: every test in this file compiles a [ControllerConfig] with one
     * action set, so [CompiledConfig.lookup] always targets that set's id. Cuts the
     * test-side noise of threading the set id through every assertion.
     */
    private fun CompiledConfig.lookup(source: InputSource, inputKey: String): CompiledInput? =
        sets.values.firstOrNull()?.inputs?.get(InputAddress(source, inputKey))

    /** Total compiled inputs across all sets in the snapshot. */
    private val CompiledConfig.totalInputCount: Int
        get() = sets.values.sumOf { it.inputs.size }

    private var nextId = 100L
}
