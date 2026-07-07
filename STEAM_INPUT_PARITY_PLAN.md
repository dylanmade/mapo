# Steam Input Parity for Mappo — Phased Roadmap

## Context

> **2026-05-27 renumbering note.** Phase 7 (Steam Input parity foundation) was inserted between the original Phase 6 (input source modes) and the original Phase 7 (VDF import). The original Phase 7 + 8 shifted down to Phase 8 + 9. Inline cross-references throughout this document predate the renumbering and use the old numbers — read "Phase 7 (VDF import)" as "Phase 8 (VDF import)" and "Phase 8 (menu scaffold)" as "Phase 9 (menu scaffold)". The new Phase 7 details live in `~/.claude/plans/phase-7-steam-input-parity-foundation.md`.

Mappo's `Remap Controls` feature today is a 1-to-1 physical-button → output mapping (16 digital buttons; keyboard / mouse / gamepad outputs). Steam Input is the gold-standard configurability model on the market: it adds **action sets**, **action layers**, **mode-shift**, **input source modes** (joystick variants, mouse modes, scroll, radial / touch menus), **activators** (long / double / start / release / chord / soft press, with turbo, toggle, cycle, delays), and a rich on-disk **VDF profile format** that already has a free Workshop ecosystem of community configurations.

The long-term goal is parity with Steam Input + the ability to import community VDF configurations on Android. This plan is the staged execution of that goal.

**Foundation-first sequencing** is in effect: Phase 1 rebuilds the data model in one shot to express every Steam concept, then later phases bring each Steam feature online on top of it. **Analog stick/trigger capture** is folded into Phase 2 (foundational) so we don't have to rewrite the input pipeline twice. **Radial / touch menu UI** is deferred (Phase 8 lands the data scaffold only — overlay rendering integrates later with virtual keyboards). **VDF import** is scoped to `legacy_set "1"` only (action-based imports need their own action-manifest hosting; deferred indefinitely).

Before any of that, Phase 0 fixes a regression: the existing Remap Controls flow silently drops the user's selection after the dialog → full-screen-destination refactor.

---

## Phase 0 — Fix the Remap Controls persistence regression — ✅ COMPLETED

### Root cause

