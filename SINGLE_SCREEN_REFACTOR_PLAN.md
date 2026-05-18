# Single-Screen Refactor Plan — overlay-hosted run-mode keyboard

## Context

Mapo today is architected around the AYN Thor's dual-screen form factor. The virtual-keyboard view lives **inside `MainActivity`'s window** (not as a system overlay), sized to fill the bottom screen; `FLAG_NOT_FOCUSABLE` is toggled per-destination so unmapped gamepad inputs flow to the game running on the top screen. This works on Thor; it does not translate to a single-screen Android device, where a full-window keyboard activity would simply obscure the game.

On 2026-05-15 the user redirected Mapo's primary target to **any single-screen Android device**. Thor remains a secondary supported form factor. New model: virtual keyboards render as overlays on top of a foregrounded game, the way production no-root Android remap apps work.

Prereq for:
- Phase 6 analog modes (motion capture's focus-routing issues are tied to the same architecture decisions resolved here).
- Phase 7 VDF import (consumes overlay-rendered concepts like radial / touch menus).
- Phase 8 radial / touch menu rendering.

Goal: every existing feature (physical remap, virtual keyboards, profile auto-switch, edit mode, layouts) works on a single-screen device with the keyboard overlaid on the foregrounded game, AND continues to work on Thor.

---

## Decision: overlay-hosted run-mode keyboard

Mount the virtual keyboard inside a `TYPE_APPLICATION_OVERLAY` window. Activity remains the home for configuration / edit-mode UI; the overlay is run-mode only.

Rejected alternatives:
- `TYPE_ACCESSIBILITY_OVERLAY` focusable → Phase 6 already proved it breaks system focus globally (IME, back gesture, cursor, app-switcher all malfunction).
- IME (`InputMethodService`) → Mapo's keyboard isn't a text-input keyboard; can't dispatch mouse / gestures; couples visibility to text-field focus.
- Game in PiP → can't force PiP on apps we don't own; many games opt out.
- Multi-window / split-screen → game-dependent; mangles fullscreen render surfaces.
- Sub-activity / `Presentation` → second activity steals focus from the game.

The overlay uses `FLAG_NOT_TOUCH_MODAL` + `FLAG_NOT_FOCUSABLE`. In-bounds taps reach Compose (touch routing is governed by `FLAG_NOT_TOUCHABLE`, which we don't set); out-of-bounds taps fall through to the foreground app; key events (gamepad button presses, back, etc.) route past the overlay to whatever window holds keyboard focus underneath — so the foreground game keeps receiving gamepad input while the keyboard is mounted. Crucially, this overlay does NOT need to receive gamepad motion events (the Phase 6 blocker), so the focus side effects observed there don't apply here.

(Original plan text claimed `FLAG_NOT_FOCUSABLE` should be omitted "so taps reach Compose." That conflated touch routing with key-event focus — Brick 1 device verification proved the overlay absorbed every gamepad press without `FLAG_NOT_FOCUSABLE`, navigating the overlay instead of the game. Adding the flag fixed the regression; taps continue to work because they were never gated on focus.)

**Activation: Quick Settings tile.** User pulls down the notification shade and taps the Mapo tile to toggle the overlay. Modern Android-native pattern, no physical-button commitment, system-wide reachable. Other activation mechanisms deferred.

---

## Locked-in contracts (Brick 0)

These contracts every later brick honors.

### State ownership split

- `KeyboardController` (`@Singleton`, Hilt) owns: active profile id, selected layout id, displayed `GridLayout`, remap-enable flag, dispatch methods (`onButtonTap`, `onButtonDoubleTap`, `onButtonHold`, trackpad gestures, drag handlers).
- `MainViewModel` retains: edit-mode UI state (`selectedButtonId`, `editingLayoutId`, tab context menus, dialog states) — all activity-local.

### Overlay contract

- Overlay mode is **run-only**. No edit affordances, no NavHost, no drawer, no Scaffold.
- Renders: slim `KeyboardTopBar` (tab selector + "Open Mapo" button to launch the activity) + `KeyboardSurface` + `KeyGrid` + run-mode `BottomBar` (remap toggle + hide-overlay).

### Activation

- QS tile only for this refactor. Additional triggers (physical-button binding, floating handle, etc.) explicitly deferred.

### Background-only operation

- Single FGS service runs whenever remap is enabled OR keyboard overlay is shown. Notification text adapts to state.

### Forward-compatibility seams

Two future architectural shifts are anticipated but NOT delivered in this refactor; the contracts above leave room for them:

- **FC1 — Action sets / action layers as the overlay's governing model (Steam Input parity).** Each `ActionSet` will eventually have an associated overlay; each `ActionLayer` an associated overlay that inherits from its base set (same override semantics Steam Input uses for physical-input bindings — inherited copies carry an `inheritedFrom` back-reference; base changes propagate down to still-tracking copies; layer-side edits break the link for that element only). **Seams:** `KeyboardController` exposes `StateFlow<GridLayout?>` (rendered grid), not `StateFlow<LayoutId>`. `KeyboardHost` takes an opaque grid, not a `Layout` entity. Tabs are opaque `KeyboardTab` objects, not `Layout` rows. The resolver `(profile, layoutId) → GridLayout` later becomes `(profile, activeSet, activeLayer) → GridLayout` with no consumer changes.

- **FC2 — Thor bottom screen as canvas extension of the active overlay.** The same active action set / layer will eventually drive elements on BOTH the top and bottom screens simultaneously (bottom screen is more canvas for the active overlay, not a separate keyboard). **Seams:** `KeyboardOverlayManager` is multi-window-capable and display-aware from Brick 1 (each window carries overlay id + display id). `KeyboardDisplayRouter` (Brick 5) returns a *collection* of physical surfaces — sized 1 today, sized N tomorrow. Renderer pipeline keeps "this overlay's elements" cleanly separated from "this overlay's elements on display X" so the future per-element display filter is a local insertion.

No data-model changes for either FC are landing in this refactor.

---

## Bricks

Sequencing rule: **every brick boundary leaves remap working end-to-end and the activity functional.**

### Brick 0 — Architectural decisions (no code) — ✅ COMPLETED

This file is the deliverable. Contracts above are agreed.

### Brick 1 — Overlay POC + foreground-service skeleton — ✅ COMPLETED

Goal: answer R1 (touch routing on a large `FLAG_NOT_TOUCH_MODAL` overlay) + R2 (FGS requirements on Android 12+).

- New `KeyboardOverlayManager` (sibling of `OverlayManager`; do NOT extend — different lifecycle, different flag matrix). Multi-window-capable attach/detach API: `attach(overlayId, displayId, content)` / `detach(overlayId)`. Brick 1 mounts a placeholder `ComposeView` grid of 12 tappable boxes (each `Log.d`s on tap). `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCH_MODAL` (without `FLAG_NOT_FOCUSABLE`). `MATCH_PARENT` × ~40% height, anchored bottom.
- New `KeyboardOverlayService` (foreground). Manifest entry with `foregroundServiceType` (likely `specialUse` with justification string on API 34+; verify target SDK in `app/build.gradle`). Low-priority persistent notification. Started on overlay attach, stopped when last overlay detaches.
- Debug-only "Mount POC keyboard overlay" entry temporarily in settings.

**Files (new unless noted):**
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayManager.kt`
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayService.kt`
- `app/src/main/AndroidManifest.xml` (FGS declaration, `FOREGROUND_SERVICE` + typed permission)
- Temporary debug entry in an existing settings screen

**Verify:**
- Taps inside placeholder grid → log lines; taps outside → underlying app responds.
- Foreground gamepad input keeps reaching the underlying game.
- Survives activity backgrounding + dismissal from recents.
- FGS notification appears, dismissible only via stopping the service.
- Tested on single-screen phone AND on Thor (overlay attaches to whichever display the foreground app is on — verify both screens).

**Exit criteria:** R1 / R2 findings documented; blockers surfaced if any.

#### Brick 1 deviations + decisions

- **`POST_NOTIFICATIONS` permission added** alongside the two FGS permissions. Android 13+ requires it for the FGS's persistent notification to render — without it the service still runs but the notification is silently dropped, which means no visible indicator that the keyboard overlay's process priority is being held.
- **Manager mounts the FGS via started-service pattern** (`Context.startForegroundService` + `stopService`), not bound-service. Simpler for Brick 1; Brick 4's QS-tile flow may want bind semantics so the service can also drive the overlay (TileService → service → manager), but that decision is deferred until the tile lands.
- **`KeyboardOverlayPocContent.kt` is its own file**, not a `private fun` inside the manager. The ViewModel needs to reference it for the debug toggle, and keeping it separate also makes its "Brick 1 placeholder, replaced in Brick 4" status clearer in the file tree.
- **Drawable added.** Mapo had zero `res/drawable/` files before this brick; the FGS notification requires a small icon, so `ic_keyboard_overlay.xml` was created (vector, 24dp, white fill). The system tints it automatically on the status bar.
- **Overlay flag matrix corrected mid-brick to add `FLAG_NOT_FOCUSABLE`.** The original plan text claimed the keyboard surface should omit `FLAG_NOT_FOCUSABLE` "so Compose taps work." Device verification proved this conflated touch routing with key-event focus — without `FLAG_NOT_FOCUSABLE` the overlay absorbed every gamepad button press and the user's controller navigated the overlay's Compose focus tree instead of driving the foreground game. Adding the flag: gamepad input reaches the game, taps inside the overlay still reach Compose (touch is gated by `FLAG_NOT_TOUCHABLE`, which we don't set), back gesture passes through. Final flag set: `FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`. Plan's "Decision" section corrected with the lesson inline.
- **`consumeSystemBack` made lifecycle-scoped (originally a Brick 5 cleanup item, pulled in here).** Device verification surfaced that Mapo's accessibility service was swallowing KEYCODE_BACK system-wide — once `consumeSystemBack` was set true (any time the user landed on the Main route with drawer closed), the global accessibility service consumed back everywhere, including in other apps. Pre-existing Thor-first behavior: the original logic assumed Mapo's activity is always foregrounded on Thor's bottom screen. The fix wraps the `setConsumeSystemBack(keyboardViewActive)` call in `repeatOnLifecycle(Lifecycle.State.STARTED)` so the flag clears automatically when Mapo's activity drops below STARTED (backgrounded) and re-evaluates when it returns. Pulled forward from Brick 5 because it actively breaks back-button behavior in other apps as soon as the user opens Mapo even once.

#### Files actually landed

- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayManager.kt` (new)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayService.kt` (new)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayPocContent.kt` (new — Brick 1 only)
- `app/src/main/res/drawable/ic_keyboard_overlay.xml` (new)
- `app/src/main/AndroidManifest.xml` (FGS perms + service declaration + property)
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` (injection + `togglePocKeyboardOverlay()`)
- `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` (debug drawer item — Brick 1 only)
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (wire callback — Brick 1 only)
- `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelTest.kt` (mock + constructor updates)

#### Hand-off to device verification

Round 1 (2026-05-17) confirmed:
- ✅ Taps inside the placeholder grid reach Compose (logcat shows `KeyboardOverlayPoc: tap on slot N`).
- ✅ Taps outside the grid reach the underlying app.
- ✅ Overlay survives Mapo's dismissal from recents (R2 satisfied — FGS is doing its job).
- ❌ **Regression found:** gamepad input was absorbed by the overlay window, navigating the overlay's focus tree instead of reaching the foreground game. Root cause: missing `FLAG_NOT_FOCUSABLE`. Fixed by adding the flag (see deviations above).

Round 2 (post-fix) — confirmed:
- ✅ Taps inside the grid still log.
- ✅ Gamepad input drives the foreground game (not the overlay).
- ✅ Back-button reaches the foreground app (lifecycle-scoped `consumeSystemBack` fix).
- ✅ Overlay survives Mapo's dismissal from recents (FGS doing its job).

Thor multi-display routing (overlay auto-attaching to whichever screen has the foreground app) was deferred to Brick 5. R1, R2, and the back-button regression are resolved.

### Brick 2 — `KeyboardController` extraction (behavior-preserving) — ✅ COMPLETED

Goal: answer R3 (ViewModel instancing across activity + overlay) without touching UI.

- New `KeyboardController` (`@Singleton`, Hilt). Owns runtime state listed above. Internally depends on `InputDispatcher`, `LayoutRepository`, `ProfileRepository`, `ControllerConfigRepository`, `KeyboardTemplateRepository`.
- `MainViewModel` delegates relocated methods to `KeyboardController`, exposing the same `StateFlow`s. No behavior change at the activity surface.
- Public surface exposes the rendered grid as `StateFlow<GridLayout?>` (FC1 seam), not `StateFlow<LayoutId>`.
- Tabs exposed as opaque `List<KeyboardTab>` (FC1 seam), not `List<Layout>`.

**Files:**
- `app/src/main/java/com/mapo/service/keyboard/KeyboardController.kt` (new)
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` (delegation, no behavior change)
- `app/src/test/java/com/mapo/service/keyboard/KeyboardControllerTest.kt` (new, Robolectric)
- `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelTest.kt` (updated)

**Verify:** all existing tests pass; manual smoke (key tap injects, remap toggle works).

**Exit criteria:** activity uses the controller through the ViewModel; nothing else does yet.

#### Brick 2 deviations + decisions

- **`MainViewModel`'s `_layouts` / `_selectedIndex` / `_remapEnabled` MutableStateFlows were deleted, not retained as mirrors.** Source of truth is now solely the controller; MainViewModel re-exposes the controller's flows under the same names (`layouts`, `selectedIndex`, `remapEnabled`) so `MainScreen` and the existing test surface see no change. Reads inside the VM go through `keyboardController.<flow>.value`; CRUD writes go through `keyboardController.replaceLayouts(...)` / `replaceLayoutById(...)` / `setSelectedIndex(...)`. Optimistic-update semantics preserved verbatim — the controller's repo-backed collector reconciles after each DB roundtrip exactly as `MainViewModel`'s did pre-brick.
- **`displayLayout` non-null compat at the VM boundary.** Controller exposes `StateFlow<GridLayout?>` (the actual FC1 seam: opaque + honest about "no layouts loaded yet"). `MainViewModel.displayLayout` keeps the pre-refactor non-null contract via a `map { it ?: DefaultLayouts.all[0] }.stateIn(...)` so MainScreen's existing `.collectAsStateWithLifecycle()` continues to deliver a guaranteed grid. Tomorrow, when KeyboardHost lands and reads the controller directly (Brick 3), it'll handle the nullable surface natively.
- **Error-message relay.** Controller's run-mode dispatch ("Accessibility service not running") emits to a new `errorMessages: SharedFlow<String>` instead of writing to a VM-owned toast field. `MainViewModel`'s `init { ... }` collects this and forwards to the existing `_toastMessage` flow so MainScreen's toast collector keeps working unchanged.
- **`MainViewModelTest` uses a real `KeyboardController`, not a mock.** Tests verify behavior through the VM's re-exposed flows (`subject.layouts.value`, `subject.selectedIndex.value`); a relaxed mock would have no real state, so the real controller wires through the same mocked `LayoutRepository` / `ProfileRepository` the VM uses. Same test-fixture pattern (`StandardTestDispatcher`, `MutableStateFlow` mocks for repos) — just one more constructor field.
- **`@OptIn(ExperimentalCoroutinesApi::class)` is applied to `KeyboardController` for `flatMapLatest` in the repo collector** — same opt-in `MainViewModel` already carried for the same reason.
- **Singleton scope is `Main.immediate`** so `StateFlow` writes from the main thread don't re-dispatch (matches the pre-refactor behavior of `MutableStateFlow.value =` on the main thread). Repos handle their own IO-thread switches as before.

#### Files actually landed

- `app/src/main/java/com/mapo/service/keyboard/KeyboardController.kt` (new)
- `app/src/main/java/com/mapo/service/keyboard/KeyboardTab.kt` (new — FC1 seam type)
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` (delegation, ~30 sites updated)
- `app/src/test/java/com/mapo/service/keyboard/KeyboardControllerTest.kt` (new, 20 tests)
- `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelTest.kt` (real-controller wiring in setUp + `rebuildSubject`)

### Brick 3 — Host-agnostic `KeyboardHost` composable — 🟡 CODE LANDED (device verification pending)

Goal: make the keyboard UI mountable from anywhere.

- Extract the keyboard subtree (`KeyboardTopBar` parameterized + `KeyboardSurface` + `KeyGrid` + run-mode `BottomBar`) into a new `KeyboardHost.kt`. Takes a `KeyboardHostState` (thin wrapper around `KeyboardController` exposing StateFlows + handler functions) and a `KeyboardHostMode` (`Activity` or `Overlay`).
- `Activity` mode: full features, edit affordances visible, edit callbacks navigate via `navController`.
- `Overlay` mode: run-only, no edit affordances, "Open Mapo" button fires an Intent.
- `MainScreen` reduced to: `NavHost` + drawer + per-route Scaffolds. Main route mounts `KeyboardHost(mode = Activity, ...)`.

**Files:**
- `app/src/main/java/com/mapo/ui/screen/keyboard/KeyboardHost.kt` (new)
- `app/src/main/java/com/mapo/ui/screen/keyboard/KeyboardHostState.kt` (new)
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (move sub-composables out)
- `app/src/test/java/com/mapo/ui/screen/keyboard/KeyboardHostTest.kt` (new, Robolectric Compose)

**Verify:** activity-side behavior identical; existing tests pass (especially `ComposeSmokeTest`); tab / edit / config paths all still work.

**Exit criteria:** `KeyboardHost(Activity, ...)` is the only path the activity uses; compiles with `Overlay` mode plugged into Brick 1's POC slot.

#### Brick 3 deviations + decisions

- **Sub-composables stay in `MainScreen.kt` with relaxed visibility (`private` → `internal`).** Original plan envisioned physically moving `KeyboardTopBar`, `KeyGrid`, `KeyboardSurface`, `BottomBar` (plus their ~600 lines of private helpers — `selectionOutline`, `circleDropShadow`, `softDropShadow`, `KeyButtonShape`, `ButtonContent`, `RegionView`, `RegionPosition.alignment`, the dozen shared `dp`/`Color` constants) into a new file. That's a 1000+ line shuffle with no architectural payoff — the seam this brick establishes is the **contract surface** (`KeyboardHost` + `KeyboardHostState` + `KeyboardHostMode`), not a file boundary. Visibility flips let `KeyboardHost.kt` call into the existing composables without moving them. Future cleanup can do the physical move once the dust settles.
- **`MainViewModel` implements `KeyboardHostState` directly.** Saves writing an adapter — all the interface methods/properties were already on the VM with matching names + signatures. The overlay-side `KeyboardHostState` impl (Brick 4) will be a tiny wrapper over `KeyboardController` instead.
- **`BottomBar` parameterized.** Old signature was `(remapEnabled, onToggleRemap, onQuit)`. New is `(remapEnabled, onToggleRemap, onLeftAction, leftActionLabel)` so the same composable serves Activity ("Quit") and Overlay ("Hide"). Single-source UI; mode-specific copy lives in the `KeyboardHostMode` branch.
- **Overlay top-bar visual polish deferred to Brick 4.** Brick 3's Overlay-mode `KeyboardTopBar` reuses the Activity-mode `KeyboardTopBar` with all edit-related callbacks stubbed out (`onLongPressMenu = {}`, etc.) and `isEditMode = false`. That gets us a compilable, functionally-correct Overlay-mode render path now; the "slim top bar — tab selector + 'Open Mapo' button only" variant in the original plan is a Brick 4 polish pass.
- **`KeyboardHostTest`** is a Robolectric smoke check, not exhaustive interaction verification. The underlying composables already have dedicated tests (`ActivatorEditorScreenTest`, `InputEditorScreenTest`, etc.); the host's job is plumbing, so the test checks plumbing — does each mode render without crash, does the bottom-bar label flip between modes.

#### Files actually landed

- `app/src/main/java/com/mapo/ui/screen/keyboard/KeyboardHost.kt` (new)
- `app/src/main/java/com/mapo/ui/screen/keyboard/KeyboardHostState.kt` (new)
- `app/src/main/java/com/mapo/ui/screen/keyboard/KeyboardHostMode.kt` (new — sealed Activity/Overlay variants)
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (visibility flips on 4 composables + main-route rewrite to call `KeyboardHost`)
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` (`: KeyboardHostState` + `override` keywords)
- `app/src/test/java/com/mapo/ui/screen/keyboard/KeyboardHostTest.kt` (new — Robolectric smoke checks for both modes)

#### Hand-off to device verification

Compile + tests green. Activity-mode rendering is the higher-risk path (this is what users see daily). Quick sanity checks worth running on device:
1. Open Mapo → keyboard view renders. Tab bar at top, key grid in the middle, bottom bar with Quit + remap switch at the bottom — same as before this brick.
2. Tap a key → it injects (same as before).
3. Long-press a tab → context menu appears, edit/configure/remove/duplicate/save-template options all work.
4. Enter edit mode → grid shows drag handles + "+" affordances, buttons can be moved/resized.
5. Drawer open/close, profile change, auto-switch prompt, remap toggle all unchanged.

If everything renders identically, Brick 3 closes. Brick 4 (production overlay keyboard via QS tile + real `KeyboardHost(mode = Overlay, ...)`) is next.

### Brick 4 — Production overlay-mounted keyboard — 🟡 CODE LANDED (device verification pending)

Goal: end-to-end overlay keyboard with QS tile activation.

- Replace POC content in `KeyboardOverlayManager` with `KeyboardHost(mode = Overlay, ...)`.
- New `KeyboardTileService` (QS tile). Tap toggles overlay visibility. Tile state synced with attach/detach.
- In-app "Show keyboard overlay" toggle in settings (likely `ProfileDrawerContent` or new settings entry).
- FGS notification gets a "Show / Hide keyboard" action button mirroring the tile.
- Auto-switch profile-create prompt (`OverlayCoordinator`): when keyboard overlay is mounted, embed inside the keyboard overlay's surface as a snackbar layer; otherwise show as today. Avoids stacked focusable overlays.
- `InputDispatcher.setOverlayFocused` boolean → typed `OverlayFocusKind { NONE, PROMPT, KEYBOARD }`.

**Files:**
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayManager.kt` (real content)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardTileService.kt` (new)
- `app/src/main/AndroidManifest.xml` (tile service)
- Settings entry (existing screen or new)
- `app/src/main/java/com/mapo/service/overlay/OverlayCoordinator.kt` (prompt-routing tweak)
- `app/src/main/java/com/mapo/service/input/InputDispatcher.kt` (typed enum)
- `app/src/test/java/com/mapo/service/overlay/keyboard/KeyboardOverlayManagerTest.kt` (new, Robolectric)

**Verify (single-screen phone):**
- Launch a game → QS tile activates overlay → tap keys → keys inject into game.
- Gamepad input still drives the game while overlay is up.
- Physical-remap pipeline still works (remapped A → ENTER fires in the game).
- Dismiss overlay → game unaffected.
- Activity backgrounded entire time → overlay + FGS notification survive.
- Auto-switch profile while overlay is active → keyboard updates layout.

**Verify (Thor):**
- Same workflow; overlay defaults to bottom screen.

**Exit criteria:** overlay keyboard is the canonical run-mode UX on single-screen. Activity still hosts keyboard for config/edit usage.

#### Brick 4 deviations + decisions

- **New `KeyboardOverlayPresenter` introduced as the single coordination point.** Not in the original brick file list, but the design that emerged: the QS tile, the drawer item, and (future) the FGS notification action all need the same composable wired with the same callbacks (Open Mapo, Hide overlay). Centralizing in a `@Singleton` presenter keeps the callers as one-liners and prevents copy-paste drift. `KeyboardOverlayManager` stays a pure window-attach mechanic underneath.
- **Adapter `KeyboardController.asKeyboardHostState()` extension.** The overlay can't reach `MainViewModel` (different `ViewModelStoreOwner`), so the controller goes through a thin `KeyboardHostState` adapter to mount `KeyboardHost(mode = Overlay)`. Adapter's `displayLayout` fallback to `DefaultLayouts.all[0]` mirrors what MainViewModel does at the same boundary — keeps activity- and overlay-side behaviorally identical. Controller's `StateFlow<GridLayout?>` (FC1 seam) stays the source of truth; both adapters apply the non-null bridge at the same point.
- **`OverlayFocusKind` is a 3-value enum, not the originally-planned 4-value (`NONE`, `PROMPT`, `KEYBOARD`, `INPUT_LAYER_RESERVED`).** Input-layer overlays (FC2-as-originally-scoped) was cancelled mid-plan-refinement, so `INPUT_LAYER_RESERVED` is gone. `KEYBOARD` stays in the enum as a value but is **never set today** — the keyboard overlay's `FLAG_NOT_FOCUSABLE` window means the service has nothing to disambiguate. Kept the value so a future "service routes gamepad differently while the keyboard is up" need lands without an enum-shape change.
- **Auto-switch profile-create prompt embedding inside the keyboard overlay was DEFERRED.** Original plan: "when keyboard overlay is mounted, embed the prompt inside the keyboard overlay's surface as a snackbar layer." That requires adding a snackbar slot to `KeyboardHost` and routing `OverlayCoordinator` decisions through the presenter. Today the prompt continues to render via `OverlayManager` (focusable, stacked above the keyboard overlay). Visual overlap is suboptimal but functionally fine — gamepad navigation still works on the prompt (`OverlayFocusKind.PROMPT` routing). Polish item for a follow-up.
- **FGS notification "Show / Hide keyboard" action button DEFERRED.** QS tile is the primary trigger; the drawer entry is the secondary; a notification action would be a third path. Skipped to keep Brick 4 focused. The FGS notification currently shows "Tap the Mapo Quick Settings tile to hide" — directs users to the tile.
- **`KeyboardOverlayPocContent.kt` deleted.** The Brick 1 placeholder is dead code now that the manager mounts the real `KeyboardHost(Overlay)`.
- **Settings entry not added.** The drawer entry already serves as the alternative-to-tile activation path. Adding a dedicated "Show keyboard overlay" toggle in a separate settings screen is a UX call the user can make later if drawer-discoverability proves insufficient.
- **Tests target `KeyboardOverlayPresenter`, not `KeyboardOverlayManager`.** The manager's behavior is window-system mechanics (`WindowManager.addView`, FGS start/stop, display-context creation) verified on-device. The presenter's orchestration logic (show / hide / toggle / isShowing semantics, canonical overlay id) is what the test suite usefully pins.

#### Files actually landed

- `app/src/main/java/com/mapo/service/input/OverlayFocusKind.kt` (new — 3-value enum)
- `app/src/main/java/com/mapo/service/input/InputDispatcher.kt` (Boolean → enum)
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` (reads `overlayFocus == PROMPT`)
- `app/src/main/java/com/mapo/service/overlay/OverlayManager.kt` (passes `OverlayFocusKind.PROMPT` / `NONE`)
- `app/src/main/java/com/mapo/service/overlay/OverlayContent.kt` (doc reflow)
- `app/src/main/java/com/mapo/service/keyboard/KeyboardControllerHostState.kt` (new — adapter)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayPresenter.kt` (new — orchestrator)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardTileService.kt` (new — QS tile)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayManager.kt` (POC_OVERLAY_ID const removed)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayPocContent.kt` (deleted)
- `app/src/main/AndroidManifest.xml` (tile service entry)
- `app/src/main/res/values/strings.xml` (tile + drawer labels)
- `app/src/main/java/com/mapo/ui/viewmodel/MainViewModel.kt` (presenter injection; `togglePocKeyboardOverlay` → `toggleKeyboardOverlay`)
- `app/src/main/java/com/mapo/ui/screen/ProfileDrawerContent.kt` (label rename + callback param rename)
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (callback rename)
- `app/src/test/java/com/mapo/service/overlay/keyboard/KeyboardOverlayPresenterTest.kt` (new — 6 tests)
- `app/src/test/java/com/mapo/ui/viewmodel/MainViewModelTest.kt` (constructor param rename)

#### Hand-off to device verification

Compile + tests green. Brick 4 is the first time the actual run-mode keyboard renders inside a system overlay — device verification is essential:

**QS tile path:**
1. Open Mapo at least once so the accessibility service is connected and a profile + layouts are loaded.
2. Pull down the notification shade → tap "Edit" (or however your device exposes tile customization) → drag the "Mapo keyboard" tile into the active row.
3. Launch a game / other app.
4. Pull down the shade → tap the Mapo tile. The real keyboard (your actual layout, not a placeholder grid) should appear over the foreground app.
5. Tap a key → it should inject to the foreground app, same as activity-mode keyboard.
6. Tap a key whose mapping is a mouse/scroll → that dispatch path should also fire.
7. Tap the "Hide" button in the overlay's bottom bar → overlay detaches.
8. Tap the tile again → overlay re-appears.

**Drawer path (alternative trigger):**
9. Inside Mapo, open the drawer → tap "Toggle keyboard overlay" → overlay appears (with the activity behind it).
10. Tap again → hides.

**Cross-checks:**
- Gamepad input while overlay is up still drives the foreground game (FLAG_NOT_FOCUSABLE on the overlay window — verified in Brick 1 and preserved here).
- Physical-button remap still works regardless of whether the overlay is showing.
- Back-button reaches the foreground app while overlay is up (lifecycle-scoped `consumeSystemBack` from Brick 1).
- Auto-switch prompt for a new app: today this still pops up via `OverlayManager` (separate window). If you trigger one while the keyboard overlay is up, the prompt will visually overlap. This is the deferred "embed prompt inside keyboard overlay" item — not a Brick 4 blocker.

If all of those work, Brick 4 closes. Brick 5 is cleanup: remove Thor-first scaffolding, verify Thor still works as a secondary device.

### Brick 5 — Thor compatibility + cleanup — 🟡 CODE LANDED (Thor device verification pending)

Goal: validate Thor as a secondary supported device; strip Thor-first scaffolding.

Thor compatibility:
- Overlay defaults to the bottom screen on dual-display devices (or honors a user preference). May need a `Presentation` shim for explicit display routing. Punt to follow-up brick if non-trivial.
- New `KeyboardDisplayRouter` shaped as "given a logical overlay, decide which physical display surface(s) it should attach to" — returns a *collection* (FC2 seam). Sized 1 today, sized N when FC2 lands.

Cleanup (now-dead Thor-first code paths):
- `MainActivity.onCreate`'s unconditional `FLAG_NOT_FOCUSABLE` — remove; defer entirely to per-destination logic.
- Simplify `ApplyMainScreenWindowBehavior` (`MainScreen.kt:929`). Keep the "user is on Main route on Thor's bottom screen while game runs on top" path but rename / re-comment.
- `InputDispatcher.consumeSystemBack` — still useful on Thor's secondary-device path; reframe comments. (Lifecycle-scoping fix was pulled forward into Brick 1 — see Brick 1 deviations.)
- `MainActivity.kt:31-37` "Secondary display detection" TODO — replace with concrete reference or remove.
- `InputAccessibilityService.kt` motion-capture comment — sync with `MotionCaptureOverlay`'s class doc (the in-service "confirmed working" comment is stale).
- `ForegroundAppMonitor.kt` doc — reframe "dual-display devices like the AYN Thor" → single-screen-first.
- `ColorContrast.kt` doc references to bottom-screen surface.
- `CLAUDE.md` already updated; reverify after refactor.

**Files:**
- `app/src/main/java/com/mapo/MainActivity.kt`
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (`ApplyMainScreenWindowBehavior`)
- `app/src/main/java/com/mapo/service/input/InputDispatcher.kt` (doc only)
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` (stale comment fix)
- `app/src/main/java/com/mapo/service/foreground/ForegroundAppMonitor.kt` (doc only)
- `app/src/main/java/com/mapo/ui/theme/ColorContrast.kt` (doc only)
- new `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardDisplayRouter.kt`

**Verify:**
- Thor: keyboard works on bottom screen, remap works during top-screen game, no back-button ANR, no profile-switch glitches.
- Phone: nothing regresses from Brick 4.
- Full Robolectric suite green.

**Exit criteria:** both device classes verified end-to-end. No Thor-specific code paths exist that aren't documented as "secondary-device support."

#### Brick 5 deviations + decisions

- **Foreground-display-aware routing on Thor was DEFERRED**, per the plan's "punt if non-trivial." `KeyboardDisplayRouter.routeOverlay` returns `listOf(Display.DEFAULT_DISPLAY)` unconditionally today, which on Thor means the overlay always lands on the primary (top) screen. For most Thor users that's the screen the game is on, so the overlay-over-game flow works the same as on a phone. The "overlay follows whichever screen the foreground app is on" feature needs `DisplayManager` + `ForegroundAppMonitor` integration that's its own design pass — landing it now would have stretched Brick 5 well past its scope. The router's contract (returns a `List<Int>`) is what FC2 needs; the implementation is a one-file follow-up.
- **`MainActivity.onCreate` no longer bootstraps `FLAG_NOT_FOCUSABLE`.** The flag is now driven entirely by `ApplyMainScreenWindowBehavior` from the Compose side. There's a sub-frame gap on cold launch where the activity is briefly focusable before composition runs — harmless because no input is pending at that instant. Also removed the unused `import android.view.WindowManager` and the "Secondary display detection" TODO comment block (replaced with a doc-only reference to the per-destination Compose toggle).
- **`ApplyMainScreenWindowBehavior` kept; doc reframed.** Body unchanged — still sets/clears `FLAG_NOT_FOCUSABLE` + gesture exclusion on Main route + drawer-closed. Reframed the rationale: primary purpose is now AYN Thor secondary-device support (activity-mode keyboard on bottom while game on top); on single-screen devices it's a near no-op since the user reaches the keyboard via the overlay.
- **`InputDispatcher.consumeSystemBack` doc reframed**, body unchanged. Same Thor-secondary justification as above; references the lifecycle-scoping fix pulled forward into Brick 1.
- **`InputAccessibilityService.kt` motion-capture comment synced.** Old comment claimed "confirmed working 2026-05-16" — stale; `MotionCaptureOverlay`'s class doc has had the correct (reverted-to-non-focusable, Phase 6 deferred) state since Phase 6 closed. New comment matches.
- **`ForegroundAppMonitor` + `ColorContrast` doc reframes** — removed "dual-display devices like the AYN Thor" framing, replaced with single-screen-first context (overlay over game on every device) with Thor as the additional supporting case.
- **`CLAUDE.md` Virtual Keyboard Layouts section gained an entry** describing the run-mode overlay (TYPE_APPLICATION_OVERLAY + QS tile activation) vs edit-mode-in-activity split, so future conversations have the architectural mental model loaded out of the box.

#### Files actually landed

- `app/src/main/java/com/mapo/MainActivity.kt` (removed unconditional `FLAG_NOT_FOCUSABLE` bootstrap + TODO; doc reframe)
- `app/src/main/java/com/mapo/ui/screen/MainScreen.kt` (`ApplyMainScreenWindowBehavior` doc reframe)
- `app/src/main/java/com/mapo/service/input/InputDispatcher.kt` (`consumeSystemBack` doc reframe)
- `app/src/main/java/com/mapo/service/InputAccessibilityService.kt` (motion-capture stale comment fixed)
- `app/src/main/java/com/mapo/service/foreground/ForegroundAppMonitor.kt` (doc reframe)
- `app/src/main/java/com/mapo/ui/util/ColorContrast.kt` (doc reframe)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardDisplayRouter.kt` (new — FC2 seam, single-display passthrough)
- `app/src/main/java/com/mapo/service/overlay/keyboard/KeyboardOverlayPresenter.kt` (router injected; documented FC2 fan-out as next change point)
- `app/src/test/java/com/mapo/service/overlay/keyboard/KeyboardOverlayPresenterTest.kt` (router injected + stubbed)
- `CLAUDE.md` (Virtual Keyboard Layouts section gained run/edit-mode entry)

#### Hand-off to device verification

Single-screen phone (new primary target) should be unaffected by this brick — nothing structural changed for it. Worth a quick sanity check that the overlay still works from the QS tile.

**Thor** is where this brick's claims need confirming:
1. Open Mapo on Thor's bottom screen. Drawer → "Toggle keyboard overlay." Overlay should appear on the **top** screen (default display) by default.
2. Verify gamepad input still drives a game running on top while the overlay is up.
3. Verify back-button behavior in another app while Mapo is backgrounded (lifecycle-scoped `consumeSystemBack` from Brick 1 still works).
4. Activity-mode keyboard (Mapo's bottom-screen activity view) should still render and respond to taps — that's the secondary-device path `ApplyMainScreenWindowBehavior` is now documented to serve.

If those work, the refactor is closed. The follow-up "overlay follows the foreground app's screen on Thor" item is filed as a future brick separate from this plan.

---

## Features that need redesigning (not just relocating)

1. **Profile drawer** — activity-only concept. Overlay's bottom bar gets a compact "Open Mapo" button.
2. **Profile auto-switch "Create profile for $appLabel?" prompt** — embed inside keyboard overlay (snackbar layer) when overlay is mounted; standalone otherwise.
3. **`PermissionsRequiredDialog`** — reword from "auto-switcher to work" to "Mapo to work over a game."
4. **`BottomBar` quit-app button** — split: "Hide keyboard" closes overlay; separate "Exit Mapo" in activity drawer for full shutdown.
5. **`InputDispatcher.setOverlayFocused`** — boolean → typed `OverlayFocusKind { NONE, PROMPT, KEYBOARD }`.
6. **Background-only operation** — preserved. Single FGS runs whenever remap is enabled OR keyboard is shown. Notification text adapts.

---

## High-risk unknowns

1. **R1** — Touch routing on a large `FLAG_NOT_TOUCH_MODAL` overlay over a fullscreen game. **Answered in Brick 1.**
2. **R2** — FGS requirements on Android 12+. **Answered in Brick 1.**
3. **R3** — ViewModel instancing across activity + overlay. **Answered in Brick 2** (`@Singleton KeyboardController`).
4. **R4** — Removing `FLAG_NOT_FOCUSABLE` / `consumeSystemBack` without regression. **Mitigated by Brick 5 sequencing.**
5. **R5** — Overlay activation lifecycle. **Answered in Brick 4.**
6. **R6** — Edit mode in overlay? **No.** Overlay is run-mode only.
7. **R7** — IME stacking above `TYPE_APPLICATION_OVERLAY` — known visual artifact, not fixed here.
8. **R8** — Overlay window insets vs activity's `enableEdgeToEdge` sidestep — POC verifies.

---

## Verification (end-to-end, on completion of Brick 5)

Single-screen device (phone or Odin 2 Mini):
1. Install Mapo, grant accessibility + overlay permissions, set up a profile with at least one keyboard layout and one physical remap.
2. Launch a game.
3. QS tile → keyboard appears over the game; taps inject into game; gamepad still drives game directly.
4. Dismiss overlay → game unaffected.
5. Overlay hidden, press remapped physical button → mapped output fires.
6. Activate overlay again → still works after background time.
7. Switch foregrounded game → auto-switcher fires; if profile-create prompt is needed, embeds correctly (inside overlay when mounted; standalone when not).
8. Open Mapo from launcher → drawer / settings / edit-mode all function; activity-mode keyboard injects.

Thor (bottom screen on, Focus Lock = Auto-Lock):
- Repeat 1–8 with overlay attaching to the bottom screen by default.

Test suite: all Robolectric tests green. No regressions in `ComposeSmokeTest`, `MainViewModelTest`, the input pipeline tests, or layout/profile/repository tests.

---

## What's NOT in this plan

- Phase 6 analog modes — unblocked AFTER this refactor; separate plan.
- Phase 7 VDF import — unblocked AFTER this refactor; original Steam Input parity plan still applies.
- Activation triggers beyond QS tile (physical-button, floating handle, etc.) — explicitly deferred.
- Edit-mode in overlay — explicitly rejected (overlay = run-only).
- Game-aware overlay positioning (per-app saved position / size) — not in scope.
- IME conflict resolution — known visual artifact.
- FC1 (action-set-governed overlays) and FC2 (Thor bottom screen as canvas extension) — anticipated future shifts; only the API seams ship in this refactor.
