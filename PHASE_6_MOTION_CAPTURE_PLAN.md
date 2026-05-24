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

### Brick 3 — `MotionCaptureOverlayManager` (production focused overlay, attach/detach only) — ✅ COMPLETED

**Goal:** Stand up the production focused-overlay window with a clean attach/detach API. Not yet gated by anything; controlled by a temporary "force attach" debug toggle in the drawer.

**Files actually landed:**
- New: `app/src/main/java/com/mapo/service/input/capture/MotionCaptureOverlayManager.kt` — `@Singleton` with `isAttached: StateFlow<Boolean>`, `attach()`, `detach()`, `toggle()`, `setMotionCallback(...)`. 1×1 transparent focused `TYPE_APPLICATION_OVERLAY`, top-left anchored, `FLAG_NOT_TOUCH_MODAL` (no `FLAG_NOT_FOCUSABLE`). Main-thread-serialized via `Handler(Looper.getMainLooper())`. Borrows `KeyboardOverlayService` FGS for process priority. `setMotionCallback` retroactively patches the live view if called after attach (avoids dropped events between attach and callback wiring).
- New: `app/src/main/java/com/mapo/service/input/capture/MotionCaptureView.kt` — `View` subclass with `onMotion: ((MotionEvent) -> Unit)?` and `onGenericMotionEvent` override returning `false` (no behavioral steal).
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — `@Inject` the manager, call `setMotionCallback { evaluator.handleMotion(it) }` in `onServiceConnected`, call `detach()` in `onUnbind`. Removed the old `MotionCaptureOverlay` attach (lines 191–194). The old `MotionCaptureOverlay.kt` file is left untouched (Brick 8 deletes it).
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` — injected the manager, exposed `motionCaptureForceAttached: StateFlow<Boolean>` and `toggleMotionCaptureForceAttach()`.
- `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` — new drawer item below the existing motion-probe debug item. Label switches between "Force motion capture ON (debug)" and "Force motion capture OFF (debug)" based on current state; uses `Icons.Default.Mouse`; renders `selected = true` when attached.
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` — collected `motionCaptureForceAttached` state; wired the drawer's new callback to `viewModel.toggleMotionCaptureForceAttach()`.
- `app/src/main/res/values/strings.xml` — two new strings for the toggle label states.
- Updated existing tests (`MainViewModelTest`, `MainViewModelMultiBindTest`) — added the new constructor arg (relaxed mock).
- New: `app/src/test/java/com/mapo/service/input/capture/MotionCaptureOverlayManagerTest.kt` — 7 Robolectric tests covering initial state, idempotent detach, attach↔detach state flow, permission-denied no-op, retroactive callback wiring, toggle, idempotent attach. `mockkStatic(Settings::class)` for `canDrawOverlays`.
- New: `app/src/test/java/com/mapo/service/input/capture/MotionCaptureViewTest.kt` — 2 Robolectric tests verifying the View forwards events and never consumes them.

**Exit criteria:** Debug toggle attaches the new overlay; motion events flow into `InputEvaluator.handleMotion`; detaching restores normal IME/back/app-switcher behavior. Remap and activity unaffected.

**Deviations / decisions:**
- Manager exposes `setMotionCallback` (called by the service at connect) rather than taking the callback as a constructor param. Keeps the `@Singleton` constructor pure (Hilt-injectable) while still allowing the service-lifetime callback to be wired without a separate factory.
- Retroactive callback patching: `setMotionCallback` after `attach()` updates the live view's `onMotion`. Prevents a startup race where the manager is attached (via debug toggle or future coordinator) before the service has finished wiring the callback.
- Kept `MotionCaptureOverlay.kt` (old inert non-focused class) in place — `MotionProbeAppOverlay.kt` still calls `MotionCaptureOverlay.describe(...)` as a logging helper. Brick 8 will delete the file (and either inline `describe()` into the probe or move it elsewhere).
- Debug toggle label flips between "ON" / "OFF" rather than showing a switch. Matches the existing drawer pattern (NavigationDrawerItem with label-only); avoids cramming a Switch into the drawer item layout.
- No coordinator wiring this brick — `motionCaptureForceAttached` is the only thing driving `attach()` for now. Brick 4 replaces the toggle with the predicate.