In `app/src/main/java/com/mappo/ui/screen/RemapControlsScreen.kt`, both `draft` and `editingButton` were held in `remember { ... }` inside the screen composable. When the user tapped **Edit** → navigated to the full-screen `REMAP_TARGET_PICKER`, NavHost removed `RemapControlsScreen` from composition. On return, the screen recomposed from scratch — `editingButton` reset to `null`. The `LaunchedEffect(pickerResult)` fired, but `editingButton?.let { ... }` was a no-op, and the picker result was consumed without ever being written to the draft. The row stayed "Unbound". Regression from the dialog → full-screen refactor (dialogs overlay the parent, preserving `remember`; nav destinations don't).

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

- `app/src/main/java/com/mappo/data/db/GamepadMappingDao.kt`
- `app/src/main/java/com/mappo/data/repository/GamepadMappingRepository.kt`
- `app/src/main/java/com/mappo/ui/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/mappo/ui/screen/RemapControlsScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/MainScreen.kt` (REMAP_CONTROLS destination block only)
- `app/src/test/java/com/mappo/data/repository/GamepadMappingRepositoryTest.kt` (5 new tests for `setMapping`)
- `app/src/test/java/com/mappo/ui/viewmodel/MainViewModelTest.kt` (2 new tests for `setRemapMapping`)

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
- **Legacy `gamepad_mappings` data is wiped on v6→v7 upgrade** (decided in 1.2). Pre-release stance ([memory: project_mappo_pre_release.md](../.claude/projects/-Users-dylanbperry-projects-mappo/memory/project_mappo_pre_release.md)) said destructive OK; the user confirmed they're fine re-creating any test profiles. The repository's `ensureSeeded` path is the only seed mechanism — there is no migrator that reads old rows. If a release-ready migration path is ever needed, write a real Room `Migration(6, 7)` and the repository can stay as-is.
- **`BindingGroup.settingsJson` defaults to `"{}"`** at seed time. Mode-specific settings (deadzones, sensitivities, etc.) parse from this string in later phases; for brick 1.2 every group has empty settings and a single default sub-input set per mode.
- **Default seed excludes trackpads, back paddles, gyro.** The schema supports them (for VDF import compatibility) but Generic Android doesn't expose them, so seeding configurable-but-never-fireable groups would just be UI clutter. Imports can still create groups for these sources.

#### Brick 1.3 deviations + decisions

- **Master-detail layout is custom, not `ListDetailPaneScaffold`.** M3's adaptive scaffold is designed for phone↔tablet adaptation; we always want both panes. Built a `SectionedListDetailPane` composable in `app/src/main/java/com/mappo/ui/component/layout/` instead — 30/70 split, M3-styled rail (`surfaceContainer` background, `surfaceContainerHighest` for selection), gamepad focus with wraparound, cross-pane focus handoff via shared `FocusRequester`.
- **Section hierarchy reorganized from Steam's input-source taxonomy** into Buttons (face + bumpers + menu) / D-Pad / Triggers / Joysticks / Gyro. Lives in `RemapSections.kt` as a typed registry, not in the data layer — section grouping is a UI concept and the user wants flexibility to evolve it independently.
- **Soft Pull / Analog Output Trigger / Behavior dropdowns / Gyro section ship as disabled placeholders.** Visible in the UI now (so the eventual feature lands without re-doing layout); none have wire-up. SOFT_PRESS activator is Phase 3, behavior-dropdown mode picker is Phase 6, gyro requires Phase 2 motion capture + AYN Thor IMU validation.
- **Back / Home buttons are NOT added to `DeviceButton` / `InputSource`.** They're inconsistent across Android devices and the user explicitly opted out of including them in 1.3's data layer. Revisit when device-specific capability detection lands.
- **Action Set tab strip + Layers chip render at the top, fully disabled.** Tab clicks no-op until Phase 4 plumbs `CHANGE_PRESET`; the Layers chip is just a `(no layers)` AssistChip until Phase 5.
- **The picker stays unchanged.** `RemapTargetPickerScreen` still speaks `RemapTarget`. The bridge happens in `RemapControlsScreen` via `BindingOutput.toRemapTarget()` (opening picker) and `BindingOutput.fromRemapTarget()` (consuming result). New picker categories for `GameAction` / `ControllerAction` / `ModeShift` get added when those phases land.
- **`GamepadMappingRepository` + the legacy `setRemapMapping` VM method stay in place during 1.3.** Brick 1.4 deletes them once nothing references them outside tests.

#### Brick 1.4 deviations + decisions

- **Runtime input dispatch is intentionally dead between brick 1.4 and Phase 2.** Removing the legacy `activeProfileMappings → inputDispatcher.setCurrentMappings` collector means physical remaps don't fire at runtime until Phase 2's evaluator reads bindings directly from `ControllerConfigRepository`. The user confirmed this gap is acceptable; bindings still persist correctly through the editor, they just don't drive output yet.
- **No interim adapter built.** Considered mirroring new-schema writes into the legacy table to keep the dispatcher alive across the gap, but it'd be throwaway code on the wrong side of the deletion, and Phase 2 lands soon. Pre-release status ([memory: project_mappo_pre_release.md](../.claude/projects/-Users-dylanbperry-projects-mappo/memory/project_mappo_pre_release.md)) means no end-user impact.
- **`ProfileRepository.duplicateProfile`'s `copyMappings` call replaced with `ControllerConfigRepository.copyConfig`** — deep-clones the full controller_profile → action_set → action_layer → binding_group → group_input → activator → binding + preset_binding + game_action graph with fresh autogenerated PKs at every level. Honors [`feedback_duplicates_own_their_data`](../.claude/projects/-Users-dylanbperry-projects-mappo/memory/feedback_duplicates_own_their_data.md): the duplicate is fully independently addressable; editing the copy never bleeds back into the source. Verified by the new `copyConfig_editsToDestDoNotAffectSource` test.
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

- New Kotlin domain models under `app/src/main/java/com/mappo/data/model/steam/`:
  - `ControllerProfile`, `ActionSet`, `ActionLayer`, `BindingGroup`, `GroupInput`, `Activator`, `Binding`, `PresetBinding`, `GameAction`
  - Sealed-class settings types: `GroupSettings` (per-mode), `ActivatorSettings` (universal + type-specific), `BindingOutput` (typed args)
- DAOs per entity under `app/src/main/java/com/mappo/data/db/steam/`
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

- **Create** `app/src/main/java/com/mappo/data/db/steam/` (full directory)
- **Create** `app/src/main/java/com/mappo/data/model/steam/` (full directory)
- **Create** `app/src/main/java/com/mappo/data/repository/ControllerConfigRepository.kt`
- **Edit** `app/src/main/java/com/mappo/data/db/AppDatabase.kt` — add entities, bump version, add migration
- **Edit** `app/src/main/java/com/mappo/data/repository/GamepadMappingRepository.kt` — deprecate (keep as thin adapter during transition or delete outright)
- **Edit** `app/src/main/java/com/mappo/ui/screen/RemapControlsScreen.kt` — rewrite to the section-grouped layout
- **Edit** `app/src/main/java/com/mappo/ui/viewmodel/MainViewModel.kt` — switch from `activeProfileMappings` (legacy) to `activeControllerConfig` flow

### Verify

- Fresh install: one default profile, one default controller_profile, one default action_set, base preset_bindings for every physical input source.
- Existing install (with mappings from before): migration produces equivalent bindings; logcat shows "Migrated N legacy mappings".
- Picking ENTER for BUTTON_A still drives the accessibility service correctly — the runtime path uses the *compiled* config, but for a single Full_Press + key_press it's a no-op behavior change.
- Robolectric tests: round-trip save+load of a config with multiple sets / layers / groups / activators / bindings.

---

## Phase 2 — Input pipeline upgrade (analog capture + runtime evaluator)

### Progress

Phase 2 ships as three sub-bricks (one moved out — see 2.3 below):

- **2.1 — CompiledConfig + plumbing** ✅ COMPLETED. New `CompiledConfig` / `InputAddress` / `CompiledInput` / `CompiledActivator` types + `ControllerConfig.toCompiled()` compiler under `app/src/main/java/com/mappo/service/input/CompiledConfig.kt`. `InputDispatcher.compiledConfig` StateFlow added alongside the legacy `currentMappings` (not replacing it yet); `MainViewModel` collects `activeControllerConfig` and publishes compiled snapshots into the dispatcher. Compiler tests under `app/src/test/java/com/mappo/service/input/CompiledConfigTest.kt` cover round-trip, state-filter, multi-activator preservation, cycle-binding order, and missing-address null. Nothing fires at runtime yet — that's brick 2.2.
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

A new class under `app/src/main/java/com/mappo/service/input/InputEvaluator.kt`. Inputs:

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

- **Create** `app/src/main/java/com/mappo/service/input/InputEvaluator.kt`
- **Create** `app/src/main/java/com/mappo/service/input/OutputEmitter.kt`
- **Create** `app/src/main/java/com/mappo/service/input/CompiledConfig.kt` (in-memory representation)
- **Edit** `app/src/main/java/com/mappo/service/InputAccessibilityService.kt` — add `onGenericMotionEvent`; route both key + motion events through `InputEvaluator`
- **Edit** `app/src/main/res/xml/accessibility_service_config.xml` — verify capability flags (do not flip `canRetrieveWindowContent` — load-bearing for cross-display auto-switch + planned screen-scrape)
- **Edit** `app/src/main/java/com/mappo/service/input/InputDispatcher.kt` — replace `currentMappings` with `compiledConfig`
- **Edit** `app/src/main/java/com/mappo/ui/viewmodel/MainViewModel.kt` — publish `CompiledConfig` to the dispatcher
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

- **Create** `app/src/main/java/com/mappo/ui/screen/binding/InputEditorScreen.kt` (the activator list)
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/ActivatorEditorScreen.kt`
- **Edit** `app/src/main/java/com/mappo/ui/nav/MappoRoute.kt` — add `INPUT_EDITOR`, `ACTIVATOR_EDITOR` routes
- **Edit** `app/src/main/java/com/mappo/service/input/InputEvaluator.kt` — implement the full state machine
- **Edit** `app/src/main/java/com/mappo/data/model/steam/Activator.kt` — flesh out settings sealed class

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
| 3.6 | **Multi-command authoring** per activator (`[+ Add Command]` list under each activator row) — required for `cycle_bindings` to be user-exercisable. Steam exposes this as "Cycle Commands" with sub-command rows; same data shape as Mappo's. Data model and runtime were ready since Phase 1 / Brick 3.3; only the UI was missing. | ✅ COMPLETED 2026-05-13 |

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

- **Edit** `app/src/main/java/com/mappo/ui/screen/RemapControlsScreen.kt` — wire up set tabs
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/ActionSetManagementDialogs.kt`
- **Edit** `app/src/main/java/com/mappo/data/repository/ControllerConfigRepository.kt` — `addActionSet`, `renameActionSet`, `duplicateActionSet`, `deleteActionSet`
- **Edit** `app/src/main/java/com/mappo/service/input/InputEvaluator.kt` — `CHANGE_PRESET` verb
- **Edit** `app/src/main/java/com/mappo/data/defaults/RemapInputOptions.kt` — add `ControllerAction` category

### Verify

- Two sets: "Gameplay" / "Menu". In Gameplay, A=ENTER. In Menu, A=SPACE. Bind B in Gameplay → `Switch Action Set: Menu`. Press B → A subsequently emits SPACE.
- Switching back via another binding clears layers (verified in Phase 5).

### Brick breakdown

| Brick | Scope | Status |
|---|---|---|
| 4.1 | Repo CRUD: `addActionSet` (blank + inherit), `renameActionSet`, `duplicateActionSet`, `deleteActionSet` (with last-set guard), `setDefaultActionSet`; `ControllerProfile.defaultActionSetId` field + db v10→v11 bump; `ControllerConfig.activeActionSet` honors the new pointer | ✅ COMPLETED 2026-05-13 |
| 4.2 | Runtime: `InputEvaluator` tracks `activeSetId`; `CHANGE_PRESET` controller_action verb (intercepted before `OutputEmitter`) swaps the active set + flushes all transient runtime state from the old set; `CompiledConfig` widened to `Map<ActionSetId, CompiledActionSet>` with `defaultActionSetId` | ✅ COMPLETED 2026-05-13 |
| 4.3 | UI: live Action Set tabs in `RemapControlsScreen` (viewing selector — editor preview, distinct from runtime active set); `MainViewModel.viewingActionSetId` with stale-id + profile-change cleanup; editor screens (`InputEditorScreen` / `ActivatorEditorScreen`) follow the viewing pointer via `ControllerConfig.findGroupInput(setId = ...)` / `resolveActionSet(setId)` | ✅ COMPLETED 2026-05-14 |
| 4.4 | UI: set management — `[+]` button opens AddSetDialog (name + optional "inherit from existing set"); `[⋮]` overflow menu on the tab bar exposes Rename / Duplicate / Set as default / Delete for the currently-viewing set; Delete disabled when last set; "Set as default" reads "Default set" + disabled when viewing the default; new VM methods (`addControllerActionSet` / `renameControllerActionSet` / `duplicateControllerActionSet` / `deleteControllerActionSet` / `setDefaultControllerActionSet`) delegate to the repo; add/duplicate flip `viewingActionSetId` to the new set | ✅ COMPLETED 2026-05-14 |
| 4.4.1 | Strip the "default action set" concept entirely (no Steam analog): drop `ControllerProfile.defaultActionSetId` + repo `setDefaultActionSet` + VM `setDefaultControllerActionSet` + the "Set as default" overflow menu item; `CompiledConfig.defaultActionSetId` renamed `startingActionSetId` (computed = first set by orderIndex); `ControllerConfig.activeActionSet` becomes first-by-orderIndex; db v11→v12 destructive bump | ✅ COMPLETED 2026-05-14 |
| 4.5 | Picker: new **Switch Action Set** category in `RemapTargetPickerScreen`; switches the picker's saved-state encoding to `BindingOutput` (was `RemapTarget`); context-aware `displayLabel(config)` resolves CHANGE_PRESET to "Switch to: &lt;title&gt;"; ConfigureButton legacy path converts at the boundary | ✅ COMPLETED 2026-05-14 |

### Brick 4.4 deviations + decisions

- **Long-press → overflow icon.** The plan called for long-press on each tab to open a context menu, but `Tab` from M3 wraps its content in a `selectable` modifier that conflicts with `combinedClickable`; layering both produces racing-or-swallowed gestures, and the only clean workaround is to abandon `Tab` and reimplement the tab visuals (losing the underline animation). Long-press is also poorly discoverable on Android-tablet/handheld form factors. Switched to a trailing `[⋮]` IconButton (M3 expressive `smallContainerSize`) that opens a `DropdownMenu` of actions for the **currently-viewing** set. Each menu item is titled with the set name ("Rename 'Menu'", "Delete 'Menu'") so the target is unambiguous. `[+]` button next to `[⋮]` opens the Add Set dialog. Documenting here in case we want to revisit the per-tab gesture once we have a custom tab component.
- **"Default set" visual marker location.** Brick 4.3 deferred the default-set marker. Brick 4.4 lands it inside the `[⋮]` overflow menu: the "Set as default" item reads "Default set" + is disabled when viewing the default. Surfacing the marker in the menu (vs. inline on the tab) keeps the tab row visually clean and ties the marker to the contextual action that controls it — saw the marker, learned what action governs it. If user feedback wants the marker more prominent later, a small dot suffix on the default tab's title is a one-liner.
- **UI collapses Steam's `name`/`title` distinction to a single "Name" field.** The schema keeps both — Steam's `name` is a slug used by VDF importers, `title` is human-display. The dialog asks for a single "Name" (used as the human-display title) and `deriveActionSetName(title)` synthesizes the slug (`"Vehicle / Walking"` → `"vehicle_walking"`). Users almost never need to manage the slug directly; if VDF round-tripping someday needs to preserve a specific slug, we'll add an advanced edit affordance.
- **`InheritFromPicker` is a `Surface` + `DropdownMenu`, not `ExposedDropdownMenuBox`.** The M3 idiomatic dropdown for forms is `ExposedDropdownMenuBox`, but its anchor is an editable `TextField` that summons the IME on focus — directly violates `feedback_no_keyboard_autospawn` when the dialog appears. Built a clickable Surface that opens a regular `DropdownMenu` instead. Visually similar; no IME risk.
- **`AddSetDialog` shows the inherit-from picker only when there are existing sets.** With one set today the picker is hidden; once the user has 2+ sets it appears. The blank vs. inherit choice gets a helper-subtext line below the picker so the user knows what they're choosing ("Starts blank — every input is unbound." / "Copies every binding from 'Gameplay'.").
- **Post-add / post-duplicate: viewing pointer flips to the new set.** Without this, the user creates a set and is still looking at the old set's bindings — confusing. `addControllerActionSet` / `duplicateControllerActionSet` await the repo and then update `_viewingActionSetId.value = newId`.
- **Last-set delete guard.** Both layers refuse: the repo's `deleteActionSet` returns false when the controller_profile has only one set, AND the UI disables the Delete menu item when `sets.size <= 1`. The defense-in-depth keeps the invariant safe if the menu state ever drifts from the data.
- **Dialog state hoisted with plain `remember`.** Considered `rememberSaveable` with a custom `Saver` so typed text survives rotation, but: the dialogs are short-lived, the app's gaming-handheld target rarely rotates mid-edit, and the Saver code wasn't justified by the user benefit. If we hit a rotation-loss complaint later, swap to `rememberSaveable`.
- **No-active-config guard on `addControllerActionSet` / `setDefaultControllerActionSet`.** Both need the controller_profile id, derived from `activeControllerConfig.value`. When that's null (transient state before the first config emits), both no-op with logged delegation skipped. Tests cover the no-op case.
- **Test count.** +9 in `MainViewModelTest` (89 total): each of the 5 new methods × (happy path delegation, no-active-profile no-op where it applies); add/duplicate viewing-pointer flip; setDefault no-active-config no-op. +5 in `RemapControlsScreenTest` (14 total): `[+]` opens AddSet, overflow shows 4 items, Delete disabled w/ single set, "Set as default" disabled when viewing default, "Set as default" invokes callback. All ~280 project tests green.

### Brick 4.4.1 deviations + decisions

- **Why strip it.** Reviewed Steam's actual UX during 4.4 verification: Steam has no exposed "set as default" affordance and no analog for re-pointing which set loads first. The starting set is simply whichever appears first in the VDF — creation order. The "default" concept I introduced in 4.1 was foundation-laying I rationalized at the time, but it didn't map to user-visible Steam semantics; it just hung around as a stale-feeling menu item with a UI sync bug to fix.
- **User-visible bug it fixed.** Brick 4.4's `[⋮]` overflow menu read "Set as default" → "Default set" inconsistently across profile switches (the dropdown captured a stale snapshot of `defaultActionSetId`). Removing the concept removes the bug surface; no menu item, no inconsistency.
- **Naming.** Renamed `CompiledConfig.defaultActionSetId` → `startingActionSetId` to match Steam's mental model: "this is the set the controller boots into," not "this is a user-chosen default." `InputEvaluator.activeSetId` lazy-inits from `cfg.startingActionSetId`; the fallback path when the active set disappears from a config swap also reads `startingActionSetId`. `ControllerConfig.activeActionSet` collapses to `actionSets.firstOrNull()`.
- **DB version bump.** Destructive v11 → v12 because the column is removed. Pre-release per `project_mappo_pre_release.md`, so no migration needed; `fallbackToDestructiveMigration` does its job.
- **Repo simplification.** `deleteActionSet` lost its default-reassignment branch (the starting set is whatever's first by orderIndex on whatever remains — no pointer to chase). `copyConfig` lost its post-loop default-pointer remap. `seedDefaultConfig` no longer does a second `controllerProfileDao.update()` after seeding.
- **UI simplification.** `RemapControlsScreen` drops the `onSetDefaultActionSet` param + the "Set as default" / "Default set" `DropdownMenuItem`. `ActionSetAndLayersBar` no longer reads `controllerProfile.defaultActionSetId`. `ActionSetOverflowMenu` is now 3 items (Rename / Duplicate / Delete).
- **Future "starting set" control, if needed.** If users ever want to choose which set boots first, the answer is drag-to-reorder the tab row — the starting set becomes whatever ends up at index 0. No need for a separate pointer concept. YAGNI for now.
- **Test count.** -2 in `MainViewModelTest` (89 → 87), -1 in `RemapControlsScreenTest` (14 → 13), -4 in `ControllerConfigRepositoryTest` (set-default specific tests + the copyConfig pointer-remap test). `CompiledConfigTest` lost the explicit-pointer test and gained a clearer `startingActionSetId_isFirstActionSetInGraph` test. All ~270 project tests green.

### Brick 4.5 deviations + decisions

- **No typed `BindingOutput.ChangePreset(setId)` variant.** The original brick description floated a typed subclass, but `BindingOutput.ControllerAction(verb="CHANGE_PRESET", args=[setId])` is already the on-disk form, the runtime path (`tryHandleControllerAction` in `InputEvaluator`) is already wired against it, and the picker's emit/decode is just one call site. Adding a typed wrapper would duplicate every encoding/decoding/runtime-handler edge for marginal type-safety gain — and would silently diverge from how VDF import (Phase 7) will land Steam configs (as `CONTROLLER_ACTION` rows). Sticking with the generic shape keeps one path live.
- **Picker generalization, not split.** Two reasonable options to host the new category: (a) split into `SteamBindingPickerScreen` + keep the legacy `RemapTargetPickerScreen`, or (b) generalize the existing picker to speak `BindingOutput`, with the legacy trackpad call site (`ConfigureButtonScreen`) converting at the boundary. Picked (b) — one screen, one URL, one saved-state encoding. Trackpad picks still degrade gracefully: `BindingOutput.toRemapTarget()` for Steam-only outputs already returns `Unbound`, and the new category is hidden when `availableActionSets` is empty.
- **Picker encoding switched to `BindingOutput`.** Added `BindingOutput.encode()` / `BindingOutput.decode(String)` mirroring `toEntity` / `fromEntity` — same `<type>|<args>` shape. The picker route's `currentEncoded` arg now carries a `BindingOutput`-encoded string. `MainScreen` decodes the picker's saved-state result as `BindingOutput` and passes through to `InputEditorScreen.pickerResult: BindingOutput?` (was `RemapTarget?`). `ConfigureButtonScreen` keeps its `RemapTarget` shape; conversion happens at the boundary in `MainScreen` via `BindingOutput.toRemapTarget()` / `fromRemapTarget()`.
- **`showActionSets: Boolean` nav arg gates the new category.** Added to `MappoRoute.REMAP_TARGET_PICKER` so the picker only renders the category when called from the Steam-Input flow. ConfigureButton sets false; InputEditor sets true. Defended-in-depth: even if the flag flipped accidentally, the trackpad's `BindingOutput.toRemapTarget()` already drops Steam-only outputs.
- **Context-aware display label.** `BindingOutput.displayLabel()` (no args) only knows the binding shape, so it would have to read "Switch to: Set #42" — useless to the user. Added `BindingOutput.displayLabel(config: ControllerConfig?)` which resolves the target set's `title` from the config. Falls back to the numeric form when the config is null (legacy callers) or the set isn't in the config (stale binding after delete). Wired into `InputEditorScreen.CommandRow` and `RemapControlsScreen.BindingRowItem`, both of which thread `config` down.
- **`InputEditorScreen` API change.** `pickerResult: RemapTarget?` → `BindingOutput?`; `onOpenPicker: (String, RemapTarget) -> Unit` → `(String, BindingOutput) -> Unit`. The screen no longer wraps with `BindingOutput.fromRemapTarget()` — picker results arrive already in the right shape. Existing `InputEditorScreenTest` tests didn't need a churn pass; they always passed Steam-shaped inputs.
- **Saved-state result key is unchanged.** `PICKER_RESULT_KEY` still carries a `String`; only the encoded shape inside changed. Both call sites are updated, so nothing else reads the key.
- **No filter in the Switch Action Set list.** Action set counts are realistically 2–5 per controller_profile (Steam's stock templates have ~3); a search field would add IME-spawn risk for no benefit. The list is short enough to scroll instantly.
- **Helper subtext on action set rows.** Per `feedback_list_item_helper_subtext`: each row reads "Switches the active set at runtime" so a first-time user can tell the row does *something* rather than just being a navigational link. Will revisit if it reads redundant once the picker has been used a few times.
- **`testTag("category_<label>")` on category rows.** Light addition for test stability — the picker test for "Switch Action Set" category visibility leans on text lookups, but the tag is in place for tests that need to click the category by structural anchor.
- **Test count.** +6 in `BindingOutputTest` (encode/decode round-trip + 2 malformed-input fallbacks + 3 displayLabel cases). New `RemapTargetPickerScreenTest` (4 tests: visibility off / on, selection emits `ControllerAction("CHANGE_PRESET", [setId])`, current binding marks selected). +1 in `RemapControlsScreenTest` (BindingRowItem renders "Switch to: Menu" for CHANGE_PRESET binding). All ~280 project tests green.
- **Open follow-up: phantom layer chips.** Picker doesn't yet show **Add Layer** / **Remove Layer** / **Hold Layer** / **Mode Shift** categories — those land in Phase 5. The picker's category list is shaped to accept them without further refactoring.

### Brick 4.3 deviations + decisions

- **"Viewing" vs "active" terminology.** The screen-side selection is explicitly called "viewing" everywhere — `MainViewModel.viewingActionSetId`, `RemapControlsScreen.viewingActionSetId`, `onSelectActionSet`. Reserved "active" for the *runtime* set in the evaluator (Brick 4.2). The two are fully decoupled: a `CHANGE_PRESET` binding fires → evaluator's `activeSetId` flips → the editor doesn't move. The user taps a tab in the editor → `viewingActionSetId` flips → the evaluator doesn't move. Documented inline at every public surface.
- **Fallback semantics: null = follow default.** `viewingActionSetId: StateFlow<Long?>` defaults to null, meaning "render the controller_profile's default set." Lets the VM stay agnostic about which set is currently the default — that pointer can change underneath (Brick 4.4's "Set as default" will mutate it) without the VM having to chase. The screen's `viewingSet` computation does `viewingId?.let { lookup } ?: config.activeActionSet`. `ControllerConfig.resolveActionSet(setId: Long?): ActionSetGraph?` centralizes the same fallback for editor screens.
- **Stale-id cleanup runs in `activeControllerConfig.collect`.** The collector that already publishes to `InputDispatcher` got two extra lines: if the currently-viewed set isn't in the new config, reset the pointer to null. Cheap (one set-id scan per config emission), preserves correctness if the user deletes a set mid-edit (Brick 4.4 territory). A separate `activeProfile.collect` resets on profile-change because the new controller's sets don't map id-for-id.
- **Editor screens follow the viewing pointer.** Without this, the user would see set B's bindings in the overview but tapping a row would open set A's input editor — silent confusion. Threaded `viewingActionSetId: Long? = null` through `InputEditorScreen` and `ActivatorEditorScreen`; added `setId: Long?` to `ControllerConfig.findGroupInput` and `findActivator`; introduced `ControllerConfig.resolveActionSet(setId)` used by both editors + the chord-partner result handler. Default values keep the screens cheap to call from tests.
- **No "default set" visual marker yet.** Original brick description mentioned "default set highlighted" but I deferred a visual marker (dot / chip) on the default-set tab to Brick 4.4 (when "Set as default" becomes a user action). Rationale: marking a state the user can't change yet would feel inert; M3 PrimaryTabRow's underline already conveys "which set you're viewing," which is the active concept this brick. The default is naturally the *initial* selection (via the null-fallback). Easy to add a marker later if it reads thin once 4.4 lands.
- **Default values on `RemapControlsScreen` params.** `viewingActionSetId: Long? = null` + `onSelectActionSet: (Long) -> Unit = {}` carry defaults so the 6 existing Robolectric tests didn't need a churn pass. MainScreen always passes both explicitly.
- **Test count.** +3 tests in `RemapControlsScreenTest` (tab click invokes callback, viewing-pointer drives row preview, null falls back to default) → 9 total; +3 tests in `MainViewModelTest` (setter flow, profile-change reset, stale-id reset on config drop) → 80 total. Full suite 270+ tests green.

### Brick 4.2 deviations + decisions

- **`CompiledConfig` shape change.** Widened from `(activeActionSetId, inputs)` to `(defaultActionSetId, sets: Map<Long, CompiledActionSet>)` where each `CompiledActionSet(actionSetId, inputs)` carries one set's address→activators map. Runtime set switching is now an in-evaluator pointer flip — no recompilation needed, and the snapshot stays immutable. `toCompiled()` walks every action set in the graph; `defaultActionSetId` mirrors `controllerProfile.defaultActionSetId` (with fallback to the first set by orderIndex through `ControllerConfig.activeActionSet`).
- **Active set tracking on the evaluator.** `InputEvaluator.activeSetId: Long` is mutable runtime state, lazy-initialized to the snapshot's `defaultActionSetId` on the first event. A new `resolveActiveSet()` helper does the lazy init and also falls back to the new default if the current `activeSetId` disappeared from the snapshot (e.g., user deleted the active set via the editor). All lookups now flow through `lookupActive(address)` instead of `compiledConfig.lookup(source, key)`.
- **`CHANGE_PRESET` intercepted in the evaluator, not the emitter.** A new `tryHandleControllerAction(output)` helper checks for `ControllerAction(verb="CHANGE_PRESET", args=[setIdStr, ...])` *before* delegating to `OutputEmitter.emitPress`. Called from `doEmitPress`, `emitTap`, and `startRepeatJob` (defensively, in case a misconfigured turbo activator carries a CHANGE_PRESET). Rationale: ControllerAction verbs are evaluator-state mutations, not output-stream events — putting them on the emitter would have required an awkward back-channel callback. The emitter still has its `ControllerAction` no-op log path as a defense-in-depth fallback for verbs the evaluator doesn't recognize.
- **Steam semantics on swap: full transient flush.** Brought up `flushAllRuntime()`: releases all held bindings (emitting `emitRelease` for each), cancels every in-flight timer (pending long-press, double-tap windows, fire-start-delays, hold-to-repeat jobs), clears long-press deferrals and active chord links, **and releases toggle latches** (toggled-on bindings get their `emitRelease`; `activatorState[id].toggledOn` reset to false). `physicallyHeld` is intentionally *not* cleared — it tracks physical state, which doesn't change just because a software set switched. Phase 5's layer-stack clearing will reuse this same helper.
- **Stale active-set fallback is event-driven, not collector-based.** Considered observing `compiledConfig` changes via a collector to proactively reset `activeSetId`, but that would have required injecting a CoroutineScope into the evaluator's startup. The event-driven check in `resolveActiveSet()` is simpler and matches the evaluator's overall design (every action triggered by an actual input event, no background work).
- **`CompiledConfig.lookup` signature changed.** The old `lookup(source, key)` couldn't disambiguate between sets; now `lookup(setId, source, key)`. Test-side migration: a tiny `lookup(source, key)` extension on `CompiledConfig` in `CompiledConfigTest` picks the single configured set's inputs, so the existing tests didn't have to grow a `setId` parameter on every line. `InputEvaluatorTest` got a `configWithSet(setId, ...)` helper and a `configWithTwoSets(default, setA, setB)` helper for the new cases; the old single-set `configWith(...)` now delegates to `configWithSet(1L, ...)`.
- **Test count.** 7 new tests across the input package: `CompiledConfigTest.multipleActionSets_eachCompilesToItsOwnInputs`, and 6 in `InputEvaluatorTest` (lazy default-init, CHANGE_PRESET swap + cross-set lookup, invalid-target no-op, held-binding release on swap, stale-active-set fallback after config swap, toggle-latch clearing on swap). Existing `defaultActionSetId_propagatesFromGraph` test renamed from `activeActionSetId_propagatesFromGraph`. All 270+ project tests green.
- **`MainViewModel` log string update.** The "Published CompiledConfig" log line referenced the old `activeActionSetId`/`inputs` fields; updated to `defaultActionSetId` / `sets.size`. The only consumer of the old shape outside tests.

### Brick 4.1 deviations + decisions

- **Default-set tracking.** Added `defaultActionSetId: Long?` to `ControllerProfile` rather than `isDefault: Boolean` on `ActionSet`. The pointer lives on the parent → at-most-one-default is enforced by data shape, and "which set is default" is a property of the controller_profile, not of any individual set. **No FK declared** on the column — declaring one would create a cyclic FK between `controller_profile` and `action_set` (both tables already cross-reference each other), and Room's migration story for circular FKs is fragile. Repo maintains integrity instead: `deleteActionSet` clears/reassigns the pointer on delete, `setDefaultActionSet` rejects sets that don't belong to the named controller_profile, and `ControllerConfig.activeActionSet` falls back to "first set by orderIndex" if the pointer is null or stale.
- **`activeActionSet` semantics.** Updated `ControllerConfig.activeActionSet` to prefer `controllerProfile.defaultActionSetId`, falling back to `actionSets.firstOrNull()`. "Active" here means *default-active at config load time*; **runtime set switching** (which set is *currently* in effect after a `CHANGE_PRESET` fired) is Brick 4.2 territory, lives in the evaluator's mutable state, and is not reflected in the materialized graph.
- **Db version bump.** `AppDatabase` v10 → v11. Pre-release `fallbackToDestructiveMigration(dropAllTables = true)` (per `project_mappo_pre_release.md`) means no manual migration code — the field arrives via destructive wipe on first launch after upgrade.
- **`copyConfig` default-pointer remap.** `copyConfig` was previously copying `defaultActionSetId` verbatim — which left the cloned controller_profile pointing at the *source* set's id, not the clone's. Fixed by remapping through the `setIdMap` after the per-set clone loop. Test `copyConfig_remapsDefaultActionSetIdToClonedSet` covers it.
- **Add / duplicate consolidation.** `addActionSet(controllerProfileId, name, title, inheritFromSetId = null)` handles both "blank seeded set" and "inherit from existing set"; `duplicateActionSet(sourceSetId, name, title)` is a one-line wrapper that resolves the source's parent + delegates. UI layer can call either depending on which dialog flow the user is in (Brick 4.4).
- **Set-content seeding refactored.** Extracted `seedDefaultSetContents(actionSetId)` out of `seedDefaultConfig`; `addActionSet`'s blank path calls the same helper. Avoids duplicating the default-input-source seed loop. `cloneSetContents(sourceSetId, destSetId)` is the parallel helper for the inherit path — adapts the per-set portion of `copyConfig`'s logic to a single set.
- **Delete guards: last-set + cascade.** `deleteActionSet` refuses to delete when only one set remains under a controller_profile (returns `false` — UI is expected to disable the Delete affordance in that case). Cascades through Room's FK ON DELETE for group/input/activator/binding/preset/layer cleanup — the unit-test fakes don't model cascades, but every assertion checks the canonical row (`action_set`) so the test pass doesn't depend on cascade behavior. If a future test needs to assert on orphan cleanup we'll thread explicit deletes through the repo.
- **Test count.** 13 new tests in `ControllerConfigRepositoryTest` (39 total in that file): seed-sets-default, addActionSet blank/inherit, rename, duplicate independence, delete guards (last-set, non-last, default reassignment), setDefault (happy path + cross-profile rejection), copyConfig default remap.

---

## Phase 5 — Action layers (mode-shift deferred → Phase 6)

> **Scope change vs. original plan:** mode-shift was originally bundled with layers but is conceptually closer to *modes* (the per-source authoring problem) than to layers (the stacking-overlay problem). It moves into Phase 6 alongside mode authoring, where the source ↔ target compatibility matrix can be checked when wiring the picker. Phase 5 is now layers-only.

### Brick breakdown

- **5.1 — Runtime: layer stack in InputEvaluator + CompiledConfig overlays**. New verbs `add_layer` / `remove_layer` / `hold_layer` in `tryHandleControllerAction`; `CompiledConfig` carries per-layer overlay groups; last-in-wins resolution; `CHANGE_PRESET` clears stack.
- **5.2 — Repo CRUD: addLayer / renameLayer / duplicateLayer / deleteLayer** (empty seed for new layers). When duplicating, every overlay's groups/inputs/activators/bindings get fresh ids per `feedback_duplicates_own_their_data`.
- **5.3 — VM: viewingLayerId pointer** with stale-id and profile-change cleanup, plus an `availableLayers` flow for picker categories.
- **5.4 — UI: Layers pill row beside Action Set tabs**, with `[+]` and per-pill overflow menu (Rename / Duplicate / Delete).
- **5.5 — UI: Overlay editing mode** with ghost text for parent-set bindings and a "Show all / Only overrides" toggle. This is where the per-layer preset-binding schema needs to land (so an overlay can actually associate a group with an input source).
- **5.6 — Picker: Layer activation categories** (Add Layer sticky, Add Layer while-held, Hold Layer, Remove Layer).

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

- **Edit** `app/src/main/java/com/mappo/ui/screen/RemapControlsScreen.kt` — Layers row + ghost-text overlay rendering
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/LayerManagementDialogs.kt`
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/LayerActivationPickerScreen.kt`
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/ModeShiftPickerScreen.kt`
- **Edit** `app/src/main/java/com/mappo/service/input/InputEvaluator.kt` — layer stack + mode_shifts table

### Decision points

- **Layer "while-held add/remove" UX**: Steam pairs `Full_Press add_layer` with a `release remove_layer` activator on the same input. Options: (a) require the user to add both activators manually (faithful to Steam), or (b) collapse it to one UI action ("Add Layer (while held)") that auto-generates both. Recommend (b) — much friendlier; the underlying data still encodes two activators.
- **Layer override indicator on the controller overview**: small dot or text suffix? — flag at implementation time.

### Verify

- Bind RB → Add Layer (while held) = "Scope". Layer "Scope" overrides A = MOUSE_LEFT. Hold RB → A emits left-click; release RB → A emits ENTER again.
- Switching action sets clears the layer stack (verified by activating two stacked layers, switching sets, switching back — stack is empty).

### Brick 5.6 deviations + decisions

- **What it covers**: a new "Layer" category in `RemapTargetPickerScreen` (Brick 4.5's generalized picker). Two-step flow — pick verb → pick layer. The picker emits `BindingOutput.ControllerAction(verb, [layerId])` for one of three verbs. New `MappoRoute.ARG_SHOW_LAYERS` follows the same call-site rule as `ARG_SHOW_ACTION_SETS` (InputEditor true; ConfigureButton false). `availableLayers` is sourced from the viewing action set's layers in `MainScreen` (Steam-faithful — layers are scoped to a set's namespace).
- **Three verbs, not four**: parity plan listed four options ("Add Layer sticky", "Add Layer while-held with auto-release", "Hold Layer", "Remove Layer"). Collapsed to **three** — `add_layer` (sticky), `hold_layer` (while held), `remove_layer`. The dropped "Add Layer while-held with auto-release" is Steam UI sugar that generates an explicit `FULL_PRESS add_layer` + `RELEASE_PRESS remove_layer` activator pair; `hold_layer` is Steam's single-verb shorthand that does the same thing. Exposing both as user-facing options would have been duplicative and confusing — the user would have no way to know which to pick. The pair form can still be authored manually (FULL_PRESS bound to `add_layer` + a RELEASE activator bound to `remove_layer`); a future brick can add a one-tap sugar option if the manual path turns out to be too friction-heavy.
- **`displayLabel(config)` extended** to resolve all three layer verbs to their layer's title — "Add Layer: Scope" / "Hold Layer: Vehicle" / "Remove Layer: Scope". Falls back to "Add Layer: Layer #N" form when config is null or the layer is missing (stale binding after delete), mirroring the `CHANGE_PRESET` "Set #N" fallback.
- **Verb-then-layer order** (not layer-then-verb). With 3 verbs × N layers, the matrix grows on the layer side. Putting the verb first keeps the verb list a stable 3 rows and lets the layer list expand independently. Also makes the picker's "current selection" check work cleanly — the verb screen highlights the bound verb if any.
- **`availableLayers` from `viewingSet.layers`, not the VM's `availableLayers` flow.** MainScreen already has the active config in scope; reading from the config's viewing-set graph is one fewer hop than collecting the StateFlow. Both produce the same data — the VM flow exists for the eventual InputEditor picker call site to consume directly (5.6 still routes through MainScreen for nav, so the config-side read works).
- **New picker states**: `RemapPickerState.LayerVerbList` (object — no params; verbs are fixed) and `LayerSelectionList(verb)` (data class — carries the chosen verb forward so the layer-selection screen knows what to emit).
- **`matchesCategory` recognizes any of the three verbs as "Layer"**: a binding bound to any of `add_layer`/`hold_layer`/`remove_layer` flags the top-level Layer category as selected. The verb-list screen then highlights the specific verb. The layer-list screen highlights the specific layer.
- **Empty-state copy**: "No layers in this action set yet" — surfaces only if the user navigates into the layer list with zero layers, which shouldn't happen in practice (the Layer category is gated on `availableLayers.isNotEmpty()`) but is defensive against a config-emission race.
- **Tests added (11)**: 6 picker Robolectric (`layerCategory_hiddenWhenAvailableLayersEmpty`, `layerCategory_visibleWhenLayersProvided`, `layerCategory_routesToVerbList_withThreeOptions`, plus one emission test per verb), 5 `displayLabel` unit (`addLayer/holdLayer/removeLayer` resolve to titles; fallback to "Layer #N"; no-config fallback). The existing `controllerAction_nonChangePreset_keepsVerbLabel` test was repurposed for unknown verbs ("mode_shift_combo") since `add_layer` now has its own label.

### Brick 5.5.c deviations + decisions

- **What it covers**: full overlay-editing UX. `BindingRowItem` resolves the row by checking the focused layer's preset first, falling through to the base set's preset. Override rows render in primary color with a trailing `[⋮]` "Override actions" menu; ghost rows (base shown through) render at alpha 0.5 with no trailing menu. A "Show all / Only overrides" FilterChip toggle renders above the binding list in overlay mode and filters the items list to overridden rows plus their surviving subheaders. Tapping a row in overlay mode materializes the override eagerly (via MainScreen-level coroutine), then navigates to `InputEditorScreen` which is now layer-aware and reads from the layer's preset.
- **Ghost styling via `Modifier.alpha(0.5f)` on the row content** rather than a color-token swap. M3 doesn't have a "ghost text" token; alpha is the visual idiom the parity plan calls for and matches how comparable apps (Reply, Jetcaster) render derived/inherited state. Limited to the content columns, not the clickable area — ghost rows are still tappable (tap materializes the override).
- **Eager materialization on tap** (the option we discussed earlier). The simpler path: row tap → materialize → navigate to a real editor screen. The alternative (lazy materialize on first picker-result write) would have required the editor to know about layer context and choose its write target at runtime — more complex and harder to test. The downside (an unbound override remains if the user navigates away without binding anything) is mitigated by the visual feedback: the row immediately shifts from ghost to "Unbound" in primary color, signaling that an override was created. The user can clear via the trailing menu.
- **Row-level overflow only when override exists** (not on ghost rows). Ghost rows have nothing to clear, so the affordance is meaningless. This is consistent with the Brick 5.4 layer-row overflow being disabled when no layer is focused.
- **`onlyOverrides` toggle uses `rememberSaveable`** to survive screen rotation but intentionally NOT nav (re-entering the screen starts from "Show all"). Auto-resets to false when `viewingLayer == null` so a leftover "Only overrides" filter from a prior overlay session doesn't carry into the next.
- **Subheader-aware filtering**: `filterToOverrides` queues each subheader until a surviving binding row follows; if the next subheader arrives first, the queued one is discarded. Disabled rows are always hidden in overrides-only (they can't be overridden anyway). Pure-function so the exhaustive shape behavior is testable without Robolectric — `FilterToOverridesTest` covers 6 cases.
- **`InputEditorScreen` layer plumbing**: new `viewingLayerId: Long? = null` prop. `findGroupInput` extended with a `layerId: Long?` param — when non-null, query the layer's preset first; fall through to the base only as a stale-pointer safety net (5.5.c materializes before navigating, so a missing layer override at editor-open shouldn't normally happen). Activator/binding CRUD paths target the layer's binding ids naturally because the screen reads them off the layer's `GroupInputGraph`.
- **No editor-side ghosting of base activators**: when the user opens the editor against a freshly-materialized override, the screen shows ONLY the new (default `FULL_PRESS`+unbound) activator. The base set's activators are not displayed as ghosts. Rationale: Steam doesn't inherit activators across layer/base boundaries — an override replaces the whole activator set, not individual activators. Displaying base activators as ghosts in the editor would imply selective per-activator overrides that don't exist.
- **Materialization-then-nav in MainScreen** via `scope.launch`. The `onOpenInputEditor` callback wraps `viewModel.materializeLayerOverride(...)` (suspend) before `navController.navigate(...)` when `viewingLayerId != null`. Base mode bypasses materialize entirely (no extra round-trip).
- **Renamed local `viewingLayer` → `viewingLayerEntity`** in the dialog block to free `viewingLayer` for the new graph-typed binding used by the detail pane. The dialogs need the `ActionLayer` entity; the detail pane needs the `ActionLayerGraph`. Different views of the same underlying row.
- **Tests added (10)**: 4 Robolectric (`overlayMode_ghostRow_showsBaseBindingWhenLayerHasNoOverride`, `overlayMode_overriddenRow_showsLayerBindingAndOverflowMenu`, `overlayMode_clearOverride_invokesCallback_withLayerSourceAndKey`, `overridesFilterToggle_hiddenInBaseMode`, `overridesFilterToggle_visibleInOverlayMode_andOverriddenRowSurvivesFilter`) + 6 pure-function in new `FilterToOverridesTest` (subheader resurrection, drop-when-no-survivors, disabled-always-hidden, mid-section pendingSubheader reset semantics, etc.). Added a `configWithLayerOverride` helper in `RemapControlsScreenTest` that builds a single-layer config with optional `button_a` override.
- **Robolectric LazyColumn caveat**: tried asserting "B/X/Y rows disappear after toggle" but Robolectric doesn't materialize below-the-fold rows in the semantic tree reliably (per `feedback_robolectric_compose_pitfalls`), so the negative assertions were flaky. Moved exhaustive filter shape to the pure-function test instead. The Robolectric test only confirms the overridden row survives, which is the positive-signal side.

### Brick 5.5.b deviations + decisions

- **What it covers**: `ControllerConfigRepository.materializeLayerOverride(layerId, inputSource, groupInputKey): Long` and `clearLayerOverride(...)`. VM wrappers `materializeLayerOverride` (suspend, returns `Long?` — null when no active profile) and `clearLayerOverride` (launches via `viewModelScope`). The materialize call is what the 5.5.c UI will invoke when the user taps a ghost row; clear is the "revert to inheritance" affordance.
- **Override grain — one overlay group per input source on the layer**, shared across its sub-inputs. Mirrors the base set's grain (a single `BUTTON_DIAMOND` group holds all four diamond sub-inputs, even when the user has only authored two). The runtime evaluator (5.1) already keys overrides at the sub-input level (`InputAddress(source, key)`), so layer overrides naturally fall through per-address — there's no "whole-group override" semantics to worry about. Tested in `materializeLayerOverride_secondSubInputOnSameSource_reusesOverlayGroup`.
- **Idempotency on the sub-input**: re-materializing the same `(layerId, inputSource, groupInputKey)` returns the existing `GroupInput.id` and doesn't duplicate the activator/binding. Important for the UI flow where a "ghost row tap" may race with a config emission — the call must be safe to repeat. Tested in `materializeLayerOverride_calledTwiceForSameSubInput_isIdempotent`.
- **Inherits base group's mode + settingsJson** when the overlay group is first created. The user's natural expectation when overriding a trackpad button on a layer is that the trackpad's deadzones/sensitivity stay the same as on the base. Per-layer mode + settings divergence is a Phase 6 affordance, not 5.5. Tested in `materializeLayerOverride_inheritsBaseGroupModeAndSettings`.
- **Cleanup of empty overlay groups**: when `clearLayerOverride` removes the last sub-input from an overlay group, the group itself and its `LayerPresetBinding` row are also dropped. **Explicit cleanup** rather than relying on the FK cascade — the cascade exists in the real DB but the test fakes don't replicate it, and explicit code at the call site makes the cleanup intent obvious anyway. Tested in `clearLayerOverride_lastSubInput_alsoDropsOverlayGroupAndPresetRow` and `clearLayerOverride_keepsSiblingSubInputsIntact`.
- **Defaults for materialized chain**: `FULL_PRESS` activator with `settingsJson = "{}"`, single `UNBOUND` binding. The 5.5.c UI flow is "tap ghost row → materialize → immediately open the picker → write the real output" — the unbound stub only exists for the microsecond before `setBinding` overwrites it. Symmetric with how `addLayer` seeds empty layers.
- **No-op semantics on clear**: clearing an unknown layer, unknown input source, or unknown sub-input is a silent no-op (no throw, no log noise). Defensive but cheap — the UI shouldn't be able to drive these calls with bad ids in practice, but a stale tap from a concurrent edit shouldn't blow up. Tested in `clearLayerOverride_unknownOverride_isNoOp` and `clearLayerOverride_unknownSubInputOnExistingOverlayGroup_isNoOp`.
- **Tests added (12)**: 8 repo cases (`materializeLayerOverride_freshLayer_createsGroupInputActivatorAndBindingChain`, `_secondSubInputOnSameSource_reusesOverlayGroup`, `_calledTwiceForSameSubInput_isIdempotent`, `_inheritsBaseGroupModeAndSettings`; `clearLayerOverride_existingOverride_deletesGroupInput`, `_lastSubInput_alsoDropsOverlayGroupAndPresetRow`, `_keepsSiblingSubInputsIntact`, `_unknownOverride_isNoOp`, `_unknownSubInputOnExistingOverlayGroup_isNoOp`) + 4 VM wrapper cases (no-profile + delegate + return-id flavors for materialize; no-profile + delegate for clear).

### Brick 5.5.a deviations + decisions

- **What it covers**: schema + compiled-config wiring for layer-scoped preset bindings. New `LayerPresetBinding` entity + `LayerPresetBindingDao` (separate table — Option 2 from the schema-shape discussion); `ActionLayerGraph` gains a `preset: List<PresetEntry>` field parallel to `ActionSetGraph.preset`; `ControllerConfigRepository.loadConfigSnapshot` hydrates it from the new DAO; `ControllerConfig.toCompiled()` folds each layer's preset into `CompiledLayer.inputs` so the runtime layer-stack walk (Brick 5.1) actually produces overrides. `duplicateLayer` extended to also clone `LayerPresetBinding` rows, remapping `bindingGroupId` through the existing groupIdMap.
- **Schema shape — Option 2 (separate `layer_preset_binding` table)** chosen over a nullable column on `preset_binding` or a polymorphic owner column. Reasoning: each table has a single, unambiguous purpose; FK constraints stay clean both sides; the boilerplate cost is bounded (one entity + DAO, doesn't grow with feature work). The inconsistency with `BindingGroup.ownerKind/ownerId` (which is polymorphic) is logged in `project_schema_polymorphic_owner_audit_deferred` as a post-parity audit item — harmonization isn't a no-brainer because option-3 → option-2 just relocates polymorphism into another FK.
- **DB version bumped to 13** (destructive migration via `fallbackToDestructiveMigration(dropAllTables = true)` per `project_mappo_pre_release`).
- **No 5.5.a runtime change to `InputEvaluator`**: the layer-stack walk landed in 5.1 with `CompiledLayer.inputs` as an empty map placeholder. 5.5.a only populates that map. Verified by the new `actionLayer_presetEntries_areFoldedIntoCompiledLayerInputs` test — the existing 5.1 walk produces overrides as soon as the map is non-empty.
- **Hilt provider added** for `LayerPresetBindingDao` (`provideLayerPresetBindingDao` in `AppModule`). `FakeLayerPresetBindingDao` added under `app/src/test/.../FakeSteamDaos.kt` for the repo tests.
- **Tests added (5)**: `CompiledConfigTest.actionLayer_presetEntries_areFoldedIntoCompiledLayerInputs` (base + layer override both compile correctly), `CompiledConfigTest.actionLayer_inactiveStatePresetEntries_areSkipped` (mirror of base-set inactive-state behavior), `ControllerConfigRepositoryTest.observeActiveConfig_hydratesActionLayerGraphPreset_fromLayerPresetBindingDao`, `ControllerConfigRepositoryTest.duplicateLayer_clonesLayerPresetBindingRows_pointingAtClonedGroups`, `ControllerConfigRepositoryTest.observeActiveConfig_layerWithNoPreset_yieldsEmptyPresetList`. The 5.1 `actionLayers_areMaterializedIntoCompiledActionSetLayersMap` test's docstring updated to reflect that empty inputs now means "no preset entries" rather than "schema doesn't exist yet."
- **Persistence gap closing**: 5.5.a finishes what 5.1's "Persistence gap (intentional)" deferred. From here on `CompiledLayer.inputs` carries real data driven by user-authored overrides.

### Brick 5.4 deviations + decisions

- **What it covers**: live Layers pill row in `RemapControlsScreen` beside the action-set tabs, plus four management dialogs (`AddLayerDialog` / `RenameLayerDialog` / `DuplicateLayerDialog` / `DeleteLayerConfirmDialog`) in a new `LayerManagementDialogs.kt`. New `RemapControlsScreen` props: `viewingLayerId`, `onSelectLayer`, `onAddLayer`, `onRenameLayer`, `onDuplicateLayer`, `onDeleteLayer` — wired through `MainScreen` to the VM CRUD added in 5.3.
- **Toggle semantics, no separate "Base" pill**: tapping the focused FilterChip drops focus back to base (fires `onSelectLayer(null)`). Steam doesn't model the base set as a peer-pill; the action set IS the base. A "Base" chip would have suggested a separate editing context that doesn't exist. Captured in `tappingFocusedLayerPill_togglesBackToBase_withNull`.
- **Row-level overflow, not per-pill long-press** (parallels the 4.4 action-set deviation): the parity plan called for "long-press a layer → context menu" but row-level overflow operating on the focused pill stays consistent with the existing action-set row. The original justification (Tab selectable-wrapper gesture conflict) doesn't apply to FilterChips, so this is consistency-driven — not technically forced. Documented here so the consistency intent is explicit if we reconsider later.
- **No "inherit from" picker on Add Layer** (deviation from the AddSet dialog shape). Layers are by-definition overlay overrides; "empty" means "every binding falls through to the parent set," which is the right starting point for a fresh layer. Cloning starter content is `Duplicate`'s job. The dialog instead surfaces a one-line tutorialization sentence ("Starts empty — bindings on the parent set show through until you override them.") per `feedback_list_item_helper_subtext`.
- **Empty-state explicit affordance**: when a viewing set has zero layers, the row still renders "Layers: (none) [+]" so the create path stays discoverable. The overflow [⋮] is absent entirely in that state (no target). When a set has layers but none is focused, the overflow renders disabled — both communicating "select a target first" and avoiding misclicks.
- **Per-set scoping**: the pill row reads `viewingSet?.layers` directly from the `ControllerConfig` rather than from VM's `availableLayers` flow. The flow is wired for 5.6's picker call site, where there's no config in scope; the screen already has the full config and the layers it needs.
- **Horizontal scroll**: pills are wrapped in a `Row { horizontalScroll(rememberScrollState()) }` so very long titles or many layers don't break the row layout on the AYN Thor's bottom screen. No fixed visible-count cap; the [+] + [⋮] stay pinned to the right.
- **Tests added (8)**: `layersRow_emptyState_showsNoneAndAddIsEnabled`, `layersRow_rendersPillPerLayer_andSelectionFollowsViewingLayerId`, `tappingLayerPill_invokesOnSelectLayer_withId`, `tappingFocusedLayerPill_togglesBackToBase_withNull`, `addLayerButton_opensAddLayerDialog`, `layerOverflowMenu_showsManagementItems_forFocusedLayer`, `layerOverflowMenu_disabled_whenNoLayerFocused`, `layerOverflowMenu_absent_whenZeroLayers`, `layerRow_perSet_pillsReflectViewingSet`. New `singleSetConfigWithLayers` + `twoSetConfigWithLayers` helpers attach `ActionLayerGraph` instances to the existing sample configs.

### Brick 5.3 deviations + decisions

- **What it covers**: `MainViewModel.viewingLayerId` (per-set focus pointer), `availableLayers` (derived flow for picker + pill row), and CRUD wrappers `addControllerActionLayer / renameControllerActionLayer / duplicateControllerActionLayer / deleteControllerActionLayer`. Add/duplicate flip the viewing pointer to the new layer, mirroring the action-set pattern.
- **`availableLayers` uses `SharingStarted.Eagerly`** rather than `WhileSubscribed`. The derived data is trivial (a `List<Pair<Long, String>>`) and the upstream `activeControllerConfig` is already kept hot by the in-init compile collector. `Eagerly` makes `.value` readable without a subscriber, which keeps the picker call site (5.6) and tests simple. Worth being aware of if `availableLayers` ever grows expensive.
- **Layers are per-set, by design**: the viewing-pointer collector also resets on every `viewingActionSetId` emission, since layer ids in one set are unrelated to layer ids in any other. The stale-id check on config emissions is scoped to the *resolved* viewing set (the starting-set fallback when the pointer is null), not the whole config — so editing layer X in set 2 doesn't disturb a pointer on layer Y in set 1.
- **No cleanup in `deleteControllerActionLayer`**: same pattern as `deleteControllerActionSet` — the existing `activeControllerConfig` collector handles stale-id reset when the deletion lands in the next config emission, so the wrapper doesn't have to special-case it.
- **Tests added (12)**: `setViewingLayer_flowsThroughStateFlow`, `viewingLayerId_resetsToNull_whenActiveProfileChanges`, `viewingLayerId_resetsToNull_whenViewingActionSetChanges`, `viewingLayerId_resetsToNull_whenLayerDisappearsFromConfig`, `viewingLayerId_isNotClearedWhenAnotherSetsLayerChanges`, `availableLayers_reflectsViewingSet`, `addControllerActionLayer_noActiveProfile_isNoOp`, `addControllerActionLayer_delegatesToRepo_andFlipsViewingLayerToNew`, `renameControllerActionLayer_delegatesToRepo`, `renameControllerActionLayer_noActiveProfile_isNoOp`, `duplicateControllerActionLayer_flipsViewingLayerToCopy`, `deleteControllerActionLayer_delegatesToRepo`. The `miniConfig` test helper now optionally accepts a `layersBySet` map so 5.3 cleanup tests can assert against realistic layer-bearing configs.

### Brick 5.2 deviations + decisions

- **What it covers**: `ControllerConfigRepository.addLayer / renameLayer / duplicateLayer / deleteLayer`. New layers are seeded empty (no overlay binding_groups) — the per-layer preset-binding schema still doesn't exist, and overlays only get authored in 5.5. `duplicateLayer` already has full deep-clone wiring (binding_groups → group_inputs → activators → bindings) so 5.5's overlay-authoring writes don't have to retrofit it.
- **No "last layer must remain" guard** (unlike `deleteActionSet`). An action set with zero layers is a valid state per Steam — layers are optional overlays, not mandatory siblings.
- **Plain orderIndex semantics** for now: `addLayer` appends to the end of the list; no reorder UX. The pill row in 5.4 will read these in order; reordering can land later if needed.
- **Tests added (7)**: `addLayer_appendsEmptyLayerWithOrderIndexZero`, `addLayer_secondLayerGetsIncrementedOrderIndex`, `renameLayer_updatesNameAndTitle`, `renameLayer_unknownId_isNoOp`, `deleteLayer_unknownId_returnsFalse`, `deleteLayer_existing_succeeds`, `duplicateLayer_emptySource_clonesLayerRowOnly`, `duplicateLayer_clonesGroupsInputsActivatorsBindings_withFreshIds` (seeds an overlay group by hand to exercise the cloning path forward-compat), `duplicateLayer_editsToCloneDoNotAffectSource`.

### Brick 5.1 deviations + decisions

- **What it covers**: runtime layer stack + the three new controller verbs + `CompiledConfig.CompiledActionSet.layers`. Verified end-to-end via 11 new `InputEvaluatorTest` cases (overlay overrides base; last-in-wins; sticky add/remove; hold while-held; CHANGE_PRESET clearing; idempotent add when already active; safe handling of unknown ids; force-release cleanup; tap-context hold rejection). Plus a `CompiledConfigTest` case that compiles a set with two layer rows and asserts the snapshot's `layers` map carries them.
- **The `hold_layer` address question** turned out to need plumbing: `tryHandleControllerAction(output)` now takes a nullable `InputAddress`. `doEmitPress` passes the address; turbo repeat and `emitTap` pass null. `hold_layer` rejects null with a log line ("use FULL_PRESS for while-held layers") — there's no UP for it to fire on in a tap context.
- **`add_layer` semantics chosen**: Steam-faithful — re-adding an already-active layer is a no-op, *not* a reorder. To move a layer to the top, callers must `remove_layer` then `add_layer`. Captured in the addLayer doc; tested in `addLayer_alreadyActive_isNoOp_doesNotReorderStack`.
- **Persistence gap (intentional)**: `CompiledLayer.inputs` is materialized as empty for now because there's no per-layer preset-binding schema yet. The layer stack runtime is testable today by synthesizing `CompiledConfig` directly in tests; the schema/CRUD piece arrives in 5.2 / 5.5. `ControllerConfig.toCompiled()` still walks `setGraph.layers` and emits one `CompiledLayer` per layer row — only the `inputs` map starts empty.
- **flushAllRuntime extended** (Brick 4.2 set-switch path) to clear both `activeLayers` and `heldLayerByAddress`, with a log line listing the cleared layers.
- **Tests added**: `InputEvaluatorTest` — `addLayer_overlayOverridesBaseAddress_onSubsequentPress`, `addLayer_alreadyActive_isNoOp_doesNotReorderStack`, `removeLayer_dropsFromStack_baseRebinds`, `removeLayer_unknownId_isNoOp`, `addLayer_unknownId_isNoOpAndDoesNotPushOntoStack`, `layerStack_lastInWins_onConflict`, `layerStack_removeTopLayer_underlyingLayerResolves`, `holdLayer_activatesOnPress_releasesOnUp`, `holdLayer_fromTapContext_isWarnedAndIgnored`, `changePreset_clearsActiveLayerStack`, `holdLayer_releasedByForceRelease_onDuplicateDown`. `CompiledConfigTest` — `actionLayers_areMaterializedIntoCompiledActionSetLayersMap`.

---

## Phase 6 — Input source modes — ✅ COMPLETED (via Shizuku pivot 2026-05-25)

> **Closed 2026-05-25.** The original Phase 6 plan below was reduced-scoped on 2026-05-16, then reopened via a Shizuku-based motion-capture pivot 2026-05-23 and fully shipped 2026-05-25. The shipped architecture differs substantially from the original plan — analog modes ride on a Shizuku UserService reading `/dev/input/event*` and a `/dev/uinput` virtual mouse, rather than the focused-overlay capture path originally outlined here. See `~/.claude/projects/-Users-dylanbperry-projects-mappo/memory/project_phase_6_shipped.md` for the final architecture and `~/.claude/plans/i-have-the-biggest-jazzy-micali.md` for the detailed brick-by-brick implementation record. Original plan content retained below for historical reference.

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

Mode classes live under `app/src/main/java/com/mappo/service/input/modes/` (one file per mode).

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

- **Create** `app/src/main/java/com/mappo/service/input/modes/` (one file per mode + a `BindingMode` interface)
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/SourceEditorScreen.kt`
- **Create** `app/src/main/java/com/mappo/ui/screen/binding/ModeSettingsPanels/` — one composable per mode's settings panel
- **Edit** `app/src/main/java/com/mappo/service/input/InputEvaluator.kt` — delegate sub-input event production to the active mode class
- **Edit** `app/src/main/java/com/mappo/service/InputAccessibilityService.kt` — output side: emit analog mouse moves (existing gesture infra), relative-mouse via accessibility cursor

### Decision points

- **Mouse-output mechanism**: today's mouse-click goes through accessibility `dispatchGesture`. For absolute mouse / joystick-camera relative-mouse, we'll need cursor-move events. Existing virtual-cursor logic in the accessibility service is a starting point; flag this for implementation review.
- **Flickstick + gyro**: ship without flickstick this phase; revisit once gyro is in scope. (AYN Thor IMU situation isn't characterized in current memory.)

### Verify

- Right joystick → Mouse Joystick mode. Move stick → mouse cursor moves. Sensitivity setting affects speed.
- Right trackpad → Scroll Wheel. Rotate finger → emits scroll up/down.
- L2 trigger → Trigger mode, soft-press threshold 0.3. Squeezing trigger lightly → soft-press activator fires; full pull → click activator fires.
- Left stick mode swapped Joystick Move → Dpad. Push stick up → emits dpad_up.

### Phase 6 closed in reduced scope (decided 2026-05-16)

Phase 6 ended without the analog modes or source-mode picker UI. The dependency chain is:

1. **Analog modes** need continuous gamepad-axis input.
2. **Motion capture on stock Android** without root: focusable-accessibility-overlay path captures motion events but causes system focus side effects (IME / back gesture / cursor / app switcher break) — tabled pending a wider refactor.
3. **The wider refactor depends on the single-screen-device architecture work** that's planned but hasn't started — see `project_target_single_screen_pivot.md`.

So the analog tail of Phase 6 waits for architecture work that waits for its own design pass. Locked into the order: single-screen architecture → motion-capture refactor → Phase 6 analog modes → Phase 6 mode-picker UI.

**Bricks completed:**

- **6.1** ✓ — `SourceMode` foundation + Single Button + Button Pad (compile-time sub-input validation).
- **6.2** ✓ — Motion-capture scaffolding landed (`AnalogEvent`, `MotionEventNormalizer`, `InputEvaluator.handleMotion`, `MotionCaptureOverlay`). Motion capture itself **tabled** — see `project_motion_capture_via_focusable_overlay.md`.
- **6.3** ✓ — Dpad mode (digital). No analog-stick-as-dpad gating.
- **6.4** ✓ — Trigger mode (digital click only). Soft_Press activator runtime stays inert.

**Bricks NOT done (analog tail, pending refactor):**

- **6.5** — Source mode picker UI. Skipped — most digital-only mode swaps are no-ops, so the picker had no meaningful use case yet. Existing `DisabledModeDropdown` scaffolding kept in place for future activation.
- Analog modes: Joystick Move, Joystick Camera, Mouse Joystick, Absolute Mouse, Scroll Wheel, 2D Scroll.
- Soft_Press activator runtime.
- Flickstick (gyro-dependent).
- Mouse Region (overlay-rendering-dependent).
- Reference mode — folded into Phase 7 (VDF import) where it's actually consumed.

**Next phase gate:** Phase 7 (VDF import). VDF parsing can land structurally even though analog modes don't run at runtime — imported configs that reference analog modes store cleanly; they just don't fire.

### Brick 6.4 deviations + decisions

**Scope landed.** `TriggerMode` `SourceMode` handler. `validInputs = {click}`. `defaultSettingsJson = {"click_threshold":0.95}` — Steam-default click threshold. Registry: `BindingMode.TRIGGER.handler()` now returns `TriggerMode` (was `StubMode(TRIGGER)`).

**No runtime behavior change for the user.** Existing path is unchanged: hardware-threshold trigger pulls fire `KEYCODE_BUTTON_L2` / `KEYCODE_BUTTON_R2` through `onKeyEvent`, the accessibility service routes them to `InputAddress(LEFT_TRIGGER, "click")` / `(RIGHT_TRIGGER, "click")`, the evaluator dispatches whatever `FULL_PRESS` activator is bound. The brick adds compile-time validation (TRIGGER strictly accepts only `click`) and the settings JSON shape — both data-model only.

**Soft_Press activator status.** Steam's `Soft_Press` activator fires when the analog trigger pull crosses a soft threshold *before* the click threshold. The enum value (`ActivatorType.SOFT_PRESS`) exists; the evaluator's per-press switch routes it to the no-op fall-through (`"skipping ${activator.type} activator (later brick)"`). It stays inert until the motion-capture refactor lands — without an analog pull stream, there's no "below-click" signal to threshold against.

**`click_threshold` setting is inert too** — its job is to compare an analog pull magnitude against a configurable cutoff, but we get only the hardware's binary L2/R2 events today. Stored for the schema, runs nothing.

**Tests.** 4 new in `SourceModeTest` (TriggerMode validInputs / accepts / defaultSettings / registry identity). 1 new in `CompiledConfigTest` (`triggerMode_acceptsOnlyClick` — strict validation drops a misseeded `button_a`). Registry-sweep test updated to know TRIGGER is no longer in the stub bucket. Full suite green.

**What this brick does NOT do.**
- No analog pull processing (the actual reason Trigger mode exists in Steam Input). Tabled with motion capture.
- No UI surface yet (lands in 6.5).
- No Soft_Press wiring. Activator type stays inert.

---

### Brick 6.3 deviations + decisions

**Scope landed.** `DpadMode` `SourceMode` handler in `app/src/main/java/com/mappo/service/input/modes/SourceMode.kt`. `validInputs = {dpad_north, dpad_south, dpad_east, dpad_west, click}`. `defaultSettingsJson` returns `{"dpad_layout":"4_way"}` — Steam-default layout, runtime-inert until analog source feeds the gating.

**Registry update.** `BindingMode.DPAD.handler()` now returns `DpadMode` (was `StubMode(DPAD)`). The change makes DPAD strictly validate sub-input keys at compile time — misseeded `button_a` under a DPAD group is dropped with a WARN log instead of silently passing through.

**No runtime behavior change for the user.** The existing accessibility-service path `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT → InputAddress(DPAD, "dpad_*")` is unchanged. Pressing physical dpad keys produces the same digital sub-input events as before. The settings JSON shape (`dpad_layout`) is data-model only — no analog source is feeding it, so its runtime gating doesn't fire.

**Tests.** 4 new in `SourceModeTest` (DpadMode validInputs / accepts / defaultSettings / registry identity). 1 new in `CompiledConfigTest` (`dpadMode_dropsSubInputsThatArentDpadKeysOrClick` — strict validation). 1 updated: `unimplementedMode_isPermissiveViaStubMode` swapped from DPAD to `JOYSTICK_MOVE` as the still-stub example. Registry-sweep test (`handlerRegistry_returnsStubForUnimplementedModes`) updated to know DPAD is no longer in the stub bucket. Full suite green.

**What this brick does NOT do.**
- No analog stick-as-dpad gating (8-way, cross-gate, analog_emulation layouts have no runtime effect — analog source is tabled).
- No UI surface yet (lands in 6.5).
- `click` sub-input on DPAD is reserved but inert. Digital dpads don't have a physical stick-click; analog-stick-as-dpad will populate it once motion capture returns.

---

### Brick 6.2 deviations + decisions (motion-capture path identified, tabled for refactor)

**Status: scaffold landed; motion-capture path identified but tabled for a wider refactor.** The brick went through three states:

1. **First attempt — non-focusable overlay**: silent, confirmed Android's documented behavior. Overlays with `FLAG_NOT_FOCUSABLE` don't receive motion events.
2. **AYN Thor "Focus Lock" discovery + single-screen pivot mid-brick**: surfaced that Thor has a firmware setting governing which screen owns physical input (captured as `reference_thor_focus_lock_setting.md`); user redirected Mappo's primary target from Thor dual-screen to single-screen overlay-over-game (captured as `project_target_single_screen_pivot.md`).
3. **Second attempt — focusable overlay**: the motion-capture path itself **works** — gamepad motion events reach the overlay and the foreground game continues to render. But wider on-device testing surfaced significant system side effects from the focus competition: IME couldn't appear in other apps, back gesture failed in some apps, GameNative's cursor went invisible, launcher's app-switcher swipe-up broke, Mappo's own layer / non-default-set runtime mutations stopped taking effect. **Reverted.** The motion-capture path is valid but ships only when paired with a solution for the side effects — that's a wider refactor than a single brick. Captured as `project_motion_capture_via_focusable_overlay.md`.

**Lesson:** focus is a logical property, not a visual one. A 1×1 transparent non-touchable focusable overlay still wins focus competition globally and breaks anything that keys off "the focused window" — IME placement, system gesture handling, cursor rendering, etc. Solving that is its own problem, distinct from motion-event reachability.

**What still landed (scaffolding kept for future motion-capture approaches):**

- `app/src/main/java/com/mappo/service/input/AnalogEvent.kt` — `AnalogEvent` data class + `MotionEventNormalizer.extract()`. Covers LEFT_JOYSTICK (AXIS_X/Y), RIGHT_JOYSTICK (AXIS_Z/RZ), DPAD (AXIS_HAT_X/Y), LEFT_TRIGGER (max of AXIS_LTRIGGER, AXIS_BRAKE), RIGHT_TRIGGER (max of AXIS_RTRIGGER, AXIS_GAS). Joint-magnitude deadzone for sticks; single-axis for triggers.
- `app/src/main/java/com/mappo/service/input/MotionCaptureOverlay.kt` — non-focusable `TYPE_ACCESSIBILITY_OVERLAY` (silent in production, doesn't disrupt anything). Kept as the call site for a future viable motion-capture mechanism; class doc lays out what was tried.
- `InputEvaluator.handleMotion(MotionEvent)` — extract + log stub. No analog mode consumes anything because nothing's flowing.
- Tests: 7 in `AnalogEventTest` (covers axis extraction + deadzone behavior). Full suite green.

**What's blocked behind a real motion-capture solution:**

All analog modes: Trigger soft-press, Joystick Move/Camera, Mouse Joystick, Absolute Mouse, Scroll Wheel, 2D Scroll, Flickstick. Without continuous axis values, none of these can ship.

**What's still unblocked (digital paths work via existing `onKeyEvent`):**

Single Button, Button Pad, Dpad (digital — via `KEYCODE_DPAD_*`), Trigger (digital click only — via `KEYCODE_BUTTON_L2/R2` hardware threshold), Reference. Phase 6 can ship a meaningful subset without motion capture.

**Avenues not yet tried (recorded for follow-up research):**

- Different overlay window TYPE constants beyond TYPE_ACCESSIBILITY_OVERLAY.
- ADB pre-grant patterns (`WRITE_SECURE_SETTINGS`, etc.) — some Android remap apps use these without root.
- Vendor SDKs that may expose controller state without focus competition.
- IME-based capture (Mappo as a custom IME).
- Investigation of how Octopus / Mantis / GamePad Mapper actually solve motion capture in production.

**Constraint reminders applicable here:**

- `project_no_root_ever.md` — no path can require root.
- `project_target_single_screen_pivot.md` — primary target is single-screen Android overlay-over-game, but the motion-capture solution must not break system UI on that target.

---

### Brick 6.1 deviations + decisions

**Scope landed.** `SourceMode` sealed interface + `SingleButtonMode` + `ButtonPadMode` + `StubMode` + `BindingMode.handler()` registry, all under `app/src/main/java/com/mappo/service/input/modes/SourceMode.kt`. Compile path in `CompiledConfig.kt::compileInputs` now consults the handler and drops sub-inputs that an implemented mode rejects (logs a warning at WARN level). No runtime / UI change for the user.

**Interface shape — `evaluate(...)` deferred.** The plan's sketch was three methods: `validInputs`, `evaluate`, `defaultSettings`. Brick 6.1 only ships `validInputs` + `defaultSettingsJson` + an `accepts` helper. The `evaluate(physical, settings, state)` analog-translation hook lives on later subclasses (lands in 6.3 with Trigger — the first mode that actually needs to translate analog state into sub-input events). Digital modes have nothing meaningful to translate, so adding the method now would be a dead-code surface.

**Settings name choice — `defaultSettingsJson()` rather than `defaultSettings()`.** The plan said `defaultSettings(): GroupSettings`. There's no `GroupSettings` sealed class yet in the codebase; group settings are stored as a `settingsJson: String` blob on `BindingGroup`. Returning a string keeps 6.1 a foundation rather than dragging the settings-typing problem in. The typed-wrapper rollout can swap the return type in one place when needed.

**Forward-compat: `StubMode`.** Real foundation work would normally make a contract strict. But the seeded data for `DPAD`, `LEFT_TRIGGER`, `LEFT_JOYSTICK`, etc. all uses mode values whose runtime handlers haven't landed yet. A strict 6.1 cutover would compile-drop most of the bound inputs on every user's device. `StubMode` accepts anything — until 6.2 onwards replaces each fallback with a real handler. Once every enum value has a real handler (target: end of Phase 6), `StubMode` goes away and validation is globally strict.

**Test-helper migration.** `CompiledConfigTest.groupWith(...)` gained an optional `mode` param (default `BUTTON_PAD`). One existing test that bound `dpad_north` under a default-mode group was updated to pass `mode = BindingMode.DPAD` (stub, permissive) so the test continues to assert what it always did. No other tests were affected — the rest of the suite binds `button_*` keys under `BUTTON_PAD`, which is strict-correct.

**Tests added — 11 total.** 8 in `SourceModeTest` (validInputs / accepts / defaults per mode, handler registry shape — including a registry sweep that catches missing entries when a new `BindingMode` enum value is added but never wired). 3 in `CompiledConfigTest::Mode validation` (BUTTON_PAD drops dpad sub-inputs; SINGLE_BUTTON accepts only `click`; unimplemented mode preserves all). Full suite green.

**What this brick does NOT change.** No runtime evaluator behavior. No UI. No new data-model fields. No new tables. No new repository methods. The plan said "proves the plumbing without changing what users see" — that's exactly what's here. If a user is editing their A button right now, the experience is identical to pre-6.1.

---

## Phase 7 (NEW) — Steam Input parity foundation

> **Inserted 2026-05-27.** Detailed plan at `~/.claude/plans/phase-7-steam-input-parity-foundation.md`.

### Scope

Close the parity gaps surfaced by the 2026-05-25 → 2026-05-27 Steam Input documentation audit so that Phase 8 (VDF import) can round-trip Steam configs cleanly. Major work groups:

- **Vocabulary + catalog rework** — rename `MOUSE_JOYSTICK` → `JOYSTICK_MOUSE`, drop `JOYSTICK_CAMERA` (fold to settings preset), drop `TWO_D_SCROLL`, rename `UNBOUND` → `DEVICE_DEFAULT`, add new `BindingMode.NONE` (Steam-parity silence), add `FLICK_STICK` / `MOUSE_REGION` / `HOTBAR_MENU` / `DIRECTIONAL_SWIPE` / `GYRO_TO_*` enum values. Sub-input names rename to Steam-verbatim. `SourceModeCatalog.modesValidFor` matches the canonical Steam per-source dropdowns. `SourceMode.validInputs()` becomes source-and-mode aware. Outer Ring Command sub-input added across joystick modes. Pass-through bug fix for face buttons.
- **`DEVICE_DEFAULT` + `NONE` runtime distinction** — Mappo's `[Device Default]` (passthrough) and Steam-parity `None` (intercept + silence) are both available across all source dropdowns with distinct runtime behavior.
- **Trigger picker rework** — `[Device Default]` / `None` / `Trigger (Digital)` / `Trigger (Analog)` source-aware display labels.
- **Mode Shifting runtime** — dynamic mode swap while a designated button is held; data model has the slot, runtime currently a stub.
- **Real Joystick Move** — XInput stick passthrough via `/dev/uinput` virtual gamepad (parallel to Phase 6's mouse uinput work).
- **Mouse Region runtime** — applies to joystick + gyro sources; shared `MouseRegionMode` handler.
- **Gyro sensor pipeline + gyro-driven mode runtimes** — Android `SensorManager` integration; modes: Gyro to Mouse, Gyro to Joystick Camera, Gyro to Joystick Deflection, Mouse Region on gyro, Directional Pad on gyro, Directional Swipe.
- **Flick Stick** — joystick mode using stick flick + gyro tracking. Depends on the gyro pipeline.
- **Per-mode settings parity** (final brick) — Cog-menu settings parity per mode (`dpad_layout` analog_emulation/cross_gate, joystick rotation/acceleration/anti_deadzone, etc.).

### Brick layout

A — Vocabulary + catalog rework. B — Mode Shifting runtime. C — Real Joystick Move + Mouse Region (joystick). D — Gyro sensor pipeline + gyro-driven modes. E — Flick Stick (depends on D). F — Per-mode settings parity. G — Final verification + close.

### Out of scope (deferred post-Steam-Input-parity)

- Scroll Wheel runtime (rare feature)
- Mappo-extension mode dropdowns (e.g., dpad-as-mouse) — additive post-Phase-8
- Menu rendering (Phase 9)
- VDF export

### Verify

LJ in Joystick Move → GameNative game reads virtual XInput stick. LJ in Mouse Region → cursor at corresponding screen-region position. Holding RB activates a Mode Shift → LJ's behavior swaps until release. Gyro available on AYN Thor + Odin 2 Mini. Face buttons in `[Device Default]` pass through correctly. Trigger picker shows `[Device Default]` / `None` / `Trigger (Digital)` / `Trigger (Analog)`.

---

## Phase 8 — Steam Input config import + in-app browser (legacy_set only)

### Scope

Import Steam Input controller configs into Mappo's Phase 1+ schema. Action-based configs (`legacy_set "0"`) are parsed and warned-about but not auto-resolved — `game_action` bindings land as placeholder bindings flagged "Unresolved game action: <set>/<action>" (manifest registry is out-of-scope; would require a Mappo-hosted action-manifest service).

**Architecture decision (2026-06-04, see `project_vdf_import_in_app_browser_primary.md` + `feedback_steam_login_on_android_is_fine.md`):** the primary import UX is an **in-app browser** equivalent to Steam Deck's "Browse Configs" screen, backed by **on-device Steam Guard QR login** and Steam's `IPublishedFile.QueryFiles` unified-messaging service. File-picker and paste-by-ID are demoted to secondary/fallback paths. Phase splits into three subphases.

### Phase 8a — Import pipeline + Steam auth + fallback acquisition

Shared infrastructure that every later subphase rides on.

#### Parser / translator

> **Progress — VDF reader landed (2026-06-22).** The parser half is done and tested. New files under `app/src/main/java/com/mappo/data/io/vdf/`:
> - `VdfValue.kt` — node model (`Str` leaf / `Obj` block). `Obj` is an **ordered, duplicate-tolerant list** of entries with `all()`/`objects()` (list) + `first()`/`string()`/`obj()` (first-wins) + `toStringMap()`. Case-insensitive key lookup; original casing preserved for export.
> - `VdfTokenStream.kt` — KV1 tokenizer: quoted (with `\n \t \r \" \\` escapes) + unquoted strings, `//` line comments, `[$COND]` tags skipped. `VdfParseException` on malformed input.
> - `VdfParser.kt` — recursive-descent, one-token lookahead → `VdfValue.Obj`.
> - `VdfControllerConfig.kt` — **structural reader** (the no-schema-decisions half of the translator): lifts the tree into typed records `VdfActionSet` / `VdfActionLayer` / `VdfGroup` / `VdfGroupInput` / `VdfActivator` / `VdfBinding` (CSV `command,label,icon` + verb/args split) / `VdfPreset` / `VdfSourceBinding` (parses `"<src> active modeshift"`). `isLegacyRawBindings` flags `legacy_set "0"`. VDF tokens kept verbatim (`mode="joystick_move"`, `type="Soft_Press"`) — enum mapping is deferred to `VdfImporter`.
> - Tests: `VdfParserTest` (duplicate-key preservation, escapes, comments, conditionals, error cases) + `VdfControllerConfigTest` (structural read of a GW-modeled config). Green in isolation. Validated ad-hoc against the real 1890-line Guild Wars `controller_neptune` config: 1 set / 2 layers / 46 groups / 3 presets / 24 langs / 61 bindings, all parse.
>
> **Translator landed (2026-06-22) — `VdfImporter.kt` + `VdfMappings.kt`.** VDF → a Room-id-free **import model** (`ImportedConfig` tree: `ImportedActionSet` / `ImportedActionLayer` / `ImportedPresetGroup` / `ImportedGroup` / `ImportedInput` / `ImportedActivator` / `ImportedCommand`), plus `ImportSummary` + typed `ImportWarning`s. An intermediate model (not entities) because Room ids/FKs don't exist until insertion; keeps translation pure + unit-testable.
>   - `VdfMappings.kt` — the audit surface for every token decision, tables mined from 62 real configs: controller-type, mode-token → `BindingMode` (`four_buttons`→BUTTON_PAD, `joystick_camera`→JOYSTICK_MOUSE, `absolute_mouse`→MOUSE_REGION, …), source-token → `InputSource`, dpad N/S/E/W → up/down/left/right, activator long+short forms (`Full_Press`/`release`), `key_press` renames (`RETURN`→ENTER, `UP_ARROW`→DPAD_UP, `LEFT_SHIFT`→SHIFT_LEFT), `xinput_button` (`SHOULDER_LEFT`→BUTTON_L1, `TRIGGER_L`→AXIS_L2, `JOYSTICK_L`→BUTTON_THUMBL), mouse button/wheel. Unknown token → null → warning (never a silent wrong-fallback).
>   - **Trigger soft-pull unification** done: a trigger group's `Soft_Press` activator is re-homed onto a `soft_pull` sub-input as a plain Full Press; the rest onto `full_pull` (feedback_soft_press_unified_to_soft_pull).
>   - `mode_shift <src> <gid>` binding → `ImportedCommand.ModeShiftTrigger` (kept distinct from `BindingOutput`); `game_action` → placeholder + count; `add_layer`/etc → `ControllerAction`.
>   - Tests `VdfMappingsTest` + `VdfImporterTest` green (43 vdf tests total). Verified end-to-end on the real 1890-line GW config: STEAM_DECK, 1 set / 2 layers / 40 groups / 45 bindings, warnings only for the deferred `switch` cluster + settings-not-translated.
>
> **Persistence landed (2026-06-22) — `ControllerConfigRepository.importConfig(profileId, ImportedConfig): Long`.** Walks the import model inserting `ControllerProfile`/`ActionSet`/`ActionLayer`/`BindingGroup`/`GroupInput`/`Activator`/`Binding`/`PresetBinding`/`LayerPresetBinding` in dependency order — every row a fresh Room id ([[feedback_duplicates_own_their_data]]). Mode shifts wired in a **second pass**: `ImportedCommand.ModeShiftTrigger`s collected during the walk, then resolved (`targetVdfGroupId` → inserted `modeshift`-state group) into `SourceModeShift` rows (owner = trigger's set/layer, `triggerSource`/`triggerSubInput` = the button location). `modeshift`-state groups are inserted as addressable shift targets with **no** preset row (mode shift is its own entity, not a binding — [[project_mode_shift_per_source_architecture]]). Group/activator VDF settings carried under a namespaced `_vdf` key (runtime-ignored) until schema-key translation lands. Absent sources not back-filled (runtime treats no-group as `DEVICE_DEFAULT`; `ensureSeededInputSources` materializes the visible rows on startup). Integration test `ControllerConfigImportTest` (6 cases, fake-DAO harness, no Robolectric) green.
>
> **Still TODO.** (1) `switches` mode fan-out (one VDF group → many single-button sources; currently UNMAPPED_SOURCE-skipped). (2) group/activator setting-key schema translation (now carried raw under `_vdf`). (3) remap `CHANGE_PRESET` / `add_layer` arg ids from VDF-preset space → Mappo Room ids (currently stored raw). (4) localization `#token` resolution. (5) The summary/confirm-dialog UI + `data/steam/` acquisition wiring (protocol/auth/workshop already scaffolded in `steam-client/`) + the profile-drawer entry points + nav routes.

- **Create** `app/src/main/java/com/mappo/data/io/vdf/VdfParser.kt` — Kotlin VDF parser. Honor duplicate-key semantics (collect into lists, not maps) — many `"group"` blocks, multiple `"preset"` blocks at the same level are intentional and a naive JSON-style parser will silently lose data. ✅ done
- **Create** `app/src/main/java/com/mappo/data/io/vdf/VdfImporter.kt` — schema translator:
  - `controller_mappings.actions` → `ActionSet` records
  - `controller_mappings.action_layers` → `ActionLayer` records (`parent_set_name` resolved)
  - `group` blocks → `BindingGroup` + `GroupInput` + `Activator` + `Binding`
  - `preset` blocks → `PresetBinding` records (with state qualifier)
  - Binding strings (CSV) → typed `BindingOutput`
  - `localization` → resolved during import (store resolved title/description; fall back to literal `#token` on miss)

#### Controller-type mapping

VDF targets a specific controller (`controller_neptune` / `controller_xboxelite` / `controller_xboxone` / `controller_ps4`). Our `controller_profile.controllerType` should preserve the source type so the user can re-import on a matching device. For cross-controller import, surface a mapping confirmation:

```
┌─ Import: Guild Wars 2 (Steam Deck) ──────── [×] ─┐
│ Source controller: Steam Deck (controller_neptune)│
│ Mappo will create a new controller profile for the │
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

The summary + optional preview run regardless of how the VDF was acquired (browser, paste, file-picker).

#### Steam protocol layer

- Stand up a Kotlin/JVM SteamKit-equivalent module under `data/steam/` that supports:
  - **Steam Guard QR device-grant login** (no password handling in Mappo) — refresh token persisted via Android Keystore
  - `IPublishedFile.QueryFiles` against appid 241100 (Steam Controller Configs) over Steam unified messaging
  - VDF body download (direct URL when the QueryFiles response provides one; CDN depot fetch via app-ownership-ticket + depot-decryption-key + manifest+chunks otherwise)
  - `ICloudService` access to the user's own `userdata/<steamid32>/241100/remote/controller_config/` tree
- **First step before writing this from scratch:** audit GameNative (and other open-source Android-Steam-integration prior art) — if a working Kotlin/Android implementation of the relevant SteamKit subset already exists, wrap or vendor it instead of porting fresh. Decision point lives at the top of 8a.

#### Fallback acquisition (secondary UX)

- **Paste-by-ID** — user pastes a Steam Workshop URL or `publishedfileid`; resolved to a VDF via the protocol layer. Useful for sharing a specific config out-of-band.
- **Local-folder / file import** — SAF file picker for a `.vdf` file. The original Phase 8 UI shape, now positioned as "offline / privacy-conscious" path. Profile drawer label: "Import from file."

### Phase 8b — In-app browser (primary UX)

The headline acquisition path. Functionally equivalent to Steam Deck's per-game "Browse Configs" screen, rendered natively in Mappo.

#### Entry point

- Profile drawer → "Browse Steam configs" → game picker
- Game picker pulls owned-games via `IPlayerService.GetOwnedGames` (public Web API, simple key auth, no scope issues) so the user browses by game title + icon, not appid
- Game metadata (icon, header image) from the Steam store API

#### Browser tabs (per selected game)

- **Yours** — user's own Cloud-synced configs via `ICloudService`
- **Most Popular** — `QueryFiles` ranked by subscription count
- **Most Recent** — `QueryFiles` ranked by publish date

#### Per-config row

- Title, author, vote score, subscription count
- Mappo-glyph-rendered binding summary (uses our own glyph system, not Steam's)
- One-tap **Apply** → fetches VDF body → runs through the 8a import pipeline → shows the summary dialog → lands as a new `ControllerProfile` under the current `Profile`

### Phase 8c — Polish

- **Friends tab** — `ISteamFriends` enumeration + per-friend `QueryFiles` filter
- **Per-controller ranking** — boost configs whose source `controller_*` matches the user's detected device
- **"Recommended"-spirit ranking** — our own algorithmic mix (popularity × recency × controller match), since Steam's curated/featured tabs aren't reachable via the public protocol
- **Richer preview** — full read-only controller overview screen (the optional Preview step from the summary dialog), surfaced as a sheet before Apply

### Files

- **Create** `app/src/main/java/com/mappo/data/io/vdf/VdfParser.kt`
- **Create** `app/src/main/java/com/mappo/data/io/vdf/VdfImporter.kt`
- **Create** `app/src/main/java/com/mappo/data/io/vdf/VdfTokenStream.kt` (tokenizer)
- **Create** `app/src/main/java/com/mappo/data/steam/` (whole tree — protocol client, QR login flow, token storage, QueryFiles wrapper, CDN downloader)
- **Create** `app/src/main/java/com/mappo/ui/screen/import/SteamLoginScreen.kt`
- **Create** `app/src/main/java/com/mappo/ui/screen/import/SteamConfigBrowserScreen.kt`
- **Create** `app/src/main/java/com/mappo/ui/screen/import/VdfImportScreen.kt` (shared summary dialog + fallback paste/file UI)
- **Edit** `app/src/main/java/com/mappo/ui/nav/MappoRoute.kt` — add `IMPORT_STEAM_LOGIN`, `IMPORT_STEAM_BROWSER`, `IMPORT_VDF`
- **Edit** `app/src/main/java/com/mappo/ui/screen/ProfileDrawerContent.kt` — add the entries
- **Edit** `app/src/main/AndroidManifest.xml` — SAF intent filter for `.vdf` files if desired (open-with from a file manager)

### Verify

- **8a** — import a real Steam Deck VDF via the file-picker fallback (e.g., the Guild Wars 2 example previously analyzed). All 24 groups produce equivalent BindingGroups; all activators (Full / Soft / Long / Start / release) parse correctly; layer activation pairs survive round-trip. A `legacy_set "0"` action-based VDF imports without crashing; `game_action` bindings show as placeholders. Steam Guard QR login completes end-to-end and the refresh token survives an app restart.
- **8b** — from a clean install: log in via Steam Guard QR, pick an owned game, browse Yours / Most Popular / Most Recent, one-tap-apply a config, verify the resulting `ControllerProfile` matches what file-import would produce for the same source VDF.
- **8c** — friends tab populates with at least one friend's published configs; per-controller ranking promotes configs whose source `controller_*` matches the device.
- **Re-export** (stretch, post-parity) round-trips structurally-stable: parsed → re-emitted is semantically equivalent (key reorder OK).

---

## Phase 9 — Radial / touch menu data scaffold

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

- **Create** `app/src/main/java/com/mappo/service/input/modes/RadialMenuMode.kt` (stub)
- **Create** `app/src/main/java/com/mappo/service/input/modes/TouchMenuMode.kt` (stub)
- **Create** `app/src/main/java/com/mappo/ui/screen/menus/MenusScreen.kt`
- **Create** `app/src/main/java/com/mappo/ui/screen/menus/MenuEditorScreen.kt`
- **Edit** `app/src/main/java/com/mappo/ui/nav/MappoRoute.kt` — add `MENUS`, `MENU_EDITOR`
- **Edit** `app/src/main/java/com/mappo/ui/screen/ProfileDrawerContent.kt` — add "Menus" entry

### Verify

- Create a menu, set 8 items, bind each item to keys. Bind a physical input → "Open menu: Weapons". Trigger it — logcat shows "menu activation requested"; nothing visible yet (expected).
- VDF import (Phase 7) of a config containing a `radial_menu` / `touch_menu` group lands as a Mappo menu with the right item count + bindings.

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

Mappo is pre-release; destructive changes are OK. Phase 1's schema rebuild wipes and migrates from `gamepad_mappings`. Subsequent phases are additive (no destructive migrations expected). When release approaches, this plan's migration story will need re-examining.

### Things explicitly NOT in this plan

(Recorded here so we don't accidentally bake in dependencies on them):

- Glyph database / `GetGlyphForActionOrigin` equivalent
- Action origin translation across controller families
- (Workshop / community-config browsing was previously listed here as out-of-scope; pulled in 2026-06-04 as the **primary** import UX — see Phase 8b)
- Game action manifest registration (forces legacy-only import scope)
- Preview-before-apply for layouts beyond the VDF import flow
- Controller HUD / debug overlay
- Gyro capture + flickstick mode (Phase 6 ships without)

### Post-parity Mappo extensions (deferred until parity work is done)

Mappo-specific enhancements that go *beyond* Steam Input parity. Recorded here so we don't lose them; not in scope until the parity roadmap is largely complete.

- **Long_Press "fire as tap" mode** — per-activator toggle to emit DOWN+UP at threshold instead of holding while the physical button is held. Current Steam-parity behavior holds the key, which on Wine/Windows targets (GameNative) produces OS-level key auto-repeat — undesirable for menu keys like ESC. User verified (2026-05-12) that Steam Deck exhibits identical Wine-repeat behavior with the same config, so this is true Steam parity, not a Mappo bug. The extension would add an opt-in alternative mode. See `project_fire_as_tap_post_parity.md` in memory.

- **Virtual-keyboard buttons as chord partners** — today's chord partner is a physical input only (captured via the listen-for-press picker in 3.3). User direction (2026-05-13): the broader virtual-keyboard rework will explore on-screen overlay use of virtual keyboards, and that work may change the right way to use virtual buttons in chord activators. Punted to wait on that direction. Data shape doesn't preclude it — `chord_partner_source` could grow a new enum value for virtual buttons later without migrating existing rows.

---

## Critical files index

A consolidated list for quick scanning at execution time.

**Touched in multiple phases:**

- `app/src/main/java/com/mappo/ui/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/mappo/ui/screen/MainScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/RemapControlsScreen.kt`
- `app/src/main/java/com/mappo/ui/nav/MappoRoute.kt`
- `app/src/main/java/com/mappo/service/InputAccessibilityService.kt`
- `app/src/main/java/com/mappo/service/input/InputDispatcher.kt`
- `app/src/main/java/com/mappo/data/db/AppDatabase.kt`

**New in Phase 1 (data model):**

- `app/src/main/java/com/mappo/data/model/steam/` (whole tree)
- `app/src/main/java/com/mappo/data/db/steam/` (whole tree)
- `app/src/main/java/com/mappo/data/repository/ControllerConfigRepository.kt`

**New in Phase 2 (runtime):**

- `app/src/main/java/com/mappo/service/input/InputEvaluator.kt`
- `app/src/main/java/com/mappo/service/input/OutputEmitter.kt`
- `app/src/main/java/com/mappo/service/input/CompiledConfig.kt`

**New in Phase 3 (activators UI):**

- `app/src/main/java/com/mappo/ui/screen/binding/InputEditorScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/binding/ActivatorEditorScreen.kt`

**New in Phase 5 (layers + mode-shift UI):**

- `app/src/main/java/com/mappo/ui/screen/binding/LayerActivationPickerScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/binding/ModeShiftPickerScreen.kt`

**New in Phase 6 (modes):**

- `app/src/main/java/com/mappo/service/input/modes/` (one file per mode)
- `app/src/main/java/com/mappo/ui/screen/binding/SourceEditorScreen.kt`

**New in Phase 8 (Steam config import + in-app browser):**

- `app/src/main/java/com/mappo/data/io/vdf/` (parser + importer)
- `app/src/main/java/com/mappo/data/steam/` (Steam protocol client, QR login, QueryFiles, CDN downloader)
- `app/src/main/java/com/mappo/ui/screen/import/SteamLoginScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/import/SteamConfigBrowserScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/import/VdfImportScreen.kt`

**New in Phase 8 (menu scaffold):**

- `app/src/main/java/com/mappo/service/input/modes/RadialMenuMode.kt`
- `app/src/main/java/com/mappo/service/input/modes/TouchMenuMode.kt`
- `app/src/main/java/com/mappo/ui/screen/menus/MenusScreen.kt`
- `app/src/main/java/com/mappo/ui/screen/menus/MenuEditorScreen.kt`

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
| 6 ✅ | Right joystick set to Joystick Mouse mode moves the cursor (shipped via Shizuku pivot 2026-05-25). |
| 7 | Real Joystick Move + Mouse Region + gyro modes work on AYN Thor; Mode Shift swap on button-hold reverts on release; face buttons in `[Device Default]` pass through cleanly. |
| 8 | A real community Steam Deck VDF imports and the resulting Mappo config plays back equivalently. |
| 9 | A menu can be authored end-to-end; firing its open-binding logs activation (overlay deferred). |
