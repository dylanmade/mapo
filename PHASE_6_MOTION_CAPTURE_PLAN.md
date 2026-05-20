# Phase 6 Motion-Capture Refactor — analog input modes via focused `TYPE_APPLICATION_OVERLAY`

## Context

Phase 6 (Steam Input parity — input source modes) closed in reduced scope on 2026-05-16: digital handlers (`DpadMode`, `TriggerMode`, `SingleButtonMode`, `ButtonPadMode`) shipped; all analog modes (Joystick Move/Camera, Mouse Joystick, Trigger Soft_Press, Scroll modes, Flickstick) shipped inert as `StubMode`. The block was capture: Android's input dispatcher routes `MotionEvent` to the focused window, and Mapo isn't focused — the foreground game is. The earlier focused-overlay experiment with `TYPE_ACCESSIBILITY_OVERLAY` captured motion but broke GameNative's cursor (a Mapo flagship use case), and `AccessibilityService.onMotionEvent` is API 34+ which excludes Mapo's target device base (AYN Thor, Odin 2 / Mini, Anbernic, Retroid — Android 13 on the latest gen).

A probe landed 2026-05-19 (`MotionProbeAppOverlay`) testing a focused `TYPE_APPLICATION_OVERLAY` — the third unexplored cell in the matrix. Device verification on Thor:
- ✅ GameNative's cursor stays visible.
- ✅ Motion events flow (sticks, triggers, dpad axes).
- ❌ IME / app-switcher gesture / back gesture break **while attached**.

`TYPE_APPLICATION_OVERLAY` (app-level, designed to coexist with foreground apps) behaves differently from `TYPE_ACCESSIBILITY_OVERLAY` (system-level) with respect to GameNative's software-rendered cursor. The remaining side effects are inherent to focused-window semantics on Android < 14 and can't be eliminated structurally — but they can be **scoped** by attaching the overlay narrowly: only while a profile-bound game is foregrounded AND that profile's active action set/layer actually has an analog mode configured. Same opt-in model Steam Input uses on Steam Deck (Steam captures system navigation while in-game).

Goal: ship the analog modes that unblock PC-game emulation on Mapo's target device base, on Android 13, no root, with side effects scoped to in-game time.

## Decision: focused `TYPE_APPLICATION_OVERLAY` gated by foreground app + active analog config

The production motion-capture overlay:
- `TYPE_APPLICATION_OVERLAY`, focusable (omits `FLAG_NOT_FOCUSABLE`), `FLAG_NOT_TOUCH_MODAL`, transparent + invisible (no visible probe square).
- Borrows the existing keyboard FGS for process priority.
- Receives motion events via `View.onGenericMotionEvent`; pipes through `MotionEventNormalizer` → `InputEvaluator.handleMotion` → per-source `SourceMode.evaluate(...)`.

Attachment is **gated** by a `MotionCaptureCoordinator` predicate:
```
attach if and only if:
    foregroundPackage ∈ activeProfile.boundApps
    AND any InputSource in (activeActionSet ⊕ activeLayers).compiledInputs has a non-stub analog mode
```

This bounds the IME / back / app-switcher side effects to "while playing a game the user has explicitly opted into analog modes for." Outside that window: zero side effects, normal Android.

Rejected alternatives (already explored):
- `TYPE_ACCESSIBILITY_OVERLAY` focused → broke GameNative cursor (verified 2026-05-16).
- `AccessibilityService.onMotionEvent` (API 34+) → excludes target device base on Android 13.
- Hidden-API `InputMonitor` → requires `MONITOR_INPUT` signature-level permission; not bypassable via `HiddenApiBypass`.
- Per-emulator integration (GameNative API) → narrow, not generalizable.
- Root → violates `project_no_root_ever.md`.

---

## Locked-in contracts (Brick 0)

### D1 — `SourceMode.evaluate(...)` signature

A single hook shape, set once, held across all analog modes:

```kotlin
fun evaluate(
    reading: AnalogReading,           // (source, x, y, t) from MotionEventNormalizer
    ctx: ModeContext,                 // active layer ids, settings JSON for this source
    digitalEmit: (subInput: String, isDown: Boolean) -> Unit,  // synthetic edges
    mouse: MouseEmitter,              // continuous cursor / scroll output
)
```