**Hand-off — device verification:**
1. Open drawer → see the new "Force motion capture ON (debug)" item below the existing motion probe.
2. Tap it → label switches to "Force motion capture OFF (debug)" (now attached). Drawer closes.
3. Logcat: search for `MotionCaptureOverlay: attached — focused TYPE_APPLICATION_OVERLAY 1×1 active`.
4. Foreground a game, move the analog sticks → logcat verbose motion events flow via `InputEvaluator.handleMotion` (under `MOTION_TAG`).
5. While attached: IME / back gesture / app-switcher gesture are suspended (expected — same focused-overlay side effects characterized by the probe).
6. Open drawer → tap the now-"OFF" item to detach. Drawer closes. Logcat: `MotionCaptureOverlay: detached`.
7. IME / back / app-switcher gestures restore to normal immediately.
8. Existing remap (digital buttons) keeps working through both attach and detach.

**Risks:** Visible artifacts — the production window must be truly invisible (the probe is intentionally red). Verify 1×1 + `PixelFormat.TRANSPARENT` doesn't break event delivery on some OEMs.

### Brick 4 — `MotionCaptureCoordinator` (gating + first-time tradeoffs dialog) — ✅ COMPLETED

**Goal:** Replace Brick 3's "force attach" with the real predicate (D3): attach only when `(foreground app ∈ active profile's bound apps) AND (any source in the resolved set/layer has an analog mode configured)`.

**Files actually landed:**
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — added `ANALOG_MODES_REQUIRING_MOTION_CAPTURE: Set<BindingMode>` constant (joystick / mouse / scroll modes — TRIGGER deliberately excluded; Brick 5 will revisit when Soft_Press lands) plus `BindingMode.requiresMotionCapture()` extension.
- `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — exposed `activeSetIdFlow: StateFlow<Long>` and `activeLayerIdsFlow: StateFlow<List<Long>>` (publish-mirrors of the internal state; mutations call `publishActiveLayers()`). New `flushAnalog()` method — empty body for now, Brick 5+ fill it per mode. `flushAllRuntime` now calls `flushAnalog()` on set-switch.
- New: `app/src/main/java/com/mapo/service/input/capture/MotionCaptureCoordinator.kt` — `@Singleton`. `start()` / `stop()` lifecycle. `evaluatePredicate(...)` extracted as a pure function for testability. Uses the 6-flow `combine` vararg form, casts indexed values to typed locals. On profile-id change in the combine collector, calls `inputEvaluator.flushAnalog()` before recomputing (idempotent no-op today; future-proofs the brick boundary).
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` — injected the coordinator; `onServiceConnected` calls `coordinator.start()` after wiring the manager callback; `onUnbind` calls `coordinator.stop()`.
- New: `app/src/main/java/com/mapo/data/settings/AnalogModePreferences.kt` — `@Singleton` SharedPreferences-backed ack store (chose SharedPreferences over DataStore to match the existing `AutoSwitchSettings` pattern — no new dependency to justify). `tradeoffsAcknowledged: StateFlow<Boolean>` + `setTradeoffsAcknowledged()`.
- New: `app/src/main/java/com/mapo/ui/screen/dialog/AnalogModeTradeoffsDialog.kt` — M3 `AlertDialog` shown once on first analog-mode pick. Body explains the IME / back / app-switcher gesture suspension while the capture overlay is active.
- `app/src/main/res/values/strings.xml` — 5 new strings under the Brick-4 comment for the dialog body + title + confirm button.
- `app/src/main/java/com/mapo/ui/screen/RemapControlsScreen.kt` — new `analogModeTradeoffsAcknowledged` + `onAcknowledgeAnalogModeTradeoffs` params. Internal `gatedSetBindingGroupMode` wraps the original callback: analog mode pick + not yet acked → stash in `pendingAnalogPick` state and show the dialog. Confirm → ack and apply; cancel → drop.
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` — injected `AnalogModePreferences`. Exposed `analogModeTradeoffsAcknowledged: StateFlow<Boolean>` and `acknowledgeAnalogModeTradeoffs()`. **Removed** the Brick 3 force-attach surface (`motionCaptureForceAttached`, `toggleMotionCaptureForceAttach`); the coordinator drives attachment now.
- `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` — removed the Brick 3 force-attach drawer item, its params, and the `Mouse` icon import.
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` — removed the force-attach state collection and drawer wiring; collected the new `analogModeTradeoffsAcked` and wired it + ack callback into `RemapControlsScreen`.
- Updated existing tests (`MainViewModelTest`, `MainViewModelMultiBindTest`) — dropped the now-unused `motionCaptureOverlayManager` field/mock/constructor arg; added the `analogModePreferences` mock.
- New: `app/src/test/java/com/mapo/service/input/capture/MotionCaptureCoordinatorTest.kt` — 8 truth-table tests covering null-foreground, null-profile, unbound-app, all-digital, base-set-analog, layer-overlay-analog, inactive-layer-with-analog, and the `activeSetId == 0L` lazy-uninit fallback to `startingActionSetId`.

**Exit criteria:** Switching foreground app to/from a bound-with-analog-config app attaches/detaches the overlay observably; unrelated apps don't attach. First analog-mode pick triggers the dialog. Remap unaffected throughout.

**Deviations / decisions:**
- SharedPreferences instead of DataStore for the tradeoffs ack — matches the rest of the codebase, no new dependency.
- `combine` of 6 flows uses the vararg form with indexed casts. (Kotlin's `combine` only has typed overloads up to 5 flows.) Casts are localized to one function and the surrounding types are concrete.
- TRIGGER is NOT in `ANALOG_MODES_REQUIRING_MOTION_CAPTURE` for this brick. Brick 5 will move it in once Soft_Press requires motion capture (likely conditional on activator config to avoid attaching for vanilla trigger clicks).
- `flushAnalog()` shipped as an empty no-op rather than deferred. The hook is at the right call sites today (`flushAllRuntime` + coordinator profile-switch) so Brick 5+ just fill in the body.
- The mode picker dialog is rendered inside `RemapControlsScreen` rather than the screen's parent. Keeps the pending-pick state local to the only place that produces it.

**Hand-off — device verification:**

Setup:
1. Open Auto-Switch → bind a launchable app to your active profile (e.g. GameNative).
2. Open Remap Controls → Joysticks → pick "Mouse Joystick" for the left joystick. (As of the post-Brick-4 follow-up, every analog-capable source — sticks, dpad, triggers — defaults to `[Device default]` / UNBOUND on a fresh profile; the overlay stays detached until you explicitly pick an analog mode for at least one source.)
3. First analog-mode pick should trigger the "Heads up — analog modes need a capture overlay" dialog. Read it, tap "Got it".
4. The mode persists. Subsequent analog-mode picks (e.g., switching the right joystick to "Joystick Camera") proceed silently — dialog was already acknowledged.

Predicate verification (use `adb logcat | grep MotionCaptureCoord`):
5. Background to the bound app → logcat shows `attached — focused TYPE_APPLICATION_OVERLAY 1×1 active` (manager) and `decision: shouldAttach=true` (coordinator). Motion events flow.
6. Switch to an UNBOUND app (e.g. Chrome) → logcat shows `decision: shouldAttach=false` and the overlay detaches. IME / back / app-switcher gestures work normally in the unbound app.
7. Switch back to the bound app → re-attaches.
8. To verify the predicate flips false the other direction: in Remap Controls, switch **every** analog-mode source back to `[Device default]` (the gating predicate is per-set: if ANY source in the active set has an analog mode, the overlay attaches — so leaving just one analog stick configured keeps it attached). Once no source in the set is analog, the coordinator detaches even while the bound app is still foreground.
9. Re-set an analog mode on any source → re-attaches.

**Risks:**
- Cold-launch race — `compiledConfig` lazy-loads after first event; coordinator tolerates `EMPTY` config via `compiled.sets[resolvedSetId] ?: return false`.
- `ProfileAutoSwitcher` may swap profile mid-attached-overlay; coordinator calls `InputEvaluator.flushAnalog()` on profile-id transition (today a no-op — Brick 5+ fill).

### Brick 5 — First analog mode: Trigger Soft_Press — ✅ COMPLETED 2026-05-20

**Goal:** Ship the first real `SourceMode.evaluate()`. LEFT/RIGHT_TRIGGER analog pull crossing the configured `soft_threshold` synthesizes a SOFT_PRESS-activator fire on the source's `"click"` sub-input. Hysteresis on release.

**Files actually landed:**
- `app/src/main/java/com/mapo/service/input/modes/SourceMode.kt` — added `evaluate()` to the interface (D1), `ModeContext` data class (settings + prior-latched + layer ids), `MouseEmitter` placeholder interface + `NOOP` singleton (D2 lock for Brick 7). `TriggerMode` implements `evaluate()` with hysteresis edge detection; added `SYNTH_SOFT_PRESS = "soft_press"` constant. Settings: `click_threshold=0.95`, `soft_threshold=0.10`, `soft_hysteresis=0.05` (Steam-default). `TriggerSettings.parse()` is tolerant of missing keys / malformed JSON (falls back to defaults). Added `BindingMode.TRIGGER` to `ANALOG_MODES_REQUIRING_MOTION_CAPTURE`.
- `app/src/main/java/com/mapo/service/input/CompiledConfig.kt` — added `modeSettingsJson: String = ""` to `CompiledInput`, threaded through `toCompiled()` from `preset.group.group.settingsJson`.
- `app/src/main/java/com/mapo/service/input/InputEvaluator.kt` — `handleMotion` becomes a real dispatcher: per-reading walks layers + base set for the source's mode, calls `mode.evaluate(reading, ctx, digitalEmit, MouseEmitter.NOOP)`. Synthetic edges route through `dispatchSyntheticEdge` — `"soft_press"` is special-cased to fire/release SOFT_PRESS-type activators on the source's `"click"` address; other sub-input strings route through `onPress`/`onRelease` (Brick 6 hook). Added `analogLatched` per-(source × virtual sub-input) state map. Removed the `else -> "skipping"` branch in `onPress` and added an explicit `ActivatorType.SOFT_PRESS -> { /* analog path owns this */ }` no-op so the hardware DOWN passes through naturally. `flushAnalog()` body filled in: for each latched virtual edge, synthesize a falling-edge dispatch (releases held bindings through the same release path a real motion-driven UP would use).
- `app/src/main/java/com/mapo/ui/screen/InputEditorScreen.kt` — `UNIMPLEMENTED_ACTIVATORS` is now empty; SOFT_PRESS no longer surfaces a "Coming soon" hint.
- New: `app/src/test/java/com/mapo/service/input/modes/TriggerModeSoftPressTest.kt` — 11 focused tests (rising-edge fire-once, sustained-no-refire, hysteresis dead-band, falling-edge fire-once, custom threshold honored, missing-keys / malformed JSON tolerance, defaults pinned, validInputs shape). Robolectric runner — plain JUnit gets the `isReturnDefaultValues=true` stub of `org.json.JSONObject` and every reading would read 0.0.

**Deviations from plan:**
- **Soft Pull row unified after first device verification (2026-05-20).** Initial implementation kept Soft_Press as an activator type on the `"click"` sub-input — but `RemapSections.kt` already had a `RemapPaneItem.DisabledRow("triggers.left.soft", "L2 Soft Pull")` placeholder waiting for analog. The UI design always intended Soft Pull as a separate sub-input row. After device verification surfaced both the firing failure and the user's "how does this differ from Soft Pull?" confusion, I unified: added `"soft_press"` to `TriggerMode.validInputs` + the trigger seed; converted L2/R2 Soft Pull from `DisabledRow` → live `BindingRow` at `(LEFT/RIGHT_TRIGGER, "soft_press")`; simplified `dispatchSyntheticEdge` to route the synthetic edge through normal `onPress`/`onRelease` (no special-casing); removed `SOFT_PRESS` from the activator-type dropdown (`ACTIVATOR_RENDER_ORDER` in `InputEditorScreen.kt`) — the enum value stays for VDF import. `handleSoftPressEdge` and `releaseAnalogActivatorsAt` helpers retired. The defensive `ActivatorType.SOFT_PRESS -> { /* no-op */ }` case in `onPress` stays in case any legacy data carries that activator type on the click row.
- **`MouseEmitter` is an empty interface for now**, not the full Brick-7 sink. Locked the signature for D1; Brick 7 fills in the methods.
- **TRIGGER unconditionally added to the motion-capture set.** A loose-but-correct gating: a binding_group in TRIGGER mode with no soft-pull activators bound doesn't strictly need motion capture. A tighter predicate that inspects activator presence is a coordinator-side refinement; deferred — code comment in `ANALOG_MODES_REQUIRING_MOTION_CAPTURE` flags the trade-off.

**Hand-off — device verification (post-Soft-Pull-unification):**

Setup (**wipe app data first** — the new seed adds a `"soft_press"` sub-input row to every trigger binding_group; existing profiles created before this change won't have it):
1. Clear-app-data Mapo, re-install the new APK, set up a fresh profile.
2. Auto-Switch → bind a launchable app (e.g. GameNative) to the active profile.
3. Open Remap Controls → Left Trigger → mode dropdown → pick "Trigger". First analog-mode pick triggers the tradeoffs dialog → "Got it".
4. Under the Left Trigger subheader you should now see **two live rows**: "L2 Full Pull" (hardware threshold) and "L2 Soft Pull" (analog soft-pull). The "Analog Output Trigger" row is still a disabled placeholder.
5. Tap the "L2 Soft Pull" row → InputEditor opens. Add a Regular Press activator → bind ENTER (or any recognizable key).

Predicate verification (use `adb logcat "MotionCaptureCoord:D" "InputEvaluator:D" "InputEvaluator.Motion:D" "*:S"`):
6. Foreground the bound app → coordinator attaches; `decision: shouldAttach=true`.
7. Pull LT softly past ~10% → `synthetic edge: LEFT_TRIGGER.soft_press DOWN`; ENTER fires DOWN on the foreground app.
8. Sustain the pull → no re-fire.
9. Wobble around the threshold (e.g. 0.08 ↔ 0.12) → no flutter (within hysteresis band).
10. Release fully (below ~5%) → `synthetic edge: LEFT_TRIGGER.soft_press UP`; ENTER releases.
11. Full-pull past the hardware click threshold → if no activator is wired on "L2 Full Pull" (the `"click"` sub-input), the hardware L2 click passes through to the foreground app naturally.
12. (Optional) Bind a different key to L2 Full Pull — soft-pull and full-pull fire independently.
13. (Optional) Set LT back to `[Device default]` — coordinator detaches if no other source is analog; IME / back / app-switcher gestures return.

**Risks (settled):**
- Hysteresis (5% Steam default) bakes in non-flutter behavior — verified by the dead-band test.
- Hardware UP releasing both Full- and Soft-Pull HeldEntries when both fire on (source, "click") is not a concern with the unified design — they live on different addresses now (`"click"` vs `"soft_press"`).

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
