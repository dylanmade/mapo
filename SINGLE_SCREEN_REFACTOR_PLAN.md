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

The overlay uses `FLAG_NOT_TOUCH_MODAL` (touches outside the keyboard pass through to the game) without `FLAG_NOT_FOCUSABLE` for the keyboard surface itself — taps inside reach Compose, taps outside reach the game. Crucially, this overlay does NOT need to receive gamepad motion events (the Phase 6 blocker), so the focus side effects observed there don't apply here.

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

### Brick 1 — Overlay POC + foreground-service skeleton

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

### Brick 2 — `KeyboardController` extraction (behavior-preserving)

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

### Brick 3 — Host-agnostic `KeyboardHost` composable

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

### Brick 4 — Production overlay-mounted keyboard

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

### Brick 5 — Thor compatibility + cleanup

Goal: validate Thor as a secondary supported device; strip Thor-first scaffolding.

Thor compatibility:
- Overlay defaults to the bottom screen on dual-display devices (or honors a user preference). May need a `Presentation` shim for explicit display routing. Punt to follow-up brick if non-trivial.
- New `KeyboardDisplayRouter` shaped as "given a logical overlay, decide which physical display surface(s) it should attach to" — returns a *collection* (FC2 seam). Sized 1 today, sized N when FC2 lands.

Cleanup (now-dead Thor-first code paths):
- `MainActivity.onCreate`'s unconditional `FLAG_NOT_FOCUSABLE` — remove; defer entirely to per-destination logic.
- Simplify `ApplyMainScreenWindowBehavior` (`MainScreen.kt:929`). Keep the "user is on Main route on Thor's bottom screen while game runs on top" path but rename / re-comment.
- `InputDispatcher.consumeSystemBack` — still useful on Thor's secondary-device path; reframe comments.
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