Digital modes default to no-op `evaluate()`. The signature carries both emitters because some modes need each; modes that don't use one ignore it.

### D2 — Two emission paths

- **Synthetic-digital sub-input edges** (`digitalEmit("dpad_north", true)`) route back into the existing activator engine. Joystick-Move and Soft_Press use this.
- **Continuous cursor / scroll output** via `MouseEmitter` (new, sibling of the existing trackpad cursor injection in `InputAccessibilityService`). Joystick-Camera and Mouse-Joystick use this; doesn't go through the activator path.

Locking this split now keeps `InputEvaluator` from growing a `Loop<MotionEvent>` consumer.

### D3 — Activation gate predicate

`MotionCaptureCoordinator` owns the attach/detach decision based on:
- `ForegroundAppMonitor.currentPackage`
- `AppProfileBindingRepository.getAll()` (which packages map to which profiles)
- `InputDispatcher.compiledConfig` + `InputEvaluator.activeLayers` (whether any source in the resolved set+layer slice has a non-stub analog mode)

Predicate evaluated reactively on any input change. Side effect window = exactly the union of "foreground in profile's apps" × "active config wants motion." When the user backgrounds to Chrome → coordinator detaches → IME/back/app-switcher all work normally.

### D4 — `BindingGroup.mode` resolves through the existing set/layer override chain

`BindingGroup.mode` already exists at `data/model/steam/BindingGroup.kt:41`. Mode resolves through the same set/layer override chain as bindings already do — a layer's binding group overrides the base set's for that source, and `mode` rides along. No new mechanics. The contract is: don't bake "mode is set-scoped only" assumptions into Brick 1's compile work.

---

## Bricks

Sequencing rule (same as Single-Screen plan): **every brick boundary leaves remap working end-to-end and the activity functional.**

### Brick 0 — Architectural decisions (no code)

Lock D1–D4 above. Deliverable: this plan file.

**Exit criteria:** D1–D4 agreed; brick file list reviewed against them. ✅ Plan approved by user 2026-05-19.

### Brick 0 status — ✅ COMPLETED

### Brick 1 — `BindingGroup.mode` plumbing end-to-end (no behavior change) — ✅ COMPLETED

**Goal:** Mode is editable in UI, persists, arrives at `InputEvaluator` via `CompiledConfig` — but every mode still routes to `StubMode` so runtime behavior is unchanged. This is the contract-shaping brick: nothing analog runs yet, but the wires are live.

**Files (modified unless marked new):**
- `app/src/main/java/com/mapo/data/repository/ControllerConfigRepository.kt` — verify `BindingGroup.mode` is carried in compile; if `CompiledInput` doesn't carry it (per Plan agent's audit), add the field.
- `app/src/main/java/com/mapo/service/input/CompiledConfig.kt` — extend `CompiledInput` with `mode: BindingMode`; thread through `toCompiled()`.
- `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt` — replace `DisabledModeDropdown` (line 715) with a real `ExposedDropdownMenuBox` populated from new `SourceModeCatalog.modesValidFor(inputSource)`; on-pick → ViewModel write.
- `app/src/main/java/com/mapo/ui/screen/RemapSections.kt` — drop hardcoded `modeDropdownLabel` literals (lines 61–100); derive label from current `BindingGroup.mode`. Subheader now needs `(inputSource, actionSetId/layerId)` to address its target row.
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — new `SourceModeCatalog.modesValidFor(InputSource): List<BindingMode>` helper.
- New: `app/src/test/java/com/mapo/service/input/CompiledConfigModeTest.kt` — asserts mode propagates from data → compiled, including layer override.

**Exit criteria:** User can pick a mode in Remap Controls; choice persists; verbose log in `InputEvaluator.lookupActive` shows the chosen mode arriving for the right address. Existing remap and activity behavior unchanged.

**Risks:** `RemapPaneItem.Subheader` ripple — small surface, contained.

#### Brick 1 deviations + decisions

