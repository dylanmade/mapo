# Overlay Toolbar + Implementation-B Navigation Plan

## Context

After the bottom-toolbar redesign, Mapo's "home" is the floating pill (master switch ·
profile split-button · options) rendered **inside `MainActivity`** — a translucent,
fullscreen, `singleTask` activity that floats over the foregrounded game. The activity
also hosts a `NavHost` whose deeper routes (Edit Controls, Edit Overlay, Change Profile,
Auto-switch, Shizuku Setup, Theme Studio, …) are full-screen destinations in the same
task. Tapping the backdrop calls `moveTaskToBack`.

This is still an **activity-shaped home**: while it's up, the Mapo window owns focus, so
it's a modal "I opened Mapo" state, not an always-available control surface layered on the
game. The single-screen target (`project_target_single_screen_pivot`) wants the inverse —
the chrome should be a **persistent passthrough overlay** the user can reach mid-game
without backgrounding it, navigable by **physical gamepad** (no touchscreen assumption),
while the game keeps receiving input.

This plan converts the toolbar chrome into a system overlay and adds gamepad navigation of
that non-focusable overlay via the accessibility service ("implementation B"). It builds
directly on the keyboard-overlay refactor (`SINGLE_SCREEN_REFACTOR_PLAN.md`) and reuses the
per-element window machinery from the overlay rebuild (`OVERLAY_REBUILD_PLAN.md`).

Prereq/relation:
- Reuses `OverlayElementWindowManager`'s window recipe (`TYPE_APPLICATION_OVERLAY` +
  `FLAG_NOT_FOCUSABLE` + `FLAG_HARDWARE_ACCELERATED`, gesture-routed) and
  `OverlayLifecycleOwner`.
- Reuses the FGS (`KeyboardOverlayService`) that already holds priority while overlay
  windows are mounted.
- Mirrors the existing `OverlayFocusKind.PROMPT` contract (service owns focus/selection
  state, UI owns content) — but **inverts the focus decision** (see Decision below).

---

## Decision: non-focusable toolbar overlay + accessibility-driven selection (Implementation B)

Mount the toolbar chrome as a `TYPE_APPLICATION_OVERLAY` window that is **not focusable**.
Heavy screens stay in an activity (one task). The non-focusable overlay can't receive
gamepad key events through the window system, so a selection cursor is driven by the
**accessibility service**, which already intercepts every physical key via
`FLAG_REQUEST_FILTER_KEY_EVENTS`.

### Why not Implementation A (focusable overlay + Compose focus traversal)

`OverlayManager.showCreatePrompt(focusable = true)` already proves a focusable overlay can
be gamepad-navigated: `InputAccessibilityService` translates A→ENTER / B→BACK and lets DPAD
fall through to Compose's focus traversal (the `OverlayFocusKind.PROMPT` branch). That's
perfect for a **transient modal** prompt.

It is **fatal for a persistent toolbar**: a focusable `TYPE_APPLICATION_OVERLAY` window
takes key-event focus away from the game for the *entire* time it's mounted. The whole
reason every Mapo overlay window carries `FLAG_NOT_FOCUSABLE` (and why the flag was removed
from the activity in `feedback_no_flag_not_focusable_on_activity`) is so unmapped gamepad
input flows to the game. A persistent home chrome cannot own focus.

### Why Implementation B works

The toolbar window stays `FLAG_NOT_FOCUSABLE` (passthrough). Gamepad input reaches the game
normally. Only when the user **explicitly enters toolbar-nav mode** (a dedicated trigger)
does the service start consuming DPAD/A/B to drive a selection index; everything else still
passes. On exit, input returns to the game. This is the same "service owns the focus state,
UI owns the content" split as `OverlayFocusKind.PROMPT`, generalized from "is a prompt up"
to "which toolbar element is selected."

### Rejected alternatives

- **Focusable toolbar overlay (Impl. A)** — steals game input persistently (above).
- **Keep the toolbar in an activity** — the status quo; modal, backgrounds the game, no
  always-available mid-game chrome. This is what we're replacing.
- **`Presentation` / second activity for deep screens on a second display** — steals focus;
  also Thor-specific. Deep screens stay in the existing single task.
- **Compose-side DPAD handling in the overlay** — impossible: a non-focusable window never
  receives the key events; they go to the game. The service is the only component that sees
  them. This is the architectural crux that *forces* Implementation B.

---

## Architecture: the service ↔ UI contract

The hard constraint: **the service sees the keys, the UI knows the layout.** So we split
responsibilities exactly along that line.

