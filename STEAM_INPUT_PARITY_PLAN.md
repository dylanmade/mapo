# Steam Input Parity for Mapo — Phased Roadmap

## Context

Mapo's `Remap Controls` feature today is a 1-to-1 physical-button → output mapping (16 digital buttons; keyboard / mouse / gamepad outputs). Steam Input is the gold-standard configurability model on the market: it adds **action sets**, **action layers**, **mode-shift**, **input source modes** (joystick variants, mouse modes, scroll, radial / touch menus), **activators** (long / double / start / release / chord / soft press, with turbo, toggle, cycle, delays), and a rich on-disk **VDF profile format** that already has a free Workshop ecosystem of community configurations.

The long-term goal is parity with Steam Input + the ability to import community VDF configurations on Android. This plan is the staged execution of that goal.

**Foundation-first sequencing** is in effect: Phase 1 rebuilds the data model in one shot to express every Steam concept, then later phases bring each Steam feature online on top of it. **Analog stick/trigger capture** is folded into Phase 2 (foundational) so we don't have to rewrite the input pipeline twice. **Radial / touch menu UI** is deferred (Phase 8 lands the data scaffold only — overlay rendering integrates later with virtual keyboards). **VDF import** is scoped to `legacy_set "1"` only (action-based imports need their own action-manifest hosting; deferred indefinitely).

Before any of that, Phase 0 fixes a regression: the existing Remap Controls flow silently drops the user's selection after the dialog → full-screen-destination refactor.

---

## Phase 0 — Fix the Remap Controls persistence regression — ✅ COMPLETED

### Root cause