- **Picker uses `Box` + `DropdownMenu`, not `ExposedDropdownMenuBox`** as originally planned. Visual continuity with the prior `DisabledModeDropdown` (same `Surface` + alpha pattern) was cheaper than introducing an EDM-style outlined-text-field affordance. Same M3 dropdown menu primitive underneath.
- **Layer-mode-override editing not exposed.** The picker is read-only when `viewingLayer != null` (it still shows the effective mode — inherited from the base set or, if a layer override exists, the layer's mode — but the user can't change it from inside a layer view). Brick 1 scope is base-set mode editing; layer-override editing of mode lands in a follow-up brick (or a sub-brick during the analog-modes work).
- **Bumpers + Menu Buttons sections no longer show a "Single Button" decorative label.** Those sections span multiple sources (each individually `SINGLE_BUTTON`), and the picker's "Mode: X" affordance is per-source. Rather than render the same label on each subheader, the affordance is omitted when `Subheader.inputSource == null`. The title text remains.
- **`SourceModeCatalog` lives in `service/input/modes/SourceMode.kt`**, not its own file. It's small and conceptually inside `SourceMode`'s catalog responsibility. Move to its own file later if it grows.
- **`BindingMode.displayName()` added alongside `InputSource.displayName()`** in `data/model/steam/SteamEnums.kt`. Same pattern, same file.
- **Sub-input filtering on mode change is silent**, not destructive. The compile step's existing `SourceMode.accepts()` check warn-and-drops invalid sub-inputs from `CompiledInput`. The user's existing GroupInputs aren't deleted on mode change — picking back the original mode restores them. Trade-off documented in the repository's `updateBindingGroupMode` kdoc.
- **Test sites updated.** `InputEvaluatorTest` (6 sites) and `FilterToOverridesTest` (8 sites) updated for the new constructor shapes. New `bindingGroupMode_propagatesToCompiledInput` + `bindingGroupMode_layerOverride_carriesItsOwnMode` in `CompiledConfigTest`.

#### Files actually landed

- `app/src/main/java/com/mapo/service/input/CompiledConfig.kt` — `CompiledInput.mode: BindingMode` added; `toCompiled()` threads it through.
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — new `SourceModeCatalog.modesValidFor(InputSource)`.
- `app/src/main/java/com/mapo/data/model/steam/SteamEnums.kt` — new `BindingMode.displayName()`.
- `app/src/main/java/com/mapo/data/repository/ControllerConfigRepository.kt` — new `updateBindingGroupMode(bindingGroupId, mode)`.
- `app/src/main/java/com/mapo/ui/screen/remap/RemapSections.kt` — `Subheader.modeDropdownLabel: String` → `Subheader.inputSource: InputSource?`; registry updated per-section.
- `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt` — real `ModePicker` composable; `SubheaderRow` rewritten; `onSetBindingGroupMode` callback threaded through `RemapDetailPane` → `RemapControlsScreen`.
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` — callsite wired to new VM method.
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` — `setBindingGroupMode(bindingGroupId, mode)`.
- `app/src/test/java/com/mapo/service/input/CompiledConfigTest.kt` — 2 new mode-propagation tests + helper updates.
- `app/src/test/java/com/mapo/service/input/InputEvaluatorTest.kt` — 6 `CompiledInput` constructions updated.
- `app/src/test/java/com/mapo/ui/screen/FilterToOverridesTest.kt` — 8 `Subheader` constructions updated.

#### Hand-off to device verification

Compile + full unit test suite green. Worth a quick device sanity:
1. Open Remap Controls → Joysticks → see "Mode: Joystick Move" affordance under "Left Joystick Behavior."
2. Tap the affordance → dropdown with the catalog options (Joystick Move / Joystick Camera / Mouse Joystick / D-Pad / Scroll Wheel / Absolute Mouse).
3. Pick a different mode → persists across activity recreate (rotation / backgrounding).
4. Bumpers + Menu Buttons subheaders have NO mode affordance (intentional — multi-source sections).
5. Open a layer view → mode picker visible but dimmed (read-only by design in this brick).
6. Existing remap behavior unchanged: bindings under the same source still fire as before.

### Brick 2 — Profile↔app multi-binding editor — ✅ COMPLETED

**Goal:** User can add/remove app associations to a profile from inside Mapo (not only via the auto-switch create-profile prompt). `AppProfileBindingRepository` already supports many-apps-per-profile; the gap is UI.