```
 physical DPAD/A/B ──► InputAccessibilityService.onKeyEvent
                          │  (only while toolbarNavActive)
                          ▼
                       InputDispatcher  ◄── shared state bus (already exists)
                          │  toolbarNavActive: StateFlow<Boolean>
                          │  toolbarSelection: StateFlow<Int>
                          │  toolbarTargets:   StateFlow<List<ToolbarTargetId>>   (UI → bus)
                          │  activateToolbar(index): SharedFlow signal            (svc → UI)
                          ▼
                       Toolbar overlay Compose
                          • publishes its ordered focusable targets to the bus
                          • renders the highlight ring on toolbarSelection
                          • runs the target's onClick when activateToolbar fires
```

- **Service = cursor + activation signal.** DPAD moves `toolbarSelection` within
  `[0, toolbarTargets.size)`; A emits `activateToolbar(selection)`; B clears
  `toolbarNavActive`. These keys are consumed (not forwarded to the game) only while nav is
  active. The service knows *nothing* about what a target does or where it is on screen.
- **UI = topology + actions + highlight.** The toolbar composable owns the ordered list of
  focusable targets (switch, split-leading, split-trailing, options, and — when a menu is
  open — that menu's items). It republishes the list whenever the topology changes (menu
  open/close), renders the highlight on the selected index, and executes the action when the
  activation signal arrives. Republishing resets the selection to 0; a small nav stack in
  the UI handles descend-into-menu / ascend-out.

This mirrors `setOverlayFocus(PROMPT)` exactly: the service flips a coarse mode flag and the
UI reacts. We're adding an index + a target list + an activation channel to the same bus
(`InputDispatcher`), nothing structurally new.

---

## Locked-in contracts (Brick 0)

### Window
- Toolbar overlay: single `TYPE_APPLICATION_OVERLAY` window, bottom-center gravity, sized
  WRAP_CONTENT around the pill. Flags identical to `OverlayElementWindowManager`'s set:
  `FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS
  | FLAG_HARDWARE_ACCELERATED`. Out-of-bounds taps pass through; in-bounds taps reach
  Compose; key events route to the game. (`project_overlay_windows_need_hw_accel`.)
- Menus (profile / options) open as their own overlay popups, reusing the `UpwardMenu`
  pattern already built for `MainBottomToolbar` (custom `Popup` + position provider; the
  stock `DropdownMenu` auto-flip is broken under `FLAG_LAYOUT_NO_LIMITS`).

### State bus (`InputDispatcher`)
- `toolbarNavActive: StateFlow<Boolean>` — gamepad is currently driving the toolbar.
- `toolbarSelection: StateFlow<Int>` — selected target index (service-owned).
- `toolbarTargets: StateFlow<List<String>>` — ordered target ids (UI-owned, published up).
- `activateToolbar: SharedFlow<Int>` — one-shot "A pressed on index i" (service → UI).
- These coexist with `overlayFocus`/`captureMode`; nav mode and `PROMPT` are mutually
  exclusive (a prompt is modal).

### Deep screens
- The existing `NavHost` deep routes stay in **one task** (the current `MainActivity`,
  acting as a config host). The host is launchable directly into a route via an intent
  extra; the toolbar fires that intent for any element needing a full screen. The toolbar
  **home route is removed** from the NavHost (the overlay replaces it).

### Coexistence / kill-switch
- Build behind the existing overlay machinery; the in-activity toolbar is **not deleted**
  until the final brick (per `OVERLAY_REBUILD_PLAN` coexistence discipline). Every brick
  boundary leaves remap + the activity working end-to-end.

### Forward-compat
- Honors `project_action_layer_overlay_inheritance` FC seams: the toolbar's profile/set/
  layer scope is read through the same resolver the keyboard overlay uses, not a private
  copy.

---

## Resolved product decisions (2026-06-17)

1. **Launcher-icon behavior → shows the overlay toolbar.** Tapping the Mapo icon reveals the
   passthrough toolbar over the current foreground app (and routes to permission setup if
   overlay/accessibility perms are missing). It no longer opens a modal activity home. This
   is the same reveal path as the QS tile (decision 3).
2. **Toolbar-nav entry trigger → long-press Select + A (chord), later configurable.** Default
   is the chord *hold Select, then A*. This is authored as a normal binding so it later flows
   through Mapo's standard activator configuration (regular / long-press / etc., per
   `reference_steam_input_activators`) and becomes user-rebindable with no special-case code.
   For the MVP the chord is the seeded default; the config UI is a follow-up, but the binding
   shape is chosen now so we don't hardcode a one-off.
3. **Toolbar visibility → shown on demand.** Hidden by default; revealed via the QS tile, the
   nav trigger, **or the launcher icon** (decision 1); auto-hides on exit. No persistent pill
   over the game.