In `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt`, both `draft` and `editingButton` were held in `remember { ... }` inside the screen composable. When the user tapped **Edit** → navigated to the full-screen `REMAP_TARGET_PICKER`, NavHost removed `RemapControlsScreen` from composition. On return, the screen recomposed from scratch — `editingButton` reset to `null`. The `LaunchedEffect(pickerResult)` fired, but `editingButton?.let { ... }` was a no-op, and the picker result was consumed without ever being written to the draft. The row stayed "Unbound". Regression from the dialog → full-screen refactor (dialogs overlay the parent, preserving `remember`; nav destinations don't).

### Fix (commit-on-select)

Match the virtual-keyboard `ConfigureButtonScreen` pattern: every picker result commits immediately to the database; no draft, no Save button.

- New `GamepadMappingDao.deleteMapping(profileId, button)` query
- New `GamepadMappingRepository.setMapping(profileId, button, target)` — Unbound → delete; concrete → upsert via REPLACE
- New `MainViewModel.setRemapMapping(button, target)` — delegates to the repo for the active profile (no-ops if no active profile)
- `RemapControlsScreen` rewritten:
  - Removed `draft` mutable state map; reads from the live `mappings` prop instead
  - Removed Save icon from the TopAppBar
  - `editingButtonName` uses `rememberSaveable` (String-saved via `DeviceButton.name`) so the in-flight edit survives the picker round-trip
  - On `pickerResult` callback, calls `onPickResult(btn, target)` which dispatches to the VM immediately
- `MainScreen` NavHost: replaced `onSave` wiring with `onPickResult` wiring

### Files touched

- `app/src/main/java/com/mapo/data/db/GamepadMappingDao.kt`
- `app/src/main/java/com/mapo/data/repository/GamepadMappingRepository.kt`
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt`
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (REMAP_CONTROLS destination block only)
- `app/src/test/java/com/mapo/data/repository/GamepadMappingRepositoryTest.kt` (5 new tests for `setMapping`)
- `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelTest.kt` (2 new tests for `setRemapMapping`)

### Verify

1. Open Remap Controls. Edit BUTTON_A → select Keyboard / ENTER. Row immediately shows `ENTER`.
2. Edit BUTTON_B → select Mouse / Left Click. Row shows `Left Click`. (BUTTON_A still ENTER — single-button writes don't clobber others.)
3. Pop back, re-open Remap Controls. Both selections persist.
4. Edit BUTTON_A → Unbound. Row reverts to "Unbound" (DB row deleted).
5. Logcat: `MainViewModel` logs "Pushed N remap entries to dispatcher" after each pick.
6. Robolectric: `./gradlew :app:testDebugUnitTest` — all 7 new tests pass.

---

## Phase 1 — Data model + persistence rebuild

### Progress

Phase 1 is split into four sub-bricks so each lands the build green:

- **1.1 — Schema** ✅ COMPLETED. Entities, enums, DAOs, `SteamTypeConverters`, `AppDatabase` bump to v7, Hilt providers. Old `gamepad_mappings` + `GamepadMappingRepository` still in place; no runtime behavior change yet.
- **1.2 — Repository + seeding** ✅ COMPLETED. `BindingOutput` sealed wrapper, materialized-graph types (`ControllerConfig`/`ActionSetGraph`/.../`ActivatorGraph`), `ControllerConfigRepository` with `ensureSeeded` / `seedDefaultConfig` / `observeActiveConfig` / `getActiveConfigOnce` / `setBinding`. Auto-seeds on first observe. **No legacy translation** — see Deviations.
- **1.3 — UI rewrite** ✅ COMPLETED. `SectionedListDetailPane` reusable master-detail composable with gamepad focus + wraparound. `RemapSections` registry maps the user's Buttons/D-Pad/Triggers/Joysticks/Gyro hierarchy onto the data model. `RemapControlsScreen` rewritten using both, reading from `ControllerConfigRepository` via the new VM surface (`activeControllerConfig` + `setControllerBinding`). Inert Action Set tabs + Layers chip at the top.
- **1.4 — Cleanup** ✅ COMPLETED. Legacy `GamepadMappingRepository` / `GamepadMappingDao` / `GamepadMapping` entity deleted; AppDatabase bumped v7→v8; `MainViewModel` lost its `gampadMappingRepository` field, `activeProfileMappings` flow, `saveRemapMappings`/`setRemapMapping` methods, and the collector that pushed mappings into the dispatcher; `ProfileRepository.duplicateProfile` re-pointed at the new `ControllerConfigRepository.copyConfig`. Test suite updated (deleted: `GamepadMappingRepositoryTest`, `GamepadMappingDaoTest`; updated: `MainViewModelTest`, `ProfileRepositoryTest`; new: 5 `copyConfig` tests in `ControllerConfigRepositoryTest`).

### Deviations from the original schema (decided during 1.1)

- **`group_setting` table removed.** Originally planned as a per-(group, key) row store with `valueJson`. Collapsed into a single `BindingGroup.settingsJson` column instead — matches how `Activator.settingsJson` already works, fewer joins, parser layer is the same code path for both. Net: 9 tables instead of 10. Sealed-class parsing in the repository layer makes the table-vs-column choice invisible upstream, so this is safe to revisit later if we ever need queryable settings (we don't).
- **`BindingGroup` ownership uses two nullable FKs** (`actionSetId?` + `actionLayerId?`) rather than a polymorphic `(ownerKind, ownerId)` pair. Real FKs + cascades work; polymorphic FKs don't. Repository invariant: exactly one is non-null.
- **Schema bump uses existing `fallbackToDestructiveMigration(dropAllTables = true)`.** No hand-written Room `Migration` block needed.
- **Legacy `gamepad_mappings` data is wiped on v6→v7 upgrade** (decided in 1.2). Pre-release stance ([memory: project_mapo_pre_release.md](../.claude/projects/-Users-dylanbperry-projects-mapo/memory/project_mapo_pre_release.md)) said destructive OK; the user confirmed they're fine re-creating any test profiles. The repository's `ensureSeeded` path is the only seed mechanism — there is no migrator that reads old rows. If a release-ready migration path is ever needed, write a real Room `Migration(6, 7)` and the repository can stay as-is.
- **`BindingGroup.settingsJson` defaults to `"{}"`** at seed time. Mode-specific settings (deadzones, sensitivities, etc.) parse from this string in later phases; for brick 1.2 every group has empty settings and a single default sub-input set per mode.
- **Default seed excludes trackpads, back paddles, gyro.** The schema supports them (for VDF import compatibility) but Generic Android doesn't expose them, so seeding configurable-but-never-fireable groups would just be UI clutter. Imports can still create groups for these sources.

#### Brick 1.3 deviations + decisions

- **Master-detail layout is custom, not `ListDetailPaneScaffold`.** M3's adaptive scaffold is designed for phone↔tablet adaptation; we always want both panes. Built a `SectionedListDetailPane` composable in `app/src/main/java/com/mapo/ui/component/layout/` instead — 30/70 split, M3-styled rail (`surfaceContainer` background, `surfaceContainerHighest` for selection), gamepad focus with wraparound, cross-pane focus handoff via shared `FocusRequester`.
- **Section hierarchy reorganized from Steam's input-source taxonomy** into Buttons (face + bumpers + menu) / D-Pad / Triggers / Joysticks / Gyro. Lives in `RemapSections.kt` as a typed registry, not in the data layer — section grouping is a UI concept and the user wants flexibility to evolve it independently.
- **Soft Pull / Analog Output Trigger / Behavior dropdowns / Gyro section ship as disabled placeholders.** Visible in the UI now (so the eventual feature lands without re-doing layout); none have wire-up. SOFT_PRESS activator is Phase 3, behavior-dropdown mode picker is Phase 6, gyro requires Phase 2 motion capture + AYN Thor IMU validation.
- **Back / Home buttons are NOT added to `DeviceButton` / `InputSource`.** They're inconsistent across Android devices and the user explicitly opted out of including them in 1.3's data layer. Revisit when device-specific capability detection lands.
- **Action Set tab strip + Layers chip render at the top, fully disabled.** Tab clicks no-op until Phase 4 plumbs `CHANGE_PRESET`; the Layers chip is just a `(no layers)` AssistChip until Phase 5.
- **The picker stays unchanged.** `RemapTargetPickerScreen` still speaks `RemapTarget`. The bridge happens in `RemapControlsScreen` via `BindingOutput.toRemapTarget()` (opening picker) and `BindingOutput.fromRemapTarget()` (consuming result). New picker categories for `GameAction` / `ControllerAction` / `ModeShift` get added when those phases land.
- **`GamepadMappingRepository` + the legacy `setRemapMapping` VM method stay in place during 1.3.** Brick 1.4 deletes them once nothing references them outside tests.

#### Brick 1.4 deviations + decisions

- **Runtime input dispatch is intentionally dead between brick 1.4 and Phase 2.** Removing the legacy `activeProfileMappings → inputDispatcher.setCurrentMappings` collector means physical remaps don't fire at runtime until Phase 2's evaluator reads bindings directly from `ControllerConfigRepository`. The user confirmed this gap is acceptable; bindings still persist correctly through the editor, they just don't drive output yet.
- **No interim adapter built.** Considered mirroring new-schema writes into the legacy table to keep the dispatcher alive across the gap, but it'd be throwaway code on the wrong side of the deletion, and Phase 2 lands soon. Pre-release status ([memory: project_mapo_pre_release.md](../.claude/projects/-Users-dylanbperry-projects-mapo/memory/project_mapo_pre_release.md)) means no end-user impact.
- **`ProfileRepository.duplicateProfile`'s `copyMappings` call replaced with `ControllerConfigRepository.copyConfig`** — deep-clones the full controller_profile → action_set → action_layer → binding_group → group_input → activator → binding + preset_binding + game_action graph with fresh autogenerated PKs at every level. Honors [`feedback_duplicates_own_their_data`](../.claude/projects/-Users-dylanbperry-projects-mapo/memory/feedback_duplicates_own_their_data.md): the duplicate is fully independently addressable; editing the copy never bleeds back into the source. Verified by the new `copyConfig_editsToDestDoNotAffectSource` test.
- **AppDatabase v7→v8 bump.** `fallbackToDestructiveMigration(dropAllTables=true)` handles the entity removal (legacy `gamepad_mappings` table); no migration code written, matching the rest of Phase 1's destructive-OK stance.
- **`DeviceButton` model class kept.** Still used by the virtual-keyboard `GridButton` gesture system (unrelated to physical remap). `RemapTarget` likewise stays — both are loadbearing for the on-screen keyboard layouts.

### Goal

Replace today's flat `gamepad_mappings(profileId, gamepadButton, targetEncoded)` table with a hierarchy that can express every Steam Input concept. Pre-release status means destructive schema changes are OK; we wipe and reseed on upgrade.

### New schema (Room)

| Entity | Purpose | Key columns |
|---|---|---|
| `controller_profile` | A profile's binding configuration for one controller type (Steam Deck, Xbox, PS4, Generic-Android). One profile can hold N; one is `active`. | `id`, `profileId` (FK Profile), `controllerType`, `name`, `legacySet` (Bool) |
| `action_set` | Mutually-exclusive logical grouping. ≥1 per controller_profile. | `id`, `controllerProfileId`, `name`, `title`, `legacy` (inherits from controller_profile) |
| `game_action` | Action-based mode only (legacy=false). The set's action namespace. | `id`, `actionSetId`, `category` (StickPadGyro / AnalogTrigger / Button), `name`, `inputMode` (joystick_move / absolute_mouse for StickPadGyro), `localizationToken` |
| `action_layer` | Stacking overlay on an action_set. | `id`, `parentActionSetId`, `name`, `title` |
| `binding_group` | A binding payload with a `mode`. Lives under either an action_set (base) or an action_layer (override). Mode-specific settings (deadzones, sensitivities, etc.) live in `settingsJson`. | `id`, `actionSetId` (nullable FK), `actionLayerId` (nullable FK), `name`, `mode`, `settingsJson` |
| `group_input` | A sub-input within a binding_group (e.g., `dpad_north`, `click`, `edge`, `touch_menu_button_3`). | `id`, `bindingGroupId`, `inputKey` |
| `activator` | One activator on a group_input. Multiple per input allowed. | `id`, `groupInputId`, `type` (Full / Soft / Long / Double / Start / Release / Chord), `settingsJson`, `orderIndex` |
| `binding` | One output binding on an activator. Multiple per activator allowed (cycle_binding). | `id`, `activatorId`, `outputType` (key_press / xinput_button / mouse_button / mouse_wheel / game_action / controller_action / mode_shift), `args`, `label`, `iconRef`, `orderIndex` |
| `preset_binding` | Maps physical input source → binding_group, state-qualified. | `id`, `actionSetId`, `inputSource`, `state` (active / inactive / modeshift, comma-joined per Steam), `bindingGroupId` |

Settings live in JSON columns (`binding_group.settingsJson` and `activator.settingsJson`) — typed at the Kotlin layer by sealed-class wrappers in the repository. The user almost always edits a whole settings panel at once, so the JSON denormalization wins on write frequency, and a uniform pattern across the two settings stores keeps the parser surface small.

### Domain models + repositories

- New Kotlin domain models under `app/src/main/java/com/mapo/data/model/steam/`:
  - `ControllerProfile`, `ActionSet`, `ActionLayer`, `BindingGroup`, `GroupInput`, `Activator`, `Binding`, `PresetBinding`, `GameAction`
  - Sealed-class settings types: `GroupSettings` (per-mode), `ActivatorSettings` (universal + type-specific), `BindingOutput` (typed args)
- DAOs per entity under `app/src/main/java/com/mapo/data/db/steam/`
- Repository: `ControllerConfigRepository` exposes `getActiveConfig(profileId): Flow<ControllerConfig>` returning a materialized graph (controller_profile + all sets + all layers + all groups + all inputs + all activators + all bindings + all preset_bindings). Use Room's `@Relation` / `@Transaction` for one-shot reads; emit a precompiled in-memory form (`CompiledConfig`) for the runtime evaluator (Phase 2).

### Migration

- Drop `gamepad_mappings`. For each existing `Profile`, seed:
  - 1 `controller_profile` (controllerType = `controller_generic_android`, legacySet = true)
  - 1 `action_set` named "Default"
  - 1 `binding_group` per non-Unbound existing mapping, mode = `single_button`, one `group_input` `click`, one `activator` type=`Full_Press`, one `binding` translated from the existing `RemapTarget`
  - `preset_binding` rows assigning each generic input source to its corresponding group
- Migration runs in a Room `Migration` block on first launch after upgrade. One-time toast: "Updated binding format — your mappings have been preserved."

### Existing `RemapTarget` becomes `BindingOutput`

`RemapTarget` (Keyboard / Mouse / Gamepad / Unbound) is the legacy 1-to-1 wrapper. Phase 1 keeps `RemapTarget` for the existing virtual-keyboard `GridButton.onTap/onDoubleTap/onHold` fields (unrelated to physical remap), but introduces `BindingOutput` as the richer Steam-compatible form. A small adapter converts between them at the keyboard-dispatch boundary.

### UI for Phase 1

The Remap Controls top-level screen evolves from a flat list of 16 buttons to a **controller-binding overview** (Steam-style, text-based first):

```
┌─ Remap Controls ──────────────────────── [Save] ─┐
│ Controller: [Default (Generic Android) ▾]        │
│ Action Set: ◉ Default        [+]                 │
│ Layers:    (none)            [+]                 │
│ ─────────────────────────────────────────────    │
│ ▸ Face Buttons (button_diamond)                  │
│     A — ENTER                          [Edit]    │
│     B — ESCAPE                         [Edit]    │
│     X — Unbound                        [Edit]    │
│     Y — Unbound                        [Edit]    │
│ ▸ D-Pad                                          │
│     ...                                          │
│ ▸ Shoulders                                      │
│ ▸ Triggers                                       │
│ ▸ Left Joystick                                  │
│ ▸ Right Joystick                                 │
│ ▸ Switches (Start/Select/etc.)                   │
└──────────────────────────────────────────────────┘
```

Each **section header** is a physical *input source* (Steam's `button_diamond`, `dpad`, `left_trigger`, etc.); under each, the section's group_input list shows current bindings. **Tap Edit** on any row opens the picker (same Phase-0-fixed flow, just plumbed through the new schema).

The Action Set / Layers UI is present-but-empty for Phase 1 — no add/remove yet. They become live in Phases 4 + 5.

### Files

- **Create** `app/src/main/java/com/mapo/data/db/steam/` (full directory)
- **Create** `app/src/main/java/com/mapo/data/model/steam/` (full directory)
- **Create** `app/src/main/java/com/mapo/data/repository/ControllerConfigRepository.kt`
- **Edit** `app/src/main/java/com/mapo/data/db/AppDatabase.kt` — add entities, bump version, add migration
- **Edit** `app/src/main/java/com/mapo/data/repository/GamepadMappingRepository.kt` — deprecate (keep as thin adapter during transition or delete outright)
- **Edit** `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt` — rewrite to the section-grouped layout
- **Edit** `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` — switch from `activeProfileMappings` (legacy) to `activeControllerConfig` flow

### Verify

- Fresh install: one default profile, one default controller_profile, one default action_set, base preset_bindings for every physical input source.
- Existing install (with mappings from before): migration produces equivalent bindings; logcat shows "Migrated N legacy mappings".
- Picking ENTER for BUTTON_A still drives the accessibility service correctly — the runtime path uses the *compiled* config, but for a single Full_Press + key_press it's a no-op behavior change.
- Robolectric tests: round-trip save+load of a config with multiple sets / layers / groups / activators / bindings.

---

## Phase 2 — Input pipeline upgrade (analog capture + runtime evaluator)

### Progress

Phase 2 ships as three sub-bricks (one moved out — see 2.3 below):

- **2.1 — CompiledConfig + plumbing** ✅ COMPLETED. New `CompiledConfig` / `InputAddress` / `CompiledInput` / `CompiledActivator` types + `ControllerConfig.toCompiled()` compiler under `app/src/main/java/com/mapo/service/input/CompiledConfig.kt`. `InputDispatcher.compiledConfig` StateFlow added alongside the legacy `currentMappings` (not replacing it yet); `MainViewModel` collects `activeControllerConfig` and publishes compiled snapshots into the dispatcher. Compiler tests under `app/src/test/java/com/mapo/service/input/CompiledConfigTest.kt` cover round-trip, state-filter, multi-activator preservation, cycle-binding order, and missing-address null. Nothing fires at runtime yet — that's brick 2.2.
- **2.2 — InputEvaluator + OutputEmitter (digital, FULL_PRESS-only)** ✅ COMPLETED. Built `OutputEmitter` (BindingOutput → dispatcher calls with press/release semantics for key+gamepad, fire-and-done for mouse/wheel, log-stubs for game_action/controller_action/mode_shift). Built `InputEvaluator` (FULL_PRESS-only state machine, `held: Map<InputAddress, List<BindingOutput>>` tracking pressed bindings, with defensive guards for duplicate DOWN and config-change-while-held). Extended `InputSink` + `InputDispatcher` with string-taking `injectKeyDown`/`injectKeyUp`. `InputAccessibilityService` injects the evaluator, exposes a `KEYCODE_TO_INPUT_ADDRESS` map matching the default seed, and `onKeyEvent` now routes every gamepad keycode through `evaluator.handleDigital(...)`. 24 new tests across `OutputEmitterTest` and `InputEvaluatorTest`. **Physical button remap is alive again on the new schema.**
- **2.3 — Analog capture** ⏭ DESCOPED to Phase 6. The original plan assumed we could override `onGenericMotionEvent` on `AccessibilityService`; that method **does not exist on `AccessibilityService`** — it's only on `View`. Motion events from controllers flow to focused views, not accessibility services. Practical impact is minimal: digital `KEYCODE_BUTTON_L2`/`R2` and `KEYCODE_DPAD_*` already cover triggers + dpad on the Thor (user-verified — L2 → MOUSE_RIGHT fires correctly in a native game). The first feature that genuinely needs analog values is `JOYSTICK_MOVE` mode (Phase 6), so we'll design the motion-capture mechanism then. See Phase 6 notes + Phase 3 note about `SOFT_PRESS` deferral.
- **2.4 — Cleanup** ✅ COMPLETED. Deleted `InputDispatcher.currentMappings` + `setCurrentMappings` + the unused `DeviceButton` import; deleted `InputAccessibilityService.dispatchRemapTarget` (no longer reachable). `GAMEPAD_KEYCODE_MAP`/`DEVICE_BUTTON_TO_KEYCODE` stay alive — still used by `dispatchTargetAsClick` for trackpad-gesture → gamepad-button output in the virtual keyboard (legacy `RemapTarget.Gamepad` shape, unrelated to physical remap).

#### Brick 2.1 deviations + decisions

- **Layer overrides not yet honored.** Phase 2.1 compiles only the base action set; layers come online in Phase 5. The compiler comment marks this explicitly.
- **State qualifier filter is exact-match "active".** Real Steam configs comma-join state qualifiers (`"active,modeshift"`) but our seed never produces those; defer compound-state parsing to whenever a config actually carries one.
- **`CompiledConfig.EMPTY` is a shared singleton.** Cheaper than re-allocating for null configs (which happens transiently during seeding). Also exported as the dispatcher's default so the service never sees a null compiled config.
- **The compiler is a pure extension function**, not a method on the repository. Compilation runs on the same dispatcher the VM uses (the collector's coroutine); the materialized graph is already in memory by the time it's called.

#### Brick 2.2 deviations + decisions

- **Press semantics differ by output kind, returned by `OutputEmitter.emitPress(): Boolean`.** Key/gamepad outputs need a matching UP edge (return `true`, evaluator persists in its held set). Mouse buttons + wheel are fire-and-done via the existing accessibility gesture path (return `false`, no release tracking). GameAction/ControllerAction/ModeShift log-and-drop (return `false`). This shape keeps the evaluator's held-set rule simple — "hold what the emitter said to hold" — and matches the existing single-shot mouse semantics already in place for trackpad gestures.
- **`KEYCODE_TO_INPUT_ADDRESS` lives next to the existing `GAMEPAD_KEYCODE_MAP` in `InputAccessibilityService.companion`.** Considered putting it under `service/input/` but the keycode integers are an Android-API concept (`KeyEvent.KEYCODE_*`), and the parallel `DeviceButton`-keyed map is already there. The two maps will diverge or merge in Phase 6 when analog modes change which sub-input a stick click resolves to; keeping them adjacent makes that future audit easier.
- **`onKeyEvent` consumes the event when an address matches, even if no FULL_PRESS activator fires.** The address matched a configured input in the user's profile — the press shouldn't also leak through to the foreground game. Phase 3 will refine this further (a LONG_PRESS waiting for its threshold conceptually wants to swallow the press until it can decide).
- **Held-set lookup on release uses the snapshot of what was pressed, not a re-resolution against the current config.** Config edits while a key is held would otherwise orphan the DOWN edge; the existing behavior matches what Steam Input does on Deck.
- **Old `dispatchRemapTarget` (private) and `currentMappings` (dispatcher) still in place** — only the call sites that fed them from `onKeyEvent` are gone. Brick 2.4 deletes the rest. Keeping them alive across 2.2 → 2.3 means analog capture (2.3) can land without touching the dead code.
- **`InputAccessibilityService` is `@AndroidEntryPoint`** so Hilt field-injects `InputEvaluator` directly — same pattern as the existing `InputDispatcher` and `ForegroundAppMonitor` injections. No new module wiring needed.

#### Brick 2.3 descope + decisions

- **Root cause:** original plan assumed `AccessibilityService.onGenericMotionEvent` existed. It doesn't — that's a `View`-level callback. Probe compile confirmed: `'onGenericMotionEvent' overrides nothing`.
- **Available paths for future motion capture** (when Phase 6 needs them):
  - Attach a `TYPE_ACCESSIBILITY_OVERLAY` view (the project already has `OverlayManager`) and listen on `View.onGenericMotionEvent`. Adds an always-on overlay window; preferred approach when we actually need analog.
  - Hidden `InputManager` reflection. Higher risk, breaks on Android updates. Last resort.
- **What we lose by deferring:** nothing immediate on the Thor — every digital fallback (BUTTON_L2/R2/DPAD_*) verified working. `SOFT_PRESS` activator (Phase 3) and `JOYSTICK_MOVE`/`JOYSTICK_CAMERA`/`MOUSE_JOYSTICK`/`ABSOLUTE_MOUSE` modes (Phase 6) move with the capture work.
- **What we gain by deferring:** when we do build motion capture, we'll know the *exact* shape required (which axes, what threshold model, what deadzone settings) instead of guessing during foundation work.

#### Brick 2.4 deviations + decisions

- **`GAMEPAD_KEYCODE_MAP` + `DEVICE_BUTTON_TO_KEYCODE` retained.** Still needed by `dispatchTargetAsClick`'s `RemapTarget.Gamepad` branch (used by virtual-keyboard trackpad gestures that emit a synthetic gamepad button). Renaming to e.g. `VIRTUAL_KEYBOARD_GAMEPAD_KEYCODE_MAP` would be more accurate but creates churn for zero functional gain.
- **No test cleanup required.** Brick 1.4 already removed every test that referenced `currentMappings`/`setCurrentMappings` (the legacy gamepad-mapping pipeline tests). The dispatcher's interface is leaner now but no tests needed updating.
- **Doc comment on `MainViewModel.activeControllerConfig` updated** to remove the "stays empty until Phase 2" caveat — Phase 2 is done, runtime evaluator reads compiled snapshots directly.

### Why now

Steam Input semantics (activators with timing, layer stacks, mode-shift, mode-based interpretation) can't be implemented by tweaking the current `currentMappings: Map<DeviceButton, RemapTarget>` lookup in `InputAccessibilityService.onKeyEvent` — they require a stateful event-driven evaluator (per discrete input event, *not* a frame-rate scan loop). Build it once now, on top of the Phase 1 data model, so every later feature plugs in cleanly.

### Analog capture

Override `onGenericMotionEvent(event: MotionEvent): Boolean` in `InputAccessibilityService` (already has `canRequestFilterKeyEvents="true"`; verify `accessibility_service_config.xml` flag for motion if needed). Capture:

- `AXIS_X`, `AXIS_Y` — left stick
- `AXIS_Z`, `AXIS_RZ` — right stick (Android quirk)
- `AXIS_HAT_X`, `AXIS_HAT_Y` — dpad as analog
- `AXIS_LTRIGGER`, `AXIS_RTRIGGER` — triggers
- `AXIS_BRAKE`, `AXIS_GAS` — alt trigger axes (some pads)

Normalize axis values, apply deadzones from the binding_group's settings, feed into the evaluator. Keep ALL motion event logging.

### Runtime evaluator (`InputEvaluator`)

A new class under `app/src/main/java/com/mapo/service/input/InputEvaluator.kt`. Inputs:

- Current `CompiledConfig` (snapshot from `ControllerConfigRepository`)
- Live physical input state: digital button states, axis values, timestamps
- Active action_set + layer stack + active mode_shifts (state managed inside evaluator)

Per physical event:

1. Resolve which `binding_group` is active for that input source (preset_binding lookup, considering mode_shifts and layer overrides).
2. Resolve which `group_input` matches the sub-input event (e.g., trackpad press at coord X,Y → `click` or `edge`).
3. Run the temporal activator state machine on that input (Phase 3 implements activator types — this phase scaffolds the queue/dispatch mechanism with only `Full_Press` initially).
4. For each fired activator, dispatch its `binding`s through a new `OutputEmitter`.

The `OutputEmitter` replaces the current `dispatchRemapTarget` switch. It handles every Steam binding type:

- `key_press` → InputManager hidden-API key inject (existing path)
- `xinput_button` → same path, mapped to Android gamepad keycodes (existing path)
- `mouse_button`, `mouse_wheel` → accessibility gesture (existing path)
- `game_action` → resolve via the active action_set's action namespace (Phase 4 / not legacy-set) → fall back to a registered output if mapped; otherwise log and drop
- `controller_action` → in-engine verb (add_layer / remove_layer / hold_layer / CHANGE_PRESET / SHOW_KEYBOARD / etc.) — most verbs land in Phases 4–5; this phase implements only the no-op stubs + logging
- `mode_shift` → set state on the evaluator (Phase 5)

### Dispatcher refactor

`InputDispatcher` currently holds `currentMappings: StateFlow<Map<DeviceButton, RemapTarget>>`. Replace with:

- `compiledConfig: StateFlow<CompiledConfig?>`
- The accessibility service hands every key/motion event to `evaluator.handleEvent(...)`; the evaluator calls back into the dispatcher's sink for outputs.

### UI for Phase 2

No new UI. This is plumbing.

### Files

- **Create** `app/src/main/java/com/mapo/service/input/InputEvaluator.kt`
- **Create** `app/src/main/java/com/mapo/service/input/OutputEmitter.kt`
- **Create** `app/src/main/java/com/mapo/service/input/CompiledConfig.kt` (in-memory representation)
- **Edit** `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — add `onGenericMotionEvent`; route both key + motion events through `InputEvaluator`
- **Edit** `app/src/main/res/xml/accessibility_service_config.xml` — verify capability flags (do not flip `canRetrieveWindowContent` — load-bearing for cross-display auto-switch + planned screen-scrape)
- **Edit** `app/src/main/java/com/mapo/service/input/InputDispatcher.kt` — replace `currentMappings` with `compiledConfig`
- **Edit** `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` — publish `CompiledConfig` to the dispatcher
- **Edit** `app/src/main/AndroidManifest.xml` if joystick capability needs declaring

### Verify

- Physical button presses still remap (no regression vs. Phase 1).
- Logcat shows analog axis values streaming when sticks/triggers move (verifies capture).
- Robolectric test: a `CompiledConfig` with one Full_Press binding produces the same emitted output as today for that input.

---

## Phase 3 — Activators

### Scope

The full Steam activator catalog *minus `Soft_Press`*: `Full_Press`, `Long_Press`, `Double_Press`, `Start_Press`, `Release_Press`, `Chorded_Press`. All universal settings (`toggle`, `fire_start_delay`, `fire_end_delay`, `haptic_intensity`, `cycle_binding`) and type-specific (`long_press_time`, `double_tap_time` / VDF `doubetap_max_duration`, `interruptable`, `hold_to_repeat`, `repeat_rate`).

`Soft_Press` requires analog trigger axis values that we can't currently capture from the accessibility service (see Phase 2.3 descope notes). It moves to Phase 6 with the motion-capture work — the activator type stays in `ActivatorType` enum and `RemapControlsScreen`'s disabled placeholder row, just without a runtime impl until Phase 6 lands.

### Evaluator state machine

Per `group_input`, maintain:

- Press/release timestamps (ring buffer for double-tap)
- Pending-activator queue (a `Long_Press` waiting on threshold; a `Double_Press` waiting on the second tap window)
- Active-activator set (currently-firing activators that will release on event or timeout)
- Interruption rules: `Long_Press` interrupts `Full_Press` on the same input unless `Full_Press` is non-interruptable; `Double_Press` blocks single-tap until window expires; chord requires the chord button currently held.

Turbo (`hold_to_repeat`) emits the binding repeatedly while active at `repeat_rate`. `toggle` flips active state on each fire. `fire_start_delay` / `fire_end_delay` shift activation/release timing.

### UI: per-input editor screen

Replaces today's flat picker for digital inputs. Reached by tapping **Edit** on any row in the Phase 1 controller overview:

```
┌─ A Button ──────────────────────────── [Done] ─┐
│ Activators                                     │
│ ─────────────────────────────────────────────  │
│ ▸ Regular Press                       [⋮] [×]  │
│     ENTER                                      │
│ ▸ Long Press · 0.30s                  [⋮] [×]  │
│     ESCAPE                                     │
│ ▸ Double Press · 0.25s                [⋮] [×]  │
│     SPACE                                      │
│                                                │
│         [+ Add Activator]                      │
└────────────────────────────────────────────────┘
```

Each activator row shows: type + key threshold (if relevant) + the binding(s). Tapping an activator opens its detail screen:

```
┌─ Long Press ──────────────────────────── [Done] ─┐
│ Activation                                       │
│   Hold time           [▭▭▭━━━━] 0.30s            │
│   Fire start delay    [━━━━━━━] 0.00s            │
│   Fire end delay      [━━━━━━━] 0.00s            │
│   ◯ Toggle                                       │
│   ◯ Hold-to-repeat (Turbo)                       │
│       Repeat rate     [━━▭▭▭━━] 0.50s            │
│   Interruptable       ◉                          │
│   Haptic intensity    ○ Off ○ Low ◉ Med ○ High   │
│                                                  │
│ Bindings                                         │
│ ▸ ESCAPE                              [⋮] [×]    │
│         [+ Add Binding]                          │
│ Cycle bindings  ◯  (sequence on each fire)       │
└──────────────────────────────────────────────────┘
```

Adding a binding opens the existing `RemapTargetPickerScreen` (with Phase 0's fix applied). Sliders / radio rows use M3 conventions; helper subtext under each slider explains the behavior.

### Files

- **Create** `app/src/main/java/com/mapo/ui/screen/binding/InputEditorScreen.kt` (the activator list)
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/ActivatorEditorScreen.kt`
- **Edit** `app/src/main/java/com/mapo/ui/nav/MapoRoute.kt` — add `INPUT_EDITOR`, `ACTIVATOR_EDITOR` routes
- **Edit** `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — implement the full state machine
- **Edit** `app/src/main/java/com/mapo/data/model/steam/Activator.kt` — flesh out settings sealed class

### Decision points (flag before implementing)

- **Activator ordering UX**: drag-to-reorder vs. fixed order by type — Steam shows them in fixed order. **Decided** (3.1 prep, 2026-05-12): fixed order — Full / Soft / Long / Double / Start / Release / Chord.
- **"Chorded Press" UX**: how the user picks the chord button. **Re-decided** (3.3, 2026-05-13): a listen-for-press capture screen. The accessibility service flips into a `captureMode` while the picker is up; the next physical button DOWN becomes the partner. The earlier direction (use a list/input picker that could include virtual keyboard buttons) was deferred to the post-parity section because the broader virtual-keyboard rework will reshape how virtual buttons participate in chord activators.
- **Activator UX shape** (added during 3.1 prep): each input keeps a *list* of activators (Steam-faithful multi-activator). Per-row layout: `[binding picker] [activator-type dropdown] [⚙ settings]`, with a `[+ Add Activator]` action under the list.

### Brick breakdown

Reordered after 3.1 to put the **per-input editor UI ahead of further runtime bricks**, so each new runtime activator can be authored + verified in-app the same day it ships. Original sequence kept all runtime first.

| Brick | Scope | Status |
|---|---|---|
| 3.1 | Coroutine-scoped scheduling in `InputEvaluator` + `Long_Press` + `Start_Press` + `Release_Press` (each with type-specific settings parsing) | ✅ COMPLETED 2026-05-12 |
| 3.4 | `InputEditorScreen` — per-input activator list UI + repo methods for add/remove/changeType | ✅ COMPLETED 2026-05-12 |
| 3.2 | `Double_Press` window state machine + Full/Double coexistence semantics (hardcoded interruptable=true) | ✅ COMPLETED 2026-05-12 |
| 3.5 | `ActivatorEditorScreen` + per-type settings panels (long_press_time slider, double_tap_time slider) — universal settings as placeholders | ✅ COMPLETED 2026-05-12 |
| 3.3 | `Chorded_Press` (physical partner) + universal settings (toggle, turbo / `hold_to_repeat`, `fire_start_delay` / `fire_end_delay`, `cycle_binding`, `interruptable`) | ✅ COMPLETED 2026-05-13 |
| 3.6 | **Multi-command authoring** per activator (`[+ Add Command]` list under each activator row) — required for `cycle_bindings` to be user-exercisable. Steam exposes this as "Cycle Commands" with sub-command rows; same data shape as Mapo's. Data model and runtime were ready since Phase 1 / Brick 3.3; only the UI was missing. | ✅ COMPLETED 2026-05-13 |

### Brick 3.6 deviations + decisions

- **User-facing terminology: "binding" → "command"** per user direction. The word "input" was first floated and rejected because the codebase already uses `Input*` throughout for *physical* input (InputAddress, InputEvaluator, InputEditorScreen, GroupInput). "Command" reads natively, has no clash, and matches Steam's "Cycle Commands" UX. **Code identifiers stay `Binding` / `BindingOutput` / `addCommand` / `setCommand`** — the rename is UI-text-only. New repo methods deliberately named `addCommand` / `removeCommand` / `setCommand` (not `addBinding` etc.) to signal the user-facing concept in API shape too.
- **Activator always has ≥1 command** invariant maintained by `addActivator` (seeds one Unbound binding) + UI guard (the [×] on each command row is only enabled when `bindings.size > 1`). `removeCommand` itself doesn't refuse — the UI is the enforcer. Tests cover the multi-row case.
- **Picker round-trip now targets bindingId.** Previously `onPickResult(activatorId, output)` replaced all bindings on the activator. Now `onPickResult(bindingId, output)` writes to one specific Binding row via `setCommand`. The legacy `setBinding(activatorId, output)` still exists but is unused by the multi-command UI; kept around for callers that haven't migrated.
- **`[+ Add Command]` is a `TextButton` (subordinate visual weight) vs `[+ Add Activator]`'s `FilledTonalButton` (top-level).** Conveys hierarchy: commands belong to an activator, activators belong to an input.
- **No reorder UI in 3.6.** `orderIndex` is preserved in the DB and respected by the runtime cycle, but the user can't drag-reorder commands today. Files as a future polish if cycle ordering becomes a common edit. Steam-faithful: Steam doesn't expose drag-reorder either; commands cycle in insertion order.
- **String renames in `ActivatorEditorScreen`**: "Cycle bindings" → "Cycle commands"; helper text for `hold_to_repeat` / `fire_start_delay` / `fire_end_delay` swaps "binding" → "command"; the helper line under each CommandRow says "Tap to choose what this command emits."
- **Tests**: 3 new repo tests (addCommand returns id + appends at next orderIndex; setCommand updates a specific row only; removeCommand targets one row only) + 1 new screen test (Add Command button fires the callback with the right activatorId). Existing InputEditor + ActivatorEditor screen tests updated for the new callback signature.

### Brick 3.3 deviations + decisions

- **Universal settings ship as live controls**, replacing the 3.5 placeholder rows. `SettingsSwitchRow` (interactive Switch + label + helper subtext, whole-row tap target) replaces `ComingSoonRow`. Sliders for `repeat_rate_ms` / `fire_start_delay_ms` / `fire_end_delay_ms` use the same `TimingSlider` pattern as 3.5 (onValueChangeFinished commit, 10 ms rounding, local draft state). The repeat-rate slider only renders when `holdToRepeat=true` — keeps the panel uncluttered when turbo is off.
- **Defaults match Steam Deck**: `interruptable=true`, `repeat_rate_ms=150`, everything else off/0. `interruptable=true` preserves 3.2's deferral behavior for FULL+DOUBLE and now (3.3 follow-up after user-reported gap on 2026-05-13) also generalizes to FULL+LONG: a Regular Press with `interruptable=true` is deferred whenever ANY longer-duration activator coexists. If the longer activator fires, Regular is suppressed; if the user releases before its threshold, Regular fires retroactively as a tap. New `longPressDeferrals` map tracks the FULL deferred by LONG; suppression happens in the LONG timer callback, retroactive tap happens in `onRelease`. **Known limitation**: when FULL + LONG + DOUBLE all coexist on one input, the deferral routes through DOUBLE's window first; if the DOUBLE window expires before LONG threshold, FULL fires as it always did in 3.2 — LONG can't suppress an already-late-fired Regular in that path. Triple-coexistence is unusual config; revisit if a user hits it.
- **Runtime architecture: per-activator `ActivatorRuntimeState`** (toggle latch + bindings, repeat job, fire-start-delay job, cycle index) keyed by `activatorId`. Keyed by activator (not address) so two inputs sharing one activator via Phase 6 reference groups would correctly share state — forward-compat, not load-bearing today.
- **Toggle latches survive UP and config swaps.** UP is suppressed while toggled-on; the next fire (same activator) releases the bindings. Deliberate: matches Steam, and the user's mental model is "the latch is the user's state, not transient event state." Force-release (duplicate-DOWN guard, partner UP on chord) does NOT clear toggle — only a toggle-fire does.
- **`fire_start_delay` cancels on release before elapsed.** `onRelease` cancels any in-flight start-delay job. Without this, a user could press-then-release inside the delay and still get the binding fired late.
- **`fire_end_delay` snapshotted at press time** into the `HeldEntry`. A config swap between press and release uses the press-time value — matches the existing 3.2 invariant that release semantics are frozen at press.
- **`hold_to_repeat` (turbo) pulses as DOWN+UP back-to-back at `repeat_rate_ms`.** The initial fire is still a held press; turbo adds the pulses on top. Cancelled cleanly on UP (or on toggle-off). 10 ms minimum repeat rate via `coerceAtLeast` so a misconfigured 0 ms doesn't busy-loop.
- **`cycle_bindings` advances index even on `emitTap`** so `START_PRESS`/`RELEASE_PRESS`/window-expired-tap all participate. Index wraps via modulo on the binding count; if bindings change underneath the user, the next fire is clamped into range. The MULTI-binding authoring UI for cycle_bindings lives outside 3.3's scope (single-binding picker is what InputEditor exposes today); the runtime is ready when the authoring lands.
- **`Chorded_Press` is order-sensitive** (Steam-faithful): chord input must be pressed AFTER partner is held. Pressing chord first and then partner does NOT retroactively fire. The chord output releases when EITHER the chord input OR the partner releases — verified via dedicated tests.
- **Listen-for-press chord partner picker** (not list-picker, per user direction). New `captureMode: StateFlow<Boolean>` + `capturedInputs: SharedFlow<InputAddress>` on `InputDispatcher`. While the picker is up, the accessibility service consumes physical key events and forwards them to the SharedFlow instead of running the evaluator. `ChordPartnerPickerScreen` sets capture mode true on enter (via `DisposableEffect`) and false on dispose — even if the OS yanks the screen unexpectedly, remap resumes.
- **Chord partner result delivery via `savedStateHandle`**: picker writes `"<source>|<inputKey>"` to `CHORD_PARTNER_RESULT_KEY` on the previous back-stack entry and pops; `ActivatorEditorScreen`'s route handler observes it, applies via VM (preserving other settings via `current.copy(...)`), then clears the savedStateHandle entry to prevent re-application on recomposition.
- **Virtual-keyboard chord partners deferred** to the post-parity section. User noted that "I eventually want to expand virtual keyboards so they can be used as on-screen overlays, and that expansion may change how virtual buttons could/should be used with chorded inputs" — the design call belongs to that broader virtual-keyboard direction, not 3.3.
- **`CHORDED_PRESS` removed from `UNIMPLEMENTED_ACTIVATORS`**; only `SOFT_PRESS` remains as the gated type (Phase 6 analog).
- **Tests**: 12 new InputEvaluator tests (6 universal-settings + 4 chord + 2 toggle paths) + 3 new CompiledConfig parse / round-trip tests. All 64 unit tests pass.

### Brick 3.5 deviations + decisions

- **Universal settings are placeholders that visibly say "Coming in 3.3."** Every universal row (toggle, hold-to-repeat, fire_start_delay, fire_end_delay, cycle_binding, interruptable) renders as a disabled Switch with the typical label/helper-subtext layout but with `alpha = 0.6f` and a `Coming in 3.3` micro-label underneath. Lets the layout land now so the 3.3 diff is "remove alpha + the Coming-soon line + wire the control to state." Setting cog enables for *every* activator type — even ones that won't have type-specific sliders — because the universal panel is universally relevant.
- **`Interruptable` is type-gated** (FULL / RELEASE only), matching Steam's docs. Per `reference_steam_input_activators.md` it's a type-specific setting on Regular and Release Press, not universal.
- **`SOFT_PRESS` and `CHORDED_PRESS` show only the universal panel** (no type-specific section). The screen still opens cleanly when the user has selected one of those types — the placeholder universal section is the message.
- **Sliders commit on `onValueChangeFinished`, not `onValueChange`.** Per-frame writes would hammer the DB during drag and aren't the actual cadence of user intent (the lift-off is the commit edge). The dragged value lives in local Compose state keyed off the persisted value so a re-navigation or external write resets the slider cleanly.
- **Slider values round to 10 ms granularity** on commit. Clean storage; no `423.7…` ms persisted from a continuous slider. Min/max enforced via `.coerceIn`.
- **`CompiledActivatorSettings.toJson()`** is the inverse of `parse()`. Default-valued fields are still written so the row round-trips byte-stably. Unknown keys from the prior stored JSON aren't preserved — that fidelity isn't needed yet; if it becomes needed for VDF round-tripping in Phase 7, we'll switch to a merge-write pattern.
- **`Column + verticalScroll` over `LazyColumn`.** The section count is small and fixed (max ~8 items); LazyColumn's below-the-viewport lazy composition broke a Robolectric `assertExists()` on the FULL_PRESS test because the Interruption section didn't compose. Column is more appropriate per `feedback_robolectric_compose_pitfalls.md`.
- **Repository write is the verbatim JSON string** (`updateActivatorSettings(activatorId, settingsJson)`) rather than the typed settings object. Keeps the repo layer free of any `service.input` dependency; the VM is the only place that calls `settings.toJson()`. Slight repetition of the JSON shape in tests (full literal-string assertions instead of structural ones) accepted as a tradeoff.
- **`[+ Add Binding]` was in the original Phase 3 mockup but did not ship in 3.5.** Each activator still owns a single binding via `InputEditorScreen`'s row. The data model (Phase 1) and runtime (3.3 `cycle_bindings`) both support multi-binding-per-activator — only the authoring UI is missing. Filed as Brick 3.6 above; without it, `cycle_bindings` is functional but has nothing to cycle through.

### Brick 3.2 deviations + decisions

- **Hardcoded `interruptable=true` for Full_Press when Double_Press coexists** (Steam's default). The proper read of `interruptable` off `CompiledActivatorSettings` lands in Brick 3.3. For 3.2, Regular gets deferred whenever Double exists on the same input — matches what user verified on Steam Deck.
- **Deferred-Regular fires either as a tap or as held depending on physical state at window expiration.** Steam-faithful: if the user releases the button before the double-tap window closes, Regular fires as a tap (DOWN+UP back-to-back); if the user is still holding the button at expiration, Regular emits its DOWN edge and lands in `held` so the eventual UP releases it normally. There's a small late-binding cost in the "held past window" case (Regular's DOWN arrives ~250 ms late by default) — accepted as parity behavior.
- **Double_Press second-tap-window timing is keyed off `doubleTapTimeMs`** (default **190 ms**, matching what user observed as Steam Deck's actual default on 2026-05-12 — adjusted from the initial 250 ms guess). The VDF spelling is the famous `doubetap_max_duration` typo; Phase 7 (VDF import) will map it on the way in. We store the value in our settings JSON with the correct `double_tap_time_ms` key so the data layer can be free of the typo.
- **Other activator types fire independently during the Double window.** Long_Press still schedules its threshold timer; Start_Press still fires on the first DOWN; Release_Press still fires on first UP. Steam's interruption rules nominally would have Long_Press also block Regular (and Double, in some cases), but the full priority/interruption matrix lands in 3.3.
- **Triple-tap starts a fresh sequence.** On the third DOWN — past the window of the second-tap-hold — the address has no active window. We open a new Double window. This makes "tap-tap-pause-tap" behave reasonably: first two fire Double, third becomes a new deferred-Regular candidate. UX-tested only via unit tests so far.
- **`doubleTapWindows` does NOT need to coexist with `held` for the same address simultaneously.** First-tap-in-window → no `held` entry yet (Regular deferred); second-tap-hold → `held` entry for Double's bindings, no window; deferred-Regular fires held → window cleared, `held` entry for Regular. The state machine never overlaps, which keeps cleanup simple.
- **InputEditorScreen** removes DOUBLE_PRESS from the "Coming soon" set in `UNIMPLEMENTED_ACTIVATORS`. The dropdown now treats Double like a fully-supported type.
- **Verified in tests** (not yet in-game — user verification step): all 7 new evaluator scenarios (Double-only single/double/late-second, Regular+Double single tap/double tap/held-past-window) + 1 settings-parse test. 50 unit tests total green.

### Brick 3.4 deviations + decisions

- **Brick order reshuffled** (see table above). 3.1 (runtime) couldn't be verified end-to-end without an authoring UI — the existing Remap Controls flow only knows how to bind a FULL_PRESS. Inserted 3.4 next so 3.1's Long/Start/Release land verifiable. Subsequent runtime bricks slot back in afterwards (3.2 → 3.5 → 3.3).
- **Picker round-trip moved into `InputEditorScreen`**. Tapping an input row on `RemapControlsScreen` now navigates to `INPUT_EDITOR(inputSource, groupInputKey)`. The activator picker (`RemapTargetPickerScreen`) is invoked from inside the editor; its result lands on the editor's `savedStateHandle`, not the controls screen's.
- **Row preview shows "+N more"** when an input has multiple activators. The primary line still shows the FULL_PRESS binding, but the user gets a glanceable hint that there's more under the row before they tap in.
- **Settings cog is a placeholder** (disabled `IconButton(Icons.Filled.Settings)`). 3.5 wires it to the per-activator settings sheet. Visible-now so layout doesn't shift when 3.5 lands.
- **"Coming soon" labels** on Double / Chord / Soft in both the type dropdown and the row's helper-subtext. The user can change an activator's type to one of these — the row will save, but the evaluator will treat it as "consumed but no emission" per the 3.1 brick. Prevents the affordance from feeling broken when 3.2/3.3/Phase 6 land.
- **Canonical activator order** (Full / Soft / Long / Double / Start / Release / Chord) is enforced in `ACTIVATOR_RENDER_ORDER` for both display sort and dropdown entries. Within the same type, sorts by `orderIndex` then `id` for stable order across edits.
- **`addActivator` seeds an Unbound binding**. Otherwise the new row would have nothing to display in the binding-picker button and tapping it would be a noop — adding a row makes it immediately editable instead.
- **`ActivatorType.displayLabel()`** lives in `InputEditorScreen.kt` for now; if 3.5 (or VDF import in Phase 7) needs the same labels they migrate to `data/model/steam/SteamEnums.kt` then.
- **Repository methods bump `configDirtyTick`** like the existing `setBinding`. The `compiledConfig` flow in `MainViewModel` recompiles automatically, so a Long-Press authored in the editor takes effect on the next button press — no service restart needed.
- **Row click on RemapControlsScreen** uses `groupInput != null` as the readiness gate instead of "has a FULL_PRESS activator." The new editor handles missing/empty activator lists gracefully (still shows "Add Activator"), so we don't need to gate the screen entry on having an activator already.

### Brick 3.1 deviations + decisions

- **`CompiledActivatorSettings` is a data-class bag, not a sealed class.** Adopted to keep additive growth across 3.x bricks frictionless. Every field has a Steam-default that's used when settingsJson is empty or malformed. 3.1 surfaces `longPressTimeMs` (default 600 ms); 3.2 will add `doubleTapTimeMs`; 3.3 will add universal settings + chord partner.
- **Parser uses `org.json.JSONObject`**. Android stdlib, no dep added. Production runs on Android so it Just Works; **JVM unit tests need Robolectric** because the Android stub jar returns `false` from `has(key)` and silently defeats the parser. `CompiledConfigTest` annotated `@RunWith(RobolectricTestRunner::class)` accordingly.
- **`InputEvaluator` gains `@ApplicationScope CoroutineScope`** for scheduling. Pending timers stored as `Map<InputAddress, Map<Long, Job>>` (per-address, per-activator). Cancelled on UP and on duplicate-DOWN.
- **`held` state refactored to per-activator records.** Was `Map<InputAddress, List<BindingOutput>>`; is now `Map<InputAddress, MutableList<HeldEntry>>` where each `HeldEntry` carries its source activatorId. Required because Long_Press + Full_Press can both contribute holdable bindings to the same address; release needs to fire both sets.
- **`START_PRESS` / `RELEASE_PRESS` are taps**, not held bindings. Evaluator calls `emitter.emitPress(b); emitter.emitRelease(b)` back-to-back. For mouse-button outputs (fire-and-done), the release call is a no-op — the existing `emitPress` short-circuit handles it.
- **Coexistence default**: a button with both `FULL_PRESS` and `LONG_PRESS` fires both when held past the threshold (FULL on DOWN, LONG on threshold). Steam's `interruptable` setting that suppresses FULL when LONG fires is universal-setting territory and lands in Brick 3.3.
- **Long-press default is 600 ms** (Steam Input's default). Override per activator via `long_press_time_ms` in settingsJson.
- **Test infrastructure**: `kotlinx-coroutines-test` `TestScope` + `StandardTestDispatcher` injected into the evaluator. `advanceTimeBy` / `runCurrent` drive virtual time. Existing FULL_PRESS tests unaffected.

### Verify

- Bind A → Regular = ENTER, Long Press = ESCAPE. Tap A briefly → ENTER fires. Hold A 0.3s+ → both fire (FULL on DOWN, LONG on threshold) — adjusting that to "ESCAPE suppressed" arrives with the `interruptable` setting in Brick 3.3.
- Bind A → Double Press = SPACE with 0.25s window. Two quick taps → SPACE. One tap then nothing → after window expires, the single ENTER fires. **(Brick 3.2)**
- Hold-to-repeat on A → ENTER pulses at the configured rate. **(Brick 3.3)**
- Robolectric: unit test for each activator type with simulated event timestamps.

---

## Phase 4 — Action sets + preset switching

### Scope

Multiple mutually-exclusive action sets per controller_profile. Switching between them via `controller_action CHANGE_PRESET <id> 1 1` bindings. Switching clears all active layers on the old set (Steam semantics).

### Runtime

Evaluator tracks `activeActionSetId`. `CHANGE_PRESET` verb sets it and clears the layer stack. Preset binding resolution always reads from the active set.

### UI

The Phase 1 controller overview's **Action Set selector** becomes live:

```
│ Action Set: ◉ Gameplay  ○ Menu  ○ Driving  [+]  │
```

- Tap a set name → switch which set the editor is viewing (preview only; runtime set switching is via bindings).
- Long-press a set → context menu: Rename / Duplicate / Delete / Set as default.
- `[+]` → "Add Action Set" dialog: name + (optional) inherit from existing set.

A new binding output category in the picker: **Steam → Switch Action Set**, target = one of the sets in this controller_profile.

### Files

- **Edit** `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt` — wire up set tabs
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/ActionSetManagementDialogs.kt`
- **Edit** `app/src/main/java/com/mapo/data/repository/ControllerConfigRepository.kt` — `addActionSet`, `renameActionSet`, `duplicateActionSet`, `deleteActionSet`
- **Edit** `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — `CHANGE_PRESET` verb
- **Edit** `app/src/main/java/com/mapo/data/defaults/RemapInputOptions.kt` — add `ControllerAction` category

### Verify

- Two sets: "Gameplay" / "Menu". In Gameplay, A=ENTER. In Menu, A=SPACE. Bind B in Gameplay → `Switch Action Set: Menu`. Press B → A subsequently emits SPACE.
- Switching back via another binding clears layers (verified in Phase 5).

---

## Phase 5 — Action layers + mode-shift

### Action layers

Stack overlays on top of an action_set. Bindings draw from the parent set; the layer specifies only what's overridden. Multiple layers stack with later-activated winning conflicts. Switching action sets clears the stack.

**Activation verbs** (all implemented in `OutputEmitter`):

- `add_layer N 1 1` paired with `release → remove_layer N 1 1` — while-held
- `hold_layer N 1 1` — while-held single-binding variant
- `add_layer N 0 0` — sticky toggle

### Mode-shift

Lightweight single-source while-held override (no layer). Bind a button → `mode_shift <input_source> <binding_group_id>`. While held, that input source rebinds to the specified group; on release, reverts.

### UI

#### Layers tab

The Phase 1 **Layers** row goes live as a horizontal pill row beside Action Sets:

```
│ Layers:  ┌─Scope──┐  ┌─Vehicle─┐  [+]          │
```

Tap a layer → enter "edit overlay" mode where every row shows the parent set's binding *as faded ghost text* and any overlay-overrides in primary color. Editing a row in overlay mode writes to the layer, not the parent set. A toggle "Show all / Only overrides" filters the list.

#### Layer activation binding

In any activator's binding list, a new category in the picker: **Steam → Layer**, with sub-options:

- Add Layer (sticky)
- Add Layer (while held, with auto-release)
- Hold Layer
- Remove Layer

#### Mode-shift binding

In any activator's binding list: **Steam → Mode Shift**, target = (input source + binding group). The UI shows it as "Mode Shift: Right Trackpad → Scroll Wheel" so the semantics are legible at a glance.

### Files

- **Edit** `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt` — Layers row + ghost-text overlay rendering
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/LayerManagementDialogs.kt`
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/LayerActivationPickerScreen.kt`
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/ModeShiftPickerScreen.kt`
- **Edit** `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — layer stack + mode_shifts table

### Decision points

- **Layer "while-held add/remove" UX**: Steam pairs `Full_Press add_layer` with a `release remove_layer` activator on the same input. Options: (a) require the user to add both activators manually (faithful to Steam), or (b) collapse it to one UI action ("Add Layer (while held)") that auto-generates both. Recommend (b) — much friendlier; the underlying data still encodes two activators.
- **Layer override indicator on the controller overview**: small dot or text suffix? — flag at implementation time.

### Verify

- Bind RB → Add Layer (while held) = "Scope". Layer "Scope" overrides A = MOUSE_LEFT. Hold RB → A emits left-click; release RB → A emits ENTER again.
- Bind A → Mode Shift right trackpad → Scroll Wheel. Hold A; drag right trackpad → emits scroll up/down. Release A; right trackpad reverts to its primary mode.
- Switching action sets clears the layer stack (verified by activating two stacked layers, switching sets, switching back — stack is empty).

---

## Phase 6 — Input source modes

### Scope

Implement the per-source mode catalog. Each physical input *source* can be in exactly one mode at a time (per binding_group). The mode determines which sub-inputs (group_inputs) are valid and which settings the source accepts.

**Inherited from Phase 2.3 descope**: this phase also owns the **motion-capture pipeline** (sticks, triggers as analog, dpad as analog hat). `AccessibilityService` doesn't have `onGenericMotionEvent`, so the leading-candidate approach is attaching a `TYPE_ACCESSIBILITY_OVERLAY` view to the existing `OverlayManager` and overriding `View.onGenericMotionEvent` on it. Once motion events flow, the `Soft_Press` activator (deferred from Phase 3) and every analog-mode (Joystick Move/Camera, Mouse Joystick, Absolute Mouse, Trigger soft-fire) become implementable.

### Modes shipped this phase

In priority order:

1. **Dpad** — 4-way / 8-way / cross-gate layouts; sub-inputs `dpad_north/south/east/west/click`
2. **Button Pad** — direct button bindings; sub-inputs `button_a/b/x/y`
3. **Joystick Move** — analog stick output; response curves (linear/aggressive/relaxed/wide); spring-return; sub-inputs `click`
4. **Joystick Camera** — first/third-person camera mode; sub-inputs `click`; output left/right joystick or relative mouse; swipe-duration / flick momentum
5. **Mouse Joystick** — mouse-feel that outputs joystick (for games rejecting mouse+gamepad); sub-inputs `click`
6. **Absolute Mouse** — 1:1 trackpad → screen; acceleration; trackball momentum; sub-inputs `click`
7. **Trigger** — analog trigger as digital with click threshold; sub-inputs `click`; supports `Soft_Press` activator (analog now real)
8. **Single Button** — one click → one binding; sub-inputs `click`
9. **Scroll Wheel** — rotation-driven; CW/CCW bindings; up to 10 list items
10. **2D Scroll** — dpad-style sub-inputs bound to scroll game actions
11. **Reference** — alias group; `referenced_mode` points at another group's id

**Deferred to a sub-phase** (Phase 6.b, if within this plan; otherwise punt to later):

- **Flickstick** — gyro-flick aim mode (needs gyro capture; AYN Thor's gyro pipeline TBD)
- **Mouse Region** — constrained-region mouse mapping (needs overlay rendering; tie this to the Phase 8 menu work)
- **Touch Menu** / **Radial Menu** — UI deferred per user direction; data scaffold lands in Phase 8

### Implementation pattern

Each mode is a class implementing `BindingMode`:

- `validInputs(): List<String>` — sub-input names this mode exposes
- `evaluate(physical: PhysicalSourceState, settings: GroupSettings, state: ModeRuntimeState): List<GroupInputEvent>` — translates source state into per-input events the activator engine consumes
- `defaultSettings(): GroupSettings` — sensible defaults per mode

Mode classes live under `app/src/main/java/com/mapo/service/input/modes/` (one file per mode).

### UI: source mode picker

Tap a physical source header in the controller overview (e.g., "Left Joystick") → its detail screen:

```
┌─ Left Joystick ──────────────────────── [Done] ─┐
│ Mode                                            │
│ ◉ Joystick Move                                 │
│ ○ Joystick Camera                               │
│ ○ Mouse Joystick                                │
│ ○ Dpad                                          │
│ ○ Button Pad                                    │
│ ○ Single Button                                 │
│                                                 │
│ Settings ─────────────────────────────────      │
│  Output joystick    ◉ Left  ○ Right             │
│  Inner deadzone     [▭▭━━━━] 0.10               │
│  Outer deadzone     [━━━━━▭] 0.90               │
│  Response curve     [Linear ▾]                  │
│  Anti-deadzone      [━━━━━━] 0.00               │
│                                                 │
│ Inputs ───────────────────────────────────      │
│ ▸ Click                                 [Edit]  │
│     (Unbound)                                   │
└─────────────────────────────────────────────────┘
```

Each mode swaps both the settings panel and the inputs list. Switching modes preserves bindings on any sub-inputs whose names match across the modes (e.g., `click` survives mode swaps) — others are stashed in case the user switches back.

### Files

- **Create** `app/src/main/java/com/mapo/service/input/modes/` (one file per mode + a `BindingMode` interface)
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/SourceEditorScreen.kt`
- **Create** `app/src/main/java/com/mapo/ui/screen/binding/ModeSettingsPanels/` — one composable per mode's settings panel
- **Edit** `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — delegate sub-input event production to the active mode class
- **Edit** `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — output side: emit analog mouse moves (existing gesture infra), relative-mouse via accessibility cursor

### Decision points

- **Mouse-output mechanism**: today's mouse-click goes through accessibility `dispatchGesture`. For absolute mouse / joystick-camera relative-mouse, we'll need cursor-move events. Existing virtual-cursor logic in the accessibility service is a starting point; flag this for implementation review.
- **Flickstick + gyro**: ship without flickstick this phase; revisit once gyro is in scope. (AYN Thor IMU situation isn't characterized in current memory.)

### Verify

- Right joystick → Mouse Joystick mode. Move stick → mouse cursor moves. Sensitivity setting affects speed.
- Right trackpad → Scroll Wheel. Rotate finger → emits scroll up/down.
- L2 trigger → Trigger mode, soft-press threshold 0.3. Squeezing trigger lightly → soft-press activator fires; full pull → click activator fires.
- Left stick mode swapped Joystick Move → Dpad. Push stick up → emits dpad_up.

---

## Phase 7 — VDF import (legacy_set only)

### Scope

Parse a Steam VDF controller config and translate it into our Phase 1+ schema. Action-based configs (`legacy_set "0"`) are parsed and warned-about but not auto-resolved — `game_action` bindings land as placeholder bindings flagged "Unresolved game action: <set>/<action>" (manifest registry is out-of-scope; would require a Mapo-hosted action-manifest service).

### Parser

- **Create** `app/src/main/java/com/mapo/data/io/vdf/VdfParser.kt` — Kotlin VDF parser. Honor duplicate-key semantics (collect into lists, not maps) — many `"group"` blocks, multiple `"preset"` blocks at the same level are intentional and a naive JSON-style parser will silently lose data.
- **Create** `app/src/main/java/com/mapo/data/io/vdf/VdfImporter.kt` — schema translator:
  - `controller_mappings.actions` → `ActionSet` records
  - `controller_mappings.action_layers` → `ActionLayer` records (`parent_set_name` resolved)
  - `group` blocks → `BindingGroup` + `GroupInput` + `Activator` + `Binding`
  - `preset` blocks → `PresetBinding` records (with state qualifier)
  - Binding strings (CSV) → typed `BindingOutput`
  - `localization` → resolved during import (store resolved title/description; fall back to literal `#token` on miss)

### Controller-type mapping

VDF targets a specific controller (`controller_neptune` / `controller_xboxelite` / `controller_xboxone` / `controller_ps4`). Our `controller_profile.controllerType` should preserve the source type so the user can re-import on a matching device. For cross-controller import, surface a mapping confirmation:

```
┌─ Import: Guild Wars 2 (Steam Deck) ──────── [×] ─┐
│ Source controller: Steam Deck (controller_neptune)│
│ Mapo will create a new controller profile for the │
│ Steam Deck. Some inputs may not exist on your     │
│ device (back paddles, capacitive sticks).         │
│                                                   │
│ Action sets:    2 (Gameplay, Menu)                │
│ Action layers:  3 (Combat, Mounted, UI)           │
│ Groups:         24                                │
│ Bindings:       147 (143 concrete · 4 game_action │
│                       — will land as placeholders)│
│                                                   │
│             [Cancel]  [Preview]  [Import]         │
└───────────────────────────────────────────────────┘
```

### UI

- **Profile drawer** → new entry "Import Steam Config"
- File picker (Android Storage Access Framework) selecting a `.vdf` file
- The summary dialog above
- Optional **Preview** flips to a read-only version of the controller overview screen so the user can inspect the imported config before committing
- On Import: lands as a new `ControllerProfile` under the current `Profile`, named from the VDF's title

### Files

- **Create** `app/src/main/java/com/mapo/data/io/vdf/VdfParser.kt`
- **Create** `app/src/main/java/com/mapo/data/io/vdf/VdfImporter.kt`
- **Create** `app/src/main/java/com/mapo/data/io/vdf/VdfTokenStream.kt` (tokenizer)
- **Create** `app/src/main/java/com/mapo/ui/screen/import/VdfImportScreen.kt`
- **Edit** `app/src/main/java/com/mapo/ui/nav/MapoRoute.kt` — add `IMPORT_VDF`
- **Edit** `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` — add the menu entry
- **Edit** `app/src/main/AndroidManifest.xml` — declare SAF intent filter for `.vdf` files if desired (open-with from a file manager)

### Verify

- Import a real Steam Deck VDF (e.g., the Guild Wars 2 example previously analyzed). All 24 groups produce equivalent BindingGroups; all activators (Full / Soft / Long / Start / release) parse correctly; layer activation pairs survive round-trip.
- Re-export (Phase 7.b, stretch) round-trips structurally-stable: parsed → re-emitted is semantically equivalent (key reorder OK).
- A `legacy_set "0"` action-based VDF imports without crashing; `game_action` bindings show as placeholders.

---

## Phase 8 — Radial / touch menu data scaffold

### Scope

Per user direction: scaffold the data model and bindings, **don't** build the overlay rendering yet. The eventual rendering will integrate with the existing virtual keyboard infrastructure (TBD design).

### Schema additions

- New `BindingGroup.mode` values: `radial_menu`, `touch_menu`
- For `touch_menu`: settings include `touch_menu_button_count` (2/4/7/9/12/13/16), `layout`, `touch_menu_position_x/y`, `touch_menu_opacity`
- For `radial_menu`: settings include button count (≤20), `position_x/y`, `opacity`, `size`, activation style (Button Click / Button Release / Touch Release / Modeshift End / Always)
- `GroupInput.inputKey` accepts `touch_menu_button_<N>` (0-indexed) — already implied by Phase 1 schema (no migration needed)

### Runtime stubs

`BindingMode` implementations `RadialMenuMode` and `TouchMenuMode` exist but their `evaluate()` methods only log "menu activation requested — overlay rendering deferred" and dispatch nothing. They will be filled in alongside the eventual virtual-keyboard-menu integration work.

### UI: menu editor (no rendering yet)

A new screen for configuring menus without an overlay:

```
┌─ Menus ──────────────────────── [+ New Menu] ──┐
│ ▸ Weapons (Radial · 8 items)         [Edit]    │
│ ▸ Pings    (Touch · 9 items)         [Edit]    │
└────────────────────────────────────────────────┘
```

Tapping Edit opens a per-menu editor: name, type (radial/touch), button count, positions (sliders), opacity, items list. Each item: label, icon, binding. The grid / radial layout is shown as a static preview diagram (not the live overlay).

Triggering a menu from a binding: the picker gets a new category **Menu → Open ...** with the user's menus listed. When the binding fires at runtime, today nothing happens (logged); after the eventual integration with virtual keyboards, this is what activates the overlay.

### Files

- **Create** `app/src/main/java/com/mapo/service/input/modes/RadialMenuMode.kt` (stub)
- **Create** `app/src/main/java/com/mapo/service/input/modes/TouchMenuMode.kt` (stub)
- **Create** `app/src/main/java/com/mapo/ui/screen/menus/MenusScreen.kt`
- **Create** `app/src/main/java/com/mapo/ui/screen/menus/MenuEditorScreen.kt`
- **Edit** `app/src/main/java/com/mapo/ui/nav/MapoRoute.kt` — add `MENUS`, `MENU_EDITOR`
- **Edit** `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` — add "Menus" entry

### Verify

- Create a menu, set 8 items, bind each item to keys. Bind a physical input → "Open menu: Weapons". Trigger it — logcat shows "menu activation requested"; nothing visible yet (expected).
- VDF import (Phase 7) of a config containing a `radial_menu` / `touch_menu` group lands as a Mapo menu with the right item count + bindings.

---

## Cross-phase concerns

### M3 / UI conventions

Every new screen follows project Material 3 standards and uses a `Scaffold` root. Typography per M3 typography conventions (bodyLarge for list primaries, labelLarge for buttons, titleSmall for sections). Helper subtext under list items for tutorialization. Color pickers (where colors are settings — icon colors on menu items, e.g.) default to HSL. Drop shadows via `BlurMaskFilter`. Don't auto-spawn the IME. Don't propagate Theme Studio's broken L/C/R alignment picker pattern.

### Logging

Every input event continues to log (per project standing rule: never remove input logging). The evaluator (Phase 2+) logs: incoming physical event → resolved source/group/sub-input → activators evaluated → bindings emitted. Activator state transitions log too (helpful for debugging activator interactions).

### Tests

Robolectric under `app/src/test/` (the AYN Thor backgrounds test-launched activities, so traditional androidTest doesn't work). Each phase ships:

- DAO / repository round-trip tests for new schema
- Evaluator unit tests for the phase's runtime semantics (deterministic — simulated input events with explicit timestamps)
- ViewModel tests where draft/commit semantics are non-trivial

### Migrations and pre-release status

Mapo is pre-release; destructive changes are OK. Phase 1's schema rebuild wipes and migrates from `gamepad_mappings`. Subsequent phases are additive (no destructive migrations expected). When release approaches, this plan's migration story will need re-examining.

### Things explicitly NOT in this plan

(Recorded here so we don't accidentally bake in dependencies on them):

- Glyph database / `GetGlyphForActionOrigin` equivalent
- Action origin translation across controller families
- Workshop / community-config sharing (file import in Phase 7 is the MVP)
- Game action manifest registration (forces legacy-only import scope)
- Preview-before-apply for layouts beyond the VDF import flow
- Controller HUD / debug overlay
- Gyro capture + flickstick mode (Phase 6 ships without)

### Post-parity Mapo extensions (deferred until parity work is done)

Mapo-specific enhancements that go *beyond* Steam Input parity. Recorded here so we don't lose them; not in scope until the parity roadmap is largely complete.

- **Long_Press "fire as tap" mode** — per-activator toggle to emit DOWN+UP at threshold instead of holding while the physical button is held. Current Steam-parity behavior holds the key, which on Wine/Windows targets (GameNative) produces OS-level key auto-repeat — undesirable for menu keys like ESC. User verified (2026-05-12) that Steam Deck exhibits identical Wine-repeat behavior with the same config, so this is true Steam parity, not a Mapo bug. The extension would add an opt-in alternative mode. See `project_fire_as_tap_post_parity.md` in memory.

- **Virtual-keyboard buttons as chord partners** — today's chord partner is a physical input only (captured via the listen-for-press picker in 3.3). User direction (2026-05-13): the broader virtual-keyboard rework will explore on-screen overlay use of virtual keyboards, and that work may change the right way to use virtual buttons in chord activators. Punted to wait on that direction. Data shape doesn't preclude it — `chord_partner_source` could grow a new enum value for virtual buttons later without migrating existing rows.

---

## Critical files index

A consolidated list for quick scanning at execution time.

**Touched in multiple phases:**

- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt`
- `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt`
- `app/src/main/java/com/mapo/ui/nav/MapoRoute.kt`
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt`
- `app/src/main/java/com/mapo/service/input/InputDispatcher.kt`
- `app/src/main/java/com/mapo/data/db/AppDatabase.kt`

**New in Phase 1 (data model):**

- `app/src/main/java/com/mapo/data/model/steam/` (whole tree)
- `app/src/main/java/com/mapo/data/db/steam/` (whole tree)
- `app/src/main/java/com/mapo/data/repository/ControllerConfigRepository.kt`

**New in Phase 2 (runtime):**

- `app/src/main/java/com/mapo/service/input/InputEvaluator.kt`
- `app/src/main/java/com/mapo/service/input/OutputEmitter.kt`
- `app/src/main/java/com/mapo/service/input/CompiledConfig.kt`

**New in Phase 3 (activators UI):**

- `app/src/main/java/com/mapo/ui/screen/binding/InputEditorScreen.kt`
- `app/src/main/java/com/mapo/ui/screen/binding/ActivatorEditorScreen.kt`

**New in Phase 5 (layers + mode-shift UI):**

- `app/src/main/java/com/mapo/ui/screen/binding/LayerActivationPickerScreen.kt`
- `app/src/main/java/com/mapo/ui/screen/binding/ModeShiftPickerScreen.kt`

**New in Phase 6 (modes):**

- `app/src/main/java/com/mapo/service/input/modes/` (one file per mode)
- `app/src/main/java/com/mapo/ui/screen/binding/SourceEditorScreen.kt`

**New in Phase 7 (VDF import):**

- `app/src/main/java/com/mapo/data/io/vdf/` (parser + importer)
- `app/src/main/java/com/mapo/ui/screen/import/VdfImportScreen.kt`

**New in Phase 8 (menu scaffold):**

- `app/src/main/java/com/mapo/service/input/modes/RadialMenuMode.kt`
- `app/src/main/java/com/mapo/service/input/modes/TouchMenuMode.kt`
- `app/src/main/java/com/mapo/ui/screen/menus/MenusScreen.kt`
- `app/src/main/java/com/mapo/ui/screen/menus/MenuEditorScreen.kt`

---

## Per-phase verification at a glance

| Phase | One-line end-to-end test |
|---|---|
| 0 ✅ | Edit BUTTON_A → ENTER → row immediately shows ENTER; pop back, re-open → still ENTER. |
| 1 | Existing remap survives schema migration; new schema round-trips. |
| 2 | Logcat shows axis values; existing remap still works through new evaluator. |
| 3 | A Long Press of 0.3s emits a different binding than a tap. |
| 4 | Switching action sets via a binding changes the live mapping. |
| 5 | Holding RB activates a layer that overrides A's binding; releasing reverts. |
| 6 | Right joystick set to Mouse Joystick mode moves the cursor. |
| 7 | A real community Steam Deck VDF imports and the resulting Mapo config plays back equivalently. |
| 8 | A menu can be authored end-to-end; firing its open-binding logs activation (overlay deferred). |