**Files actually landed:**
- `app/src/main/AndroidManifest.xml` — added `<queries>` block for `ACTION_MAIN` / `CATEGORY_LAUNCHER` (mandatory on Android 11+ to enumerate other apps; narrower than `QUERY_ALL_PACKAGES`).
- New: `app/src/main/java/com/mapo/data/repository/InstalledAppsRepository.kt` — `@Singleton` with `launchableApps()` suspend fn. Single PM pass off the IO dispatcher; dedupes by package; sorts by label.
- `app/src/main/java/com/mapo/data/repository/AppProfileBindingRepository.kt` — new `bindMany(profileId, packageNames)`. Loop over `dao.upsert` (REPLACE semantics so re-binding re-points the package).
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` — new `installedAppsRepository` injection, `installedApps: StateFlow<List<InstalledApp>>`, `bindAppsToProfile(profileId, packages)`, `loadInstalledApps()`. (Note: the plan called for a separate `AutoSwitchViewModel` but the existing auto-switch surface lives in `MainViewModel`; we kept that pattern rather than fork off a new VM.)
- New: `app/src/main/java/com/mapo/ui/screen/AppPickerSheet.kt` — M3 `ModalBottomSheet` with `OutlinedTextField` search, multi-select checkboxes, "already bound" / "bound elsewhere" supporting text. `surfaceContainerLow` per M3 standards memo; `LazyColumn` with stable keys.
- `app/src/main/java/com/mapo/ui/screen/AutoSwitchScreen.kt` — restructured from flat binding list into per-profile sections. Each section has a header with profile name + "Add app" `TextButton`; an empty-state helper subtext when the profile has no bindings (per list-item-helper-subtext memo); bindings rendered as before. Picker sheet is opened by tapping "Add app", parented by `pickerTargetProfile` state.
- `app/src/main/res/values/strings.xml` — added 12 new strings under the "Brick 2 multi-binding editor" comment.
- `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelTest.kt` — updated 3 `MainViewModel(...)` construction sites (setUp + rebuildSubject + the inline rebuild in `viewingActionSetId_resetsToNull...`) to pass the new `installedAppsRepository` mock.
- New: `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelMultiBindTest.kt` — 3 tests: forwards-to-repository, empty-set-is-no-op, loadInstalledApps populates StateFlow.

**Exit criteria:** Without launching a game, user can add apps to a profile; the binding shows in the list and is picked up by `ProfileAutoSwitcher` on next foreground change. Sequenced here so Brick 4's gating predicate has a UI to populate it.

**Deviations / decisions:**
- No new `AutoSwitchViewModel`. Auto-switch state lives in `MainViewModel`; adding the multi-bind functionality there keeps a single source of truth and avoids the cross-VM data plumbing for `appLabels` / `appProfileBindings`.
- Picker reloads installed apps on every open (cheap PM pass off IO dispatcher) rather than caching. Live install/uninstall pickup beats a stale cache.
- `bindMany` is a loop of `upsert`, not a transactional `@Transaction` Room method. The Auto-Switch screen is informational, not safety-critical; partial failure (extremely unlikely on a single connection) is preferable to introducing a transactional DAO method just for this.
- Per-profile grouping in the AutoSwitchScreen list. Plan said "Add app affordance per profile"; grouping into profile sections (with the affordance in the section header) is the clearest M3 UX for that.

**Hand-off — device verification:**
1. Open drawer → Auto-switch → screen now shows each profile as a section header with an "Add app" text button.
2. Tap "Add app" → bottom sheet opens listing every launchable app, sorted by label, with a search field.
3. Type a partial name → list filters.
4. Multi-select 2-3 apps → confirm button reads "Bind N apps" → tap → sheet closes, the bindings appear under the chosen profile.
5. Re-open picker for the same profile → previously-bound apps show "Already bound here" subtext; apps bound to a different profile show "Currently bound to '…' — will switch".
6. Background app, foreground a bound app → profile switches (existing `ProfileAutoSwitcher` path; no regression).
7. Profile with zero bindings shows helper subtext "No apps bound to this profile yet." instead of going entirely blank.

**Risks:** PackageManager label enumeration cost — many devices list 200+ launchable apps. Sheet uses `LazyColumn` + label resolution in a single pass off the IO dispatcher.

### Brick 3 — `MotionCaptureOverlayManager` (production focused overlay, attach/detach only)

**Goal:** Stand up the production focused-overlay window with a clean attach/detach API. Not yet gated by anything; controlled by a temporary "force attach" debug toggle in the drawer.

**Files:**
- New: `app/src/main/java/com/mapo/service/input/capture/MotionCaptureOverlayManager.kt` — generalize `MotionProbeAppOverlay`'s window mechanics into a `@Singleton` with `attach()` / `detach()` / `isAttached: StateFlow<Boolean>`. Hosts an invisible (transparent, 1×1) `View` whose `onGenericMotionEvent` routes through an injected callback. Borrows the keyboard FGS for process priority.
- New: `app/src/main/java/com/mapo/service/input/capture/MotionCaptureView.kt` — `View` subclass with the motion-event override.
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — delete the old `MotionCaptureOverlay` attach (lines 191–194); wire `evaluator.handleMotion` callback through the new manager via DI.
- New: `app/src/test/java/com/mapo/service/input/capture/MotionCaptureOverlayManagerTest.kt` (Robolectric — attach/detach state, callback wiring).

**Exit criteria:** Debug toggle attaches the new overlay; motion events flow into `InputEvaluator.handleMotion`; detaching restores normal IME/back/app-switcher behavior. Remap and activity unaffected.

**Risks:** Visible artifacts — the production window must be truly invisible (the probe is intentionally red). Verify 1×1 + `PixelFormat.TRANSPARENT` doesn't break event delivery on some OEMs.

### Brick 4 — `MotionCaptureCoordinator` (gating + first-time tradeoffs dialog)

**Goal:** Replace Brick 3's "force attach" with the real predicate (D3): attach only when `(foreground app ∈ active profile's bound apps) AND (any source in the resolved set/layer has an analog mode configured)`.

**Files:**
- New: `app/src/main/java/com/mapo/service/input/capture/MotionCaptureCoordinator.kt` — `@Singleton`, started from `InputAccessibilityService.onServiceConnected`. Combines `ForegroundAppMonitor.currentPackage` + `AppProfileBindingRepository.getAll()` + `InputDispatcher.compiledConfig` + `InputEvaluator.activeLayers` into a single attach/detach decision.
- `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — expose `activeLayers` and `activeSetId` as `StateFlow`s (not just test seams). New `flushAnalog()` method for clean state on profile/set switch.
- New: `app/src/main/java/com/mapo/ui/screen/dialog/AnalogModeTradeoffsDialog.kt` — one-time M3 dialog the first time any mode picker is set to an analog mode in the user's lifetime. Explains IME / back / app-switcher gestures suspended while the capture overlay is active. Acks persist in DataStore.
- New: `app/src/main/java/com/mapo/data/AnalogModePreferences.kt` — DataStore wrapper for the ack.
- New: `app/src/test/java/com/mapo/service/input/capture/MotionCaptureCoordinatorTest.kt` (Robolectric — predicate truth table).

**Exit criteria:** Switching foreground app to/from a bound-with-analog-config app attaches/detaches the overlay observably; unrelated apps don't attach. First analog-mode pick triggers the dialog. Remap unaffected throughout.

**Risks:**
- Cold-launch race — `compiledConfig` lazy-loads after first event; coordinator must tolerate `EMPTY` config.
- `ProfileAutoSwitcher` may swap profile mid-attached-overlay; coordinator must call `InputEvaluator.flushAnalog()` to release any in-flight synthetic state before re-evaluating.

### Brick 5 — First analog mode: Trigger Soft_Press

**Goal:** Ship the first real `SourceMode.evaluate()`. LEFT/RIGHT_TRIGGER analog pull crossing the configured `soft_threshold` synthesizes a `(source, "soft_press")` sub-input edge into the activator engine; the existing `SOFT_PRESS` activator type fires.

**Files:**
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — add `evaluate()` to the interface (D1); implement on `TriggerMode`. Settings: `soft_threshold` + hysteresis (5% default, Steam-compatible).
- `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — `handleMotion` becomes a real dispatcher: per-reading, look up the source's compiled mode, call `mode.evaluate(...)`, route synthetic-digital edges back through `onPress`/`onRelease`. Remove the `else -> ` "skipping" branch for `SOFT_PRESS` in `onPress`.
- `app/src/main/java/com/mapo/service/input/AnalogEvent.kt` — verify trigger axes surface as `(LEFT_TRIGGER, magnitude)` / `(RIGHT_TRIGGER, magnitude)` (Brick 6.2 work already did this — verify).
- New: `app/src/test/java/com/mapo/service/input/modes/TriggerModeSoftPressTest.kt`.

**Exit criteria:** Configure LT to mode `TRIGGER`, bind a `SOFT_PRESS` activator on the `"soft_press"` sub-input to a key, soft-pull LT in a game → key fires; full-pull → existing click path still fires. Other analog modes still `StubMode`.

**Risks:** Without hysteresis, a wobbly finger flutters the synthetic edge — bake in 5% as default.

### Brick 6 — Joystick Move (synthetic dpad-direction edges)

**Goal:** Stick → 4-way or 8-way directional sub-input events, deadzone-gated. WASD-style game control.

**Files:**
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — move `JOYSTICK_MOVE` off `StubMode`; new `JoystickMoveMode` object emitting `dpad_north/south/east/west` synthetic edges.
- New: `app/src/main/java/com/mapo/service/input/modes/JoystickMoveSettings.kt` — `outer_deadzone`, `inner_deadzone`, `dpad_layout` (`"4_way"` / `"8_way"`) parsed from `BindingGroup.settingsJson`.
- New: `app/src/test/java/com/mapo/service/input/modes/JoystickMoveModeTest.kt`.

**Exit criteria:** LJ mapped to Joystick Move → WASD drives a game's character. Trigger Soft_Press still works.

**Risks:** Re-emission tracking — a stick held at NW shouldn't flap N/W presses per motion event; the mode must track previous synthetic state per source and emit only on transitions. State lives in the `MotionCaptureCoordinator`-owned per-source map (not `InputEvaluator` which is digital-event-only).

### Brick 7 — Mouse Joystick + Joystick Camera (continuous mouse output)

**Goal:** Two analog modes whose output is cursor-delta. Joystick Camera = mouse-look (FPS aiming); Mouse Joystick = system cursor (menu navigation). Mechanically similar; settings differ.

**Files:**
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — `JoystickCameraMode`, `MouseJoystickMode`.
- New: `app/src/main/java/com/mapo/service/input/MouseEmitter.kt` — D2's continuous-output sibling; thin wrapper around existing cursor-injection in `InputAccessibilityService`. Exposes `moveCursorBy(dx, dy)`.
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — extract trackpad cursor-move math into `MouseEmitter` (no behavior change for trackpad).
- New: `app/src/main/java/com/mapo/service/input/capture/AnalogTick.kt` — continuous-mode coroutine tick (~120 Hz default). Android motion events are sparse when the stick is still-but-displaced, so a periodic re-evaluation is required. Coordinator drives the tick while any continuous-mode source is non-deadzoned; auto-stops on full release.
- New: `app/src/test/java/com/mapo/service/input/modes/MouseModesTest.kt`.

**Exit criteria:** RJ → Joystick Camera moves the in-game camera in a GameNative title. LJ → Mouse Joystick moves the system cursor in a non-GameNative menu. Cursor visibility verified across both (re-confirms probe finding).

**Risks:**
- Polling battery cost — tick must auto-stop when all continuous-mode sources are inside their deadzone.
- Set-switch mid-deflection — coordinator must zero pending deltas, else cursor jerks on the next tick using a stale source-magnitude cache.

### Brick 8 — Probe relocation to "Debug Tools" drawer section + cleanup

**Goal:** Move the existing motion probe out of the main drawer items into a collapsible "Debug Tools" section (keep it operational for future regression / diagnostic use, per user direction). Sunset the deprecated non-focused `MotionCaptureOverlay`.

**Files:**
- `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` — new `DebugToolsSection` composable; collapsed-by-default group; move probe entry into it.
- Delete: `app/src/main/java/com/mapo/service/input/MotionCaptureOverlay.kt` — old inert non-focused overlay; only remaining caller removed in Brick 3.
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — verify no lingering references.
- `CLAUDE.md` — Phase 6 status from "deferred" to "shipped"; document the gating predicate.

**Exit criteria:** Probe still works from Debug Tools, all analog modes from Bricks 5–7 still functional, dead code gone.

**Risks:** Low — cleanup brick.

---

## What's NOT in this plan

Out of scope for this plan:
- **Scroll Wheel / 2D Scroll** — straightforward extensions of the continuous-output path; pulled out to keep this plan focused.
- **Absolute Mouse / Mouse Region** — Generic Android has no trackpad source; relevant later for Steam-Deck-class devices.
- **Flickstick** — needs gyro capture (separate sensor pipeline, not covered by motion-event overlay).
- **Per-app inheritance layers within a profile** (the user's deferred item).
- **Reference mode** — folded into Phase 7 (VDF import) per prior decision.
- **In-overlay analog-mode "pause" affordance** (e.g. quick-disable via FGS notification action) — UX polish.

---

## Verification (end-to-end, on completion of Brick 8)

Single-screen device (phone or Thor) with target use case:
1. Install Mapo, grant overlay + accessibility perms, set up a profile.
2. Open the profile → Auto-Switch screen → tap "Add app" → pick GameNative from the picker → binding persists.
3. Open Remap Controls → LJ subheader → mode dropdown → pick "Mouse Joystick"; RJ → "Joystick Camera"; LT → "Trigger" with a `SOFT_PRESS` activator bound to a key.
4. First analog pick triggers the tradeoffs dialog; ack.
5. Launch GameNative.
6. Verify motion-capture overlay attaches (logcat: `MotionCoord: attached`).
7. Stick → in-game camera moves; stick → cursor moves in menus; soft-pull LT → bound key fires; full-pull LT → existing click path fires.
8. GameNative's cursor remains visible throughout (probe finding re-confirmed at production scale).
9. Background to Chrome → coordinator detaches; IME works in Chrome; app-switcher gesture works; back gesture works.
10. Foreground GameNative again → re-attaches.
11. Switch profile (via auto-switch or manual) while GameNative still foreground → no leaked synthetic state; flush worked.

Test suite: full Robolectric green; no regressions in `ComposeSmokeTest`, `MainViewModelTest`, `InputEvaluatorTest`, `KeyboardControllerTest`, controller-config compile tests.

---

## Critical files

Heavy-touch (most edits across bricks):
- `app/src/main/java/com/mapo/service/input/InputEvaluator.kt`
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt`
- `app/src/main/java/com/mapo/service/input/CompiledConfig.kt`
- `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt`
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt`

New (most load-bearing additions):
- `app/src/main/java/com/mapo/service/input/capture/MotionCaptureOverlayManager.kt`
- `app/src/main/java/com/mapo/service/input/capture/MotionCaptureView.kt`
- `app/src/main/java/com/mapo/service/input/capture/MotionCaptureCoordinator.kt`
- `app/src/main/java/com/mapo/service/input/capture/AnalogTick.kt`
- `app/src/main/java/com/mapo/service/input/MouseEmitter.kt`
- `app/src/main/java/com/mapo/ui/screen/AppPickerSheet.kt`
- `app/src/main/java/com/mapo/ui/screen/dialog/AnalogModeTradeoffsDialog.kt`

Light-touch (small logic, docs, or single-call wiring):
- `app/src/main/java/com/mapo/data/repository/ControllerConfigRepository.kt`
- `app/src/main/java/com/mapo/ui/screen/RemapSections.kt`
- `app/src/main/java/com/mapo/ui/screen/AutoSwitchScreen.kt`
- `app/src/main/java/com/mapo/ui/viewmodel/AutoSwitchViewModel.kt`
- `app/src/main/java/com/mapo/data/repository/AppProfileBindingRepository.kt`
- `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt`
- `CLAUDE.md` (post-refactor)

Deleted at refactor close:
- `app/src/main/java/com/mapo/service/input/MotionCaptureOverlay.kt` (old non-focused inert overlay; Brick 8)