---

## Bricks

Sequencing rule (inherited): **every brick boundary leaves remap working end-to-end and the
activity functional.**

### Brick 0 — Decisions (this file) — ⏳ awaiting sign-off
Architecture + contracts above. Resolve the three open decisions.

### Brick 1 — Toolbar overlay POC (touch only) — ✅ LANDED (awaiting device verify)
`ToolbarOverlayManager` (`service/overlay/element/`, sibling of `OverlayElementWindowManager`)
renders `MainBottomToolbar` in a single non-focusable overlay window with the shared flag
matrix + FGS; state collected directly from the singletons (`ProfileRepository`,
`SteamCredentialStore`, `KeyboardController`, `OverlayPresenter`, `OverlayLiveEditController`).
Master switch + menus work by touch; `WRAP_CONTENT` width so side taps fall through. Deep-screen
items launch `MainActivity` (precise routing is Brick 2); `onEditOverlay` → live editor works.
Mounted via a temporary "Toolbar overlay (dev)" entry in the in-activity toolbar's More menu
(`MainViewModel.toggleToolbarOverlayDev()`; optional `MainBottomToolbar` param, null on the
overlay's own copy). Coexists with the in-activity toolbar; nothing deleted. **Verify on
device:** touch works, out-of-bounds passthrough works, game keeps gamepad input while up.

### Brick 2 — Config-host routing + launcher reveal
`MainActivity` becomes launchable directly into a deep route via an intent extra; remove the
home route from the NavHost. Toolbar elements that need a full screen (Edit Controls, Edit
Overlay, Change Profile, options items) fire that intent. The **launcher icon now reveals the
toolbar overlay** instead of opening the home (decision 1) — the icon's entry point shows the
overlay (and routes to permission setup if perms are missing) rather than hosting chrome.
Everything is now drivable by touch end-to-end.

### Brick 3 — Implementation-B selection engine (the heart)
`InputDispatcher` gains the four toolbar state members. `InputAccessibilityService.onKeyEvent`
gains a `toolbarNavActive` branch: DPAD moves selection (consumed), A emits `activateToolbar`
(consumed), B exits (consumed); all other keys pass through. The toolbar composable publishes
its target list, renders the highlight, and runs the activated target's action.
**Enter-nav trigger (decision 2): long-press Select + A.** MVP seeds this chord; detection
should route through the existing activator/chord machinery (`captureMode` + the evaluator's
chord handling, `feedback_soft_press_unified_to_soft_pull` / `reference_steam_input_activators`)
rather than a bespoke `onKeyEvent` special-case, so the later "make it configurable" step is
a UI addition, not a rewrite. **Verify on device:** hold Select + A enters nav, walk
switch→profile→options with DPAD, A toggles/opens, B exits, game input restored on exit.

### Brick 4 — Menu nav stack
Opening the profile/options menus republishes `toolbarTargets` (now the menu items); a UI-side
nav stack handles descend/ascend; selection resets to 0 on each republish; highlight follows.
Activating a leaf either performs its action inline or launches the config host (Brick 2).

### Brick 5 — Reveal triggers, tile & polish
Finalize the shown-on-demand reveal (decision 3): QS tile + nav-trigger + launcher icon all
show the toolbar and it auto-hides on exit. Wire permission/health surfacing (overlay +
accessibility) consistent with the Shizuku pattern. Highlight enter/exit + move animation per
`feedback_handrolled_components_animations` (reproduce conventional focus-traversal motion,
not just a static ring). Sentence-case all strings; M3 surface roles per
`feedback_m3_compose_standards`.

### Brick 6 — Retire the in-activity home
Remove the home route, backdrop tap-to-background, and dead drawer/menu code; the launcher
icon now shows the overlay (decision 1). Pre-release, so destructive removal is fine
(`project_mapo_pre_release`); flag back-compat reinstatement as release approaches.

---

## Risks / watch-items
- **Highlight legibility over arbitrary game art.** The selection ring must read on any
  backdrop — use an M3 focus-indication treatment with a contrasting halo, not a thin tint.
- **Consumed-key correctness.** While nav is active, only DPAD/A/B are consumed; a stuck
  `toolbarNavActive` would swallow the game's DPAD. Guarantee exit on activity launch, on
  toolbar hide, and on any modal `PROMPT`.
- **Multi-display (Thor).** The toolbar window must mount on the display the user is looking
  at; reuse the display-routing logic already in the accessibility service. Single-screen is
  the primary target, so Thor parity is a follow-up, not a Brick-1 gate.
- **Latency.** The DPAD→selection→highlight path is event-driven (no polling); keep it off
  any frame loop (`feedback_latency_phrasing`).
```
