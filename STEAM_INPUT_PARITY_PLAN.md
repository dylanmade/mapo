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
- **1.3 — UI rewrite.** New section-grouped `RemapControlsScreen` reading the new graph. Picker writes via per-binding setter (Phase 0's commit-on-select pattern).
- **1.4 — Cleanup.** Delete `GamepadMappingRepository`, `GamepadMappingDao`, `gamepad_mappings` entity, legacy VM methods. Backfill tests.

### Deviations from the original schema (decided during 1.1)

- **`group_setting` table removed.** Originally planned as a per-(group, key) row store with `valueJson`. Collapsed into a single `BindingGroup.settingsJson` column instead — matches how `Activator.settingsJson` already works, fewer joins, parser layer is the same code path for both. Net: 9 tables instead of 10. Sealed-class parsing in the repository layer makes the table-vs-column choice invisible upstream, so this is safe to revisit later if we ever need queryable settings (we don't).
- **`BindingGroup` ownership uses two nullable FKs** (`actionSetId?` + `actionLayerId?`) rather than a polymorphic `(ownerKind, ownerId)` pair. Real FKs + cascades work; polymorphic FKs don't. Repository invariant: exactly one is non-null.
- **Schema bump uses existing `fallbackToDestructiveMigration(dropAllTables = true)`.** No hand-written Room `Migration` block needed.
- **Legacy `gamepad_mappings` data is wiped on v6→v7 upgrade** (decided in 1.2). Pre-release stance ([memory: project_mapo_pre_release.md](../.claude/projects/-Users-dylanbperry-projects-mapo/memory/project_mapo_pre_release.md)) said destructive OK; the user confirmed they're fine re-creating any test profiles. The repository's `ensureSeeded` path is the only seed mechanism — there is no migrator that reads old rows. If a release-ready migration path is ever needed, write a real Room `Migration(6, 7)` and the repository can stay as-is.
- **`BindingGroup.settingsJson` defaults to `"{}"`** at seed time. Mode-specific settings (deadzones, sensitivities, etc.) parse from this string in later phases; for brick 1.2 every group has empty settings and a single default sub-input set per mode.
- **Default seed excludes trackpads, back paddles, gyro.** The schema supports them (for VDF import compatibility) but Generic Android doesn't expose them, so seeding configurable-but-never-fireable groups would just be UI clutter. Imports can still create groups for these sources.

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

### Why now

Steam Input semantics (activators with timing, layer stacks, mode-shift, mode-based interpretation) can't be implemented by tweaking the current `currentMappings: Map<DeviceButton, RemapTarget>` lookup in `InputAccessibilityService.onKeyEvent` — they require a stateful per-frame evaluator. Build it once now, on top of the Phase 1 data model, so every later feature plugs in cleanly.

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

The full Steam activator catalog: `Full_Press`, `Long_Press`, `Double_Press`, `Start_Press`, `Release_Press`, `Soft_Press`, `Chorded_Press`. All universal settings (`toggle`, `fire_start_delay`, `fire_end_delay`, `haptic_intensity`, `cycle_binding`) and type-specific (`long_press_time`, `double_tap_time` / VDF `doubetap_max_duration`, `interruptable`, `hold_to_repeat`, `repeat_rate`).

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

- **Activator ordering UX**: drag-to-reorder vs. fixed order by type — Steam shows them in fixed order; we'll start fixed and revisit.
- **"Chorded Press" UX**: how the user picks the chord button — picker that says "Press the other button now" vs. a dropdown of inputs.

### Verify

- Bind A → Regular = ENTER, Long Press = ESCAPE. Tap A briefly → ENTER fires. Hold A 0.3s+ → ESCAPE fires, ENTER suppressed.
- Bind A → Double Press = SPACE with 0.25s window. Two quick taps → SPACE. One tap then nothing → after window expires, the single ENTER fires.
- Hold-to-repeat on A → ENTER pulses at the configured rate.
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
